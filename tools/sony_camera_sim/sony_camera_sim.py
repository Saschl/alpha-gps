#!/usr/bin/env python3
"""
Run a simulated Sony camera so the Alpha GPS app has something to talk to.

    python sony_camera_sim.py --transport usb:0

Needs a Bluetooth controller that Bumble can drive over HCI (a USB dongle on
macOS, the built-in adapter on Linux, or the Android emulator's virtual
controller). See README.md for the details.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys

from bumble import hci
from bumble.core import UUID, AdvertisingData
from bumble.device import Device, DeviceConfiguration
from bumble.keys import JsonKeyStore
from bumble.pairing import PairingConfig, PairingDelegate
from bumble.transport import open_transport

from sony_camera import (
    LOCATION_SERVICE_UUID,
    OMITTABLE,
    CameraOptions,
    SonyCameraSimulator,
    parse_omit,
)

SONY_COMPANY_ID = 0x012D

# Arbitrary payload — the app only filters on the company ID (Android's
# CompanionDeviceManager filter and the iOS scan filter both ignore the rest).
DEFAULT_MANUFACTURER_PAYLOAD = bytes([0x03, 0x00, 0x64, 0x00, 0x45, 0x31])

DEFAULT_ADDRESS = 'F0:F1:F2:F3:F4:F5'

logger = logging.getLogger('sony_camera_sim')


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description='Simulate a Sony camera GATT server for the Alpha GPS app.',
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        '--transport',
        default='usb:0',
        help='Bumble HCI transport (usb:0, hci-socket:0, android-netsim, ...)',
    )
    parser.add_argument('--name', default='ILCE-7RM4', help='advertised device name')
    parser.add_argument(
        '--address', default=DEFAULT_ADDRESS, help='static random address to advertise with'
    )
    parser.add_argument(
        '--keystore',
        default='sony_camera_keys.json',
        help='file the pairing keys (bonds) are stored in',
    )
    parser.add_argument(
        '--manufacturer-payload',
        default=DEFAULT_MANUFACTURER_PAYLOAD.hex(),
        help='hex payload advertised after the Sony company ID 0x012D',
    )

    behaviour = parser.add_argument_group('camera behaviour')
    behaviour.add_argument(
        '--no-timezone',
        action='store_true',
        help='report no timezone/DST support, so the app sends 91-byte location packets',
    )
    behaviour.add_argument(
        '--config-value',
        help='hex value returned by the DD21 config read (overrides --no-timezone)',
    )
    behaviour.add_argument(
        '--require-encryption',
        action='store_true',
        help='require an encrypted link, forcing the phone to pair first',
    )
    behaviour.add_argument(
        '--remote-off',
        action='store_true',
        help='act as if Bluetooth remote control is disabled in the camera menu',
    )
    behaviour.add_argument(
        '--focus-delay', type=float, default=0.4, help='seconds until focus is acquired'
    )
    behaviour.add_argument(
        '--exposure', type=float, default=0.6, help='seconds the exposure takes'
    )
    behaviour.add_argument(
        '--omit',
        action='append',
        default=[],
        metavar='NAME',
        help=f'leave a characteristic out; repeatable ({", ".join(sorted(OMITTABLE))})',
    )

    faults = parser.add_argument_group('fault injection')
    faults.add_argument(
        '--fail-config-reads',
        type=int,
        default=0,
        metavar='N',
        help='fail the first N config reads (exercises the app\'s retry)',
    )
    faults.add_argument(
        '--no-focus-status',
        action='store_true',
        help='never report focus acquired (the shutter sequence must fall back to a timeout)',
    )
    faults.add_argument(
        '--no-shutter-active-status',
        action='store_true',
        help='never report the shutter active (as if the camera refused to fire)',
    )
    faults.add_argument(
        '--disconnect-after',
        type=float,
        default=0.0,
        metavar='SECONDS',
        help='drop every connection after this many seconds (0 = never)',
    )

    parser.add_argument('-v', '--verbose', action='store_true', help='log Bumble internals too')
    return parser.parse_args()


def build_options(args: argparse.Namespace) -> CameraOptions:
    return CameraOptions(
        name=args.name,
        time_zone_supported=not args.no_timezone,
        config_value=bytes.fromhex(args.config_value) if args.config_value else None,
        fail_config_reads=args.fail_config_reads,
        require_encryption=args.require_encryption,
        remote_enabled=not args.remote_off,
        focus_delay=args.focus_delay,
        exposure_time=args.exposure,
        report_focus=not args.no_focus_status,
        report_shutter_active=not args.no_shutter_active_status,
        omit=parse_omit(args.omit),
        disconnect_after=args.disconnect_after,
    )


def build_advertising_data(name: str, manufacturer_payload: bytes) -> bytes:
    data = bytes(
        AdvertisingData(
            [
                # LE General Discoverable + BR/EDR not supported
                (AdvertisingData.FLAGS, bytes([0x06])),
                (AdvertisingData.COMPLETE_LOCAL_NAME, name.encode('utf-8')),
                (
                    AdvertisingData.MANUFACTURER_SPECIFIC_DATA,
                    SONY_COMPANY_ID.to_bytes(2, 'little') + manufacturer_payload,
                ),
            ]
        )
    )
    if len(data) > 31:
        raise SystemExit(
            f'advertising data is {len(data)} bytes (max 31) — use a shorter --name '
            f'or --manufacturer-payload'
        )
    return data


def build_scan_response_data() -> bytes:
    # Not needed by the app, but it makes the simulator recognisable in nRF Connect.
    return bytes(
        AdvertisingData(
            [
                (
                    AdvertisingData.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                    UUID(LOCATION_SERVICE_UUID).to_pdu_bytes(),
                )
            ]
        )
    )


async def run_console(simulator: SonyCameraSimulator) -> None:
    """Line-based control while the simulator runs. Silently skipped when not a tty."""
    if not sys.stdin.isatty():
        return

    loop = asyncio.get_running_loop()
    reader = asyncio.StreamReader()
    try:
        await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    except (NotImplementedError, ValueError) as error:
        logger.debug('interactive console unavailable: %r', error)
        return

    logger.info('console: [r] toggle remote control  [d] disconnect  [s] state  [q] quit')
    while True:
        line = await reader.readline()
        if not line:
            return
        command = line.decode(errors='replace').strip().lower()
        if command == 'r':
            simulator.set_remote_enabled(not simulator.options.remote_enabled)
        elif command == 'd':
            if simulator.connection is not None:
                await simulator.connection.disconnect()
            else:
                logger.info('nothing connected')
        elif command == 's':
            logger.info('state: %s', simulator.describe_state())
        elif command == 'q':
            raise SystemExit(0)
        elif command:
            logger.info('unknown command %r', command)


async def main() -> None:
    args = parse_args()
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format='%(asctime)s %(levelname)-7s %(name)s: %(message)s',
        datefmt='%H:%M:%S',
    )
    if not args.verbose:
        logging.getLogger('bumble').setLevel(logging.WARNING)

    options = build_options(args)
    simulator = SonyCameraSimulator(options)

    async with await open_transport(args.transport) as transport:
        config = DeviceConfiguration(
            name=args.name,
            # Parsed as a static random address, so the bond survives restarts.
            address=hci.Address(args.address),
            advertising_data=build_advertising_data(
                args.name, bytes.fromhex(args.manufacturer_payload)
            ),
            scan_response_data=build_scan_response_data(),
            keystore=f'JsonKeyStore:{args.keystore}',
        )
        device = Device.from_config_with_hci(config, transport.source, transport.sink)
        device.keystore = JsonKeyStore.from_device(device, filename=args.keystore)

        # Sony cameras pair without a passkey; bond so the phone can reconnect.
        device.pairing_config_factory = lambda _connection: PairingConfig(
            sc=True,
            mitm=False,
            bonding=True,
            delegate=PairingDelegate(PairingDelegate.NO_OUTPUT_NO_INPUT),
        )

        simulator.attach(device)

        await device.power_on()
        await device.start_advertising(auto_restart=True)

        logger.info('advertising as %r at %s', args.name, device.random_address)
        logger.info(
            'timezone/DST %s, remote control %s, encryption %s',
            'supported' if options.time_zone_supported else 'not supported',
            'on' if options.remote_enabled else 'off',
            'required' if options.require_encryption else 'not required',
        )
        if options.omit:
            logger.info('omitted characteristics: %s', ', '.join(sorted(options.omit)))

        console = asyncio.ensure_future(run_console(simulator))
        try:
            await transport.source.terminated
        finally:
            console.cancel()


if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
