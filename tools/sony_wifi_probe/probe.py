#!/usr/bin/env python3
"""PTP/IP connection probe with optional negotiation, live view and shutter tests.

Default mode only reads DeviceInfo. --capture-once sends one shutter press;
--liveview-frames enables preview temporarily. No credentials or images are logged.
"""

import argparse
import json
import socket
import struct
import time
import uuid
from pathlib import Path
from xml.etree import ElementTree

MAX_PACKET = 8 * 1024 * 1024


class PacketReader:
    """Keep incomplete TCP packets across socket timeouts."""

    def __init__(self, sock):
        self.sock = sock
        self.buffer = bytearray()

    def read(self, deadline=None):
        while True:
            length = 8
            if len(self.buffer) >= 8:
                length, packet_type = struct.unpack_from("<II", self.buffer)
                if not 8 <= length <= MAX_PACKET:
                    raise ValueError("invalid PTP/IP packet length")
                if len(self.buffer) >= length:
                    body = bytes(self.buffer[8:length])
                    del self.buffer[:length]
                    return packet_type, body
            previous_timeout = self.sock.gettimeout()
            try:
                if deadline is not None:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError("PTP/IP operation deadline")
                    self.sock.settimeout(remaining)
                chunk = self.sock.recv(min(length - len(self.buffer), 64 * 1024))
            finally:
                if deadline is not None:
                    self.sock.settimeout(previous_timeout)
            if not chunk:
                raise EOFError("camera closed the PTP/IP connection")
            self.buffer.extend(chunk)


def read_exact(sock: socket.socket, length: int) -> bytes:
    parts = bytearray()
    while len(parts) < length:
        chunk = sock.recv(length - len(parts))
        if not chunk:
            raise EOFError("camera closed the PTP/IP connection")
        parts.extend(chunk)
    return bytes(parts)


def send_packet(sock: socket.socket, packet_type: int, body: bytes) -> None:
    length = 8 + len(body)
    if length > MAX_PACKET:
        raise ValueError("PTP/IP packet is too large")
    sock.sendall(struct.pack("<II", length, packet_type) + body)


def read_packet(sock: socket.socket) -> tuple[int, bytes]:
    length, packet_type = struct.unpack("<II", read_exact(sock, 8))
    if not 8 <= length <= MAX_PACKET:
        raise ValueError("invalid PTP/IP packet length")
    return packet_type, read_exact(sock, length - 8)


def parse_command_ack(body: bytes) -> tuple[int, int]:
    if len(body) < 26 or len(body) % 2:
        raise ValueError("malformed command acknowledgement")
    connection_number = struct.unpack_from("<I", body)[0]
    name_end = next((offset for offset in range(20, len(body) - 4, 2)
                     if body[offset:offset + 2] == b"\0\0"), -1)
    if name_end < 0 or name_end + 2 != len(body) - 4:
        raise ValueError("malformed camera name")
    version = struct.unpack_from("<I", body, len(body) - 4)[0]
    return connection_number, version


def parse_device_info_operations(data: bytes) -> list[int]:
    """Read only the public operation codes; never decode serial-bearing fields."""
    offset = 0

    def take(length: int) -> bytes:
        nonlocal offset
        if length < 0 or length > len(data) - offset:
            raise ValueError("malformed DeviceInfo dataset")
        result = data[offset:offset + length]
        offset += length
        return result

    take(2 + 4 + 2)  # PTP version, vendor-extension ID and version
    description_chars = take(1)[0]
    take(description_chars * 2)  # vendor-extension description, not reported
    take(2)  # functional mode
    count = struct.unpack("<I", take(4))[0]
    if count > (len(data) - offset) // 2:
        raise ValueError("malformed DeviceInfo operation count")
    return list(struct.unpack(f"<{count}H", take(count * 2)))


def execute_operation(sock: socket.socket, trace: list[dict], stage: str,
                      code: int, transaction_id: int,
                      parameters: tuple[int, ...] = (), data_out: bytes | None = None,
                      reader: PacketReader | None = None) -> tuple[int, bytes, list[int]]:
    if len(parameters) > 5:
        raise ValueError("too many PTP operation parameters")
    deadline = time.monotonic() + (sock.gettimeout() or 30)
    if data_out is not None and len(data_out) > MAX_PACKET - 12:
        raise ValueError("data-out payload is too large")
    request = struct.pack("<IHI", 2 if data_out else 1, code, transaction_id)
    request += struct.pack(f"<{len(parameters)}I", *parameters)
    send_packet(sock, 6, request)
    if data_out:
        send_packet(sock, 9, struct.pack("<IQ", transaction_id, len(data_out)))
        send_packet(sock, 12, struct.pack("<I", transaction_id) + data_out)
    reader = reader or PacketReader(sock)
    chunks = bytearray()
    expected_length = None
    ended = False
    while True:
        packet_type, body = reader.read(deadline)
        trace.append({"stage": stage, "type": packet_type, "body_bytes": len(body)})
        if packet_type == 7 and len(body) >= 6:
            received_id = struct.unpack_from("<I", body, 2)[0]
        elif packet_type in (9, 10, 12) and len(body) >= 4:
            received_id = struct.unpack_from("<I", body)[0]
        else:
            raise ValueError("unexpected or malformed operation packet")
        if received_id < transaction_id:
            continue
        if received_id != transaction_id:
            raise ValueError("wrong transaction ID")
        if packet_type == 9:
            if len(body) != 12 or expected_length is not None:
                raise ValueError("malformed start-data packet")
            received_id, expected_length = struct.unpack("<IQ", body)
            if received_id != transaction_id or expected_length > MAX_PACKET:
                raise ValueError("invalid data transfer")
        elif packet_type in (10, 12):
            if len(body) < 4 or expected_length is None or ended:
                raise ValueError("unexpected data packet")
            received_id = struct.unpack_from("<I", body)[0]
            if received_id != transaction_id or len(chunks) + len(body) - 4 > expected_length:
                raise ValueError("invalid data chunk")
            chunks.extend(body[4:])
            if packet_type == 12:
                ended = True
        elif packet_type == 7:
            if len(body) < 6 or (len(body) - 6) % 4 or len(body) > 26:
                raise ValueError("malformed operation response")
            response_code, received_id = struct.unpack_from("<HI", body)
            if received_id != transaction_id:
                raise ValueError("wrong transaction ID")
            if expected_length is not None and (not ended or len(chunks) != expected_length):
                raise ValueError("incomplete data transfer")
            parameters = list(struct.unpack_from(f"<{(len(body) - 6) // 4}I", body, 6))
            return response_code, bytes(chunks), parameters
        else:
            raise ValueError(f"unexpected PTP/IP packet type {packet_type}")


def server_version(did_xml: bytes) -> str:
    try:
        root = ElementTree.fromstring(did_xml)
    except ElementTree.ParseError as failure:
        raise ValueError("malformed DID XML") from failure
    versions = [element.text.strip() for element in root.iter()
                if element.tag.rsplit("}", 1)[-1] == "X_ServerVersion" and element.text]
    if len(versions) != 1 or not versions[0].replace(".", "", 1).isdigit():
        raise ValueError("DID XML has no unambiguous numeric server version")
    return versions[0]


def parse_extended_info(data: bytes) -> tuple[int, list[int], list[int]]:
    offset = 0

    def take(length: int) -> bytes:
        nonlocal offset
        if length < 0 or length > len(data) - offset:
            raise ValueError("malformed extended device info")
        result = data[offset:offset + length]
        offset += length
        return result

    def codes() -> list[int]:
        count = struct.unpack("<I", take(4))[0]
        if count > 4096 or count > (len(data) - offset) // 2:
            raise ValueError("malformed extended device info code count")
        return list(struct.unpack(f"<{count}H", take(count * 2)))

    extension_version = struct.unpack("<H", take(2))[0]
    return extension_version, codes(), codes()


def dd_liveview_url_present(dd_xml: bytes) -> bool:
    try:
        root = ElementTree.fromstring(dd_xml)
    except ElementTree.ParseError as failure:
        raise ValueError("malformed DD XML") from failure
    return any(element.tag.rsplit("}", 1)[-1] == "X_ScalarWebAPI_LiveView_URL" and
               bool(element.text and element.text.strip()) for element in root.iter())


def negotiate(sock: socket.socket, trace: list[dict], operations: list[int], diagnostics=None) -> dict:
    stages = []
    transaction_id = 2
    session_open = False
    reader = PacketReader(sock)

    def run(stage: str, code: int, parameters: tuple[int, ...] = (),
            data_out: bytes | None = None) -> tuple[bytes, list[int]]:
        nonlocal transaction_id
        if code not in operations:
            raise ValueError(f"operation_0x{code:04x}_not_advertised")
        current_id = transaction_id
        transaction_id += 1
        response, data, response_params = execute_operation(
            sock, trace, stage, code, current_id, parameters, data_out, reader)
        stages.append({"stage": stage, "operation": f"0x{code:04x}",
                       "response": f"0x{response:04x}", "data_bytes": len(data),
                       "response_parameter_count": len(response_params)})
        if response != 0x2001:
            raise ValueError(f"{stage} returned 0x{response:04x}")
        return data, response_params

    try:
        if not {0x9201, 0x9202}.issubset(operations):
            raise ValueError("camera omits Sony connect or extended-info operation")
        version = None
        dd = b"<Device/>"
        liveview_url_in_dd = False
        if 0x923a in operations:
            dd, _ = run("get_dd_xml", 0x923a, (1,))
            liveview_url_in_dd = dd_liveview_url_present(dd)
            did, _ = run("get_did_xml", 0x923a, (2,))
            version = server_version(did)
        use_sdio_open = version is not None and int(version.split(".")[0]) >= 4
        if use_sdio_open:
            if 0x9210 not in operations:
                raise ValueError("camera omits SDIO_OpenSession")
            run("open_session", 0x9210, (1, 2))
        else:
            run("open_session", 0x1002, (1,))
        session_open = True
        run("sdio_connect_1", 0x9201, (1, 0, 0))
        run("sdio_connect_2", 0x9201, (2, 0, 0))
        vendor_version = None
        if 0x9216 in operations:
            _, params = run("get_vendor_code_version", 0x9216)
            vendor_version = params[0] if params else None
        ext_params = (300, 1) if vendor_version is not None and vendor_version >= 310 else (300,)
        ext_info, _ = run("get_extended_device_info", 0x9202, ext_params)
        extension_version, property_codes, control_codes = parse_extended_info(ext_info)
        run("sdio_connect_3", 0x9201, (3, 0, 0))
        result = {"server_version": version, "session_mode": "sdio_remote_with_transfer" if use_sdio_open
                else "standard_remote", "vendor_code_version": vendor_version,
                "extended_info_bytes": len(ext_info), "extension_version": extension_version,
                "supported_property_codes": [f"0x{code:04x}" for code in property_codes],
                "supported_control_codes": [f"0x{code:04x}" for code in control_codes],
                "liveview_url_in_dd": liveview_url_in_dd, "stages": stages}
        if diagnostics is not None:
            result["diagnostics"] = diagnostics(run, dd, property_codes, control_codes, vendor_version)
        return result
    except (OSError, EOFError, ValueError) as failure:
        error = str(failure) if isinstance(failure, ValueError) else "socket or connection failure"
        return {"error": error, "stages": stages}
    finally:
        if session_open:
            try:
                run("close_session", 0x1003)
            except (OSError, EOFError, ValueError):
                if not stages or stages[-1]["stage"] != "close_session":
                    stages.append({"stage": "close_session", "error": "failed"})


def probe(host: str, port: int, timeout: float, do_negotiate: bool = False,
          liveview_frames: int = 0, capture_once: bool = False, half_press_ms: int = 0) -> dict:
    if not 0 <= half_press_ms <= 2000:
        raise ValueError("half_press_ms must be between 0 and 2000")
    trace = []
    client_uuid = uuid.uuid4().int
    # The APK writes the two UUID halves as little-endian 64-bit integers.
    wire_guid = struct.pack("<QQ", client_uuid >> 64, client_uuid & ((1 << 64) - 1))
    name = "AlphaGPS Probe".encode("utf-16le") + b"\0\0"
    init_body = wire_guid + name + struct.pack("<I", 0x00010000)

    with socket.create_connection((host, port), timeout=timeout) as command:
        command.settimeout(timeout)
        send_packet(command, 1, init_body)
        packet_type, body = read_packet(command)
        trace.append({"stage": "command_init", "type": packet_type, "body_bytes": len(body)})
        if packet_type != 2:
            raise ValueError(f"camera refused command connection (packet type {packet_type})")
        connection_number, version = parse_command_ack(body)
        with socket.create_connection((host, port), timeout=timeout) as event:
            event.settimeout(timeout)
            send_packet(event, 3, struct.pack("<I", connection_number))
            packet_type, body = read_packet(event)
            trace.append({"stage": "event_init", "type": packet_type, "body_bytes": len(body)})
            if packet_type != 4:
                raise ValueError(f"camera refused event connection (packet type {packet_type})")
            response_code, data, _ = execute_operation(
                command, trace, "get_device_info", 0x1001, 1)
            if response_code == 0x2001:
                operations = parse_device_info_operations(data)
                if do_negotiate or liveview_frames or capture_once:
                    if liveview_frames or capture_once:
                        from diagnostics import EventMonitor, run_diagnostics
                        with EventMonitor(event, PacketReader) as monitor:
                            negotiated = negotiate(command, trace, operations,
                                lambda run, dd, props, controls, vendor: run_diagnostics(
                                    run, dd, props, controls, vendor, command.getpeername()[0],
                                    timeout, monitor, liveview_frames, capture_once, half_press_ms))
                    else:
                        negotiated = negotiate(command, trace, operations)
    result = {
        "protocol_version": f"0x{version:08x}",
        "get_device_info_response": f"0x{response_code:04x}",
        "device_info_bytes": len(data),
        "packets": trace,
    }
    if response_code == 0x2001:
        operations = parse_device_info_operations(data)
        result["supported_operations"] = [f"0x{code:04x}" for code in operations]
        result["sony_initialization_operations"] = {
            name: f"0x{code:04x}" in result["supported_operations"]
            for name, code in {
                "get_device_description_file": 0x923a,
                "sdio_open_session": 0x9210,
                "sdio_connect": 0x9201,
                "get_vendor_code_version": 0x9216,
                "get_extended_device_info": 0x9202,
                "control_device": 0x9207,
            }.items()
        }
        if do_negotiate or liveview_frames or capture_once:
            result["negotiation"] = negotiated
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="camera IP from the joined network")
    parser.add_argument("--port", type=int, default=15740,
                        help="PTP/IP port; 15740 is the APK default, not a universal camera value")
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--negotiate", action="store_true",
                        help="open and close a Sony remote session, without capture or live view")
    parser.add_argument("--liveview-frames", type=int, default=0, choices=range(0, 11),
                        metavar="0..10", help="enable preview, inspect this many JPEG frames, then disable preview")
    parser.add_argument("--capture-once", action="store_true",
                        help="send one shutter press/release sequence; use single-shot drive mode")
    parser.add_argument("--half-press-ms", type=int, default=0, metavar="0..2000",
                        help="diagnostic pause between half and full press (requires --capture-once)")
    parser.add_argument("--output", type=Path, help="save sanitized packet metadata as JSON")
    args = parser.parse_args()
    if args.half_press_ms and not args.capture_once:
        parser.error("--half-press-ms requires --capture-once")
    try:
        result = probe(args.host, args.port, args.timeout, args.negotiate,
                       args.liveview_frames, args.capture_once, args.half_press_ms)
    except (OSError, EOFError, ValueError) as failure:
        parser.exit(2, f"Probe failed: {failure.__class__.__name__}: {failure}\n")
    rendered = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n")
    print(rendered)


if __name__ == "__main__":
    main()
