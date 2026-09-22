import io
import queue
import socket
import struct
import threading
import time
import unittest
from unittest.mock import patch

from diagnostics import (EventMonitor, jpeg_dimensions, jpeg_extent, liveview_url_from_properties,
                         read_liveview_frames, run_diagnostics, shooting_state)
from probe import PacketReader, execute_operation, probe, read_packet, send_packet


class DiagnosticsTest(unittest.TestCase):
    def test_capture_probe_runs_handshake_controls_event_and_close(self):
        errors = queue.Queue()
        controls = []
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(2)
            server.settimeout(3)

            def camera():
                try:
                    with server.accept()[0] as command:
                        command.settimeout(3)
                        read_packet(command)
                        ack = struct.pack("<I", 7) + bytes(16) + b"A\0\0\0" + struct.pack("<I", 0x10000)
                        send_packet(command, 2, ack)
                        with server.accept()[0] as event:
                            read_packet(event)
                            send_packet(event, 4, b"")
                            operations = [0x1001, 0x1003, 0x9201, 0x9202, 0x9207, 0x9209, 0x9210, 0x9216, 0x923a]
                            info = (struct.pack("<HIH", 100, 6, 1) + b"\x01\0\0" +
                                    struct.pack("<HI9H", 0, len(operations), *operations))
                            payloads = {1: info, 2: b"<Device/>",
                                        3: b"<X_ServerVersion>4.00</X_ServerVersion>",
                                        8: struct.pack("<HIIHH", 1, 0, 2, 0xd2c1, 0xd2c2),
                                        10: bytes(8), 12: bytes(8), 16: bytes(8)}
                            for tx in range(1, 18):
                                kind, body = read_packet(command)
                                phase, code, actual_tx = struct.unpack_from("<IHI", body)
                                self.assertEqual((6, tx), (kind, actual_tx))
                                if code == 0x9207:
                                    self.assertEqual(2, phase)
                                    control, flag = struct.unpack_from("<II", body, 10)
                                    self.assertEqual(1, flag)
                                    self.assertEqual((9, struct.pack("<IQ", tx, 2)), read_packet(command))
                                    end_type, end_data = read_packet(command)
                                    self.assertEqual((12, tx), (end_type, struct.unpack_from("<I", end_data)[0]))
                                    value = struct.unpack_from("<H", end_data, 4)[0]
                                    controls.append((control, value))
                                    if (control, value) == (0xd2c2, 2):
                                        send_packet(event, 8, struct.pack("<HI", 0xc206, tx))
                                data = payloads.get(tx)
                                if data:
                                    send_packet(command, 9, struct.pack("<IQ", tx, len(data)))
                                    send_packet(command, 12, struct.pack("<I", tx) + data)
                                params = struct.pack("<I", 310) if tx == 7 else b""
                                send_packet(command, 7, struct.pack("<HI", 0x2001, tx) + params)
                                if tx == 17:
                                    self.assertEqual(0x1003, code)
                except BaseException as failure:
                    errors.put(failure)

            worker = threading.Thread(target=camera, daemon=True)
            worker.start()
            result = probe("127.0.0.1", server.getsockname()[1], 2, capture_once=True)
            worker.join(3)
            if not errors.empty():
                raise errors.get()
            self.assertFalse(worker.is_alive())
        capture = result["negotiation"]["diagnostics"]["capture"]
        self.assertTrue(capture["command_accepted"])
        self.assertTrue(capture["captured_event_observed"])
        self.assertEqual([(0xd2c1, 2), (0xd2c2, 2), (0xd2c2, 1), (0xd2c1, 1)], controls)

    def test_data_out_has_one_request_start_and_end(self):
        client, camera = socket.socketpair()
        with client, camera:
            client.settimeout(2)
            send_packet(camera, 7, struct.pack("<HI", 0x2001, 1))
            response, _, _ = execute_operation(client, [], "press", 0x9207, 1,
                                                (0xd2c2, 1), b"\x02\0")
            self.assertEqual(0x2001, response)
            kind, request = read_packet(camera)
            self.assertEqual((6, 2, 0x9207, 1), (kind, *struct.unpack_from("<IHI", request)))
            self.assertEqual((9, struct.pack("<IQ", 1, 2)), read_packet(camera))
            self.assertEqual((12, struct.pack("<I", 1) + b"\x02\0"), read_packet(camera))

    def test_partial_packet_survives_timeout(self):
        client, camera = socket.socketpair()
        with client, camera:
            client.settimeout(0.02)
            reader = PacketReader(client)
            packet = struct.pack("<IIHI", 14, 7, 0x2001, 1)
            camera.sendall(packet[:10])
            with self.assertRaises(socket.timeout):
                reader.read()
            camera.sendall(packet[10:])
            self.assertEqual((7, packet[8:]), reader.read())

    def test_capture_failure_releases_both_buttons_without_another_press(self):
        calls = []

        def run(stage, code, params=(), data_out=None):
            if code == 0x9207:
                calls.append((stage, params, data_out))
            if stage == "full_press":
                raise TimeoutError()
            return b"", []

        class Events:
            def capture_events(self, *_args, **_kwargs):
                return {"captured_event_observed": False}

        result = run_diagnostics(run, b"<Device/>", [], [0xd2c1, 0xd2c2], 310,
                                 "127.0.0.1", 1, Events(), 0, True)
        self.assertEqual(["half_press", "full_press", "release_full", "release_half"],
                         [call[0] for call in calls])
        self.assertEqual([b"\x02\0", b"\x02\0", b"\x01\0", b"\x01\0"],
                         [call[2] for call in calls])
        self.assertFalse(result["capture"]["command_accepted"])
        self.assertEqual([], result["capture"]["release_errors"])
        self.assertEqual("unconfirmed_do_not_retry_automatically", result["capture"]["outcome"])

    def test_shutter_reads_state_and_optional_half_press_pause_without_retry(self):
        calls = []

        def run(stage, *_args):
            calls.append(stage)
            return bytes(8), []

        class Events:
            def capture_events(self, *_args, **_kwargs):
                return {"captured_event_observed": False}

        with patch("diagnostics.time.sleep") as sleep:
            report = run_diagnostics(run, b"<Device/>", [], [0xd2c1, 0xd2c2], 310,
                                     "127.0.0.1", 1, Events(), 0, True, 500)
        sleep.assert_called_once_with(0.5)
        self.assertEqual(["properties_before_capture", "half_press", "properties_half_pressed",
                          "full_press", "release_full", "release_half", "properties_after_release"], calls)
        self.assertTrue(report["capture"]["command_accepted"])
        self.assertFalse(report["capture"]["captured_event_observed"])

    def test_shooting_state_only_reports_allowlisted_numeric_values(self):
        def string(value):
            return bytes([len(value) + 1]) + value.encode("utf-16le") + b"\0\0"

        properties = (struct.pack("<QHHBBHHB", 4, 0x500a, 4, 0, 1, 0, 1, 0) +
                      struct.pack("<HHBB", 0xd2c2, 4, 1, 0) + bytes(5) +
                      struct.pack("<HHBB", 0xd278, 0xffff, 0, 1) + string("") +
                      string("http://127.0.0.1/private") + b"\0" +
                      struct.pack("<HHBB", 0xffff, 0xffff, 0, 1) + string("") +
                      string("PRIVATE CAMERA STRING") + b"\0")
        state = shooting_state(properties, [0xd2c2])
        self.assertEqual({"focus_mode", "full_press"}, set(state))
        self.assertEqual(1, state["focus_mode"]["value"])
        self.assertEqual(0, state["full_press"]["enabled"])
        self.assertNotIn("private", str(state).lower())

    def test_event_monitor_distinguishes_stale_capture_from_current(self):
        client, camera = socket.socketpair()
        with client, camera:
            with EventMonitor(client, PacketReader) as monitor:
                send_packet(camera, 8, struct.pack("<HI", 0xc206, 0))
                deadline = time.monotonic() + 1
                while not monitor.events and time.monotonic() < deadline:
                    time.sleep(0.005)
                since = time.monotonic()
                self.assertFalse(monitor.capture_events(since, timeout=0.01)["captured_event_observed"])
                send_packet(camera, 8, struct.pack("<HI", 0xc206, 2))
                self.assertTrue(monitor.capture_events(since, timeout=1)["captured_event_observed"])

    def test_property_changes_do_not_confirm_a_capture(self):
        client, camera = socket.socketpair()
        with client, camera:
            with EventMonitor(client, PacketReader) as monitor:
                since = time.monotonic()
                send_packet(camera, 8, struct.pack("<HII", 0xc203, 0, 0xd213))
                result = monitor.capture_events(since, timeout=0.05)
                self.assertEqual(["0xc203"], result["event_codes"])
                self.assertFalse(result["captured_event_observed"])
                self.assertIsNone(result["event_channel_error"])

    def test_preview_failure_preserves_layout_and_disables_liveview(self):
        stages = []

        def run(stage, *_args):
            stages.append(stage)
            return b"", []

        def invalid_frame(_url, _host, _count, _timeout, report):
            report["last_frame_layout"] = {"image_offset": 16, "starts_with_jpeg": False}
            raise ValueError("invalid_jpeg_markers")

        with patch("diagnostics.read_liveview_frames", invalid_frame):
            report = run_diagnostics(run,
                b"<Device><X_ScalarWebAPI_LiveView_URL>http://127.0.0.1/private</X_ScalarWebAPI_LiveView_URL></Device>",
                [], [0xd313], 310, "127.0.0.1", 1, None, 3, False)
        self.assertEqual(["enable_liveview", "properties_liveview_enabled", "disable_liveview"], stages)
        self.assertEqual(16, report["liveview"]["last_frame_layout"]["image_offset"])
        self.assertEqual("invalid_jpeg_markers", report["liveview"]["reason"])
        self.assertNotIn("private", str(report))

    def test_rejects_unbounded_half_press_before_connecting(self):
        with self.assertRaises(ValueError):
            probe("127.0.0.1", 1, 1, capture_once=True, half_press_ms=2001)

    def test_postview_precedes_liveview_and_cleanup_runs_after_failure(self):
        stages = []

        def run(stage, *_args):
            stages.append(stage)
            return bytes(8), []

        class Events:
            def control_result(self, code, since):
                stages.append(f"wait_result_{code:04x}")
                return 0

        with patch("diagnostics.read_liveview_frames", side_effect=ValueError("liveview_http_status_503")):
            report = run_diagnostics(run,
                b"<Device><X_ScalarWebAPI_LiveView_URL>http://127.0.0.1/live</X_ScalarWebAPI_LiveView_URL></Device>",
                [], [0xd312, 0xd313], 310, "127.0.0.1", 1, Events(), 3, False)
        self.assertEqual(["enable_postview", "wait_result_d312", "enable_liveview",
                          "properties_liveview_enabled", "disable_liveview", "disable_postview"], stages)
        self.assertEqual(0, report["liveview"]["postview_operation_result"])
        self.assertNotIn("capture", report)

    def test_postview_result_requires_matching_control_and_operation(self):
        client, camera = socket.socketpair()
        with client, camera:
            with EventMonitor(client, PacketReader) as monitor:
                since = time.monotonic()
                for packed in (0x9207d313, 0x9205d312, 0x9207d312):
                    send_packet(camera, 8, struct.pack("<HIII", 0xc222, 0, packed, 1))
                self.assertEqual(1, monitor.control_result(0xd312, since))
                self.assertIsNone(monitor.control_result(0xd312, time.monotonic(), timeout=0.01))

    def test_reads_url_property_without_exposing_other_strings(self):
        def string(value):
            return bytes([len(value) + 1]) + value.encode("utf-16le") + b"\0\0"

        property_data = (struct.pack("<QHHBB", 1, 0xd278, 0xffff, 0, 1) +
                         string("") + string("http://127.0.0.1/live") + b"\0")
        self.assertEqual("http://127.0.0.1/live", liveview_url_from_properties(property_data, []))
        with self.assertRaises(ValueError):
            liveview_url_from_properties(property_data[:-1], [])

    def test_unused_strings_are_skipped_but_lengths_and_used_url_remain_validated(self):
        data = (struct.pack("<QHHBB", 2, 0xd278, 0xffff, 0, 1) +
                b"\x01\x00\xd8\x01\x00\xd8\x00" +
                struct.pack("<HHBBHHB", 0x500a, 4, 0, 1, 0, 1, 0))
        self.assertEqual(1, shooting_state(data, [])["focus_mode"]["value"])
        with self.assertRaisesRegex(ValueError, "invalid_property_string"):
            liveview_url_from_properties(data, [])
        with self.assertRaisesRegex(ValueError, "truncated_property_dataset"):
            shooting_state(data[:16], [])

    def test_liveview_rejects_external_url_before_connecting(self):
        with self.assertRaisesRegex(ValueError, "connected_camera"):
            read_liveview_frames("http://external.example/live", "127.0.0.1", 1, 1)

    def test_jpeg_dimensions_and_bounded_http_frames(self):
        jpeg = b"\xff\xd8\xff\xc0\x00\x0b\x08\x00\x10\x00\x20\x01\x01\x11\x00\xff\xd9"
        self.assertEqual((32, 16), jpeg_dimensions(jpeg))

        class Response:
            status = 200
            def __init__(self):
                image_region = jpeg + bytes(9)
                packet = struct.pack("<IIII", 16, len(image_region), 0, 0) + image_region
                self.data = io.BytesIO(packet * 3)
            def read1(self, size):
                return self.data.read(min(size, 3))
            def close(self):
                pass

        statuses = iter([503, 200])
        connections = []

        class Connection:
            def __init__(self, *_args, **_kwargs):
                self.sock = self
                self.closed = False
                connections.append(self)
            def connect(self):
                pass
            def settimeout(self, _timeout):
                pass
            def request(self, *_args, **_kwargs):
                pass
            def getresponse(self):
                response = Response()
                response.status = next(statuses)
                return response
            def close(self):
                self.closed = True

        with patch("diagnostics.http.client.HTTPConnection", Connection), patch.dict("sys.modules", {"PIL": None}), patch("diagnostics.time.sleep"):
            result = read_liveview_frames("http://127.0.0.1/live", "127.0.0.1", 3, 1)
        self.assertEqual([503, 200], result["http_statuses"])
        self.assertTrue(all(connection.closed for connection in connections))
        self.assertEqual(3, result["frame_count"])
        self.assertEqual([9, 9, 9], [frame["trailing_bytes"] for frame in result["frames"]])
        self.assertFalse(result["frames"][0]["decoded"])
        self.assertEqual(32, result["frames"][0]["width"])

    def test_http_retry_is_bounded_and_only_applies_to_503(self):
        from unittest.mock import MagicMock
        for status, attempts in ((503, 3), (403, 1), (302, 1)):
            with self.subTest(status=status):
                connection = MagicMock()
                connection.getresponse.return_value.status = status
                report = {}
                with patch("diagnostics.http.client.HTTPConnection", return_value=connection) as factory, patch("diagnostics.time.sleep"):
                    with self.assertRaisesRegex(ValueError, f"liveview_http_status_{status}"):
                        read_liveview_frames("http://127.0.0.1/live", "127.0.0.1", 1, 5, report)
                self.assertEqual(attempts, factory.call_count)
                self.assertEqual([status] * attempts, report["http_statuses"])
                self.assertEqual(attempts, connection.close.call_count)
                self.assertEqual(attempts, connection.getresponse.return_value.close.call_count)

    def test_jpeg_end_ignores_embedded_marker_and_stuffed_entropy(self):
        sof = b"\xff\xc0\x00\x0b\x08\x00\x10\x00\x20\x01\x01\x11\x00"
        app = b"\xff\xe1\x00\x06\xff\xd9\xff\xd8"
        scan = b"\xff\xda\x00\x08\x01\x01\x00\x00\x3f\x00"
        jpeg = b"\xff\xd8" + app + sof + scan + b"\x01\xff\x00\xd9\xff\xd0\x02\xff\xd9"
        self.assertEqual((32, 16, len(jpeg)), jpeg_extent(jpeg + bytes(16)))
        with self.assertRaisesRegex(ValueError, "missing_jpeg_end"):
            jpeg_extent(jpeg[:-2])
        with self.assertRaisesRegex(ValueError, "invalid_jpeg_length"):
            jpeg_extent(b"\xff\xd8\xff\xe1\xff\xff\xff\xd9")


if __name__ == "__main__":
    unittest.main()
