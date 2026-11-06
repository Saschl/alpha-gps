# Wi-Fi remote protocol evidence (work in progress)

Read the [current handoff](wifi-remote-handoff.md) for the latest validation state and the
unresolved Sony Camera Control PTP 2 deprecation question. Observations below are chronological;
later hardware results supersede earlier pending gates.

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

### Automatic connection and app capture confirmed

The maintainer reports that automatic setup works well, including taking pictures. This validates
BLE startup/credential retrieval, Android joining, and app capture on the tested phone/a6700
combination. They also observed that the connection closes when the display turns off. The Android
remote host currently closes the session on `ON_STOP`, including screen-off; this follows its
initial foreground-only policy. The remote host now sets Android's view-level `keepScreenOn` while
this camera owns the session, including setup and cleanup. It restores the previous value on
disconnect or disposal. This prevents inactivity timeout during remote use; deliberate
locking/backgrounding still closes the foreground session.

### Wi-Fi shutdown on normal disconnect

The APK's `wifi/WiFiOffCommandUtil.sendWifiOffCommand` uses the active PTP/IP client, not BLE CC08.
It prefers `WiFiDirectModeOff` (`0xd308`) when property
`WiFiDirectModeOffEnableStatus` (`0xd12b`) allows it, with `WiFiPowerOff` (`0xd2e8`)
and `WiFiPowerOffEnableStatus` (`0xd296`) as the fallback. The maintainer's a6700 extended-info
report advertises both controls and both properties.

Availability uses `mIsEnable` 1 or 2 (True or DispOnly) and the current property's low 16 bits equal
to 1 (Enable). The enum-restored Java output loses constructor arguments, so this value was verified
by re-decompiling the two enable-status enum classes in jadx fallback mode: Undefined=255,
Disable=0, Enable=1. The button path sends `SDIO_ControlDevice` down=2 and then up=1 after a
successful down response, with the vendor-310 option parameter 1, as with other Sony controls.

The shared shutdown helper reads fresh `0x9209` properties and gates on both advertised capabilities
and current availability. It consumes unused property strings/arrays without decoding, rejects
malformed/ambiguous datasets, bounds each operation and never retries a shutdown press. The
controller invokes it only for normal closure of an established session, after preview/shutter jobs
finish but before sockets and Android's network request are released. Network loss skips it. The UI
reports a submitted request separately from unavailable/unconfirmed; a dropped socket is not
evidence of Wi-Fi power-off. Actual a6700 shutdown and subsequent automatic reconnection remain to
be tested.

## Existing-photo browsing and original downloads (2026-09-22)

The maintainer selected **browse and download existing camera photos**, not automatic post-capture
transfer. The shared browser and Android storage integration are now implemented. All protocol
statements below are APK evidence, covered by synthetic regression tests; no a6700 browsing/download
result has been recorded yet.

There are two distinct APIs in Creators' App 3.5.0:

- `camera/PtpIpCamera.onPtpInitialized` creates `transfer/ptp/PtpContentsRoot` for
  `REMOTE_CONTROL_WITH_TRANSFER_MODE` (the a6700's negotiated mode). This uses 64-bit content/file
  IDs, **not** standard PTP object handles.
- `onMtpInitialized` creates `mtp/mtpobject/MtpRoot`. This uses the older object browser. Its
  advertised-capability fallback is included, but existing remote initialization has not been
  hardware-validated with older MTP-only cameras.

The version-4 path (`SonyContentCatalog`):

1. Stop live view and wait for its control cleanup. `PtpContentsBrowser` enables browsing through
   `SetSelectOnCameraTransferEnable`: `0x9207`, control `0xd30f`, 16-bit down value `2`. Vendor
   version 310 adds parameter `1`. Subscribe to the
   `0xc222` operation result before sending; result `1` confirms the transition. Exit uses up value
   `1` and requires confirmation before restarting preview.
2. `BasePtpManager.getContentsInfoList` / `ContentsInfoListGetter` use `0x923c`
   parameters `[timeLow, timeHigh, count, slot, useLocalTime]`. The implementation follows
   `PtpSimpleDifferencePullContainer`'s UTC traversal from time `0`, count `100`, slot `1`,
   local-time flag `0`. It deduplicates boundary records and follows the APK's last-timestamp cursor
   rule. The full catalog is read before the first page so photos can be ordered newest first; UI
   pages contain 40 captures. Files sharing a slot and content ID appear as one thumbnail, with
   individual originals offered in the details sheet's format selector. A capture containing RAW
   uses the RAW preview rather than its JPEG/HEIF companion. Slot 2 selection is not implemented
   yet.
3. The dataset contains management info (version + reserved), database timestamp, 64-byte
   physical-media and database IDs, slot, record count, and variable
   `ContentInfo` / `ContentFile` records. Optional video/audio metadata must be consumed even though
   videos are excluded from this photo browser. File IDs
   `1/2/3` identify original JPEG/RAW/HEIF (`FileIdUtil`). Unique IDs are
   `(slot << 56) | (fileId << 32) | unsignedContentId` (`PtpPullItem`).
4. Thumbnails: `0x923e [idLow, idHigh, 1]`, from
   `ContentsCompressedDataGetter`. Bound compressed bytes and decoded dimensions; a missing
   thumbnail does not prevent downloading the original.
5. Original bytes: `ContentsDataGetter` uses `0x923d`
   `[idLow, idHigh, offsetLow, offsetHigh, length]`. Alpha GPS requests 512 KiB chunks (the APK uses
   up to 3 MiB), verifies every returned length, and streams to storage. The first two response
   parameters, when supplied, contain the modification timestamp; reject a newer timestamp. Re-read
   the content record and compare both media identity and file metadata before starting a download.
   No delete or camera-file mutation operation is sent.

Older object browsing (`SonyImageTransfer`) follows `ContentsTransferModeSetter`:
`0x9212 [2,1,0]`, wait for `0xd295` via `0x9209`, enumerate `0x1004` storage IDs and
`0x1007 [storage,0,0]` handles, read `0x1008` ObjectInfo, optionally `0x100a`
thumbnails, prefer `0x9211 [handle,offsetLow,offsetHigh,length]` original reads. Standard `0x101b`
is used only when advertised without `0x9211`. Exit is
`0x9212 [2,0,0]`. These are card objects; the post-capture fixed handle
`0xffffc001` and its free-handle operation are deliberately not used. The older browser reads object
metadata before paging so pairs cannot straddle pages. Pairing uses storage ID, parent folder,
filename stem and capture timestamp; matching filenames on different cards or in different folders
stay separate.

All operations share the existing command queue. It now accepts a per-operation maximum data length;
photo chunks cannot cause full-image buffering. Downloads are foreground-only, limited to 512
MiB/file and five minutes, with no automatic retry. Cancellation, storage failure and disconnect
abort unpublished files. Android 10+ uses pending MediaStore entries under `Pictures/Alpha GPS`;
Android 8/9 use temporary files, unique final names and media scanning after requesting write
permission. Storage I/O is off Main. See Android's
[shared-media guidance](https://developer.android.com/training/data-storage/shared/media). No image
payloads, filenames or credentials are logged.

## ZV-1 Android investigation, 2026-10-16

Reported symptoms: automatic Wi-Fi setup fails; after manual connection, live view
fails and a shutter press appears to take a photo without app confirmation. The
maintainer confirmed Android; no device logs or firmware version are available yet.
These are reported symptoms, not a captured ZV-1 protocol trace.

The root APK still matches the SHA-256 above. Recreated its sources with installed
`jadx` 1.5.6:

```bash
jadx -d /private/tmp/sony-zv1-review --no-res Creators.apk
```

The full decompile reports 171 errors; the methods cited below have readable bodies.
Paths below are relative to `sources/jp/co/sony/ips/portalapp/` in that directory.
The original temporary directory from September was no longer present.

Sony's [ZV-1 remote-control guide](https://helpguide.sony.net/dc/1910/v1/en/contents/TP0002870036.html)
specifies Imaging Edge Mobile and starting the connection through **Ctrl w/
Smartphone → Connection**. Sony's [app compatibility explanation](https://www.sony.com/electronics/support/e-mount-body-zv-e-series/zv-e10l/articles/CCCA99004)
lists ZV-1F and ZV-1 II for Creators' App, not the original ZV-1. Creators' App
contains useful legacy branches, but its presence alone does not establish ZV-1
behavior. Imaging Edge Mobile's APK is the next reference to inspect; a firmware
update should not be presented as an established fix.

### Confirmed implementation differences

| Area | Creators' App evidence | Alpha GPS behavior |
| --- | --- | --- |
| Legacy preview startup | `camera/BaseCamera.java:461`, `startLiveView`: outside `REMOTE_CONTROL_WITH_TRANSFER_MODE`, attaches the stream directly. Only that newer mode sends postview `0xd312`, then live-view `0xd313`. `ptpip/initialization/Initializer.java:58` selects that mode for server version ≥4.0. | `SonyLiveViewStream.frames` requires advertised `0xd313` and always sends it. Initialization handles older sessions, but preview does not preserve that distinction. |
| Preview endpoint | `camera/PtpIpCamera.java:707` falls back to enabled/display-only `LiveViewUrl` (`0xd278`) from device properties when the URL is missing. `ptpip/PtpIpClient.java:862` opens the HTTP downloader using that URL. | `SonyWifiSession.kt:67` requires DD XML and resolves only `X_ScalarWebAPI_LiveView_URL`. No property fallback. `SonyCameraProperties` currently skips all string values. |
| Shutter commands | `ptp/remotecontrol/controller/shoot/mode/StillShootMode.java`: S1 down on touch-down; S2 down, S2 up, S1 up on touch-up. | `SonyPtpShutter` uses that sequence, with a 500ms half-press interval. No contradictory shutter bytes found. |
| Capture feedback | `ptpip/BasePtpManager.java:1381` registers `0xc206`, but `BasePtpManager$capturedEventCallback$1.java` only logs. Separately, `ptpip/postview/PostViewStream.java:91` consumes `ShootingFileInfo` (`0xd215`): low 15 bits supply the download count, bit `0x8000` indicates postview readiness; ready data triggers `GetObjectInfo(0xffffc001)`. | `PtpIpEventMonitor` confirms only `0xc206` or standard `0x400d`, waiting 20 seconds. It does not refresh shooting-file properties on `0xc203` or observe postview readiness. |

The preview differences are definite gaps relative to Sony's legacy paths and
plausible explanations for this report. They are **not yet confirmed ZV-1 causes**:
we do not know this camera's DD, server version, advertised controls, or property
values. The capture difference identifies an investigation path, not permission
to label any property-change event or nonzero pending-download count as a new
saved photo. Pre-existing pending images must not confirm a later shutter press.

Automatic setup uses the same CC08 `{01}` / CC06 / CC07 family as
`btconnection/internal/state/TurningWifiOnState.java` and
`GettingWifiInfoAfterWifiOnState.java`. Alpha GPS exits if any of those required
characteristics is absent. Without the ZV-1 GATT inventory or a failure-stage log,
absence, rejection, status timeout and Android network-join failure remain distinct
possibilities; the APK does not establish which occurred.

### Next evidence and implementation targets

- Obtain the Imaging Edge Mobile APK and a ZV-1 session log/capability report.
  Identify server/vendor versions, DD URL presence, `0xd278`, `0xd313`, and event
  codes around one independently verified photo. Keep credentials and image data
  out of diagnostics.
- Add preview mode selection matching initialization and the property URL fallback,
  with regression tests covering legacy HTTP startup and the existing ≥4.0 path.
  Preserve the existing camera-peer URL validation and serialized command queue.
- Investigate capture feedback using before/after `0xd215` state and actual events;
  retain uncertain results and never retry an uncertain shutter automatically.
- The existing Python probe already supports the `0xd278` fallback and skips
  `0xd313` if unadvertised, unlike the app. A preview-only probe can help isolate
  these gaps. Its capture diagnostics still use the same two capture-event codes
  and do not currently report `0xd215`.

This investigation changes documentation only. No app fix or ZV-1 hardware
validation has been performed.

## Imaging Edge Mobile follow-up, 2026-10-16

The maintainer supplied `remote-app-playmemories-mobile.xapk` in the repository
root. Its manifest and the base APK's AndroidManifest identify
`com.sony.playmemories.mobile`, version **7.8.5**, version code **785260216**.

- XAPK SHA-256: `8752b015785caf2211052bc63451a2fb748eb8b60c226b68539b7f8837d23279`.
- Base APK SHA-256: `05cdfe10e66cfcfc0a96acf36168842095fbd8d9f1adec10352a7e10531a9715`.
- Extracted APKs: `/private/tmp/sony-iem-zv1-apks/` (base, arm64, xxhdpi, English).
- Decompiled base: `/private/tmp/sony-iem-zv1-review/`, using JADX 1.5.6; 24
  decompilation errors overall. No native-code inspection was needed for these paths.
- Source paths below are relative to `sources/com/sony/playmemories/mobile/`.

Reproduction after extracting the base APK:

```bash
jadx -d /private/tmp/sony-iem-zv1-review \
  /private/tmp/sony-iem-zv1-apks/com.sony.playmemories.mobile.apk
```

### Legacy BLE handover is a separate path

`btconnection/internal/BluetoothStateMachine.java:331` selects
`TurningWifiOnState` only when manufacturer-data version is `25856` (`0x6500`).
Otherwise it selects `GettingWifiInfoState`. This is an advertised protocol field,
not the camera firmware version. `ManufacturerData.java` reads it from bytes 4–5
of its manufacturer record, including the two-byte company identifier.

The older `GettingWifiInfoState` connects, discovers services, requests MTU, then
subscribes to **CC05**. Its callback interprets notification byte 3 as 0 = starting,
1 = launched; failures use byte 4. On launched, it reads CC06 then CC07. It does
not write CC08. The normal decompile duplicated branches in the status callback;
a second `--decompilation-mode simple` pass confirmed this flow in
`/private/tmp/sony-iem-GettingWifiInfoState-simple.java`.

This is a missing path in Alpha GPS, whose bootstrap requires CC08 and uses CC09
status. It does **not** prove the ZV-1 can automatically enter remote shooting:
`BluetoothLeUtil.isAvailableWifiHandover` checks advertised handover supported/enabled
flags, and the examined caller, `SingleBleCommandManager.getNeedToExecuteFunction`,
selects handover when the camera is off. The legacy flow also handles “no contents
in media,” consistent with an image-transfer use case. A blind CC05 fallback for
an already-connected GPS session is not justified by this code alone. Capture the
ZV-1's advertised capabilities and verify the mode it actually starts first.

### Live view confirms both missing legacy branches

`camera/PtpIpCamera.java:525` (`onPtpInitialized`) gets `LiveViewUrl` (`0xd278`)
from the initial property snapshot if the DD URL is absent, accepting enabled or
display-only properties. `startLiveView` at line 595 calls
`PtpIpClient.setLiveViewStreamCallback` directly: no `0xd312`/`0xd313` enable commands.
`ptpip/PtpIpClient.java:816` creates the HTTP downloader. The 16-byte image/metadata
offset-and-size frame header remains the same layout (`ptpip/liveview/http/`
`AbstractEeImageDownloader.readImageData`), so this evidence points to startup and
endpoint resolution, not a need to replace PTP/IP with the older JSON API.

There is an additional mode-specific detail: this app starts/stops preview based
on `LiveViewStatus` (`0xd221`) and accepts low-byte value 1 as enabled
(`PtpIpCamera.canGetLiveView`, line 285). Do not impose that rule on the a6700:
the September trace above had a working stream while this property was 0.

### Capture feedback and an event-name correction

`ptpipremotecontrol/controller/shoot/mode/StillShootMode.java` again matches our
S1-down → S2-down → S2-up → S1-up sequence. The initial property read is
`0x9209 [0]`. `ptpip/PtpIpManager.java:275` registers the property updater for
`0xc203`, named `SDIE_ObjectPropChanged` in this APK. Each notification requests
`0x9209 [1]` through `DevicePropertyUpdater.onEventReceived` and
`DevicePropertyGetter.execute`; `PostViewStream.onDevicePropertyChanged` then
uses `0xd215`'s pending count and readiness bit as described above, with
`GetObjectInfo(0xffffc001)` when ready. The postview downloader reports download
count, progress, success and failure to the UI. This path does not wait for our
two capture-confirmation events.

**Do not generalize the Creators' App event name across generations.** Imaging
Edge Mobile's enum initializer names **`0xc206` `SDIE_ExposureStarted`**, whereas
Creators' App names it `SDIE_CapturedEvent`. The normal decompiler's “fake field”
output was ambiguous, so the numeric code and original string were checked in a
`--decompilation-mode fallback --no-replace-consts` pass of
`com.sony.playmemories.mobile.ptpip.base.packet.EnumEventCode`, saved as
`/private/tmp/sony-iem-EnumEventCode-fallback.java` (lines 134–135).
The name is evidence against treating it as universal saved-photo confirmation;
it does not establish what the ZV-1 emits or invalidate the observed a6700 photo.

Implementation priority remains legacy preview startup plus property URL discovery,
then capability-aware capture feedback. BLE auto-start needs separate capability
and mode validation. The supplied APK strengthens the compatibility diagnosis but
does not replace a ZV-1 log or camera test. Only this evidence document was changed;
no app code or tests changed.

## Legacy live-view implementation, 2026-10-16

The maintainer requested the live-view changes. `SonyLiveViewStream` now uses the
same server-version decision as Sony session initialization: remote-with-transfer
sessions (server ≥4) retain postview/live-view controls; legacy sessions open HTTP
directly and send no enable or disable controls, even if those controls appear in
the capability list.

`SonyLiveViewEndpointResolver` prefers the DD URL and falls back to `0xd278` when
the description or URL is absent/empty. It reads `0x9209 [0]` (or `[0,1]` for vendor
version ≥310) through the existing command queue, checks property availability,
and applies the same camera-peer URL validation. Invalid or ambiguous DD URLs fail
rather than triggering fallback. Selected property strings are decoded with bounds
and terminator checks, remain in memory, and are redacted in diagnostic strings.

Regression coverage includes both startup modes, legacy cancellation/disconnect,
DD preference, both property-read parameter formats, missing capabilities,
disabled/missing/malformed URL properties, rejected/timed-out reads, and camera-peer
validation. Capture confirmation, BLE Wi-Fi handover and `LiveViewStatus` gating
are outside this change. Actual ZV-1 preview remains to be verified on the camera.

Validation passed: 327 Android host tests and 373 iOS simulator tests, including
13 new regression tests on each platform; `compileAndroidMain`, both Android
flavor debug Kotlin compiles, `compileKotlinIosArm64` and
`compileKotlinIosSimulatorArm64`. The initial offline Android invocation needed
uncached Kotlin build dependencies; the online build succeeded, followed by a
successful offline final regression run.

## Automatic-setup review after live-view commit, 2026-10-16

Live-view implementation and regression tests were committed as `7a92034`.
Further tracing of the legacy handover confirms a distinction from our foreground
remote-shooting bootstrap:

- Imaging Edge's `PowerOnOffDeviceListController` routes manufacturer version
  `0x6400` to its legacy power-control UI and `0x6500` to the newer home UI.
  `SingleBleCommandManager.getNeedToExecuteFunction` chooses Wi-Fi handover only
  for a camera reported off, with advertised handover support/enabled flags;
  a camera reported on selects power-off instead.
- Sony's [ZV-1 power-off connection guide](https://helpguide.sony.net/dc/1910/v1/en/contents/TP0002886242.html)
  explicitly describes this workflow as browsing/transferring images: turn the
  camera off, use Imaging Edge's remote-power screen, then access its card. Turning
  the camera on normally switches to shooting and ends Send to Smartphone.
- Sony's [ZV-1 Android remote-shooting guide](https://helpguide.sony.net/dc/1910/v1/en/contents/TP0002870036.html)
  instead starts the connection from **Ctrl w/ Smartphone → Connection**.
- Alpha GPS starts automatic setup only with an active transmitting BLE session
  (`CameraSessionOrchestrator.prepareCameraWifi`), requires CC08/CC06/CC07, and
  has no manufacturer-version/handover-capability input in `WifiBootstrapBlePort`.
  The credential parser's three-byte prefix matches Imaging Edge; no alternative
  credential encoding was found to explain the report.

Conclusion: CC05 is not yet a justified fallback for starting ZV-1 remote shooting
on the existing GPS connection. Do not send it merely because CC08 is absent or
rejected. Keep manual remote startup for this case until camera evidence establishes
another path. A useful next diagnostic should distinguish missing CC08/CC06/CC07,
start-write rejection, status/credential timeout and Android join failure, without
logging credentials. Neither the camera's actual GATT inventory nor its failure
stage has been captured, so its precise failure remains unconfirmed. No automatic
setup behavior was changed by this review.

## Manual Android address discovery, 2026-10-17

Imaging Edge Mobile 7.8.5's `ptpip/base/tcpip/TcpConnection` constructor uses
`WifiManager.getDhcpInfo().gateway` when no camera address is supplied, then opens
PTP/IP on port 15740. Alpha GPS already resolves the gateway after automatic joining
on both platforms, and after BLE-assisted manual joining on Android 8–9. The ordinary
manual connection path previously required a typed address.

Android manual connections now accept a blank IP field and reuse `cameraGateway`
on the single connected non-VPN Wi-Fi network. Explicit addresses still override
discovery; absent or ambiguous gateways report `CameraAddressUnavailable`. Sockets
remain bound to that network, and the existing peer/subnet checks still apply.
The shared UI advertises this only when the connector supports address discovery;
manual iOS connections still require an address.

For a ZV-1, start **Ctrl w/ Smartphone → Connection** on the powered-on camera,
join its Wi-Fi in Android Settings, then use Alpha GPS's manual connection with
the IP field empty. This implements the direct camera-AP gateway fallback, not
SSDP discovery on a shared router. It does not change BLE Wi-Fi activation or use
the power-off image-transfer handover. Real-camera validation is still required.

Validation: 333 Android host tests passed, including five address-resolution
regressions and a shared controller regression proving a blank manual address
does not invoke automatic BLE setup. Both Android flavor Kotlin compiles and both
iOS target compiles passed (`/private/tmp/sony-manual-address-build.log`).

## Imaging Edge transfer-mode investigation, 2026-10-18

Inspected the same Imaging Edge Mobile 7.8.5 APK and decompile listed above.
This is static APK evidence, not a ZV-1 transfer-session capture. The app selects
the transport from the camera's advertised services; the inspected routing does
not contain a ZV-1-specific protocol selection.

### Discovery selects the transfer backend

`common/device/DeviceDescription.java:61` (`getControlModel`) distinguishes:

- PTP information with server version below 3.0: PTP/IP remote shooting.
- PTP information with server version at least 3.0 and media-server support:
  `SelectFunction`, offering remote shooting and MTP transfer.
- Advertised UPnP `ContentDirectory:1` service: the DLNA pull browser, when the
  device is recognized as a Sony imaging device.
- Sony `XPushList:1`: the separate camera-selected/push transfer path.
- Scalar WebAPI services/default function: the existing WebAPI routes.

`common/device/did/DigitalImagingDescription.java:598` parses
`X_PTP_Versions`, `X_PTP_MediaServerSupport` and `X_PTP_RemoteControlSupport`.
`common/device/did/DeviceInfo.java:37` also recognizes the server type
`Send to Smartphone`. These describe the currently advertised connection mode;
remote shooting support alone does not establish the transfer backend.

### PTP/MTP transfer uses a distinct session

1. `selectfunction/controller/FunctionModeController.java:64` routes the transfer
   button to `CONTENTS_TRANSFER_MODE` and the MTP photo-list UI.
2. `camera/PtpIpCamera.java:617` stops live view when switching away from remote
   shooting. `ptpip/PtpIpClient.java:985` marks the session as switching and
   terminates it. `ptpip/termination/Terminator.java` sends `CloseSession`, then
   creates a new PTP/IP manager and initializes the requested mode.
3. `ptpip/initialization/Initializer.java:45` uses `SDIO_OpenSession` (`0x9210`)
   with parameters `[1, functionMode]` when MTP is enabled, otherwise standard
   `OpenSession [1]`. The MTP flag comes from the DID media-server support field.
   Remote mode is **0**, contents-transfer mode is **1**. Verified independently
   with JADX fallback output at `/private/tmp/sony-iem-function-mode-fallback.java`.
   The normal `PtpIpCamera` constructor decompile has contradictory duplicated
   branches; its inferred default-mode assignment is not used as evidence here.
4. The normal Sony three-connect handshake follows. Transfer initialization also
   starts storage discovery and MTP object-property capability discovery
   (`ptpip/PtpIpManager.java:290`, `ptpip/mtp/ObjectPropInitializer.java`).
5. `mtp/mtpobject/MtpRoot.java:159` starts browsing after MTP initialization.
   `mtp/browse/SetContentsTransferMode.java:89` sends `0x9212 [2,1,0]` for
   selection on the phone. `ptpip/mtp/ContentsTransferModeSetter.java` watches
   transfer events and the enabled status property (`0xd295`).
6. This browser uses object handles and MTP property lists (`GetObjectPropList`,
   `0x9805`). `ptpip/mtp/ObjectGetter.java` downloads via `0x9211`, passing the
   handle, 64-bit offset and chunk length (3 MiB in Imaging Edge).

### DLNA transfer is a separate browser and HTTP downloader

`common/device/SsdpUtil.java:270` extracts the ContentDirectory control URL from
the discovered device description. `cds/action/BrowseDirectChildren.java` sends
a SOAP `ContentDirectory:1#Browse` request containing object ID, starting index,
requested count and sort criteria. `cds/action/parser/CdsItemParser.java` parses
the returned item metadata and resource URLs. `cds/object/CdsItem.java:446`
selects the JPEG resource for the requested image size; `cds/Copy.java` passes
that URL to `common/content/download/ContentDownloader.java` for HTTP download.
This path does not use our PTP object-handle downloader.

### Alpha GPS implications

`SonyPtpInitializer` currently selects standard `OpenSession` for legacy sessions
or `0x9210 [1,2]` for version-4 remote-with-transfer sessions. It does **not** select
the Imaging Edge MTP transfer session `[1,1]`, parse the media-server support
flag, or reconnect between remote and transfer sessions.

`SonyImageTransfer` already has the later `0x9212`/`0xd295` object-browser flow,
but that does not replace the session-mode prerequisite. Its older object metadata
path uses `GetObjectInfo`, rather than Imaging Edge's MTP property-list browser.
There is no ContentDirectory/SOAP photo browser in Alpha GPS.

Therefore the previous "older-camera fallback exists" statement is not proof of
ZV-1 browsing compatibility. A ZV-1 DD/DID/service advertisement while in
**Send to Smartphone → Select on Smartphone**, compared with remote shooting,
would identify which backend to implement/test. Keep the camera powered on for
this investigation; the CC05/power-off handover is unnecessary. No application
behavior was changed by this investigation.

### Capability-based PTP/MTP implementation, 2026-10-18

The shared session now implements the separate MTP transfer mode:

- Parse the DID's explicit `X_PTP_MediaServerSupport=Enable`. A version-3 server
  must also advertise `SDIO_OpenSession` before the separate-session path is used.
  Version 4+ keeps the existing combined remote-with-transfer mode. Missing,
  unknown or disabled media-server support does not enable the new path; duplicate
  capability tags are rejected.
- For the separate-session path, remote shooting uses `0x9210 [1,0]`. Opening
  the photo browser closes both old channels and creates new channels with the same
  client GUID and network lease, opens `0x9210 [1,1]`, repeats Sony initialization,
  and re-reads device capabilities after entering transfer mode.
- Browser entry still checks the transfer session's advertised operations and
  transfer-enabled property before issuing browse/download commands. Metadata uses
  `GetObjectInfo` where advertised, with a bounded per-object `GetObjectPropList`
  fallback (`[handle,0,0xffffffff,0,0]`, at most 64 KiB). The fallback accepts 64-bit
  sizes and validates handles, property types, filenames and dataset boundaries.
- Returning to shooting disables transfer browsing, closes the transfer session,
  opens remote mode again, and restarts live view through the existing controller.
  Failed entry/exit can be retried; failed exit does not resume shutter/live view.
  Saved-download markers are cleared across these session changes because MTP
  handles may be reused. Final disconnect ends transfer mode without reopening
  shooting, then closes sockets and releases the network exactly once.
- Loss monitoring follows the active session. Deliberate socket closure during a
  switch is detached from the loss monitor; real event-channel/network loss still
  ends the connection. Partial sessions are closed on cancellation or failure.

This uses DID data already available through `SDIO_GetDeviceDescriptionFile`.
It does not add SSDP/HTTP device-description discovery for cameras that cannot
provide that data through PTP, or the DLNA transfer backend. ZV-1 support therefore
still depends on its advertised capabilities and needs hardware confirmation.

Validation: 358 shared Android-host tests passed, including 17 new capability,
session lifecycle and MTP metadata tests. Both Android flavors and both iOS targets
compile. Synthetic tests exercise download through both metadata paths, late
capability advertisement, rejected/timed-out/cancelled switches, return to live
view, real loss, and disconnect without a remote-session reopen.
