# DST investigation: issue #314

Investigated and implementation updated 2026-10-25.

## Reporter update: 2026-10-27

The [new screenshot](https://github.com/Saschl/alpha-gps/issues/314#issuecomment-6434756708)
shows the iPhone device-detail screen for `SONY-7RV`, with automatic time
correction and automatic area adjustment both enabled. This removes disabled
correction settings as the leading explanation for that camera at the time of
the screenshot. It does not establish their state at the original transition or
the settings on the other reported models. Remote control is off, but that
switch does not gate the time-sync handshake or location packets.

Rechecked the current iOS path: `PlatformTimeZoneInfo` obtains the current date
and calendar timezone on construction, queries Foundation's date-specific total
and DST offsets, and subtracts DST to obtain the standard offset. Each location
packet constructs a fresh instance. There is no evident cached summer-time
offset in this path. For Berlin after the fallback, the expected extended
location suffix remains `00 3C 00 00`, and the direct time command's DST byte is
zero. This code inspection does not establish what the reporter's phone sent.

The update supplies no app/build version, iOS version, firmware, reconnect test,
or logs. In particular, it does not establish that the reporter is running the
new time-sync controller. Next evidence needed is the installed build and a
fresh connection with the camera still showing the wrong time. On a build with
the fix, capture `Time sync[...]` logs for capability, calculated offsets, and
camera application-level acceptance/rejection. If reconnecting already failed
on the old build, the missing continuous-connection refresh alone cannot explain
the report.

## Implemented follow-up

### Firmware follow-up: 2026-10-27

The supplied α6700 2.00 and α7R V 4.00 images contain direct `CC13` handlers
that accept and forward DST `0`/`1`. Both inspected `DD11` decoders discard the
DST flag and do not read the packet's DST-offset bytes, while reading the
standard timezone offset. Continued GPS packets therefore cannot convey a DST
change through these paths. This supports the new direct offset-change update,
but does not prove the reporter's runtime capability or command acceptance.
See [firmware comparison and reproducible evidence](sony-firmware-dst-comparison.md).
The maintainer's clarification leaves the α6700 automatic toggle test unconfirmed.
An additional comparison against an official Sony α7R V 2.01 package found the
same GPS DST discard, direct DST 0/1 handling, and internal time/area capability
gate. A stricter packet-length check in 4.00 does not affect the valid length-12
command sent by Alpha GPS and Creators' App.

### iOS calculation and shared controller

On 2026-10-27, added `iosTest/.../coordinator/PlatformTimeZoneInfoTest.kt` to
exercise the production Kotlin/Native Foundation calculation with fixed dates
and named timezones. Four tests pass on the iOS simulator: both Berlin 2026
transition boundaries and resulting `CC13`/`DD11` fields, January/July offsets
for eight zones, and Lord Howe's 30-minute fallback. The iPhone arm64 target also
compiles. Previously, common tests supplied offsets directly and the separate
Foundation harness ran on macOS; neither executed the production iOS calculation.
The internal date/timezone constructor is a test seam; the public constructor
still uses `NSDate()` and `NSCalendar.currentCalendar.timeZone`.

Reran the four tests on 2026-10-27 with calculated offsets and serialized bytes
printed to the test output. Both the Gradle simulator task and an explicit
`simctl spawn` run inside the iPhone 16 Pro / iOS 18.6 simulator passed all four
tests. Berlin at `2026-10-25T00:59:59Z` produced standard `60`, DST `60`, and
`CC13` `0c000007ea0a19023b3b010100`; at `2026-10-25T01:00:00Z` it produced
standard `60`, DST `0`, and `CC13` `0c000007ea0a19020000000100`.

The captured output, JUnit XML, an HTML report generated from the captured
values, and a simulator screenshot are under `build/reports/dst-verification/`.
The HTML is a viewer for actual native test results, not a JavaScript
reimplementation of the timezone calculation. Tests supply fixed dates and
named timezones to the production iOS constructor; they do not establish the
reporter's phone timezone, installed app build, or camera acceptance.

```sh
./gradlew :sharednew:iosSimulatorArm64Test --tests '*PlatformTimeZoneInfoTest*' --rerun --offline
xcrun simctl spawn EA7E5CF3-E6D2-4571-AEC2-95B617F2DB0D \
  sharednew/build/bin/iosSimulatorArm64/debugTest/test.kexe \
  --ktest_filter='com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfoTest.*'
```

`CameraTimeSyncController` now owns optional direct clock setting on both platforms,
using `QueuedBleGattPort` for every operation:

- Reads the optional `CC0D` time/area capability and skips direct setting when the
  camera advertises no support. Missing, malformed or unreadable capability data
  falls back to `CC13` presence for compatibility with existing cameras.
- Subscribes to `CC0E` before writing `CC13`, arms the result waiter before the
  write, and checks time/area result type `0x0004`. Logs distinguish applied time,
  camera rejection (with reason), failed writes and unavailable/timed-out results.
- Releases the handshake after the queued write finishes, including initiation
  failure and the queue's write timeout. The camera-result wait allows 30 seconds,
  matching Creators' App, and occupies no BLE operation lane. GPS transmission and
  remote commands remain available during that wait.
- Checks the standard/DST offset pair every 30 seconds while the session is ready,
  even without a GPS fix. A change requests another direct clock update without
  restarting the handshake. Both components are compared separately because the
  DST setting can change while the total UTC offset stays the same.
- Skips later automatic direct updates when either camera correction switch is
  explicitly off. The initial handshake retains its previous one-time clock-sync
  behavior; no correction switch is changed automatically. Failed updates are
  logged once per offset change; reconnecting starts a fresh attempt.
- Cancels pending result waits and offset monitors on disconnect, re-handshake,
  and shutdown. iOS maps the new characteristics through its canonical UUID table.

The packet builder derives local time from the same standard/DST offsets encoded
in the command, avoiding a separate system-timezone lookup during construction.
The periodic location packet format is unchanged.

Validation: the full shared Android host test suite passes, along with
`:sharednew:compileKotlinIosArm64`, `:sharednew:compileKotlinIosSimulatorArm64`,
`:sharednew:compileAndroidMain`, `:app:compileGplayDebugKotlin` and
`:app:compileFossDebugKotlin`. Regression tests cover seasonal transitions,
capabilities, malformed/early/late/unrelated results, multiple cameras,
opt-outs, write failures/timeouts and cancellation. Real-camera validation is
still required to establish how each firmware applies DST to its menu switch.

## Original investigation findings

Alpha GPS already sends the phone's current DST value, including zero when DST
ends. Its BLE time encodings match the inspected Creators' App and Imaging Edge
Mobile paths. There is no evidence for fixing this by unconditionally sending
“DST off” or by combining the standard and DST offsets.

The unresolved question is whether the affected camera received and applied the
time/DST update. Before the fix, two concrete gaps made this hard to determine:

- Direct time setting (`CC13`) runs only during the connection handshake. A DST
  change during an uninterrupted connection updates subsequent location packets
  (`DD11`), but does not send another direct time-setting command.
- Alpha GPS proceeds after the `CC13` write callback even when the write fails.
  It neither subscribes to nor parses the camera's application-level time-setting
  result on `CC0E`. Creators' App waits for that result before reporting success.

These were confirmed implementation facts, not proof of which caused this report.
If the camera reconnected after the transition, the first gap alone cannot explain
it. The subsequently inspected α6700 2.00 and α7R V 4.00 firmware discards DST
from `DD11`, so those location paths do not compensate for a missing direct
update. An affected-camera capture must still establish which command was sent
and applied.

## What the report establishes

[Issue #314](https://github.com/Saschl/alpha-gps/issues/314) reports a clock one hour
ahead after the October 25 fallback. The attached photo shows Berlin/Paris,
GMT+1.0, and **Daylight Savings On**. This is consistent with a retained summer-time
setting, but the photo does not establish whether location transmission or either
automatic correction setting was enabled.

The [linked comment](https://github.com/Saschl/alpha-gps/issues/314#issuecomment-6398896445)
names the reporter's α7R V and α7 IV, and students' α6700 and ZV-E1. It does not
individually demonstrate the failure on all four models, supply firmware/app
versions or phone platform, or establish whether reconnecting fixes it. The
suggestion to switch DST off is attributed to Gemini; the comment does not say
that the reporter tested that workaround.

## Sony APK comparison

### Settings check: 2026-10-27

Re-decompiled the local Creators' App APK and traced its settings controls:

- `setup/LocationTransferSetupController.java` binds the location-link switch and
  the automatic time/area correction switches. The latter call
  `changeTimeCorrectionSetting` and `changeAreaAdjustmentSetting`.
- `btconnection/internal/BluetoothStateMachine.java` maps those commands to
  `DD32` and `DD33`; `ChangingAutoCorrectionState` writes `01` for enabled and
  `00` for disabled. These are the same camera settings Alpha GPS reads/writes,
  not additional Sony-app-only permissions that must also be enabled there.
- `setup/DateTimeSetupActivity.java` has an explicit apply action. After setting
  the date format, its result callback requests `onSettingSetupTimeArea` with
  `ZonedDateTime.now(ZoneId.systemDefault())`. `SettingSetupTimeAreaState` sends
  `CC13` and handles the `CC0E` result.
- The date/time screen's DST display (`is_dst_text`) is a `TextView` populated
  by `ZoneRules.isDaylightSavings`, not an editable switch. The initial-setup
  `oobe/DateTimeFragment` similarly derives its displayed value from the phone.
  No separate “enable DST synchronization” setting was found in these paths.

Sony's [α7R V Help Guide, “Reading location information from a smartphone,”
page 520](https://helpguide.sony.net/ilc/2230/v1/en/print.pdf#page=520)
places automatic time/area correction under the smartphone app's location-link
settings. Bluetooth pairing must work, but no additional DST-sync menu permission
is documented. Camera Setup → Area/Date → Area/Date/Time Setting → Daylight Savings
sets the actual On/Off value; On is not an “allow automatic DST” switch.

In Alpha GPS, leave both per-device correction switches enabled for automatic
updates. The current initial `CC13` attempt is independent of these switches;
the offset-change monitor respects either explicit opt-out. Their enabled state
does not prove that the camera accepted direct setting. The reporter's screenshot
already shows both enabled, so no additional user-facing activation requirement
has been identified. The firmware's internal support configuration key has not
been mapped to any user-facing setting.

Sony's [firmware history](https://support.d-imaging.sony.co.jp/www/cscs/firm/?area=jp&lang=jp&mdl=ILCE-7RM5)
lists a 2.01 fix for Creators' App's automatic time/area correction settings
unexpectedly switching off. That is a documented historical settings bug, not
proof of a DST-specific fix or the cause of this reporter's issue.

Inspected local APKs with `jadx --no-res`:

| App | Package | Version | APK SHA-256 |
| --- | --- | --- | --- |
| Creators' App | `jp.co.sony.ips.portalapp` | 3.5.0 / 350607302 | `b0716bf5b02b7c723d54d670d97c1c6c6daf0519a5ded5330fc4d9f3ff443f09` |
| Imaging Edge Mobile | `com.sony.playmemories.mobile` | 7.8.5 / 785260216 | `05cdfe10e66cfcfc0a96acf36168842095fbd8d9f1adec10352a7e10531a9715` |

Imaging Edge's hash is for `com.sony.playmemories.mobile.apk` extracted from
`remote-app-playmemories-mobile.xapk`. JADX reported 170 errors for Creators and
24 for Imaging Edge. The relevant methods are readable; both location serializers
contain duplicated decompiler output, so this is not a claim of complete APK
verification.

Source paths below are relative to each app's decompiled package directory.

| Operation | Creators' App | Imaging Edge Mobile | Alpha GPS |
| --- | --- | --- | --- |
| Extended location capability | `DD21` byte 4, mask `0x02` | Same | Same |
| Location time | `DD11`, UTC at bytes 19–25 | Same | Same encoding, using current phone time rather than fix time |
| Standard offset | Signed big-endian minutes at bytes 91–92 | Same | Same |
| DST adjustment | Signed big-endian minutes at bytes 93–94; zero outside DST | Same | Same |
| No extended capability | 91-byte packet, without timezone/DST | Same | Same |
| Direct time setting | `CC13`: local wall time, DST boolean at byte 10, signed standard-offset hours at 11, absolute minutes at 12 | No `CC13` time-setting path found in the inspected BLE package | Same packet as Creators, attempted once per handshake if present |
| Automatic correction switches | `DD32` time / `DD33` area, `01` enabled / `00` disabled | Same | Same; shared device-detail UI exposes them in the current checkout |

Creators' sources:

- `btconnection/internal/state/TransferringLocationInfoWithLockState.java`:
  capability read, correction-setting reads, and `onLocationUpdated` serializer.
- `btconnection/BluetoothLeUtil.java`: `setLocationAndTime` writes UTC.
- `btconnection/internal/BluetoothGattUtil.java`: `serializeTimeAreaData`.
- `btconnection/internal/state/SettingTimeAreaState.java` and
  `SettingSetupTimeAreaState.java`: `CC13` request and `CC0E` result handling.
- `btconnection/internal/parser/BluetoothCharacteristicParser.java`:
  `parseCameraInitialSettingResult`; result type `0x0004` is time/area,
  byte 3 `01` means success, and bytes 4–7 carry the failure reason
  (`1` status error, `2` parameter error).
- `setup/DateTimeSetupActivity.java` and `oobe/OobeActivity.java`: direct
  time-setting callers use `ZonedDateTime.now(ZoneId.systemDefault())`.

Imaging Edge sources:

- `btconnection/BluetoothLeUtil.java`: `getPayloadFromLocation` and
  `setLocationAndTime`. These use the same standard-offset/current-DST split.
- `btconnection/internal/state/TransferringLocationInfoState.java` and
  `TransferringLocationInfoWithLockState.java`: capability detection and periodic
  `DD11` writes. The latter reads `DD32`/`DD33`; it does not automatically turn
  them on at every connection.
- `btconnection/internal/state/ChangingAutoCorrectionState.java`: writes the
  correction switches and handles unavailable status `157`.
- `webapi/camera/operation/oneshot/SetCurrentTime.java`: a separate Wi-Fi Web API
  path sends UTC `dateTime`, standard `timeZoneOffsetMinute`, and current
  `dstOffsetMinute`. This is not a missing BLE DST command.

Neither inspected BLE location path sends a separate “turn DST off” command at
the seasonal transition. Creators' explicit `CC13` command already includes the
boolean that Alpha GPS sends.

Creators' wait for `CC0E` confirms the time-setting operation; it is not evidence
that GPS transmission must pause during that wait. Its `BluetoothStateMachine`
keeps separate active states keyed by command (`SendLocation`, `SettingTimeArea`,
etc.), and `actCommand` checks whether that particular command is already running.
The location callback dispatches updates to all active states, while
`TransferringLocationInfoWithLockState` writes `DD11` independently of the
time-setting result. The inspected code therefore permits an existing location
transfer to continue alongside time setting. This does not establish the exact
startup/UI sequencing in every flow or firmware behavior on a camera.

### Direct setting is an optional camera capability

Follow-up inspection found stronger evidence than characteristic presence alone:
Creators' `GettingCameraDeviceInfoState.onGattCharacteristicRead` parses a support
list from **`CC0D`**. Feature type **`0x0004`** is `EnumSupportInfo.TimeArea`, added
only when its support byte is `01`. `GettingTimeSettingDoneState.onEnter` checks
this capability alongside friendly-name and date-format support; if any is absent,
it skips the initial-setting status flow and logs `InitialSetting is not Supported`.
This establishes that Sony's protocol/app explicitly accommodates cameras without
the direct time/area-setting capability. That command's payload includes DST.

This is separate from `DD21` bit `0x02`, which controls inclusion of timezone/DST
in location packets. Neither capability alone proves that the camera changes its
Daylight Savings menu switch when receiving a location packet. The inspected code
does not provide a model/firmware map or prove that the issue's α7R V/α7 IV lack
support. It also does not establish that a camera without this BLE capability
cannot change DST through a different interface.

Before the fix, Alpha GPS checked `CC13` presence without reading `CC0D`. An affected
camera's `CC0D` support list should therefore be captured alongside `DD21`,
characteristic presence and `CC0E` results before assuming direct setting works.

## Local checks

Executed the existing local Android build's `LocationPacketBuilder` and
`PlatformTimeZoneInfo` through a temporary Java harness. Compared all 13 `CC13`
bytes with Creators' extracted `serializeTimeAreaData` method, with its annotation
and logging removed. Also compared the `DD11` offset fields with Sony's
`TimeZone.getRawOffset` / `inDaylightTime` / `getDSTSavings` calculation.

All seven zones matched: Berlin, London, New York, Adelaide, Lord Howe, Kathmandu,
and UTC. This includes active DST, half-hour DST, negative standard offsets, and
fractional-hour standard offsets. Basic packets remained 91 bytes and extended
packets 95 bytes. Berlin at the time of investigation produced `DD11` suffix
`00 3C 00 00` and `CC13` suffix `00 01 00`: standard offset +60 minutes, DST off.

Separately evaluated Android's Java timezone calculation immediately before and
after both Berlin transitions in 2026. It agrees with Sony's Java calculation:

| UTC instant | Berlin wall time | Standard minutes | DST minutes |
| --- | --- | --- | --- |
| 2026-03-29 00:59:59 | 01:59:59 | 60 | 0 |
| 2026-03-29 01:00:00 | 03:00:00 | 60 | 60 |
| 2026-10-25 00:59:59 | 02:59:59 | 60 | 60 |
| 2026-10-25 01:00:00 | 02:00:00 | 60 | 0 |

The iOS implementation calculates total offset minus current DST using
Foundation. Evaluated the equivalent Foundation APIs on macOS for the same four
instants; these also produce the table above. This checks the offset calculation,
not execution of the iOS app or BLE behavior on a camera. No fresh Gradle build
was required for this investigation, and no on-camera validation was performed.

## Focused camera test and implementation direction

1. Record phone OS, phone timezone, app version, camera model/firmware, raw `DD21`,
   the `CC0D` support list, `CC13`/`CC0E` presence, and actual `DD32`/`DD33` values.
   Verify GPS packets are being sent. The current app exposes the two correction switches; sending
   time information does not itself enable camera-side automatic correction.
2. With the phone on winter time and camera DST manually On, establish a fresh
   connection. Capture the `CC13` payload, GATT result and `CC0E` result, plus the
   subsequent `DD11` suffix. Inspect both the camera's DST menu and photo clock.
3. Repeat with Creators' App on the same supported firmware, using one app at a
   time, and compare automatic location linking with its explicit date/time setup.
   Use Imaging Edge only where the camera firmware supports it.
4. If reconnect fixes it but staying connected does not, validate the new shared
   time-sync refresh when the phone's standard/DST offset changes. Account for
   correction opt-outs, the periodic check and platform background suspension.
5. If reconnect does not fix it, first diagnose `CC13` rejection/absence and
   correction settings using the new `Time sync[...]` diagnostics. GPS transmission
   remains independent of the clock-setting outcome. A successful GATT write alone proves delivery to
   the BLE stack, not that the camera applied time or DST.

The seasonal observation does not justify always forcing DST off: that would
send the wrong setting in summer and in regions currently observing DST.
See also [the earlier time-sync investigation](sony-time-sync-investigation.md)
for issue #152; its note about commented-out settings rows predates the current UI.
