# Sony time, timezone and DST investigation

Investigated 2026-09-23 for [issue #152](https://github.com/Saschl/alpha-gps/issues/152).
No application behavior was changed.

## Findings

- Alpha GPS already implements the two time-data formats found in Creators' App.
  The inspected code does not reveal a missing DST field or a model-specific
  alternative encoding in these BLE paths.
- Automatic correction settings are distinct from sending time data. The disabled
  UI controls use the same characteristics and values as Sony's app.
- The absence of those settings in the α6700's camera menu does not mean that the
  settings do not exist: Sony documents configuring them from the phone.
- The APK cannot establish which characteristics an α7 III exposes or whether its
  firmware applies the received DST field to the camera's Daylight Savings switch.
  An affected-camera capture is needed to distinguish those cases.
- Alpha GPS does not inspect the camera's application-level result for its direct
  time-setting command. A successful BLE write is not proof that time was applied.

## Evidence and reproduction

Inspected the repository's `Creators.apk`, package `jp.co.sony.ips.portalapp`,
version **3.5.0**, version code **350607302**.
SHA-256: `b0716bf5b02b7c723d54d670d97c1c6c6daf0519a5ded5330fc4d9f3ff443f09`.

Decompile with `jadx -d /private/tmp/alpha-creators-time-sync --no-res Creators.apk`.
JADX reported 170 errors across the APK; the relevant methods were emitted and
readable, although the location serializer contains duplicated decompiler output.
This is static inspection, not a camera test or a bytecode execution test.

Source names below are relative to the decompiled
`sources/jp/co/sony/ips/portalapp/` directory.

| Purpose | Sony implementation | Alpha GPS comparison |
| --- | --- | --- |
| Automatic time correction | `DD32`, one byte `01` on / `00` off | Same in `CameraAutoCorrectionController` |
| Automatic area adjustment | `DD33`, one byte `01` on / `00` off | Same in `CameraAutoCorrectionController` |
| Location capability | `DD21` byte 4, mask `0x02` | Same in `RemoteControlCoordinator.hasTimeZoneDstFlag` |
| Periodic time data | `DD11`, UTC date/time at bytes 19–25 | Same layout in `LocationPacketBuilder` |
| Extended location packet | 95 bytes; signed big-endian standard UTC offset in minutes at 91–92, current DST adjustment in minutes at 93–94 | Same layout; platforms calculate standard and DST offsets separately |
| Basic location packet | 91 bytes, no timezone/DST fields when capability is absent | Same behavior |
| Direct time setting | `CC13`, 13 bytes: local date/time, DST boolean at 10, signed standard-offset hours at 11, absolute offset minutes at 12 | Same layout in `buildTimeSyncPacket` |

For example, Oslo in summer has standard offset +60 minutes and DST +60 minutes.
The extended location packet carries `00 3C 00 3C`; its date/time remains UTC.
The direct command carries local time, DST `01`, offset hours `01`, minutes `00`.
Adding DST into the standard-offset field would diverge from Sony's encoding.

Sony's location packet uses the location fix's timestamp and rejects fixes at
least ten seconds old. Alpha GPS uses the current phone clock when building the
packet. This is a behavioral difference, but it does not explain a missing DST
toggle by itself. Both derive timezone/DST from the phone's configured timezone,
not from latitude/longitude; a phone left on its old timezone transmits that zone.

Relevant methods:

- `btconnection/internal/state/TransferringLocationInfoWithLockState.java`:
  `onGattCharacteristicRead`, `onGattCharacteristicWrite`, `onLocationUpdated`.
- `btconnection/BluetoothLeUtil.java`: `setLocationAndTime`.
- `btconnection/internal/BluetoothGattUtil.java`: `serializeTimeAreaData`.
- `btconnection/internal/state/ChangingAutoCorrectionState.java`:
  `onEnter`, `onGattCharacteristicWrite`.
- `setup/LocationTransferSetupController.java`: camera setting listeners and
  `onClickedAutoCorrectionSetting`.

## Settings and command constraints

Sony's location-transfer path reads `DD32` and `DD33` after enabling `DD31`, then
reads the `DD21` capability. It exposes the camera's values in the phone UI.
The inspected path does not automatically write both correction settings on at
every connection. User actions issue the setting writes.

Setting writes can fail with status **157 / 0x9D**. Sony explicitly maps this to
`Unavailable`, restores the previous value, and displays “Check the camera
settings.” The code does not specify the camera modes or conditions causing that
response. Characteristic presence alone therefore does not guarantee writability
in the camera's current state.

Alpha GPS already reads these optional settings after handshake completion.
Only the two rows in `DeviceDetailScreen.kt` are commented out. The backend
detects absent characteristics and preserves the old value on write failure.
Showing these controls is supported by this evidence, but should not be presented
as a guaranteed legacy-camera DST fix.

For direct time setting, Sony's `SettingTimeAreaState` waits for a notification on
**CC0E**. `BluetoothCharacteristicParser.parseCameraInitialSettingResult` recognizes
time/area result type **0x0004**; byte 3 equals `01` for success, and bytes 4–7
carry a failure reason otherwise. Its successful GATT-write callback does not
complete the operation. The separate `SettingSetupTimeAreaState` also handles
this result for the setup flow.

Alpha GPS's `BleSessionCoordinator` writes `CC13` once during each handshake if
present, then proceeds to GPS transmission on the write callback, even on failure.
It does not process `CC0E`. GPS should remain independent of time-setting failure,
but diagnostics must distinguish “write completed” from “camera applied time.”
A timezone/DST change during a continuous connection updates subsequent location
packets; it does not trigger another direct `CC13` command.

## Model evidence and issue interpretation

- [α7 III help guide](https://helpguide.sony.net/ilc/1720/v1/en/contents/TP0001662032.html):
  automatic time correction and area adjustment are camera menu settings under
  `Loc. Info. Link Set.`. Location linking must be enabled; Sony also requires
  Bluetooth remote control to be off for location linking on this model.
- [α6700 help guide](https://helpguide.sony.net/ilc/2320/v1/en/contents/202h_smartphone_location_info.html):
  automatic time correction and area adjustment are configured in Creators' App.
  Missing camera-menu entries are not evidence of absent BLE settings.
- [α6700 firmware 2.00 release notes](https://www.sony.co.uk/electronics/support/e-mount-body-ilce-6000-series/ilce-6700/software/00298439):
  Sony fixed a problem where Creators' App automatic time correction and area
  adjustment could turn off. Firmware version is relevant to troubleshooting.
- In issue #152, the α7 III/iOS reporter says both settings were already enabled.
  Deleting the old Imaging Edge pairing and pairing anew with Alpha GPS fixed
  time and area updates; the Daylight Savings switch still required manual
  adjustment. The September commenter supplies no model or firmware version.
  These are user observations, not a demonstrated protocol cause.

The α6700's working direct clock command is a plausible explanation for its
different DST behavior. Confirming this explanation on the α7 III requires
checking its actual `CC13` presence and `DD21` value. Neither the APK nor the
issue proves that all older cameras lack `CC13` or ignore location-packet DST.
Creators' App inspection also does not establish Imaging Edge Mobile's exact
behavior on the α7 III.

## Focused next steps

1. Capture model, firmware, platform, `CC13`/`CC0E`/`DD32`/`DD33` presence, raw
   `DD21`, and current correction setting values from an affected camera.
2. Record the transmitted UTC time, standard offset and DST separately. Test
   camera local time, area setting, DST switch and photo timestamp independently;
   a correct clock and an unchanged DST switch are different outcomes.
3. Re-enable capability-aware setting controls and distinguish an unavailable
   write from an off setting. Keep camera-menu guidance for absent controls.
4. Add optional `CC0E` result diagnostics through the existing shared queue and
   orchestrator without making GPS depend on a successful time-setting command.
5. With both correction settings enabled, compare Alpha GPS against Imaging Edge
   on the same α7 III, using one app at a time. Test reconnect and continuous
   connection across a phone timezone/DST change before altering packet encoding.
