#!/usr/bin/env python3
"""Sony BLE interval shutter trigger (non-bulb).

This script keeps the shutter flow explicit: half-down -> full-down -> full-up -> half-up.
It can optionally pulse AF-ON before every shutter release.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from dataclasses import dataclass
from typing import Any, Optional, Protocol

try:
    from bleak import BleakClient, BleakScanner
    from bleak.backends.characteristic import BleakGATTCharacteristic
    from bleak.backends.device import BLEDevice
    from bleak.exc import BleakError
except ImportError:  # pragma: no cover - handled gracefully for local tests without BLE deps
    BleakClient = None
    BleakScanner = None
    BleakGATTCharacteristic = Any
    BLEDevice = Any

    class BleakError(Exception):
        """Fallback error used when bleak is unavailable."""


# Sony button command payloads.
SHUTTER_HALF_UP = b"\x01\x06"
SHUTTER_HALF_DOWN = b"\x01\x07"
SHUTTER_FULL_UP = b"\x01\x08"
SHUTTER_FULL_DOWN = b"\x01\x09"
AF_ON_UP = b"\x01\x14"
AF_ON_DOWN = b"\x01\x15"

# Sony ready / shutter-active status payloads.
STATUS_READY = b"\x02\xA0\x00"
STATUS_SHUTTER_ACTIVE = b"\x02\xA0\x20"

SONY_SERVICE_UUID = "8000ff00-ff00-ffff-ffff-ffffffffffff"
WRITE_UUID_PREFIX = "0000ff01"
NOTIFY_UUID_PREFIX = "0000ff02"


class GattWriter(Protocol):
    """Minimal protocol for clients that can write GATT characteristics."""

    async def write_gatt_char(self, char_specifier: Any, data: bytes | bytearray) -> None:
        ...


@dataclass(frozen=True)
class ShutterTiming:
    """Timing values in milliseconds for one shutter cycle."""

    pre_focus_ms: int = 80
    full_press_ms: int = 120
    settle_ms: int = 60


def parse_ready_status(data: bytes | bytearray) -> Optional[bool]:
    """Return True if camera is ready, False if exposing, or None if unknown payload."""
    if len(data) < 3 or data[0] != 0x02 or data[1] != 0xA0:
        return None
    if data[:3] == STATUS_READY:
        return True
    if data[:3] == STATUS_SHUTTER_ACTIVE:
        return False
    return None


async def write_button(client: GattWriter, handle: int, payload: bytes, delay_ms: int = 0) -> None:
    """Write one button command and optionally wait a short delay."""
    await client.write_gatt_char(handle, payload)
    if delay_ms > 0:
        await asyncio.sleep(delay_ms / 1000.0)


async def pulse_af_on(client: GattWriter, handle: int, af_on_ms: int = 120) -> None:
    """Pulse AF-ON to improve focus lock before triggering shutter."""
    await write_button(client, handle, AF_ON_DOWN, af_on_ms)
    await write_button(client, handle, AF_ON_UP, 20)


async def trigger_shutter(
    client: GattWriter,
    handle: int,
    timing: ShutterTiming,
    use_af_on: bool = False,
    af_on_ms: int = 120,
) -> None:
    """Run a complete non-bulb shutter cycle.

    Flow:
    1. Optional AF-ON pulse.
    2. Half-press down (wake/meter/focus).
    3. Full-press down (capture trigger).
    4. Full-press up.
    5. Half-press up (fully release button state).
    """
    if use_af_on:
        await pulse_af_on(client, handle, af_on_ms=af_on_ms)

    await write_button(client, handle, SHUTTER_HALF_DOWN, timing.pre_focus_ms)
    await write_button(client, handle, SHUTTER_FULL_DOWN, timing.full_press_ms)
    await write_button(client, handle, SHUTTER_FULL_UP, timing.settle_ms)
    await write_button(client, handle, SHUTTER_HALF_UP, 0)


async def connect_camera(camera_name: Optional[str]) -> BLEDevice:
    """Discover Sony cameras and return one selected device."""
    if BleakScanner is None:
        raise RuntimeError("bleak is not installed. Install dependencies from requirements.txt")

    devices = await BleakScanner.discover(timeout=2.0)
    sony_cameras = [d for d in devices if d.name and d.name.startswith("ILCE")]

    if not sony_cameras:
        raise RuntimeError("No Sony ILCE camera detected over BLE.")

    if camera_name:
        wanted = camera_name.lower()
        for cam in sony_cameras:
            if wanted in cam.name.lower():
                print(f"Using camera: {cam.name}")
                return cam
        print(f"Camera name containing '{camera_name}' not found.")

    if len(sony_cameras) == 1:
        print(f"Using camera: {sony_cameras[0].name}")
        return sony_cameras[0]

    print("Multiple cameras detected:")
    for idx, cam in enumerate(sony_cameras, start=1):
        print(f"  {idx}: {cam.name}")

    selected = input("Use camera number: ").strip()
    selected_idx = int(selected)
    if selected_idx < 1 or selected_idx > len(sony_cameras):
        raise ValueError("Wrong camera number")

    cam = sony_cameras[selected_idx - 1]
    print(f"Using camera: {cam.name}")
    return cam


async def find_shutter_handles(client: BleakClient) -> tuple[Optional[int], Optional[str]]:
    """Find Sony write handle and optional notify UUID."""
    write_handle: Optional[int] = None
    notify_uuid: Optional[str] = None

    services = client.services
    if services is None:
        await client.get_services()
        services = client.services

    for service in services.services.values():
        if service.uuid.lower() != SONY_SERVICE_UUID:
            continue
        for char in service.characteristics:
            lower_uuid = char.uuid.lower()
            if lower_uuid.startswith(WRITE_UUID_PREFIX):
                write_handle = char.handle
            elif lower_uuid.startswith(NOTIFY_UUID_PREFIX):
                notify_uuid = char.uuid

    return write_handle, notify_uuid


def build_notification_handler(ready_event: asyncio.Event):
    """Build bleak notification callback that tracks ready state."""

    def notification_handler(_sender: BleakGATTCharacteristic, data: bytearray) -> None:
        ready = parse_ready_status(data)
        if ready is None:
            return
        if ready:
            ready_event.set()
        else:
            ready_event.clear()

    return notification_handler


async def wait_until_ready(ready_event: asyncio.Event, timeout_s: int) -> bool:
    """Wait for ready status, returning False on timeout."""
    if ready_event.is_set():
        return True
    try:
        await asyncio.wait_for(ready_event.wait(), timeout=timeout_s)
        return True
    except asyncio.TimeoutError:
        return False


async def run_intervalometer(args: argparse.Namespace) -> None:
    """Run interval shooting with non-bulb shutter flow."""
    if BleakClient is None:
        raise RuntimeError("bleak is not installed. Install dependencies from requirements.txt")

    timing = ShutterTiming(
        pre_focus_ms=args.prefocus_ms,
        full_press_ms=args.fullpress_ms,
        settle_ms=args.settle_ms,
    )

    stop_at: Optional[float] = None
    target_count: int
    if args.count is not None:
        target_count = args.count
    else:
        target_count = 10**9
        stop_at = time.monotonic() + (args.minutes * 60)

    camera = await connect_camera(args.camera_name)

    started = time.monotonic()
    shots_taken = 0
    ready_event = asyncio.Event()
    ready_event.set()

    async with BleakClient(camera.address) as client:
        write_handle, notify_uuid = await find_shutter_handles(client)
        if write_handle is None:
            raise RuntimeError("Write handle not found on Sony BLE service.")

        if notify_uuid:
            await client.start_notify(notify_uuid, build_notification_handler(ready_event))
        else:
            print(
                "Warning: notification characteristic not found. "
                f"Using fixed wait ({args.wait_time}s)."
            )

        try:
            for shot in range(1, target_count + 1):
                now = time.monotonic()
                if stop_at is not None and now >= stop_at:
                    break

                shot_start = time.monotonic()
                print(f"Shot {shot}")
                await trigger_shutter(
                    client,
                    write_handle,
                    timing,
                    use_af_on=args.af_on,
                    af_on_ms=args.af_on_ms,
                )
                shots_taken += 1

                if notify_uuid:
                    is_ready = await wait_until_ready(ready_event, args.wait_time)
                    if not is_ready:
                        print(f"Warning: camera did not report ready within {args.wait_time}s")
                else:
                    await asyncio.sleep(args.wait_time)

                elapsed = time.monotonic() - shot_start
                remaining = args.interval - elapsed
                if remaining > 0:
                    await asyncio.sleep(remaining)

        except (KeyboardInterrupt, asyncio.CancelledError):
            print("Interrupted by user")
        finally:
            if notify_uuid:
                try:
                    await client.stop_notify(notify_uuid)
                except Exception:
                    pass

    total_elapsed = time.monotonic() - started
    print(f"Shots taken: {shots_taken}")
    print("Elapsed:", time.strftime("%H:%M:%S", time.gmtime(total_elapsed)))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="intervalometer.py",
        description="Non-bulb BLE interval shutter trigger for Sony alpha cameras.",
        formatter_class=argparse.RawTextHelpFormatter,
        epilog="Clear skies!",
    )

    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("-c", "--count", type=int, help="Number of shots")
    group.add_argument("-m", "--minutes", type=int, help="Total runtime in minutes")

    parser.add_argument(
        "-i",
        "--interval",
        type=float,
        required=True,
        help="Minimum seconds between shot starts",
    )
    parser.add_argument(
        "-n",
        "--camera-name",
        type=str,
        help="Unique part of camera name (skip camera index prompt)",
    )
    parser.add_argument(
        "-w",
        "--wait-time",
        type=int,
        default=20,
        help="Max seconds to wait for camera-ready state (default: %(default)s)",
    )

    parser.add_argument("--prefocus-ms", type=int, default=80, help="Half-press hold time in ms")
    parser.add_argument("--fullpress-ms", type=int, default=120, help="Full-press hold time in ms")
    parser.add_argument("--settle-ms", type=int, default=60, help="Delay after full release in ms")

    parser.add_argument(
        "--af-on",
        action="store_true",
        help="Pulse AF-ON before each shutter cycle",
    )
    parser.add_argument("--af-on-ms", type=int, default=120, help="AF-ON hold time in ms")

    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        asyncio.run(run_intervalometer(args))
        return 0
    except (BleakError, RuntimeError, ValueError) as exc:
        print(str(exc))
        return 1


if __name__ == "__main__":
    sys.exit(main())

