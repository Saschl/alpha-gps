import socket
import struct
import threading
import unittest

from probe import (dd_liveview_url_present, parse_command_ack, parse_device_info_operations,
                   parse_extended_info, probe, read_packet, send_packet, server_version)


class ProbeTest(unittest.TestCase):
    def test_two_connections_and_fragmented_data(self):
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(2)

            def camera():
                with server.accept()[0] as command:
                    packet_type, _ = read_packet(command)
                    self.assertEqual(1, packet_type)
                    ack = struct.pack("<I", 7) + bytes(16) + "A6700".encode("utf-16le") + b"\0\0" + struct.pack("<I", 0x10000)
                    wire_ack = struct.pack("<II", len(ack) + 8, 2) + ack
                    command.sendall(wire_ack[:3])
                    command.sendall(wire_ack[3:])
                    with server.accept()[0] as event:
                        packet_type, body = read_packet(event)
                        self.assertEqual(3, packet_type)
                        self.assertEqual(7, struct.unpack("<I", body)[0])
                        send_packet(event, 4, b"")
                        packet_type, body = read_packet(command)
                        self.assertEqual(6, packet_type)
                        self.assertEqual(0x1001, struct.unpack_from("<H", body, 4)[0])
                        device_info = (struct.pack("<HIH", 100, 6, 1) + b"\x01\0\0" +
                                       struct.pack("<HIHH", 0, 2, 0x9201, 0x923a))
                        send_packet(command, 9, struct.pack("<IQ", 1, len(device_info)))
                        send_packet(command, 10, struct.pack("<I", 1) + device_info[:3])
                        send_packet(command, 12, struct.pack("<I", 1) + device_info[3:])
                        send_packet(command, 7, struct.pack("<HI", 0x2001, 1))

            worker = threading.Thread(target=camera)
            worker.start()
            try:
                result = probe("127.0.0.1", server.getsockname()[1], 2)
            finally:
                worker.join(timeout=2)
            self.assertEqual("0x2001", result["get_device_info_response"])
            self.assertEqual(21, result["device_info_bytes"])
            self.assertEqual(["0x9201", "0x923a"], result["supported_operations"])
            self.assertTrue(result["sony_initialization_operations"]["get_device_description_file"])
            self.assertEqual([2, 4, 9, 10, 12, 7], [packet["type"] for packet in result["packets"]])

    def test_rejects_missing_camera_name_terminator(self):
        bad = struct.pack("<I", 7) + bytes(16) + b"A\0" + struct.pack("<I", 0x10000)
        with self.assertRaises(ValueError):
            parse_command_ack(bad)

    def test_rejects_truncated_or_overstated_operation_list(self):
        with self.assertRaises(ValueError):
            parse_device_info_operations(b"\x00" * 8)
        prefix = struct.pack("<HIH", 100, 6, 1) + b"\x01\0\0" + struct.pack("<HI", 0, 1000)
        with self.assertRaises(ValueError):
            parse_device_info_operations(prefix)

    def test_did_version_uses_only_server_version_element(self):
        did = b'<Device><X_ServerVersion>4.0</X_ServerVersion><SerialNumber>private</SerialNumber></Device>'
        self.assertEqual("4.0", server_version(did))
        with self.assertRaises(ValueError):
            server_version(b'<Device><X_ServerVersion>secret</X_ServerVersion></Device>')

    def test_extended_info_and_dd_liveview_url(self):
        data = struct.pack("<HIHHIH", 1, 2, 0xd278, 0xd279, 1, 0xd2c1)
        self.assertEqual((1, [0xd278, 0xd279], [0xd2c1]), parse_extended_info(data))
        self.assertTrue(dd_liveview_url_present(
            b'<Device><X_ScalarWebAPI_LiveView_URL>http://camera/live</X_ScalarWebAPI_LiveView_URL></Device>'))
        with self.assertRaises(ValueError):
            parse_extended_info(data[:-1])

    def test_negotiates_sony_session_and_closes_it(self):
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(2)
            requests = []

            def reply(sock, transaction_id, data=b"", params=()):
                if data:
                    send_packet(sock, 9, struct.pack("<IQ", transaction_id, len(data)))
                    send_packet(sock, 12, struct.pack("<I", transaction_id) + data)
                send_packet(sock, 7, struct.pack("<HI", 0x2001, transaction_id) +
                            struct.pack(f"<{len(params)}I", *params))

            def camera():
                with server.accept()[0] as command:
                    read_packet(command)
                    ack = struct.pack("<I", 7) + bytes(16) + b"A\0\0\0" + struct.pack("<I", 0x10000)
                    send_packet(command, 2, ack)
                    with server.accept()[0] as event:
                        read_packet(event)
                        send_packet(event, 4, b"")
                        info = (struct.pack("<HIH", 100, 6, 1) + b"\x01\0\0" +
                                struct.pack("<HI", 0, 8) +
                                struct.pack("<8H", 0x1001, 0x1002, 0x1003, 0x9201,
                                            0x9202, 0x9210, 0x9216, 0x923a))
                        payloads = {
                            1: info,
                            2: b"<Device><X_ScalarWebAPI_LiveView_URL>http://camera/live</X_ScalarWebAPI_LiveView_URL></Device>",
                            3: b"<Device><X_ServerVersion>4.0</X_ServerVersion></Device>",
                            8: struct.pack("<HIHIH", 1, 1, 0xd278, 1, 0xd2c1),
                        }
                        for expected_id in range(1, 11):
                            packet_type, body = read_packet(command)
                            self.assertEqual(6, packet_type)
                            phase, code, transaction_id = struct.unpack_from("<IHI", body)
                            self.assertEqual((1, expected_id), (phase, transaction_id))
                            params = struct.unpack_from(f"<{(len(body) - 10) // 4}I", body, 10)
                            requests.append((code, params))
                            reply(command, transaction_id, payloads.get(expected_id, b""),
                                  (310,) if expected_id == 7 else ())

            worker = threading.Thread(target=camera)
            worker.start()
            try:
                result = probe("127.0.0.1", server.getsockname()[1], 2, do_negotiate=True)
            finally:
                worker.join(timeout=2)
            self.assertEqual("sdio_remote_with_transfer", result["negotiation"]["session_mode"])
            self.assertEqual(310, result["negotiation"]["vendor_code_version"])
            self.assertEqual(["0xd2c1"], result["negotiation"]["supported_control_codes"])
            self.assertTrue(result["negotiation"]["liveview_url_in_dd"])
            self.assertEqual([(0x1001, ()), (0x923a, (1,)), (0x923a, (2,)),
                              (0x9210, (1, 2)), (0x9201, (1, 0, 0)),
                              (0x9201, (2, 0, 0)), (0x9216, ()),
                              (0x9202, (300, 1)), (0x9201, (3, 0, 0)),
                              (0x1003, ())], requests)


if __name__ == "__main__":
    unittest.main()
