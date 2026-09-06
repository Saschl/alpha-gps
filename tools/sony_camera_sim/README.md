# Sony camera simulator

A fake Sony camera that exposes exactly the GATT services the Alpha GPS app talks to, so
the app can be exercised end to end without a real camera: the handshake, the periodic
location writes, the remote-control probe loop and the shutter sequence.

Built on [Bumble](https://github.com/google/bumble), Google's Python Bluetooth stack, so
the whole peripheral — advertising data, GATT database, pairing — is under our control.

## What it simulates

| Service | Characteristic | Direction | Simulated behaviour |
| --- | --- | --- | --- |
| `8000DD00-DD00-FFFF-…` location | `DD01` location enabled | read / notify | static `01`; first notifiable characteristic, so it is the one iOS subscribes to for its pairing gate |
| | `DD11` location data | write | decodes the 91/95-byte packet and logs lat/lon/UTC/timezone |
| | `DD21` config | read | capability bytes; bit `0x02` of byte 4 = timezone/DST supported |
| | `DD30` GPS unlock | write | expects `01` |
| | `DD31` GPS lock | write | expects `01` |
| `8000CC00-CC00-FFFF-…` control | `CC13` time sync | write | decodes the 13-byte packet and logs local time + UTC offset |
| `8000FF00-FF00-FFFF-…` remote | `FF01` remote control | write | half/full press, releases, AF-ON, status probe |
| | `FF02` remote status | read / notify | `02 3F 20` focus acquired → `02 A0 20` shutter active → `02 A0 00` ready, or `02 C3 00` when remote control is off |

It advertises as a connectable peripheral with manufacturer data under Sony's company ID
`0x012D`, which is what both device pickers filter on (Android's `CompanionDeviceManager`
filter in `DeviceAssociationUtils`, and the iOS scan filter in `IosCentralShell`).

The UUIDs and byte protocol mirror `SonyBluetoothConstants.kt`; the sequencing mirrors
`BleSessionCoordinator` and `RemoteControlCoordinator`.

> If you have a spare Android phone, `:camerasim` does the same job with no extra hardware —
> see `camerasim/README.md`. The behaviour of the two is deliberately identical.

## You need a controller

Bumble drives a Bluetooth controller over HCI — it does not use the OS Bluetooth stack, so
a plain laptop Bluetooth chip is usually not enough:

- **macOS** — the built-in controller is not reachable over HCI. Use a USB Bluetooth
  dongle: `--transport usb:0` (`--transport usb:?` lists what is attached). No driver
  install needed, libusb ships with Bumble.
- **Linux / Raspberry Pi** — the built-in adapter works: `--transport hci-socket:0`. Stop
  BlueZ first (`sudo systemctl stop bluetooth`) or use a second adapter, and run as root
  (or grant `CAP_NET_RAW`).
- **Android emulator** — `pip install "bumble[android]"` and `--transport android-netsim`
  connects to the emulator's virtual controller, so the app under test and the simulator
  both run on your machine. Requires an emulator build with netsim support.

## Running it

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

python sony_camera_sim.py --transport usb:0
```

Then, on the phone:

- **Android** — the app only connects to *bonded* devices, so pair first in
  Settings → Bluetooth → Pair new device (the simulator shows up under `--name`,
  `ILCE-7RM4` by default). Afterwards add it in the app; the picker lists it because of the
  Sony company ID.
- **iOS** — nothing to do up front. Run the simulator with `--require-encryption` to make
  it behave like a real camera: subscribing to `DD01` (the app's pairing gate) then forces
  the iOS pairing prompt.

Bonds are stored in `--keystore` (`sony_camera_keys.json` by default). If you delete that
file, also forget the device on the phone — otherwise the phone keeps using keys the
simulator no longer has and every operation fails with an encryption error.

While it runs, type into its terminal: `r` toggles remote control, `d` drops the
connection, `s` prints the state, `q` quits.

## Making it misbehave

The interesting part is testing what the app does when the camera is not cooperative:

| Flag | Simulates |
| --- | --- |
| `--no-timezone` | an older camera without timezone/DST support (91-byte location packets) |
| `--config-value HEX` | any config-read answer you want |
| `--require-encryption` | a camera that rejects everything on an unpaired link |
| `--remote-off` | Bluetooth remote control disabled in the camera menu: `FF01` writes are refused and the camera reports `02 C3 00`, so the app keeps probing |
| `--omit dd21,dd30,…` | a camera missing a characteristic — the handshake must skip that step (repeat the flag; `dd01 dd11 dd21 dd30 dd31 cc13 ff01 ff02`) |
| `--fail-config-reads N` | the intermittent GATT-133 config read failure the app retries once |
| `--no-focus-status` | manual focus: no `02 3F 20`, so the shutter cycle must fall through its timeout |
| `--no-shutter-active-status` | a camera that refuses to fire: the app must skip the trailing ready-wait |
| `--disconnect-after SECONDS` | reconnect handling |
| `--focus-delay` / `--exposure` | slow autofocus / long exposures |

## Self-test

`self_test.py` runs the simulator and a stand-in for the app against each other over two
virtual controllers — no hardware, no phone:

```bash
python self_test.py
```

It replays the real handshake order, a location packet, the shutter sequence, the status
probe, the pairing flow and the fault-injection paths, and asserts the camera answers the
way the app expects. Run it after changing anything in `sony_camera.py`.

## Caveats

- **It is a test double, not a protocol reference.** Where the real protocol is unknown the
  simulator picks something plausible: the `DD21` config bytes are invented (only byte 4's
  `0x02` bit means anything to the app), as is the advertised manufacturer payload. The
  focus-acquired status `02 3F 20` comes from community protocol docs and has not been
  confirmed against a real camera either.
- Bumble's GATT server does not implement prepared (long) writes, so the ATT MTU has to be
  large enough for a 95-byte location packet. Android and iOS both negotiate a big MTU in
  practice; if a location write ever fails with "request not supported", that is why.
- Real cameras stall, drop connections and re-order things in ways no simulator reproduces.
  BLE behaviour still has to be validated against real hardware before release.
