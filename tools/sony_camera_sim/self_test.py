#!/usr/bin/env python3
"""
End-to-end checks for the camera simulator, without any hardware.

Two virtual Bumble controllers are wired to each other over a local link: one
runs the simulator, the other replays what the app does (BleSessionCoordinator's
handshake, LocationTransmissionManager's periodic write and
RemoteControlCoordinator's probe/shutter sequence) and asserts the camera
answers the way the app expects.

    python self_test.py
"""

from __future__ import annotations

import asyncio
import datetime
import struct
import sys
import traceback
from collections.abc import Awaitable, Callable

from bumble.controller import Controller
from bumble.core import UUID
from bumble.device import Device, Peer
from bumble.att import ATT_Error
from bumble.hci import Address
from bumble.host import Host
from bumble.link import LocalLink
from bumble.pairing import PairingConfig, PairingDelegate

from sony_camera import (
    CONFIG_READ_UUID,
    FULL_PRESS,
    FULL_RELEASE,
    GPS_ENABLE_COMMAND,
    GPS_LOCK_UUID,
    GPS_UNLOCK_UUID,
    HALF_PRESS,
    HALF_RELEASE,
    LOCATION_WRITE_UUID,
    REMOTE_CONTROL_UUID,
    REMOTE_STATUS_UUID,
    STATUS_FOCUS_ACQUIRED,
    STATUS_READY,
    STATUS_REMOTE_OFF,
    STATUS_SHUTTER_ACTIVE,
    TIME_SYNC_UUID,
    CameraOptions,
    SonyCameraSimulator,
)

CAMERA_ADDRESS = 'F0:F1:F2:F3:F4:F5'
PHONE_ADDRESS = 'F6:F7:F8:F9:FA:FB'


class AppHarness:
    """The central side: a minimal stand-in for the app's BLE layer."""

    def __init__(self, peer: Peer) -> None:
        self.peer = peer
        self.notifications: list[bytes] = []
        self._notification_event = asyncio.Event()

    def characteristic(self, uuid: str):
        found = self.peer.get_characteristics_by_uuid(UUID(uuid))
        return found[0] if found else None

    def require(self, uuid: str):
        characteristic = self.characteristic(uuid)
        assert characteristic is not None, f'characteristic {uuid} is missing'
        return characteristic

    async def read(self, uuid: str) -> bytes:
        return await self.require(uuid).read_value()

    async def write(self, uuid: str, value: bytes) -> None:
        await self.require(uuid).write_value(value, with_response=True)

    async def subscribe_status(self) -> None:
        await self.require(REMOTE_STATUS_UUID).subscribe(self._on_notification)

    def _on_notification(self, value: bytes) -> None:
        self.notifications.append(bytes(value))
        self._notification_event.set()

    async def wait_for_status(self, expected: bytes, timeout: float = 3.0) -> None:
        """Wait until `expected` shows up, matching the app's prefix comparison."""
        deadline = asyncio.get_running_loop().time() + timeout
        while True:
            if any(value.startswith(expected) for value in self.notifications):
                return
            remaining = deadline - asyncio.get_running_loop().time()
            assert remaining > 0, (
                f'timed out waiting for {expected.hex(" ")}; '
                f'got {[value.hex(" ") for value in self.notifications]}'
            )
            self._notification_event.clear()
            try:
                await asyncio.wait_for(self._notification_event.wait(), remaining)
            except asyncio.TimeoutError:
                pass

    async def run_handshake(self) -> bytes | None:
        """BleSessionCoordinator.beginHandshake, in order."""
        config = None
        if self.characteristic(CONFIG_READ_UUID) is not None:
            config = await self.read(CONFIG_READ_UUID)
        if self.characteristic(GPS_UNLOCK_UUID) is not None:
            await self.write(GPS_UNLOCK_UUID, GPS_ENABLE_COMMAND)
        if self.characteristic(GPS_LOCK_UUID) is not None:
            await self.write(GPS_LOCK_UUID, GPS_ENABLE_COMMAND)
        if self.characteristic(TIME_SYNC_UUID) is not None:
            await self.write(TIME_SYNC_UUID, build_time_sync_packet())
        return config


def build_time_sync_packet(
    when: datetime.datetime | None = None,
    offset_minutes: int = 120,
    dst_minutes: int = 60,
) -> bytes:
    """Mirror of LocationPacketBuilder.buildTimeSyncPacket."""
    when = when or datetime.datetime(2026, 8, 9, 14, 30, 15)
    hours, minutes = divmod(abs(offset_minutes), 60)
    signed_hours = -hours if offset_minutes < 0 else hours
    return struct.pack(
        '>BBBHBBBBBBbB',
        12,
        0,
        0,
        when.year,
        when.month,
        when.day,
        when.hour,
        when.minute,
        when.second,
        1 if dst_minutes > 0 else 0,
        signed_hours,
        minutes,
    )


def build_location_packet(
    latitude: float,
    longitude: float,
    with_time_zone: bool,
    when: datetime.datetime | None = None,
    offset_minutes: int = 120,
    dst_minutes: int = 60,
) -> bytes:
    """Mirror of LocationPacketBuilder.buildLocationDataPacket."""
    when = when or datetime.datetime(2026, 8, 9, 12, 30, 15, tzinfo=datetime.timezone.utc)
    prefix = bytes(
        [
            0x00,
            0x5D if with_time_zone else 0x59,
            0x08,
            0x02,
            0xFC,
            0x03 if with_time_zone else 0x00,
            0x00,
            0x00,
            0x10,
            0x10,
            0x10,
        ]
    )
    body = struct.pack(
        '>iiHBBBBB',
        int(latitude * 1e7),
        int(longitude * 1e7),
        when.year,
        when.month,
        when.day,
        when.hour,
        when.minute,
        when.second,
    )
    packet = prefix + body + bytes(65)
    if with_time_zone:
        packet += struct.pack('>hh', offset_minutes, dst_minutes)
    return packet


async def connect(options: CameraOptions) -> tuple[SonyCameraSimulator, AppHarness, Device]:
    link = LocalLink()
    camera_controller = Controller('camera', link=link, public_address=CAMERA_ADDRESS)
    phone_controller = Controller('phone', link=link, public_address=PHONE_ADDRESS)

    camera_device = Device(
        address=Address(CAMERA_ADDRESS),
        host=Host(camera_controller, camera_controller),
    )
    phone_device = Device(
        address=Address(PHONE_ADDRESS),
        host=Host(phone_controller, phone_controller),
    )

    for device in (camera_device, phone_device):
        device.pairing_config_factory = lambda _connection: PairingConfig(
            sc=True,
            mitm=False,
            bonding=True,
            delegate=PairingDelegate(PairingDelegate.NO_OUTPUT_NO_INPUT),
        )

    simulator = SonyCameraSimulator(options)
    simulator.attach(camera_device)

    await camera_device.power_on()
    await phone_device.power_on()
    await camera_device.start_advertising()

    connection = await phone_device.connect(camera_device.random_address)
    peer = Peer(connection)
    await peer.discover_services()
    for service in peer.services:
        await service.discover_characteristics()

    return simulator, AppHarness(peer), phone_device


# -----------------------------------------------------------------------------
# Scenarios
# -----------------------------------------------------------------------------


async def test_handshake_and_location() -> None:
    simulator, app, _ = await connect(CameraOptions())

    config = await app.run_handshake()
    assert config is not None and len(config) >= 5, f'bad config value {config!r}'
    assert config[4] & 0x02, 'timezone/DST flag should be set by default'
    assert simulator.last_time_sync is not None, 'time sync was not received'
    assert not simulator.last_time_sync.problems, simulator.last_time_sync.problems
    assert simulator.last_time_sync.offset_hours == 2

    await app.write(LOCATION_WRITE_UUID, build_location_packet(52.5200, 13.4050, True))
    location = simulator.last_location
    assert location is not None, 'location packet was not received'
    assert not location.problems, location.problems
    assert abs(location.latitude - 52.5200) < 1e-6, location.latitude
    assert abs(location.longitude - 13.4050) < 1e-6, location.longitude
    assert location.time_zone_minutes == 120, location.time_zone_minutes
    assert location.dst_minutes == 60, location.dst_minutes


async def test_no_timezone_support() -> None:
    simulator, app, _ = await connect(CameraOptions(time_zone_supported=False))

    config = await app.run_handshake()
    assert config is not None and not config[4] & 0x02, 'timezone flag should be clear'

    await app.write(LOCATION_WRITE_UUID, build_location_packet(-33.8688, 151.2093, False))
    location = simulator.last_location
    assert location is not None and not location.problems, location
    assert location.time_zone_minutes is None, 'short packet must not decode a timezone'
    assert abs(location.latitude + 33.8688) < 1e-6, location.latitude


async def test_config_read_retry() -> None:
    _, app, _ = await connect(CameraOptions(fail_config_reads=1))

    try:
        await app.read(CONFIG_READ_UUID)
    except Exception:  # noqa: BLE001 - any ATT error is what the app retries on
        pass
    else:
        raise AssertionError('the first config read should have failed')

    config = await app.read(CONFIG_READ_UUID)
    assert len(config) >= 5, 'the retried read should succeed'


async def test_shutter_sequence() -> None:
    simulator, app, _ = await connect(CameraOptions(focus_delay=0.05, exposure_time=0.1))
    await app.run_handshake()
    await app.subscribe_status()

    # RemoteControlCoordinator.runShutterCycle
    await app.write(REMOTE_CONTROL_UUID, HALF_PRESS)
    await app.wait_for_status(STATUS_FOCUS_ACQUIRED)

    await app.write(REMOTE_CONTROL_UUID, FULL_PRESS)
    await app.wait_for_status(STATUS_SHUTTER_ACTIVE)

    await app.write(REMOTE_CONTROL_UUID, FULL_RELEASE)
    await app.write(REMOTE_CONTROL_UUID, HALF_RELEASE)
    await app.wait_for_status(STATUS_READY)

    order = [value.hex(' ') for value in simulator_status_order(app)]
    assert order == [
        STATUS_FOCUS_ACQUIRED.hex(' '),
        STATUS_SHUTTER_ACTIVE.hex(' '),
        STATUS_READY.hex(' '),
    ], order
    assert simulator.remote_commands == 4, simulator.remote_commands


async def test_status_probe_is_silent() -> None:
    _, app, _ = await connect(CameraOptions())
    await app.run_handshake()
    await app.subscribe_status()

    # The probe is a half-release with nothing pressed: accepted, no status change.
    await app.write(REMOTE_CONTROL_UUID, HALF_RELEASE)
    await asyncio.sleep(0.2)
    assert not app.notifications, app.notifications


async def test_remote_control_disabled() -> None:
    _, app, _ = await connect(CameraOptions(remote_enabled=False))
    await app.run_handshake()
    await app.subscribe_status()

    try:
        await app.write(REMOTE_CONTROL_UUID, FULL_PRESS)
    except Exception:  # noqa: BLE001 - the app treats any write failure as "inactive"
        pass
    else:
        raise AssertionError('writing to FF01 should fail while remote control is off')

    await app.wait_for_status(STATUS_REMOTE_OFF)


async def test_omitted_characteristics() -> None:
    simulator, app, _ = await connect(CameraOptions(omit=frozenset({'dd21', 'cc13'})))

    assert app.characteristic(CONFIG_READ_UUID) is None, 'DD21 should be omitted'
    assert app.characteristic(TIME_SYNC_UUID) is None, 'CC13 should be omitted'

    # The app skips the missing steps and still completes the handshake.
    config = await app.run_handshake()
    assert config is None
    assert simulator.last_time_sync is None


async def test_encryption_required() -> None:
    _, app, _ = await connect(CameraOptions(require_encryption=True))

    try:
        await app.read(CONFIG_READ_UUID)
    except ATT_Error as error:
        # The app maps 5/15 to an auth error and retries after pairing.
        assert error.error_code in (5, 15), error.error_code
    else:
        raise AssertionError('reading before pairing should fail')

    await app.peer.connection.pair()
    assert app.peer.connection.encryption, 'the link should be encrypted after pairing'

    config = await app.run_handshake()
    assert config is not None and len(config) >= 5, config


async def test_disconnect_after() -> None:
    _, app, phone = await connect(CameraOptions(disconnect_after=0.2))
    disconnected = asyncio.Event()
    app.peer.connection.on(app.peer.connection.EVENT_DISCONNECTION, lambda _: disconnected.set())
    await asyncio.wait_for(disconnected.wait(), 3.0)


def simulator_status_order(app: AppHarness) -> list[bytes]:
    return app.notifications


# -----------------------------------------------------------------------------

SCENARIOS: list[tuple[str, Callable[[], Awaitable[None]]]] = [
    ('handshake + location packet', test_handshake_and_location),
    ('no timezone support', test_no_timezone_support),
    ('config read retry', test_config_read_retry),
    ('shutter sequence', test_shutter_sequence),
    ('status probe is silent', test_status_probe_is_silent),
    ('remote control disabled', test_remote_control_disabled),
    ('omitted characteristics', test_omitted_characteristics),
    ('encryption required (pairing)', test_encryption_required),
    ('disconnect after a delay', test_disconnect_after),
]


async def main() -> int:
    failures = 0
    for name, scenario in SCENARIOS:
        try:
            await asyncio.wait_for(scenario(), 15.0)
        except Exception:  # noqa: BLE001 - report and keep going
            failures += 1
            print(f'FAIL  {name}')
            traceback.print_exc()
        else:
            print(f'ok    {name}')

    print()
    print(f'{len(SCENARIOS) - failures}/{len(SCENARIOS)} scenarios passed')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(asyncio.run(main()))
