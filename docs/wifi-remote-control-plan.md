# Wi-Fi remote control: shutter and live view

Plan started 2026-09-19. Requested scope: shutter plus live view on Android and iOS.
First validation camera: the maintainer's a6700. The checkpoint below distinguishes
implemented behavior from hardware validation.

Implementation checkpoint, 2026-09-22: the shared BLE bootstrap, PTP/IP framing,
command/event socket handshake, bounded data-in/out transactions, DeviceInfo
operation-code parsing, event monitoring and per-camera Wi-Fi state are implemented
with regression tests. A manual-network probe is in `tools/sony_wifi_probe`; see
[protocol evidence](wifi-remote-protocol-evidence.md). The a6700 returned a
successful 461-byte DeviceInfo response over the two PTP/IP channels. Its
DeviceInfo advertises all six Sony initialization/control operations inspected
in the APK. The a6700 then successfully completed the APK's version-4 Sony
remote-session negotiation and clean closure. Its DD advertises an HTTP live-view
URL, and extended info advertises S1/S2 and live-view controls. The first active
test received successful command responses but took no photo. The next run in
manual focus with a 500ms half-press interval **took a photo and produced Sony's
`0xc206` capture event**. Autofocus and whether that interval is required remain
unverified. The shared sequence now includes that interval and cancellation cleanup.
Preview first failed a strict JPEG boundary check, then returned HTTP `503` on the
successful-capture run. The revised probe accepts trailing image-region padding,
follows the APK's postview-before-liveview startup, retries preview `503` within a
bounded deadline, and reports frame layout plus selected shooting-state properties.
The subsequent preview-only run received three structurally valid 640×424 JPEGs,
confirming trailing padding and the DD HTTP path. Pixel decoding of real camera
images is still pending (Pillow was absent). The shared preview pipeline now handles
startup/cleanup, bounded HTTP retries, JPEG padding, background pixel decoding,
and a one-frame buffer. The Android app now exposes an experimental shared Compose
screen from device details, with manual Wi-Fi joining and IPv4 entry. The app-owned
controller opens network-bound command/event/HTTP sockets, shows preview and capture
outcomes, suppresses competing BLE shutter probes, and cleans up on exit, background,
network loss, device removal and service shutdown. Rotation retains the connection.
Android 17 local-network permission is requested when connecting. The camera's plain
HTTP reader uses the selected network's sockets; no global cleartext exception or
process-wide network binding is enabled. This Android path is built and regression
tested, but has not yet been tested on a phone with the camera. See the
[Android test guide](wifi-remote-android-testing.md).

The maintainer requested Android-first development to collect practical experience.
Automatic BLE credential retrieval/network joining, iOS networking, autofocus,
property-based URL fallback and IPv6 remain later work. Manual entry currently
relies on the user choosing the correct camera network/IP; the connected camera's
name is displayed, but BLE-to-Wi-Fi identity is not yet verified.

The first release should let a user open a camera's remote screen, approve joining
its Wi-Fi network, see a live image, focus and take a still photo, then disconnect.
The photo stays on the camera. Use one Wi-Fi camera at a time and foreground remote
sessions initially. Exposure editing, movie recording, image downloads, interval
shooting and connecting through a home router are later milestones.

1. **Prove the a6700 protocol and connection flow.**

   Trace the inspected Creators' App 3.5.0 APK from BLE Wi-Fi startup through device
   discovery, remote-session initialization, shutter and live view. Record the
   observed sequences and packet fixtures in [the protocol evidence document](wifi-remote-protocol-evidence.md), distinguishing
   APK evidence from real-camera confirmation. Implement our own small protocol
   core from those findings.

   Prefer Sony's official [Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)
   PTP reference where access is available. It lists the ILCE-6700 and PTP/IP,
   but its model/interface matrix and command details are in Sony's supplied
   materials. The separate [Camera Remote SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/)
   targets desktop hosts, so it is a reference for capability, not an Android/iOS
   dependency.

   Evidence already found in the decompiled sources:

   | Area | APK evidence | Still to establish on the a6700 |
   | --- | --- | --- |
   | Camera Wi-Fi startup | `TurningWifiOnState` writes CC08 and observes camera Wi-Fi status | Required payload, preconditions and completion behavior |
   | Credentials | `GettingWifiInfoAfterWifiOnState` reads CC06 (SSID), CC07 (password), optionally CC0C (BSSID) | Availability and encoding on this firmware |
   | Availability | CC09 is subscribed, then read; updates include Wi-Fi and Sony remote availability | Which fields help this connection flow; never use them as Bluetooth-shutter availability |
   | Remote session | `ptpip/initialization/Initializer` negotiates device information, session opening and Sony SDIO connection stages | Supported version, client identity/pairing requirements and exact command/event setup |
   | Shutter | `ptpip/button` and the shooting controllers implement remote operations | Focus/press/release or one-shot path supported by this camera, and completion events |
   | Live view | The APK's PTP/IP camera constructs the HTTP downloader with a discovered live-view URL; `LiveViewGetter` uses PTP GetObject in the USB client path | Which URL/property branch the a6700 selects, framing, image format and any required enable property |

   Do not assume a legacy Sony JSON camera API, a fixed camera IP, a universal
   PTP initialization sequence or a universal HTTP stream URL.

   Start with manual Wi-Fi joining if necessary to isolate protocol work from
   automatic joining. Build a minimal developer test in the app, using the future
   shared protocol core. Prove session opening, one confirmed still capture, one
   decoded frame, repeated frames and clean session closure. In the same milestone,
   prove iOS can join and reach the camera with the existing AccessorySetupKit setup.
   These are the gates before building the complete remote screen.

2. **Add shared Wi-Fi remote infrastructure.**

   Put protocol encoding, packet parsing, Sony negotiation, capability discovery,
   command sequencing and session orchestration in `sharednew/commonMain`, under
   a new `remote/wifi` area. Keep native network mechanics behind small platform
   contracts for joining/leaving a network and opening network-bound connections.
   Wire these through the existing Android and iOS application graphs.

   Use a Wi-Fi transaction queue with timeouts, cancellation and transaction IDs.
   Account for partial TCP reads/writes, combined packets, bounded packet sizes,
   event-channel closure and stale responses from an earlier connection. The BLE
   queue remains responsible for every BLE operation, including Wi-Fi bootstrap.
   Socket I/O and image decoding run off the main thread; session-state mutation
   stays on the existing Main.immediate scope.

   Expose a nested Wi-Fi remote state in `CameraSession`, separate from
   `remoteFeatureActive` and the BLE handshake phase. Suggested states:
   idle → preparing camera → awaiting network approval → joining → opening session
   → ready, with closing and actionable failure states. Keep displayed device
   state in the session registry, not platform mirrors. Review existing disconnect
   and removal paths explicitly: a BLE disconnect must not silently delete a live
   Wi-Fi session; device deletion and global shutdown must close both transports.

   Live frames use a separate bounded stream with only the latest frame retained,
   rather than storing image bytes in the device-list session state or database.

3. **Implement camera Wi-Fi setup and platform joining.**

   Add a shared bootstrap coordinator for the verified CC08/CC09/CC06/CC07/CC0C
   sequence. It runs only when the user opens Wi-Fi remote control. Treat CC09 as
   connection context; a successful protocol session and negotiated capabilities
   determine readiness. Missing or unknown flags must not cause an endless wait.
   Preserve the existing GPS handshake and transmission gating.

   On Android 10+, use a specific `WifiNetworkSpecifier` request, hold its network
   callback for the remote session and bind camera connections to the returned
   network. Avoid process-wide network binding. Android 8/9 remain supported by
   the app, so provide a guided manual-join path there. Handle refusal, Wi-Fi off,
   lost networks and camera networks without Internet access. See the official
   [Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap).

   Audit permissions for the actual OS/API path, including INTERNET, Wi-Fi access
   and nearby-device permissions. This app already targets SDK 37: direct local
   networking must account for Android 17's ACCESS_LOCAL_NETWORK requirement and
   denial/revocation behavior. See Android's
   [local network permission guidance](https://developer.android.com/privacy-and-security/local-network-permission).

   On iOS, integrate joining with the existing AccessorySetupKit ownership and
   authorization model; use `NEHotspotConfigurationManager` for a temporary camera
   network where appropriate. Verify Wi-Fi accessory descriptors/SSID authorization,
   required entitlements, local-network access and behavior for existing Bluetooth-only
   accessories on a real device. Preserve the established central restoration and
   migration lifecycle; do not introduce speculative central teardown. Apple's
   [Wi-Fi API overview](https://developer.apple.com/documentation/technotes/tn3111-ios-wifi-api-overview)
   and [local network privacy guidance](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)
   are the platform references.

   Never emit credentials into raw BLE logs, diagnostics, exports or Sentry.
   Prefer retrieving them for each connection; if persistence proves necessary,
   use platform secure storage. Match the connected camera to the selected device.
   If the verified live-view path requires HTTP, scope any platform transport-policy
   exception to local camera access instead of disabling protections globally.

4. **Implement shutter and live view together.**

   Implement the negotiated still-capture path and its focus/release behavior.
   Keep camera busy, focus refusal, missing card and session rejection distinct
   where protocol evidence supports them. Release held controls on cancellation.
   A lost response must never automatically retry a capture: show an uncertain
   outcome instead of risking a duplicate photo.

   Start live view only after remote initialization and any required camera property
   changes. Use the negotiated transport, parse frame metadata and decode off the
   UI thread. Drop old frames under load and bound memory use. Give shutter/release
   transactions priority over requesting another live frame so streaming cannot
   starve controls. Recover from normal capture-related preview interruptions and
   stop fetching immediately when leaving the screen or backgrounding the app.

   Validate BLE GPS transmission during Wi-Fi use. While the Wi-Fi remote session
   owns camera controls, suspend competing Bluetooth shutter probes and commands
   for that camera. Restore normal Bluetooth monitoring after closing Wi-Fi remote.
   Keep existing Bluetooth-only remote control available as its own mode; do not
   silently send a failed Wi-Fi shutter request over Bluetooth.

5. **Build the shared remote screen.**

   Add a “Wi-Fi remote” entry from device details and implement the entire screen
   and view model in shared Compose. Show connection progress, live view, a shutter
   button, focus feedback where supported, and disconnect/retry actions. Support
   portrait and landscape and show a stale/paused indication when frames stop.
   Enable controls only when the corresponding capability and session are ready.

   Keep the first interaction simple: open remote → approve network connection →
   live view → take photo. Explain OS permission denial, camera refusal and another
   active controller in user terms. Network ownership belongs to the session, not
   a composable, so rotation/recomposition cannot reconnect or repeat a capture.
   Exiting releases session resources; backgrounding ends foreground Wi-Fi remote
   work while the existing BLE location lifecycle continues independently.

6. **Add simulation and validate the release.**

   Extend the existing simulator tools with the confirmed BLE Wi-Fi bootstrap and
   a matching PTP/IP fake endpoint, plus the selected live-view transport. Simulate
   shutter responses/events and changing frames. A separate network process can
   supply this endpoint; it need not be forced into the Android GATT server.

   Add regression tests for packet fragmentation and malformed lengths, handshake
   ordering, timeouts, busy/rejected sessions, transaction cancellation, no duplicate
   capture after reconnect, bounded frame buffering and control priority. Cover
   exiting/backgrounding at every connection stage, competing BLE commands, and
   credentials staying out of logs. Test both Android distributions and both iOS
   compilation targets. Simulation verifies our implementation, not Sony compatibility.

   Real-camera acceptance on Android and iPhone: a fresh connection and subsequent
   reconnect; live view running for ten minutes with stable memory; at least twenty
   deliberate shutter presses with no app-induced duplicate shots; camera off/on,
   network loss and denied permissions; remote closure with GPS still working;
   and successful Wi-Fi remote shooting with “Bluetooth Rmt Ctrl” switched off.
   Record preview latency and command responsiveness rather than promising a frame
   rate before measurement. Broaden camera support only after capability checks and
   another model/firmware have been tested.

Current delivery order: protocol proof and recorded fixtures; shared session,
shutter/live-view core and Android manual-join screen; Android camera testing and
refinement; automatic joining; iOS connection integration; simulator and full device
acceptance. Add regression tests with each slice. iOS authorization remains an open
platform gate, deferred at the maintainer's request while Android is exercised.
