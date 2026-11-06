# Wi-Fi remote: session handoff

Updated 2026-09-22. Read this first when continuing Wi-Fi feature development,
then [the plan](wifi-remote-control-plan.md),
[protocol evidence](wifi-remote-protocol-evidence.md), and
[Android camera tests](wifi-remote-android-testing.md). Evidence sections are
chronological; their early statements about pending validation are historical.

## Current scope and hardware results

The maintainer explicitly chose **Android first**, to collect experience before
iOS integration. The original scope is live preview and still capture, with
photos remaining on the camera. The maintainer subsequently selected browsing and
downloading existing photos as the next Android feature (implementation below).
One foreground Wi-Fi remote session at a time.

- The maintainer confirmed live preview on the a6700, then automatic BLE camera
  Wi-Fi startup, Android joining and taking pictures in the app.
- The successful Python capture used manual focus, single-shot drive and a 500ms
  half-press; it saved a photo and emitted Sony capture event `0xc206`. An earlier
  attempt acknowledged every command but did not take a photo. Focus and timing
  changed together, so the cause is not established. Keep manual focus as the
  supported test setup until autofocus is implemented and tested.
- Actual preview frames are 640×424 JPEGs with trailing region padding. Android
  pixel decoding/display is confirmed; the earlier Python `decoded: false`
  meant Pillow was absent, not that the app still cannot decode preview.
- The maintainer observed disconnect when the screen went dark. The host now
  holds `View.keepScreenOn` while the selected camera owns a session and restores
  the previous value on disposal/disconnect. Emulator tests passed; this latest
  behavior still needs phone confirmation.
- Deliberate lock/background still closes the session through `ON_STOP`.
  Keeping the connection while locked was discussed, **not selected or
  implemented**. A possible future design keeps control connected but pauses
  preview. Battery use, Android background networking and occupying the camera's
  remote session need consideration if that feature is requested.
- Normal disconnect now attempts camera Wi-Fi shutdown before closing sockets.
  **Hardware confirmation of Wi-Fi shutdown and subsequent automatic reconnect
  is the next pending test.** Accepted commands do not prove the radio turned off.

Phone model, Android version and camera firmware were not recorded in the supplied
results. Capture them in the next hardware report; do not infer general model or
firmware support from this one working combination.

## Code map and invariants

Shared protocol/controller files are under
`sharednew/src/commonMain/kotlin/com/sasch/cameragps/sharednew/remote/wifi/`:

| Area | Main files |
| --- | --- |
| BLE startup/credentials | `SonyWifiBootstrap.kt` |
| Automatic connection lifetime | `AutomaticWifiRemoteConnector.kt` |
| PTP/IP framing, serialized transactions, two sockets | `PtpIpPacketCodec.kt`, `PtpIpCommandQueue.kt`, `PtpIpSessionOpener.kt` |
| Sony initialization/capabilities/events | `SonyPtpInitializer.kt`, `PtpDeviceInfo.kt`, `PtpIpEventMonitor.kt` |
| Still capture | `SonyPtpShutter.kt` |
| HTTP preview startup/framing/cleanup | `SonyLiveViewStream.kt`, `SonyLiveViewEndpoint.kt` |
| Session ownership/state | `WifiRemoteController.kt`, `WifiRemoteState.kt` |
| Best-effort camera Wi-Fi shutdown | `SonyWifiShutdown.kt` |
| Existing photos/original downloads | `SonyImageTransfer.kt`, `SonyContentCatalog.kt`, `SonyCameraProperties.kt` |

Android network mechanics/wiring live under the matching `androidMain/.../remote/wifi/`
directory: `AndroidWifiRemote.kt`, `AndroidCameraNetworkRequest.kt`,
`AndroidPtpIpConnection.kt`, and `AndroidCameraHttpTransport.kt`.
The shared UI is `commonMain/.../ui/remote/WifiRemoteScreen.kt`; Android's thin
host is `app/.../ui/device/AndroidWifiRemoteScreen.kt`. `AppServices` owns the
controller. Consult `AGENTS.md` for the surrounding BLE/service architecture.

- All BLE traffic goes through the existing operation queue, including bootstrap.
  CC08 `{01}` starts Wi-Fi; CC09 indicates readiness; CC06/07 supply credentials
  and optional CC0C supplies BSSID. Never log or persist credentials.
- Automatic joining uses Android 10+ `WifiNetworkSpecifier` with exact camera
  credentials and holds the network request until session cleanup. Camera IPv4
  comes from the unique local default gateway, never a hard-coded Sony address.
  Android 8/9 retain manual joining. Bind sockets to the selected `Network`, not
  the whole process. Keep the existing OS-version-specific permission handling.
- Sony controls travel over PTP/IP command/data and event TCP channels. This
  a6700 preview path is a separate HTTP stream from the DD-advertised URL.
- Common state is confined to Main.immediate. Displayed state belongs in
  `CameraSession`; decoded preview uses a separate latest-frame stream.
- Suspend competing BLE shutter probes/presses during Wi-Fi ownership, preserve
  BLE GPS, and resume BLE monitoring after cleanup. Never retry an uncertain
  capture or silently switch it to BLE.
- Finish/cancel preview and shutter jobs, release pressed buttons, request camera
  Wi-Fi shutdown on normal closure, then close sockets and release the network.
  Unexpected network loss skips shutdown. Preserve this ordering.
- Shutdown reads fresh `0x9209` availability, prefers `0xd308` gated by `0xd12b`,
  and falls back to `0xd2e8` gated by `0xd296`. It uses bounded PTP down/up
  commands, not an invented inverse CC08 BLE payload. UI results distinguish
  requested, unavailable and unconfirmed; details are in the evidence document.
- New UI stays shared; gplay/foss boundaries and existing iOS BLE/ASK behavior
  must remain intact. There is no iOS Wi-Fi connection implementation yet.

## Sony reference and 2027 compatibility question

The root `Creators.apk` is the inspected Creators' App 3.5.0; its hash and class
paths are in the evidence document. Decompiled sources from this session were at
`/private/tmp/sony-gatt-144-review/sources/jp/co/sony/ips/portalapp`.
That temporary directory may disappear: the APK and durable evidence notes are
the reproducible starting point. No full official command reference/README has
been obtained. APK inspection is evidence about app behavior, not Sony's support
contract. Prefer official documentation when available.

Sony's public Camera Remote Command notice dated 2026-06-10 says Camera Control
PTP 2 commands may become unusable on some models from 2027 and discourages their
use. It directs readers to the supplied README for details. The public notice
does not identify the affected models or say whether the change concerns future
models or firmware updates. [Sony announcement](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)

**Unresolved:** obtain the current official reference/README and map our operation
codes and parameter formats to the deprecated/current command families. Do not
claim this implementation is unaffected, or that the a6700 necessarily stops
working in 2027. The observed server `4.00`, vendor version `310`, extended-info
version `300`, and PTP/IP version `0x00010000` refer to different protocol layers;
none alone establishes that mapping. PTP means Picture Transfer Protocol;
PTP/IP is the network transport, separate from Sony's command-family naming.

## Git and validation checkpoint

Branch at handoff: `feat/wifi-features`. Five logical batches were committed:

- `0c5b813`: protocol, preview and Python probe.
- `4f36df9`: BLE bootstrap and remote session ownership.
- `61b6e17`: Android experimental preview/shutter screen.
- `b5a92aa`: drop cancelled BLE operations before sending them.
- `687d313`: automatic camera Wi-Fi startup and Android joining.

**Later screen-awake and Wi-Fi-shutdown changes, tests and documentation are
still uncommitted.** Inspect the working tree before editing or committing.
There are unrelated modifications, including partially staged `AGENTS.md` and
`CLAUDE.md`, notification work, iOS/project files, and other docs/tests. Preserve
them; do not stage or commit the entire working tree as Wi-Fi work.

Latest implementation validation passed:

```bash
./gradlew :sharednew:testAndroidHostTest :sharednew:iosSimulatorArm64Test \
  :sharednew:compileKotlinIosArm64 :app:assembleFossDebug \
  :app:compileGplayDebugKotlin --offline
```

218 Android host tests and 248 iOS simulator tests passed (466 total). Both Android
flavors compiled; the FOSS APK was built. Two screen-awake instrumentation tests
passed on an Android 12 emulator in `WifiRemoteScreenAwakeTest`: restoring timeout
on disconnect and preserving an existing screen-on request when leaving. The
temporary emulator was shut down. These are prior validation results, not a
substitute for checking future edits or real camera behavior.

APK: `app/build/outputs/apk/foss/debug/app-foss-debug.apk`.
The Android test guide contains build commands and the hardware checklist.

## Next work

1. Validate **Disconnect → camera Wi-Fi off → Connect automatically** and check
   BLE GPS continuity. Record the displayed shutdown result and actual camera
   state separately. Confirm the screen stays awake during inactivity.
2. Continue Android reliability checks: rotation, join refusal/cancellation,
   network loss, repeated captures, preview after capture, and a long session.
3. Resolve Sony's command-family compatibility question before promising future
   firmware/model support. This does not erase the successful current hardware tests.
4. Validate the new **Browse camera photos** workflow. Implementation is now in
   place for paged thumbnails and individual JPEG/RAW/HEIF original downloads;
   version-4 uses the content API and card slot 1. Android stores complete files in
   `Pictures/Alpha GPS`. Read the image-transfer sections in the evidence and test
   guide before hardware testing; no browsing/download camera result is recorded.
5. Later candidates are autofocus with
   readiness feedback and preview recovery. Locked-device connectivity remains
   a product decision; iOS networking remains deferred. Exposure controls, movie
   recording, batch/background downloads and interval shooting are later milestones in the plan.

A new session can start with: "Read docs/wifi-remote-handoff.md and continue
Android Wi-Fi remote development from the existing working tree."

## Image-browser validation checkpoint (2026-09-22)

After adding existing-photo browsing, the following passed with `--offline`:
`testAndroidHostTest` (232 tests), `iosSimulatorArm64Test` (262 tests),
`compileKotlinIosArm64`, `assembleFossDebug`, and `compileGplayDebugKotlin`.
The simulator target compiled as part of its test run. The FOSS APK at
`app/build/outputs/apk/foss/debug/app-foss-debug.apk` includes the browser.
The new protocol, controller, UI, storage and documentation edits are uncommitted.
Real camera browsing, original-file integrity and Android gallery publication
remain pending the hardware checklist.
