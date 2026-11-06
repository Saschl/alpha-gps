# Sony firmware DST comparison

Investigated 2026-10-27 using the two firmware files supplied in the repository
and older α7R V 2.00 and 2.01 images downloaded from Sony for comparison.
The α6400 2.00 and α7 IV 1.05/6.02 follow-ups below cover differences in command support.
This is static analysis of extracted camera binaries, not a camera test.

## Result

The four α7R V/α6700 images contain the same relevant DST behavior:

- The direct time/area command (`CC13`) accepts a DST byte of `0` or `1`
  and forwards it to the camera application. Other values are rejected.
- The location packet (`DD11`) decoder discards the DST-presence flag and does
  not read the two DST-offset bytes. It does read the standard timezone offset.
- The clock driver explicitly preserves DST for a time-only update and resolves
  a DST value of `0x7fff` to the current DST value. Its executable code is identical
  in all four images. GPS correction does not supply a replacement DST value.
- Direct time/area support is conditional on camera configuration. Finding its
  handler does not establish the live camera's `CC0D` capability or successful
  application of a particular command.

Sending the correct DST offset in every GPS packet is therefore insufficient on
these firmware paths. The shared controller's new direct update when the phone's
offsets change addresses a real gap during an uninterrupted connection. A rejected
or unsupported direct command still leaves DST unresolved; continued GPS packets
cannot supply the discarded DST field.

No model-specific DST wire-format difference was found in the compared handlers.
This does not establish identical firmware behavior overall or prove the cause
of issue #314 without the reporter's build, firmware and time-sync logs.

## Inputs and extraction

Versions come from decrypted FDAT headers, not an assumption about “latest.”

| Camera | Supplied file | Header version | Model ID |
| --- | --- | --- | --- |
| α6700 | `BODYDATA.DAT-a6700` | 2.00 | `0x20030001` |
| α7R V | `BODYDATA.DAT-a7rv` | 4.00 | `0x11030080` |

Input SHA-256:

```text
a6700 d3c7db5b9b40c89b11ecf03addf0f6ff1d9b917acf9f55e46360462e86db847c
a7rv  51e1176a255a9a4b4782dfbcfa6c5f332b71173bdfe9ed2bcf4fca6bcbc6c2e2
```

Both UFU containers passed the unpacker's CRC validation. Both decrypted with
`CXD90057_k8` using [DavidBuchanan314/fwtool.py, commit
3955d7890fc38daf942bb52827599bfe35de4a14](https://github.com/DavidBuchanan314/fwtool.py/tree/3955d7890fc38daf942bb52827599bfe35de4a14).
The older ma1co master tried first did not decrypt the complete images.

Extraction commands, with the unpacker's dependencies installed:

```sh
python fwtool.py unpack -f BODYDATA.DAT-a6700 -o /private/tmp/alpha-firmware-analysis/a6700
python fwtool.py unpack -f BODYDATA.DAT-a7rv -o /private/tmp/alpha-firmware-analysis/a7rv
```

Relevant library under each output directory:

```text
firmware.tar_unpacked/0700_part_image/dev/nflasha15_unpacked/lib/libInfraBluetoothLowEnergy.so
```

Library SHA-256:

```text
a6700 87d24b33907a7272b1d4bc7fbc05673980ce14eaaecb7ab920103d96fbf5e09b
a7rv  5cf440aba5e35ef08c0ffad032d5247c7b1374cc2b59cd38a24e6f6d94a7db94
```

Inspect with `objdump -d --demangle`. Addresses below are ELF virtual addresses,
not file offsets. Dynamic symbols retain the relevant function names.

| Function | α6700 | α7R V | Size, both images |
| --- | --- | --- | --- |
| `DICameraDeviceControlService::pvtWriteAreaSetting` | `0x60370` | `0x60a5c` | 472 bytes |
| `InfraBluetoothLowEnergy::pvtConvertAreaSetting` | `0x3f310` | `0x3f6a4` | 180 bytes |
| `DILocationService::pvtDecodeDILInfoWriteData` | `0x69c74` | `0x6a594` | 404 bytes |
| `DILocationService::pvtSendAddtionalInfo` | `0x69080` | `0x699a0` | 156 bytes |
| `DILocationServiceTimezone::TimeZoneCorrect` | `0x68a6c` | `0x6938c` | 444 bytes |

## Direct time command

`pvtWriteAreaSetting` checks support type `4`, decodes the packet and requires
length field `12` and type `0`. It reads the year, local date/time, DST byte and
standard-offset hours/minutes in Alpha GPS's 13-byte command layout.

DST is packet byte 10 (decoded-body byte 7). Zero leaves the outgoing message's
DST enum zero; one stores enum one. Other values take the rejection path with
write status `0x90`. Valid input sends application event `0x105`, subtype `5`,
with DST at message offset `0x10`. Write status zero follows successful event
enqueueing, not confirmed application to the clock.

`pvtConvertAreaSetting` also validates and forwards the two DST enum values.
In α7R V's adjacent `appFw.so`, conversion around `0x352f438` copies the
date/time/zone fields and maps incoming DST enum `0` to application enum `2`,
and incoming `1` to application enum `1`. The deeper application and clock-driver
trace below follows this value into the clock-setting sequence. This path has not
been executed on hardware.

The support table is initialized at runtime. In both BLE libraries, the device
control constructor reads backup configuration key `0x0302000c`; a value of one
enables several features, including type `4`. The table's initially zero bytes
must not be interpreted as proof of absent support. The actual `CC0D` response
and `CC0E` application result remain necessary evidence on a connected camera.

## Location packet

The location decoder receives the body after the four-byte wire header. Its
second flags byte starts as `0x03` in Alpha GPS's extended packet. Both binaries
execute `and w1, w1, #2`, preserving the standard-offset flag and clearing the
DST flag (α6700 `0x69d20`; α7R V `0x6a640`).

The decoder reads body offsets `0x57` and `0x58`, corresponding to full packet
bytes 91–92, into the additional-information standard-offset field. It does not
read body offsets `0x59`/`0x5a` (packet bytes 93–94, DST). The later test of the
DST flag cannot succeed because the flag was cleared earlier.

`pvtSendAddtionalInfo` forwards only the enabled standard offset to
`TimeZoneCorrect`. That function compares the standard offset and, if updating,
places `0x7fff` in the adjacent time-setting field rather than the received DST
value. The deeper clock-driver trace below confirms that this sentinel means
preserve the current DST value. Incoming DST bytes never reach this path.

The enclosing location-write handler separately forwards decoded location/UTC
information and this additional timezone information. These findings concern
the inspected BLE location path, not every possible camera clock-setting API.

## Deeper trace: GPS time versus the saved DST setting

The following follow-up traces the application, GPS service, userspace clock
service and kernel clock driver. Addresses are for α7R V 4.00 unless specified;
kernel-module addresses are offsets in `.text`, since the module is relocatable.

### Direct command reaches the clock's DST parameter

In `appFw.so`:

1. The event converter around `0x352f438` maps wire DST `0`/`1` to remote enums
   `2`/`1`, respectively, at event offset `0x4dc`.
2. The event handler at `0x2e382dc`–`0x2e38344` stores that value in model field
   `0x1fe0`, accessed through `0x28d5ae4`. It also stores the date/time and standard
   timezone fields and emits event `0x010000c3` at `0x2e38470`.
3. The clock-sequence constructor at `0x3188878` reads those model fields. At
   `0x3188938`–`0x318896c`, remote enum `1` becomes setting enum `0` (On), and
   remote enum `2` becomes setting enum `1` (Off). This is the same setting enum
   type used by the manual DST clock-setting routine.
4. The Bluetooth date/time branch at `0x30f0708` reads that setting at object
   offset `0x3c`; `0x3703784` converts On to 60 minutes and Off to zero. It submits
   local date/time, standard offset and DST together through `0x370330c` at
   `0x30f0778`. The manual DST-only branch at `0x30f0498` uses the same conversion
   and clock service.
5. The clock service wrapper at `0x370307c` sends message `0x80900141`. A clock
   service failure takes the sequence's failure path; merely queuing the BLE
   event is not proof that this later step succeeded.

In 2.01 the corresponding Bluetooth clock-setting branch is at `0x28c583c`:
`0x28c58c8` reads the DST setting, `0x28c58cc` calls its conversion at `0x2c0006c`,
and `0x28c58f4` submits date/time plus timezone through `0x2bffb24`. That conversion
also maps setting On/Off to 60/0 minutes. Class layouts and logging differ between
the versions; these addresses are not interchangeable.

The menu model is also refreshed from the clock: in 4.00,
`sequence_count_up_accurate_date_time::timer_proc()` at `0x3101b80` reads the clock
and timezone, converts the DST minutes at `0x3102048`, and updates model field
`0x75e` through getter `0x28b04dc`. The minutes-to-setting conversion at
`0x37037cc` maps zero to Off and 60 to On. There is no separate BLE-only DST value
in this traced clock/model path.

### GPS time correction supplies UTC without a timezone replacement

The BLE location writer sends message `0x810804f4`, subtype `0x3000`, with the
decoded `GEO::ST_GPS_WIRELESS_INFO` record (`libInfraBluetoothLowEnergy.so`,
`0x69b20` and `0x69c48`).

`libgps_rsrc.so` registers that message at `0x1f68`–`0x1f80`. Its callback at
`0x1b70` checks the record, queues it, and its main thread constructs GPS data
for message `0x01088263`. `libObj.so` registers the receiving callback at
`0x18f670`–`0x18f684`; callback `0x18f0f8` forwards it to the GPS main thread.
The GPS event handler at `0x198d58` calls the automatic time-correction routine
at `0x19ed70` via `0x199080`.

In that time-correction routine:

- `0x19ee48`–`0x19eea4` builds the date/time from GPS data and sets date type `1`
  (UTC), rather than converting it to local time.
- `0x19eee4` writes timezone type `1` at message offset `0x18`: no timezone
  replacement. The timestamp and automatic-correction factor `2` are sent to
  `0x80900141`.
- The equivalent 2.01 writes are at `0x1b91a8` and `0x1b91f8`, respectively.

In `libosal_udate.so`, callback `0x2ef4` explicitly converts timezone type `1`
to a null timezone pointer (`0x2f34`–`0x2f48`, also the async branch). Therefore
the GPS UTC update does not carry a new DST value into the clock driver.

### The clock driver proves preservation and persistence

The previously missing implementation is in:

```text
firmware.tar_unpacked/0700_part_image/dev/nflasha15_unpacked/kmod/udate.ko
```

`udate_set_date` starts at `.text+0xa28` in all three images. It explicitly
handles the keep-current sentinel:

```text
0xb24  ldrsh w1, [x20, #2]      // requested DST minutes
0xb28  mov   w0, #0x7fff
0xb2c  cmp   w1, w0
0xb30  b.eq  0xcc0
...
0xcc0  ldrsh w0, [x23, #0x1a]  // current DST minutes
0xcc4  strh  w0, [x20, #2]     // substitute current value
```

The state-update helper at `.text+0` independently handles a null timezone
pointer: `.text+0x3c` branches around the timezone copy and backup writes when
that pointer is null. With a timezone supplied, `.text+0x40`–`0x44` copies the
timezone record into current state. It also writes the DST minutes to backup key
`0x00900000` at `.text+0x9c`–`0xa8`, subject to its existing backup-mode guard.
Thus the direct command's DST parameter reaches the actual clock state and its
persistence mechanism, while a UTC-only correction leaves that state alone.

The local-to-UTC conversion helper at `.text+0x258` subtracts
`(standardOffsetMinutes + dstMinutes) * 60000`. UTC input bypasses that conversion
in `udate_set_date`. This is why sending UTC in the location packet is consistent
with the camera's implementation; adding the phone's DST to that timestamp would
be incorrect.

### Comparison and practical conclusion

For α7R V 2.01, α7R V 4.00 and α6700 2.00, `udate.ko` has identical `.text`,
`.rodata`, `.data` and `.rela.text` sections. The entire `.text` SHA-256 is:

```text
d5ef14db10bae400e6b2d089c55e56ef3678ffe10e6ac058f5e531fa16f08bff
```

`libosal_udate.so` and `libgps_rsrc.so` also have identical executable `.text`
sections across the three images. The GPS UTC/no-timezone writes were separately
checked in both α7R V versions because their `libObj.so` files differ.

Reproduce the decisive driver inspection with:

```sh
objdump -dr path/to/kmod/udate.ko
```

These traces provide no evidence that GPS timestamps reset or undo DST. They
show preservation instead: a stale On setting can remain On while GPS packets
continue correcting UTC. A successfully applied direct Off setting is likewise
preserved by these paths. Removing time from location packets would not address
the missing DST update demonstrated here.

This narrows the remaining investigation to whether the direct update was
attempted, allowed and completed on the affected session, or whether another
untraced setting path changed it later. Static analysis does not establish the
reporter's actual command result or exclude every possible firmware bug.

## Validation limits and camera check

### α6400 2.00 follow-up

Downloaded the [official Sony α6400 2.00 Windows package](https://www.sony.com/electronics/support/downloads/00016147)
from the download link in [Sony's model-specific firmware page](https://support.d-imaging.sony.co.jp/www/cscs/firm/?area=jp&lang=jp&mdl=ILCE-6400):
`https://support.d-imaging.sony.co.jp/download/IMT/Update_ILCE6400V200.exe?fm=jp`.
The updater was inspected as data, not executed.

The unpacker did not recognize this installer wrapper. Its embedded UFU container
starts at file offset `715220` (`0xae9d4`). Following the big-endian chunk lengths
through `DEND` yields 304,833,928 bytes. Extracting that container as
`FirmwareData-a6400.dat` allowed the same unpacker to decrypt and extract it.
`config.yaml` reports version `2.00`, model `0x81030011`, key `CXD90045_k0`.

```text
SHA-256 Update_ILCE6400V200.exe
ea460cbec5f8b62119630f0a653eeca4f4ffad887670e60c0fd9c0345e6b30a6
SHA-256 FirmwareData-a6400.dat
78a6881eddd16609758951c80d533ac82042858eac919bd94453941ba6b766f2
SHA-256 libInfraBluetoothLowEnergy.so
6339670516f11590b42d92c09503ad0df3c48aca201ed624cf7995b9992d311f
```

This is a different generation: the BLE library is ELF32 ARM with Thumb code.
Use Thumb disassembly for its exported functions; the default LLVM objdump ARM
mode incorrectly decodes those functions. The kernel `udate.ko` uses ARM code.

The important difference is **absence of the direct time-setting characteristic**:

- `DICameraDeviceControlService::m_attributeTable` at `0x41484`, size 36 bytes,
  has three 12-byte rows: `CC01`, its `2902` descriptor, and `CC02`.
- There is no `CC0D`, `CC0E` or `CC13` entry, and no `pvtWriteAreaSetting`
  handler in this library. This is stronger evidence than a missing string alone:
  the actual device-control GATT attribute table lacks the required endpoint.
- The location service table at `0x416fc` contains `DD01`, `2902`, `DD11` and
  `DD21`; it also lacks the newer `DD32`/`DD33` setting characteristics. The
  camera's menu controls automatic time/area correction on this generation.

GPS still does not update DST:

- `pvtDecodeDILInfoWriteData` at `0x2d658` clears bit zero of the decoded second
  flags byte at `0x2d842` (`bfi r3, r0, #0, #1`, with zero in `r0`) and stores
  the masked flags at `0x2d848`.
- `0x2db42`–`0x2db50` reads only standard-offset body bytes `0x57`/`0x58`.
  The DST branch only checks length; no reads of the DST bytes occur.
- `pvtSendAddtionalInfo` at `0x2cb1c` forwards the standard offset to
  `TimeZoneCorrect` at `0x3198c`. At `0x31b0e`–`0x31b1a`, that function fills
  the adjacent DST field with `0x7fff`.
- The α6400 kernel's `udate_set_date` at `.text+0x850` explicitly substitutes
  current DST for that sentinel at `.text+0x9b4`–`0x9c4`. Its state-update helper
  at `.text+0x2dc` also skips the timezone copy and backup writes when the
  timezone pointer is null (`.text+0x318`–`0x31c`). The code is different from
  the ARM64 cameras, but this preservation behavior is the same.

**Consequence for Alpha GPS:** the new `CC13` refresh cannot change DST on this
firmware because that endpoint is absent. `CameraTimeSyncController.sync()`
already skips direct setting when the characteristic is missing, allowing GPS
transmission to continue. Neither enabling correction options nor reconnecting
adds the absent command or makes the discarded GPS DST bytes take effect.

The supported practical option is the camera's manual setting:
Setup → Date/Time Setup → Daylight Savings → On/Off, documented on page 342 of
the [α6400 Help Guide](https://helpguide.sony.net/ilc/1810/v1/en/print.pdf#page=342).
Sony also documents Location Info. Link, Auto Time Correct. and Auto Area Adjust.
under [Location Info. Link Set.](https://helpguide.sony.net/ilc/1810/v1/en/contents/TP0002273573.html).

These are static findings for ILCE-6400 firmware 2.00, not an on-camera test and
not a claim about every other Sony model or alternate USB/Wi-Fi control protocol.

### Older α7R V 2.01 comparison

Located the original package through an [archived Sony download page](https://web.archive.org/web/20231026/https://support.d-imaging.sony.co.jp/www/cscs/firm/?area=jp&lang=jp&mdl=ILCE-7RM5).
The original download URL needs its `?fm=jp` query to redirect successfully;
without it, Sony returns 404. The binary was downloaded directly from
[Sony's CDN](https://di.update.sony.net/NEX/eVci3UW201/BODYDATA.DAT), not from an
unverified firmware mirror. No camera was flashed.

- Download size: 953,576,872 bytes.
- Decrypted header: model `0x11030080`, version `2.01`, region 0.
- Input SHA-256: `db51f55a34c730884b161ff755a790768943e18f70f1e275c1bea37c5372ba6a`.
- Extracted BLE library SHA-256: `8794b2a6877b17066e5a1617ba128d937a3e937f2d855588f7e6ce30812ee979`.
- Same unpacker and `CXD90057_k8`; successful container validation and extraction.

The library is at the same relative path as the newer images, under the temporary
output directory `a7rv-201`.

| Function | α7R V 2.01 address | Size |
| --- | --- | --- |
| `pvtWriteAreaSetting` | `0x52e80` | 472 bytes |
| `pvtConvertAreaSetting` | `0x31d10` | 180 bytes |
| `pvtDecodeDILInfoWriteData` | `0x59a64` | 404 bytes |
| `pvtSendAddtionalInfo` | `0x58e70` | 156 bytes |
| `TimeZoneCorrect` | `0x5885c` | 444 bytes |

Compared all five functions against 4.00 with branch destinations normalized to
function-relative offsets and PLT calls resolved to their symbol names. The
remaining changes are relocated global references, logging source-line numbers,
and one direct-command length check: 2.01 rejects lengths greater than 12, whereas
4.00 rejects lengths unequal to 12. Alpha GPS and Creators' App send length 12,
so this difference does not explain rejection of their valid command.

In 2.01, `0x59b10` clears the location DST flag with `and w1, w1, #2`; only
standard-offset bytes are read. Direct DST validation at `0x52f80`–`0x52f90`
accepts the same 0/1 values and forwards the same event. The constructor at
`0x53adc`–`0x53b6c` reads the same internal key `0x0302000c` and enables support
type 4 when its value is one. The support table has fewer total entries than in
4.00, but that does not change the inspected time/area gate.

Thus ignoring GPS DST is already present in 2.01; it is not a behavior first
introduced by 4.00. The comparison does not identify an extra user setting needed
to activate direct DST setting. The internal configuration key's meaning beyond
this capability gate remains unverified.

### Original α7R V 2.00 comparison

Located the original package through an [archived Sony download page](https://web.archive.org/web/20230415id_/https://support.d-imaging.sony.co.jp/www/cscs/firm/?area=jp&lang=jp&mdl=ILCE-7RM5).
Downloaded directly from Sony using
`https://support.d-imaging.sony.co.jp/download/NEX/k5RAG7dNP7/BODYDATA.DAT?fm=jp`,
which redirected to [Sony's CDN](https://di.update.sony.net/NEX/k5RAG7dNP7/BODYDATA.DAT).

- Download size: 954,530,216 bytes.
- Decrypted header: model `0x11030080`, version `2.00`, region 0.
- Input SHA-256: `6c261f49a713bc7908a0e1eae60411f6cd4821bd39304c82c9ee9078b8392965`.
- BLE library SHA-256: `1e5d26466076eb36da4a7a0b3b6b965e1776c6c57039bc73d3275ae7a12a2cb2`.
- Same unpacker and `CXD90057_k8`; successful extraction into
  `/private/tmp/alpha-firmware-analysis/a7rv-200`. No camera was flashed.

| Function | α7R V 2.00 address | Size |
| --- | --- | --- |
| `pvtWriteAreaSetting` | `0x5262c` | 488 bytes |
| `pvtConvertAreaSetting` | `0x317e4` | 180 bytes |
| `pvtDecodeDILInfoWriteData` | `0x592fc` | 404 bytes |
| `pvtSendAddtionalInfo` | `0x58708` | 156 bytes |
| `TimeZoneCorrect` | `0x580f4` | 444 bytes |

The inspected direct-command handler checks support type 4, accepts length field
12 and type 0, and reads the same date/time, DST and standard-offset fields.
At `0x52740`–`0x52750`, DST zero is accepted, one is stored, and other values
are rejected. The event is again `0x105`, subtype 5. Compared with 2.01, this
handler has an additional `info(0x204, 0xcc13)` log call and different register/
stack allocation; the accepted DST encoding and outgoing fields are unchanged.
Like 2.01, it rejects a length greater than 12, whereas 4.00 requires exactly 12.
Our 13-byte packet has length field 12 and satisfies all three versions.

The constructor reads backup key `0x0302000c` at `0x532d8`–`0x532e4` and enables
support type 4 at `0x53360`–`0x53368` only when the key is one. This remains a
runtime capability gate, not proof that every live session permits the command.

The GPS decoder clears the DST flag at `0x593a8` (`and w1, w1, #2`), reads only
the standard offset at `0x5943c`–`0x59448`, and does not read the DST bytes.
`TimeZoneCorrect` supplies the keep-current DST value `0x7fff` at
`0x581bc`–`0x581c8`.

In `appFw.so`, the date/time-plus-zone branch at `0x27466b8` loads the DST setting
at `0x2746744`, converts it through `0x2a69474` (setting On → 60 minutes,
Off → 0), and submits it at `0x2746770` through `0x2a68f2c`/`0x2a68d94` to
clock message `0x80900141`. In `libObj.so`, GPS correction sets UTC date type 1
at `0x1b9208` and no-replacement timezone type 1 at `0x1b9258`.

The `udate.ko` `.text`, `.rodata`, `.data` and `.rela.text` sections are identical
to 2.01, 4.00 and α6700 2.00. The complete `libosal_udate.so` and
`libgps_rsrc.so` files are identical to 2.01. Therefore the previously traced
clock behavior also applies here: GPS time/standard-offset correction preserves
DST, while the direct command carries an explicit DST setting.

There is a separately documented firmware bug: [Sony's release history](https://support.d-imaging.sony.co.jp/www/cscs/firm/?area=jp&lang=jp&mdl=ILCE-7RM5)
says 2.01 fixed Creators' App's automatic time correction and automatic area
correction sometimes turning Off. This makes the actual enabled state relevant
when investigating 2.00. The precise code change behind that fix has not been
isolated here, and the release note does not establish that it caused issue #314
or specifically prevented a direct DST command.

### Search for original α7R V 1.00

Sony's [current update instructions](https://www.sony.com/electronics/support/e-mount-body-ilce-7-series/ilce-7rm5/software/00292267)
explicitly discuss upgrading from 1.00 and the transition from Imaging Edge Mobile
to Creators' App. However, no complete 1.00 firmware image was located in the
additional search of Sony download pages, public GitHub research and Internet
Archive metadata.

The [Sony download-page snapshot from March 25, 2023](https://web.archive.org/web/20230325105126/https://www.sony.com/electronics/support/e-mount-body-ilce-7-series/ilce-7rm5/downloads)
was retrieved through curl. Its download entries are Creators' Cloud Web,
Transfer & Tagging, Imaging Edge Mobile, Imaging Edge Desktop, and Catalyst
Browse / Catalyst Prepare; it lists no camera firmware update. This supports,
but does not prove, that 1.00 was factory-installed without a public updater.
Wayback's pre-March-30 model-page index supplied no firmware-download page, and
Internet Archive's item search for this model plus firmware/BODYDATA returned
zero items. These indexes are not exhaustive.

Sony does retain an [original-model open-source release](https://oss.sony.net/Products/Linux/DI/ILCE-7RM5.html),
separate from its `ILCE-7RM5_Ver200` release. Its original kernel source was
downloaded from
`https://prodgpl.blob.core.windows.net/download/DI/20221027_ILCE-7RM5/linux-kernel.tar.gz`
(109,656,779 bytes; SHA-256
`43e10c9730e2a54d36cd0c152d78329a5f02e51a3b318a4c31feb85be6382a47`).
The tar inventory includes CXD90057 platform code but no `udate`, `osal`,
`libInfraBluetoothLowEnergy` or `appFw` paths. This is a source archive, not a
complete firmware image, and does not establish the original firmware's DST
behavior. A genuine 1.00 image or the relevant files extracted from a camera
running 1.00 would be needed for the same binary comparison performed above.

### α7 IV: 1.05 versus 6.02

Downloaded both images directly from Sony and verified their decrypted headers:

| Version | Sony download page | CDN image | Size | Decryption key |
| --- | --- | --- | --- | --- |
| 1.05 | [Sony 1.05](https://support.sony.jp/electronics/support/e-mount-body-ilce-7-series/ilce-7m4/software/00400198) | [BODYDATA.DAT](https://di.update.sony.net/NEX/LN92V3FNUi/BODYDATA.DAT) | 839,741,848 bytes | `CXD90057_k0` |
| 6.02 | [Sony 6.02](https://support.sony.jp/electronics/support/e-mount-body-ilce-7-series/ilce-7m4/software/00400195) | [BODYDATA.DAT](https://di.update.sony.net/NEX/YE19b5b7b3/BODYDATA.DAT) | 757,480,856 bytes | `CXD90057_k8` |

Both headers identify model `0x01030081`, region 0. Extraction completed using
the same unpacker into `/private/tmp/alpha-firmware-analysis/a7iv-105` and
`a7iv-602`; no updater was executed and no camera was flashed.

```text
Input SHA-256:
1.05 28f77329987c1dddd1f3d31cc1479111d54be897a3da2529c2180ada74284ddd
6.02 ad6df47a0a93a46336b9920a0d8891d0c766f8c96fa68ebda8685f6c21621188

libInfraBluetoothLowEnergy.so SHA-256:
1.05 a7f2a6e7512a7eb81b72dd399ccf614af9f8a0c784d55ce4b71c08af0b748375
6.02 d8785fb95ad782209b4c94407f3911dabdbfbdf482e1528e8d7fc13e063f1884
```

| BLE function or table | 1.05 | 6.02 |
| --- | --- | --- |
| `pvtWriteAreaSetting` | Absent | `0x60748`, 472 bytes |
| `pvtConvertAreaSetting` | Absent | `0x3f584`, 180 bytes |
| `pvtDecodeDILInfoWriteData` | `0x4b378`, 404 bytes | `0x6a280`, 404 bytes |
| `TimeZoneCorrect` | `0x4a0c4`, 444 bytes | `0x69078`, 444 bytes |
| Device-control attribute table | `0x79dd0`, 1,480 bytes | `0xac528`, 3,840 bytes |

**6.02 implements the direct DST command.** Its handler checks support type 4
at `0x60784`–`0x60794`, requires length field 12 and type 0, and reads packet
byte 10 as DST at `0x60848`. Validation at `0x6084c`–`0x60858` accepts 0/1
and rejects other values. The final two bytes supply standard-offset hours and
minutes, read at `0x6085c` and `0x60870`. The command forwards event `0x105`,
subtype 5. This matches Alpha GPS's direct packet layout.

Its attribute table contains `CC13` at `0xac848`, `CC0D` at `0xac7d0` and
`CC0E` at `0xac780`. As on α7R V, runtime support is gated: the constructor
reads backup key `0x0302000c` at `0x61340`–`0x6134c`, compares it with one,
and enables feature 4 at `0x613c8`–`0x613d0`. A live capability response and
command result are still needed to establish success on a particular camera.

In `appFw.so`, the date/time-plus-zone branch at `0x3188774` loads the standard
offset and DST setting at `0x31887c8`/`0x31887d0`. Converter `0x3797760`
maps internal setting 0 to 60 minutes and setting 1 to zero minutes. The branch
passes both offsets to `0x37972e8`/`0x3797058`, which copies the explicit DST
value and submits clock message `0x80900141` at `0x3797220`.
The `udate.ko` executable `.text` section has SHA-256
`d5ef14db10bae400e6b2d089c55e56ef3678ffe10e6ac058f5e531fa16f08bff`, identical
to the α7R V/α6700 driver traced above. Thus the explicit DST clock-setting
mechanism exists; this is not just an unused BLE characteristic declaration.

**1.05 lacks this direct BLE command.** Its populated device-control rows are
`CC02`, `CC03`, `2902`, `CC06`, `CC07`, `CC08`, `CC09`, `2902`, `CC0A`,
`CC0B`, `CC0C`, followed by empty rows. There is no `CC13`, `CC0D` or `CC0E`
entry and no corresponding area-setting handler/converter symbol. Therefore
Alpha GPS's direct DST mechanism is unavailable through this service on 1.05.
This finding does not rule out other Sony interfaces or the camera's manual menu.

**Neither version applies DST from the GPS packet.** The location decoders
clear its presence flag with `and w1, w1, #2` at `0x4b424` (1.05) and
`0x6a32c` (6.02). They read only standard-offset bytes, not the DST bytes.
Their timezone correction paths supply keep-current DST sentinel `0x7fff`
at `0x4a18c`–`0x4a198` and `0x69140`–`0x6914c`, respectively.

Consequently, α7 IV DST support is firmware-dependent: 6.02 contains the direct
path used by Alpha GPS, while 1.05 has neither that path nor a GPS DST fallback.
Intermediate versions were not analyzed, so this comparison does not establish
the exact firmware version that introduced direct support. All results here are
static binary analysis, not an on-camera acceptance test.

### Scope of validation

Function boundaries and instructions were checked using ELF symbol sizes,
LLVM objdump and Capstone. An attempted Unicorn execution of isolated handlers
failed during emulator memory initialization with a host illegal-instruction
error, before any camera code ran. No emulator or hardware test pass is claimed.

The maintainer's clarification leaves the α6700's observed automatic toggle
change unconfirmed. It does have a Daylight Savings On/Off setting:
[Sony α6700 Help Guide](https://helpguide.sony.net/ilc/2320/v1/en/contents/0601_area_date_time_setting.html),
Setup → Area/Date → Area/Date/Time Setting.

For a decisive check, with the phone on winter time, set camera Daylight Savings
to On, reconnect Alpha GPS, and inspect the menu and clock alongside `Time sync`
logs. Record capability type 4, the direct payload, write status and application
result. Repeat on α7R V. This separates a successful explicit DST update from
a clock that was already correct or changed by another app.
