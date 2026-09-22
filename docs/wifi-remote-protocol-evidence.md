# Wi-Fi remote protocol evidence (work in progress)

Source inspected: `Creators.apk` in the repository root, package
`jp.co.sony.ips.portalapp`, version `3.5.0` (`versionCode 350607302`), SHA-256
`b0716bf5b02b7c723d54d670d97c1c6c6daf0519a5ded5330fc4d9f3ff443f09`.
The APK observations below come from decompiled app code, not a packet capture.
The separate a6700 result is identified explicitly. Do not treat an unused
branch in the APK as camera compatibility.

Sony's [Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)
is the official PTP/PTP-IP command reference. It lists ILCE-6700, shutter and live
view capabilities, and says interface compatibility is model-specific in the
supplied README. The current reference is available through Sony's corporate
application process. Sony's separate
[Camera Remote SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/) also lists
ILCE-6700 but supports desktop operating systems, not Android or iOS; it cannot
be linked directly into this KMP app. The old JSON Camera Remote API should not
be assumed for this model.

## APK path observed

| Stage | Decompiled source and observation | Still needs a6700 confirmation |
| --- | --- | --- |
| BLE Wi-Fi start | `btconnection/internal/state/TurningWifiOnState`: write `{01}` to CC08 after Wi-Fi status; wait for `Launched`, with a 30-second timeout. | Whether the a6700 follows this path and its exact status bytes. |
| Credentials | `GettingWifiInfoAfterWifiOnState`: read CC06, then CC07, then optional CC0C. `BluetoothGattUtil.getWifiInfo` skips the first three bytes and decodes the rest as US-ASCII. | Header validation, firmware encodings, and whether BSSID is available. Never log CC07 contents. |
| PTP/IP startup | `ptpip/PtpIpManager` opens a command TCP connection and a separate event TCP connection. `base/command/CommandInitializer` sends an init-command request; `PtpIpManager` sends the init-event request with the acknowledged connection number. | Camera endpoint discovery, pairing/client identity requirements and authorization beyond the first exchange. |
| PTP/IP packets | `base/packet/AbstractPacket` writes a little-endian 32-bit total length and 32-bit type. `EnumPacketType` gives command request/response types 6/7, event type 8 and data types 9/10/12. `InitCommandRequestPacket` uses a client UUID, UTF-16LE name and protocol version `0x00010000`. | Sony negotiation and later command behavior. |
| Sony initialization | `ptpip/initialization/Initializer`: GetDeviceInfo, optional device-description XML, OpenSession or SDIO_OpenSession, SDIO_Connect stages 1/2, vendor code/version and extended device info, then stage 3. | Which branches and modes the a6700 uses. |
| Shutter | `ptpip/button/S1Button`, `S2Button` and `RequestOneShooting` use `SDIO_ControlDevice`; the APK has press/release paths. | Supported mode, response codes, focus and capture completion on the camera. |
| Wi-Fi live view | `camera/PtpIpCamera.setLiveViewStreamCallback` calls `PtpIpClient.setLiveViewStreamCallback`, which constructs the HTTP `LiveViewDownloader` using a URL from device description or `LiveViewUrl` property. `ptpip/liveview/LiveViewGetter` uses PTP GetObject, but appears in the USB client path. | Which URL/property branch the a6700 supplies, framing and frame metadata. |

The shared `remote/wifi` code now implements the bounded TCP packet envelope,
operation encoding/response parsing, bounded data-in assembly, DeviceInfo operation
capability parsing, a serial transaction lane and
the APK-observed BLE Wi-Fi bootstrap through the existing GATT queue.
Timeouts report an uncertain outcome, and the lane never retries a command.
No Sony command, network join, or shutter action should be
enabled in the UI until packet fixtures and a real-camera run establish the
remaining branches. The first device proof needs one opened session, one still
capture, repeated decoded frames and clean closure on both phone platforms.

For the first read-only hardware check, `tools/sony_wifi_probe/probe.py` opens
command/event sockets after a manual network join and requests standard PTP
DeviceInfo. Its packet summary contains types, sizes and supported operation
codes, without camera strings or serial numbers. This is a
diagnostic probe; the product implementation remains in the shared KMP core.

## a6700 observation, 2026-09-19

The maintainer ran the first probe against the camera and supplied its sanitized
JSON result. The camera acknowledged the command channel with protocol version
`0x00010000` (packet type 2, body 44 bytes), acknowledged the event channel
(type 4), and returned standard `GetDeviceInfo` success `0x2001` with 461
dataset bytes. Data arrived as start (type 9), data (type 10), end (type 12),
then operation response (type 7). This confirms the two-channel PTP/IP envelope
and the first standard operation on that camera. The older probe discarded the
dataset. The maintainer then reran the updated probe: the same 461-byte
DeviceInfo advertised `0x923a` (description files), `0x9210` (Sony session
opening), `0x9201` (Sony connect), `0x9216` (vendor code version), `0x9202`
(extended device info), and `0x9207` (device control). These are advertised
capabilities, not proof that later operations succeed in a remote session. The
probe now has an optional `--negotiate` path that follows the APK's
description/session/connect sequence, then sends `CloseSession`. Capture and
live view still await hardware confirmation.

The maintainer's negotiation result confirms the a6700's DID server version
`4.00` and vendor code version `310`. `SDIO_OpenSession` with remote-plus-transfer
mode, all three `SDIO_Connect` stages, `SDIO_GetExtDeviceInfo` with the version
flag and `CloseSession` each returned `0x2001`. The extended-info dataset was
866 bytes. That run's probe did not yet decode the extended control/property
lists or report whether DD includes a live-view URL. The updated optional probe
does both without printing the XML or URL.

The shared initializer now uses this confirmed branch, with a capability gate,
bounded DD/DID and extended-info parsing, and `CloseSession` cleanup. The APK's
`SDIO_ControlDevice` sends a data-out operation request (phase 2), start-data
packet and end-data packet. S1 (`0xd2c1`) and S2 (`0xd2c2`) button down/up
payloads are little-endian 16-bit values 2/1; vendor version 310 adds parameter
1 after the control code. The shared shutter implementation sends one S1-down,
one S2-down, then S2-up and S1-up in cleanup. These command bytes are from the
APK, not yet confirmed as a successful a6700 capture. The command response
alone does not prove an image was saved. The shared event monitor now parses
capture events, handles ping/pong, and closes the session on event-channel loss.

The APK's PTP/IP live view uses an HTTP URL from DD's
`X_ScalarWebAPI_LiveView_URL` or the `LiveViewUrl` device property. Its HTTP
body contains repeated 16-byte little-endian headers with image and focal-frame
offset/size pairs, followed by payload bytes. Which URL branch and frame values
the a6700 supplies was initially unconfirmed; see the active test below.

## a6700 active test, 2026-09-22

The maintainer ran the combined preview/capture probe and confirmed **no photo was
taken**. Negotiation again succeeded with DID `4.00`, vendor `310`, extension
version `300` and 866 extended-info bytes. The advertised controls include S1
`0xd2c1`, S2 `0xd2c2`, postview `0xd312` and live view `0xd313`. DD contains a
live-view URL, so the probe did not need the `0xd278` property fallback.

Enable/disable live view, S1-down, S2-down, S2-up, S1-up and CloseSession all returned
`0x2001`. The event channel stayed open and supplied fifteen `0xc203` events.
The APK identifies that code as `SDIE_DevicePropChanged`; it is not proof of a
capture. Neither `0xc206` (Sony captured event) nor `0x400d` (standard capture
complete) was observed. Successful control responses therefore do not validate
the shutter sequence on this camera.

Preview failed with `invalid_jpeg_markers`, after accepting the 16-byte frame
header and reading its payload. The old validator required both SOI at the start
and EOI at the exact end of the advertised image region, and did not distinguish
which check failed. Trailing region padding is a hypothesis, not an observed
camera fact. `LiveViewDataset.valueOf` passes the image offset/length directly to
Android's image decoder; it does not enforce that boundary check. The revised
probe walks JPEG segments and entropy markers to find EOI, accepts trailing region
bytes, and reports their count. On failure it preserves numeric frame offsets,
sizes and marker booleans without logging image bytes.

The APK's `StillShootMode.onMotionEventReceived` presses S1 on touch-down, then
presses S2 and releases both on touch-up. Our first probe submitted those commands
back-to-back. The optional `--half-press-ms 500` tests a human-like half-press
interval; no required delay or root cause has yet been established. The probe now
queries `SDIO_GetAllExtDevicePropInfo` (`0x9209`, parameters `[0,1]` for vendor 310)
before capture, after half-press and after release. Only selected numeric shooting
properties and S1/S2 availability enter the report. It does not change drive,
focus, save destination or restriction settings. A lost response never causes a
second press.

Shared Kotlin protocol/sequence tests and Python loopback tests validate our own
framing and cleanup. Real-camera capture, repeated decoded preview frames, phone
network joining and iOS authorization remain open acceptance gates.

### Successful still capture, subsequent run on 2026-09-22

The maintainer confirmed that a photo was taken in manual focus and single-shot
mode. The probe used `--half-press-ms 500` and read shooting properties between
S1-down and S2-down. It observed `0xc206` after two `0xc203` events, with successful
button releases and session closure. This validates the S1/S2 command path and
capture event on this camera in those conditions. The maintainer suspects manual
focus was not selected in the previous run; that and the changed timing prevent
attributing the earlier failure to one cause. Autofocus readiness still needs a
separate implementation and camera test. The shared shutter now includes the
500ms interval, with regression coverage for cancellation during the half-press.

This run's preview HTTP endpoint returned `503` before any frame data. It neither
confirms nor disproves the padding hypothesis. The updated probe follows
`BaseCamera.startLiveView`: in the negotiated remote-with-transfer mode it first
sends SetPostViewEnable (`0xd312`), then SetLiveViewEnable (`0xd313`).
`AbstractButtonWithVendorEvent` waits up to one second for `SDIE_OperationResults`
(`0xc222`) for postview: parameter 1 packs operation `0x9207` in the upper 16 bits
and control `0xd312` in the lower 16; parameter 2 is the result. Postview failure
does not prevent the APK's live-view enable attempt. The probe mirrors this and
disables both controls during cleanup. No postview photos are fetched.

The probe retries **only HTTP `503`**, up to three GET attempts, 500ms apart within
one preview deadline. This is a bounded recovery policy, not evidence that the
camera's `503` is transient. It never repeats a shutter or enable command.

All three `0x9209` property reads returned success with datasets of 10106, 9176 and
9452 bytes, but the diagnostic parser rejected a counted string. The failing
field was not identified. The revised parser consumes unused strings as opaque
counted bytes, preserving bounds checks and validating strings only when used as
the live-view URL. The APK also consumes counted strings without asserting their
terminators. Preview-only diagnostics now read selected numeric shooting properties
after enable, so the next check does not need another shutter press.

### Repeated preview frames, subsequent run on 2026-09-22

The preview-only probe received HTTP `200` on its first attempt and three JPEG
frames at 640×424. Postview returned vendor operation result `1` (`OK` in the
APK's `EnumOperationResult`), and enable/disable operations and CloseSession all
returned `0x2001`.

| Frame | Advertised image bytes | JPEG bytes through EOI | Trailing bytes | Focal bytes |
| --- | ---: | ---: | ---: | ---: |
| 1 | 15744 | 15710 | 34 | 136 |
| 2 | 16384 | 16262 | 122 | 136 |
| 3 | 18176 | 18165 | 11 | 136 |

The last header placed focal metadata at offset 24 and the image at offset 160:
16 header bytes, 8 reserved bytes, 136 metadata bytes, then the padded image
region. This confirms why requiring EOI at the region boundary failed. All
`decoded` flags were false: the probe performed structural JPEG checks without
Pillow, so this is not yet proof of pixel decoding on a phone.

The property parser now succeeded. Reported values included focus mode `1`, drive
mode `1`, remote restriction `0`, live-view status `0`, and save destination `17`.
Live-view status `0` accompanied a working stream; do not infer stream failure
from that value. No setting was changed and no shutter command was sent in this
run.

The shared pipeline now implements the observed preview controls and frame
layout. It matches postview results before advancing, tolerates postview failure
as the APK does, retries only HTTP `503`, strips JPEG padding, and exposes frames
and decoded Compose images with a latest-frame buffer. Event loss, cancellation,
rejected enable, and frame timeout all run stream/control cleanup. Structural
parsing and pixel decoding run off Main. Android opens HTTP through the camera's
`Network`, with redirects disabled and bounded reads. The first shared endpoint
resolver supports the confirmed IPv4 DD URL path only. A synthetic JPEG fixture
tests the frame layout and native iOS decoder without retaining camera imagery.
### Android manual-join application slice, 2026-09-22

The shared controller and Compose remote screen are now wired into the Android
application graph and device details. The first path selects an already joined
Wi-Fi network, checks that the entered literal IPv4 address is on its local subnet,
and binds command, event and HTTP sockets to that network. The camera HTTP reader
handles fragmented headers and chunked/length-delimited bodies without redirects
or a global cleartext policy exception. Android 17 local-network permission is
requested at connection time. No BLE credentials are retrieved by this path.

The controller holds one foreground session, releases preview/shutter controls
before closing sockets, and suspends the selected camera's BLE shutter probes and
presses until cleanup completes. Displayed state lives in `CameraSession`; only
the latest decoded image is kept separately. Capture confirmation requires the
capture event; missing confirmation is reported as uncertain and never retried.

Both Android flavors compile and a FOSS debug APK builds. Shared Android host and
iOS simulator tests pass. No Android phone was attached for this implementation
run, so actual phone pixel decoding, repeated capture and lifecycle behavior still
need the [Android test](wifi-remote-android-testing.md). iOS networking and automatic
network joining were deferred for the first Android slice.

### Android preview confirmation and automatic setup implementation

The maintainer subsequently reported **live preview works** in the Android app.
This confirms Android pixel decoding/display through the manual Wi-Fi path on the
a6700. The report did not yet confirm app capture, rotation or long-session behavior.

Automatic connection now follows the inspected BLE setup: write CC08 `{01}` once,
poll the CC09 type-1 Wi-Fi status until launched, then read CC06/CC07 and optional
CC0C. The APK parser maps Wi-Fi states 0/1/2/3 to off/starting/on/stopping, with a
separate error byte. Unknown/missing status falls back to bounded credential reads.
Those reads skip the APK-observed three-byte prefix; credentials stay out of state
and logs. BLE bootstrap itself is still awaiting a6700 confirmation.

Android 10+ uses `WifiNetworkSpecifier` with exact SSID, WPA2 passphrase and optional
valid BSSID. An app-owned network request handles approval, cancellation and loss;
network-bound sockets open only after IPv4 link properties arrive. The endpoint is
the unique local IPv4 default gateway, following the APK's `WifiUtil.getDhcpInfo`
route lookup. No fixed Sony IP or home-router discovery is assumed. This automatic
Android flow is implemented and regression tested, not yet hardware validated.
