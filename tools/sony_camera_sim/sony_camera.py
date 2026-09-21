"""
Simulated Sony camera GATT server.

Mirrors the services, characteristics and byte protocol that the Alpha GPS app
talks to, so the app can be exercised without a real camera. The authoritative
definitions live in
`sharednew/src/commonMain/kotlin/com/sasch/cameragps/sharednew/bluetooth/SonyBluetoothConstants.kt`
and the handshake driven by `BleSessionCoordinator` / `RemoteControlCoordinator`.

This module is transport-agnostic: it only builds the GATT database and reacts
to reads/writes. `sony_camera_sim.py` wires it to a controller and advertises;
`self_test.py` wires it to a virtual link.
"""

from __future__ import annotations

import asyncio
import dataclasses
import datetime
import logging
import struct
from collections.abc import Iterable

from bumble.att import (
    ATT_UNLIKELY_ERROR_ERROR,
    ATT_WRITE_NOT_PERMITTED_ERROR,
    ATT_Error,
    Attribute,
)
from bumble.device import Connection, Device
from bumble.gatt import Characteristic, CharacteristicValue, Service

logger = logging.getLogger('sony_camera')

# -----------------------------------------------------------------------------
# Protocol constants — keep in sync with SonyBluetoothConstants.kt
# -----------------------------------------------------------------------------

LOCATION_SERVICE_UUID = '8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF'
CONTROL_SERVICE_UUID = '8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF'
REMOTE_SERVICE_UUID = '8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF'

# The characteristics are plain Bluetooth-base UUIDs, so they go on the wire in
# their 16-bit form, the way a real camera exposes them (the app spells them out
# as 0000DD11-0000-1000-8000-00805F9B34FB etc.; both forms compare equal).
LOCATION_WRITE_UUID = 'DD11'
CONFIG_READ_UUID = 'DD21'
GPS_UNLOCK_UUID = 'DD30'
GPS_LOCK_UUID = 'DD31'
LOCATION_ENABLED_UUID = 'DD01'
TIME_SYNC_UUID = 'CC13'
REMOTE_CONTROL_UUID = 'FF01'
REMOTE_STATUS_UUID = 'FF02'

# Short names accepted by --omit, mapped to the characteristic they remove.
OMITTABLE = {
    'dd01': LOCATION_ENABLED_UUID,
    'dd11': LOCATION_WRITE_UUID,
    'dd21': CONFIG_READ_UUID,
    'dd30': GPS_UNLOCK_UUID,
    'dd31': GPS_LOCK_UUID,
    'cc13': TIME_SYNC_UUID,
    'ff01': REMOTE_CONTROL_UUID,
    'ff02': REMOTE_STATUS_UUID,
}

# Remote status payloads the app matches on (prefix match).
STATUS_READY = bytes([0x02, 0xA0, 0x00])  # idle; also acks a full press
STATUS_SHUTTER_ACTIVE = bytes([0x02, 0xA0, 0x20])  # exposure running
STATUS_FOCUS_ACQUIRED = bytes([0x02, 0x3F, 0x20])  # focus locked after half press
STATUS_REMOTE_OFF = bytes([0x02, 0xC3, 0x00])  # the only "feature inactive" value

# Remote control commands written to FF01.
REMOTE_COMMANDS = {
    bytes([0x01, 0x09]): 'shutter full press',
    bytes([0x01, 0x08]): 'shutter full release',
    bytes([0x01, 0x07]): 'shutter half press',
    bytes([0x01, 0x06]): 'shutter half release / status probe',
    bytes([0x01, 0x15]): 'AF-ON press',
    bytes([0x01, 0x14]): 'AF-ON release',
}

FULL_PRESS = bytes([0x01, 0x09])
FULL_RELEASE = bytes([0x01, 0x08])
HALF_PRESS = bytes([0x01, 0x07])
HALF_RELEASE = bytes([0x01, 0x06])
AF_ON_PRESS = bytes([0x01, 0x15])
AF_ON_RELEASE = bytes([0x01, 0x14])

GPS_ENABLE_COMMAND = bytes([0x01])

# Fixed prefix of the location packet, per LocationDataConfig.kt.
LOCATION_PREFIX_TZ = bytes([0x00, 0x5D, 0x08, 0x02, 0xFC, 0x03, 0x00, 0x00, 0x10, 0x10, 0x10])
LOCATION_PREFIX_NO_TZ = bytes([0x00, 0x59, 0x08, 0x02, 0xFC, 0x00, 0x00, 0x00, 0x10, 0x10, 0x10])


# -----------------------------------------------------------------------------
# Packet decoding
# -----------------------------------------------------------------------------


@dataclasses.dataclass
class LocationPacket:
    """Decoded DD11 payload (see LocationPacketBuilder.buildLocationDataPacket)."""

    latitude: float
    longitude: float
    utc: datetime.datetime | None
    time_zone_minutes: int | None
    dst_minutes: int | None
    problems: list[str]

    def __str__(self) -> str:
        parts = [f'lat={self.latitude:+.7f} lon={self.longitude:+.7f}']
        parts.append(f'utc={self.utc.isoformat() if self.utc else "<invalid>"}')
        if self.time_zone_minutes is not None:
            parts.append(f'tz={self.time_zone_minutes:+d}min dst={self.dst_minutes:+d}min')
        if self.problems:
            parts.append('problems=[' + '; '.join(self.problems) + ']')
        return ' '.join(parts)


def decode_location_packet(data: bytes) -> LocationPacket:
    problems: list[str] = []

    if len(data) not in (91, 95):
        problems.append(f'unexpected length {len(data)} (expected 91 or 95)')

    with_time_zone = len(data) >= 95
    expected_prefix = LOCATION_PREFIX_TZ if with_time_zone else LOCATION_PREFIX_NO_TZ
    if data[: len(expected_prefix)] != expected_prefix:
        problems.append(
            f'prefix {data[: len(expected_prefix)].hex(" ")} != {expected_prefix.hex(" ")}'
        )

    latitude = longitude = 0.0
    utc: datetime.datetime | None = None
    if len(data) >= 26:
        lat_e7, lon_e7 = struct.unpack_from('>ii', data, 11)
        latitude = lat_e7 / 1e7
        longitude = lon_e7 / 1e7
        year, month, day, hour, minute, second = struct.unpack_from('>HBBBBB', data, 19)
        try:
            utc = datetime.datetime(
                year, month, day, hour, minute, second, tzinfo=datetime.timezone.utc
            )
        except ValueError as error:
            problems.append(f'invalid timestamp: {error}')
    else:
        problems.append('packet too short to decode')

    time_zone_minutes = dst_minutes = None
    if with_time_zone:
        time_zone_minutes, dst_minutes = struct.unpack_from('>hh', data, 91)

    return LocationPacket(
        latitude=latitude,
        longitude=longitude,
        utc=utc,
        time_zone_minutes=time_zone_minutes,
        dst_minutes=dst_minutes,
        problems=problems,
    )


@dataclasses.dataclass
class TimeSyncPacket:
    """Decoded CC13 payload (see LocationPacketBuilder.buildTimeSyncPacket)."""

    local_time: datetime.datetime | None
    dst_active: bool
    offset_hours: int
    offset_minutes: int
    problems: list[str]

    def __str__(self) -> str:
        parts = [f'local={self.local_time.isoformat() if self.local_time else "<invalid>"}']
        parts.append(f'utc_offset={self.offset_hours:+d}h{self.offset_minutes:02d}m')
        parts.append(f'dst={"on" if self.dst_active else "off"}')
        if self.problems:
            parts.append('problems=[' + '; '.join(self.problems) + ']')
        return ' '.join(parts)


def decode_time_sync_packet(data: bytes) -> TimeSyncPacket:
    problems: list[str] = []
    if len(data) != 13:
        problems.append(f'unexpected length {len(data)} (expected 13)')
    if len(data) < 13:
        return TimeSyncPacket(None, False, 0, 0, problems)

    if data[0] != 12:
        problems.append(f'header byte {data[0]} != 12')

    year, month, day, hour, minute, second = struct.unpack_from('>HBBBBB', data, 3)
    local_time: datetime.datetime | None = None
    try:
        local_time = datetime.datetime(year, month, day, hour, minute, second)
    except ValueError as error:
        problems.append(f'invalid timestamp: {error}')

    offset_hours = struct.unpack_from('>b', data, 11)[0]
    return TimeSyncPacket(
        local_time=local_time,
        dst_active=data[10] != 0,
        offset_hours=offset_hours,
        offset_minutes=data[12],
        problems=problems,
    )


# -----------------------------------------------------------------------------
# Simulator
# -----------------------------------------------------------------------------


@dataclasses.dataclass
class CameraOptions:
    """Everything that makes the simulated camera behave differently."""

    name: str = 'ILCE-7RM4'

    # Value returned by the DD21 config read. Only byte 4 is interpreted by the
    # app: bit 0x02 means "send timezone and DST", which also switches the
    # location packet from 91 to 95 bytes.
    time_zone_supported: bool = True
    config_value: bytes | None = None

    # Fail the first N config reads with an ATT error, to exercise the app's
    # one-shot GATT-133 retry.
    fail_config_reads: int = 0

    # Require an encrypted link for every Sony characteristic. Forces pairing
    # (and exercises the iOS pairing gate + the shared auth-error retry).
    require_encryption: bool = False

    # "Bluetooth remote control" in the camera menu. When off, FF01 writes are
    # rejected and the camera reports the feature-inactive status.
    remote_enabled: bool = True

    # Shutter timing, in seconds.
    focus_delay: float = 0.4
    exposure_time: float = 0.6

    # Skip individual status notifications to exercise the app's fallbacks.
    report_focus: bool = True
    report_shutter_active: bool = True

    # Characteristics to leave out of the GATT database (short names, see OMITTABLE).
    omit: frozenset[str] = frozenset()

    # Drop the connection this many seconds after it is established (0 = never).
    disconnect_after: float = 0.0

    def resolved_config_value(self) -> bytes:
        if self.config_value is not None:
            return self.config_value
        # Byte 4 carries the capability flags; 0x02 = timezone/DST supported.
        # The other bytes are not interpreted by the app.
        flags = 0x03 if self.time_zone_supported else 0x01
        return bytes([0x01, 0x00, 0x00, 0x00, flags])


class SonyCameraSimulator:
    """A fake Sony camera: GATT database + the bits of behaviour the app relies on."""

    def __init__(self, options: CameraOptions | None = None) -> None:
        self.options = options or CameraOptions()
        self.device: Device | None = None
        self.connection: Connection | None = None

        self._remaining_config_failures = self.options.fail_config_reads
        self._half_pressed = False
        self._full_pressed = False
        self._tasks: set[asyncio.Task] = set()

        self.remote_status_characteristic: Characteristic | None = None
        self.location_enabled_characteristic: Characteristic | None = None

        # Counters, handy for tests and for the periodic summary line.
        self.location_writes = 0
        self.remote_commands = 0
        self.last_location: LocationPacket | None = None
        self.last_time_sync: TimeSyncPacket | None = None

    # -- setup ---------------------------------------------------------------

    def attach(self, device: Device) -> None:
        """Add the camera's services to `device` and hook its connection events."""
        self.device = device
        for service in self.build_services():
            device.add_service(service)
        device.on(Device.EVENT_CONNECTION, self._on_connection)

    def build_services(self) -> list[Service]:
        omitted = {name.lower() for name in self.options.omit}
        unknown = omitted - OMITTABLE.keys()
        if unknown:
            raise ValueError(f'unknown --omit value(s): {", ".join(sorted(unknown))}')

        def included(short_name: str) -> bool:
            return short_name not in omitted

        location_characteristics = []
        if included('dd01'):
            # Read/notify status characteristic. It is the first notifiable
            # characteristic in the database, so iOS uses it as its pairing gate.
            self.location_enabled_characteristic = Characteristic(
                LOCATION_ENABLED_UUID,
                Characteristic.Properties.READ | Characteristic.Properties.NOTIFY,
                self._read_permissions(),
                bytes([0x01]),
            )
            location_characteristics.append(self.location_enabled_characteristic)
        if included('dd11'):
            location_characteristics.append(
                Characteristic(
                    LOCATION_WRITE_UUID,
                    Characteristic.Properties.WRITE
                    | Characteristic.Properties.WRITE_WITHOUT_RESPONSE,
                    self._write_permissions(),
                    CharacteristicValue(write=self._on_location_write),
                )
            )
        if included('dd21'):
            location_characteristics.append(
                Characteristic(
                    CONFIG_READ_UUID,
                    Characteristic.Properties.READ,
                    self._read_permissions(),
                    CharacteristicValue(read=self._on_config_read),
                )
            )
        if included('dd30'):
            location_characteristics.append(
                Characteristic(
                    GPS_UNLOCK_UUID,
                    Characteristic.Properties.WRITE,
                    self._write_permissions(),
                    CharacteristicValue(write=self._on_gps_unlock_write),
                )
            )
        if included('dd31'):
            location_characteristics.append(
                Characteristic(
                    GPS_LOCK_UUID,
                    Characteristic.Properties.WRITE,
                    self._write_permissions(),
                    CharacteristicValue(write=self._on_gps_lock_write),
                )
            )

        control_characteristics = []
        if included('cc13'):
            control_characteristics.append(
                Characteristic(
                    TIME_SYNC_UUID,
                    Characteristic.Properties.WRITE,
                    self._write_permissions(),
                    CharacteristicValue(write=self._on_time_sync_write),
                )
            )

        remote_characteristics = []
        if included('ff01'):
            remote_characteristics.append(
                Characteristic(
                    REMOTE_CONTROL_UUID,
                    Characteristic.Properties.WRITE
                    | Characteristic.Properties.WRITE_WITHOUT_RESPONSE,
                    self._write_permissions(),
                    CharacteristicValue(write=self._on_remote_control_write),
                )
            )
        if included('ff02'):
            self.remote_status_characteristic = Characteristic(
                REMOTE_STATUS_UUID,
                Characteristic.Properties.READ | Characteristic.Properties.NOTIFY,
                self._read_permissions(),
                CharacteristicValue(read=lambda _: self._current_status()),
            )
            self.remote_status_characteristic.on(
                Characteristic.EVENT_SUBSCRIPTION, self._on_status_subscription
            )
            remote_characteristics.append(self.remote_status_characteristic)

        services = []
        if location_characteristics:
            services.append(Service(LOCATION_SERVICE_UUID, location_characteristics))
        if control_characteristics:
            services.append(Service(CONTROL_SERVICE_UUID, control_characteristics))
        if remote_characteristics:
            services.append(Service(REMOTE_SERVICE_UUID, remote_characteristics))
        return services

    def _read_permissions(self) -> Attribute.Permissions:
        permissions = Attribute.Permissions.READABLE
        if self.options.require_encryption:
            permissions |= Attribute.Permissions.READ_REQUIRES_ENCRYPTION
        return permissions

    def _write_permissions(self) -> Attribute.Permissions:
        permissions = Attribute.Permissions.WRITEABLE
        if self.options.require_encryption:
            permissions |= Attribute.Permissions.WRITE_REQUIRES_ENCRYPTION
        return permissions

    # -- connection lifecycle ------------------------------------------------

    def _on_connection(self, connection: Connection) -> None:
        self.connection = connection
        logger.info('=== connected: %s ===', connection.peer_address)
        connection.on(Connection.EVENT_DISCONNECTION, self._on_disconnection)
        connection.on(
            Connection.EVENT_CONNECTION_ENCRYPTION_CHANGE,
            lambda: logger.info('link encryption: %s', connection.encryption),
        )
        self._reset_state()

        if self.options.disconnect_after > 0:
            self._spawn(self._disconnect_later(connection, self.options.disconnect_after))

    def _on_disconnection(self, reason: int) -> None:
        logger.info('=== disconnected (reason 0x%02X) ===', reason)
        self.connection = None
        self._reset_state()

    def _reset_state(self) -> None:
        self._half_pressed = False
        self._full_pressed = False
        for task in list(self._tasks):
            task.cancel()
        self._tasks.clear()
        self._remaining_config_failures = self.options.fail_config_reads

    async def _disconnect_later(self, connection: Connection, delay: float) -> None:
        await asyncio.sleep(delay)
        logger.warning('dropping the connection after %.1fs (--disconnect-after)', delay)
        await connection.disconnect()

    # -- location service ----------------------------------------------------

    def _on_config_read(self, _connection: Connection | None) -> bytes:
        if self._remaining_config_failures > 0:
            self._remaining_config_failures -= 1
            logger.warning(
                'DD21 config read -> failing on purpose (%d more to fail)',
                self._remaining_config_failures,
            )
            raise ATT_Error(ATT_UNLIKELY_ERROR_ERROR)

        value = self.options.resolved_config_value()
        logger.info(
            'DD21 config read -> %s (timezone/DST %s)',
            value.hex(' '),
            'supported' if len(value) >= 5 and value[4] & 0x02 else 'not supported',
        )
        return value

    def _on_location_write(self, _connection: Connection | None, value: bytes) -> None:
        self.location_writes += 1
        packet = decode_location_packet(value)
        self.last_location = packet
        logger.info('DD11 location #%d: %s', self.location_writes, packet)
        if packet.problems:
            logger.warning('DD11 raw: %s', value.hex(' '))

    def _on_gps_unlock_write(self, _connection: Connection | None, value: bytes) -> None:
        self._log_gps_command('DD30 GPS unlock', value)

    def _on_gps_lock_write(self, _connection: Connection | None, value: bytes) -> None:
        self._log_gps_command('DD31 GPS lock', value)

    def _log_gps_command(self, label: str, value: bytes) -> None:
        if value == GPS_ENABLE_COMMAND:
            logger.info('%s: enable', label)
        else:
            logger.warning('%s: unexpected payload %s', label, value.hex(' '))

    # -- control service -----------------------------------------------------

    def _on_time_sync_write(self, _connection: Connection | None, value: bytes) -> None:
        packet = decode_time_sync_packet(value)
        self.last_time_sync = packet
        logger.info('CC13 time sync: %s', packet)
        if packet.problems:
            logger.warning('CC13 raw: %s', value.hex(' '))

    # -- remote service ------------------------------------------------------

    def _current_status(self) -> bytes:
        if not self.options.remote_enabled:
            return STATUS_REMOTE_OFF
        if self._full_pressed:
            return STATUS_SHUTTER_ACTIVE
        return STATUS_READY

    def _on_status_subscription(
        self, _connection: Connection, notify_enabled: bool, indicate_enabled: bool
    ) -> None:
        logger.info(
            'FF02 subscription: notify=%s indicate=%s', notify_enabled, indicate_enabled
        )
        if notify_enabled and not self.options.remote_enabled:
            # A camera with the remote function switched off reports it right away.
            self._spawn(self._notify_status(STATUS_REMOTE_OFF, delay=0.05))

    def _on_remote_control_write(self, _connection: Connection | None, value: bytes) -> None:
        command = REMOTE_COMMANDS.get(bytes(value), 'unknown command')
        self.remote_commands += 1

        if not self.options.remote_enabled:
            logger.info('FF01 %s (%s) -> rejected, remote control is off', value.hex(' '), command)
            # The app treats the write failure as "feature inactive" and keeps probing.
            self._spawn(self._notify_status(STATUS_REMOTE_OFF, delay=0.05))
            raise ATT_Error(ATT_WRITE_NOT_PERMITTED_ERROR)

        value = bytes(value)
        if value == HALF_RELEASE and not self._half_pressed:
            logger.info('FF01 %s -> status probe', value.hex(' '))
            return

        logger.info('FF01 %s -> %s', value.hex(' '), command)

        if value in (HALF_PRESS, AF_ON_PRESS):
            self._half_pressed = True
            if self.options.report_focus:
                self._spawn(
                    self._notify_status(STATUS_FOCUS_ACQUIRED, delay=self.options.focus_delay)
                )
            else:
                logger.warning('not reporting focus (--no-focus-status)')
        elif value in (HALF_RELEASE, AF_ON_RELEASE):
            self._half_pressed = False
        elif value == FULL_PRESS:
            self._full_pressed = True
            self._spawn(self._run_exposure())
        elif value == FULL_RELEASE:
            self._full_pressed = False
        else:
            logger.warning('FF01 unknown command %s', value.hex(' '))

    async def _run_exposure(self) -> None:
        """Shutter active -> exposure -> ready, like a camera in single-shot mode."""
        if self.options.report_shutter_active:
            await asyncio.sleep(0.05)
            await self._send_status(STATUS_SHUTTER_ACTIVE)
        else:
            logger.warning('not reporting the shutter active (--no-shutter-active-status)')

        await asyncio.sleep(self.options.exposure_time)
        self._full_pressed = False
        await self._send_status(STATUS_READY)

    async def _notify_status(self, status: bytes, delay: float = 0.0) -> None:
        if delay:
            await asyncio.sleep(delay)
        await self._send_status(status)

    async def _send_status(self, status: bytes) -> None:
        if self.device is None or self.remote_status_characteristic is None:
            return
        logger.info('FF02 notify %s (%s)', status.hex(' '), _status_name(status))
        await self.device.notify_subscribers(self.remote_status_characteristic, status)

    # -- runtime toggles (used by the interactive console) -------------------

    def set_remote_enabled(self, enabled: bool) -> None:
        self.options.remote_enabled = enabled
        logger.info('remote control is now %s', 'ON' if enabled else 'OFF')
        self._spawn(self._notify_status(self._current_status(), delay=0.0))

    def describe_state(self) -> str:
        return (
            f'connected={self.connection is not None} '
            f'remote={"on" if self.options.remote_enabled else "off"} '
            f'half_pressed={self._half_pressed} full_pressed={self._full_pressed} '
            f'location_writes={self.location_writes} remote_commands={self.remote_commands}'
        )

    # -- helpers -------------------------------------------------------------

    def _spawn(self, coroutine) -> asyncio.Task:
        task = asyncio.ensure_future(coroutine)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        task.add_done_callback(_log_task_error)
        return task


def _status_name(status: bytes) -> str:
    return {
        STATUS_READY: 'ready',
        STATUS_SHUTTER_ACTIVE: 'shutter active',
        STATUS_FOCUS_ACQUIRED: 'focus acquired',
        STATUS_REMOTE_OFF: 'remote control off',
    }.get(status, 'unknown')


def _log_task_error(task: asyncio.Task) -> None:
    if task.cancelled():
        return
    error = task.exception()
    if error is not None:
        logger.error('background task failed: %r', error)


def parse_omit(values: Iterable[str]) -> frozenset[str]:
    """Normalise --omit values (repeated and/or comma-separated), raising on unknowns."""
    names = frozenset(
        name.strip().lower()
        for value in values
        for name in value.split(',')
        if name.strip()
    )
    unknown = names - OMITTABLE.keys()
    if unknown:
        raise ValueError(
            f'unknown characteristic(s) {", ".join(sorted(unknown))}; '
            f'known: {", ".join(sorted(OMITTABLE))}'
        )
    return names
