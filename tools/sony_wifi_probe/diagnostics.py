"""Optional active diagnostics. Camera values and image bytes never enter the report."""

import collections
import http.client
import io
import socket
import struct
import threading
import time
from urllib.parse import urlsplit
from xml.etree import ElementTree

MAX_FRAME = 4 * 1024 * 1024
CAPTURE_EVENTS = {0x400d, 0xc206}
SHOOTING_PROPERTIES = {
    0x500a: "focus_mode", 0x500e: "exposure_program", 0x5013: "drive_mode",
    0xd213: "focus_indication", 0xd21a: "disable_indication",
    0xd21d: "movie_recording_state", 0xd221: "liveview_status",
    0xd222: "still_image_save_destination", 0xd248: "slot1_status",
    0xd256: "slot2_status", 0xd264: "remote_control_restriction",
}


class EventMonitor:
    def __init__(self, sock, reader_factory):
        self.sock = sock
        self.reader = reader_factory(sock)
        self.events = collections.deque(maxlen=128)
        self.operation_results = collections.deque(maxlen=32)
        self.condition = threading.Condition()
        self.stop = threading.Event()
        self.error = None

    def __enter__(self):
        self.previous_timeout = self.sock.gettimeout()
        self.sock.settimeout(0.25)
        self.worker = threading.Thread(target=self._read, daemon=True)
        self.worker.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.worker.join(timeout=1)
        self.sock.settimeout(self.previous_timeout)

    def _read(self):
        while not self.stop.is_set():
            try:
                packet_type, body = self.reader.read()
                if packet_type == 13 and not body:
                    self.sock.sendall(struct.pack("<II", 8, 14))
                    continue
                if packet_type != 8 or len(body) < 6 or len(body) > 26 or (len(body) - 6) % 4:
                    raise ValueError("invalid event packet")
                code = struct.unpack_from("<H", body)[0]
                with self.condition:
                    stamp = time.monotonic()
                    self.events.append((stamp, code))
                    if code == 0xc222 and len(body) >= 14:
                        operation_control, result = struct.unpack_from("<II", body, 6)
                        if operation_control >> 16 == 0x9207:
                            self.operation_results.append((stamp, operation_control & 0xffff, result))
                    self.condition.notify_all()
            except socket.timeout:
                continue
            except (OSError, EOFError, ValueError):
                with self.condition:
                    self.error = "event_channel_closed_or_invalid"
                    self.condition.notify_all()
                return

    def control_result(self, code, since, timeout=1):
        deadline = time.monotonic() + timeout
        with self.condition:
            while True:
                for stamp, control, result in self.operation_results:
                    if stamp >= since and control == code:
                        return result
                remaining = deadline - time.monotonic()
                if self.error or remaining <= 0:
                    return None
                self.condition.wait(remaining)

    def capture_events(self, since, timeout=5):
        deadline = time.monotonic() + timeout
        with self.condition:
            while True:
                codes = [code for stamp, code in self.events if stamp >= since]
                if any(code in CAPTURE_EVENTS for code in codes) or self.error:
                    break
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.condition.wait(remaining)
            return {"captured_event_observed": any(code in CAPTURE_EVENTS for code in codes),
                    "event_codes": [f"0x{code:04x}" for code in codes],
                    "event_channel_error": self.error}


def liveview_url_from_dd(data):
    try:
        root = ElementTree.fromstring(data)
    except ElementTree.ParseError:
        raise ValueError("malformed_dd_xml") from None
    urls = [item.text.strip() for item in root.iter()
            if item.tag.rsplit("}", 1)[-1] == "X_ScalarWebAPI_LiveView_URL" and item.text]
    if len(urls) > 1:
        raise ValueError("ambiguous_liveview_url")
    return urls[0] if urls else None


def read_device_properties(data, control_codes, include_liveview_url=False):
    offset = 0

    def take(size):
        nonlocal offset
        if size < 0 or size > len(data) - offset:
            raise ValueError("truncated_property_dataset")
        chunk = data[offset:offset + size]
        offset += size
        return chunk

    def number(size):
        return int.from_bytes(take(size), "little")

    def value(kind, decode_string=False):
        if kind == 0xffff:
            chars = number(1)
            text = take(chars * 2)
            if not decode_string:
                return None  # Unused strings are opaque counted fields, as in the APK.
            if chars and text[-2:] != b"\0\0":
                raise ValueError("invalid_property_string")
            try:
                return text[:-2].decode("utf-16le") if chars else ""
            except UnicodeError:
                raise ValueError("invalid_property_string") from None
        sizes = {1: 1, 2: 1, 3: 2, 4: 2, 5: 4, 6: 4, 7: 8, 8: 8, 9: 16, 10: 16}
        if kind not in sizes:
            raise ValueError(f"unsupported_property_type_0x{kind:04x}")
        return number(sizes[kind])

    count = number(8)
    if count > 4096:
        raise ValueError("too_many_properties")
    retained = {}
    for _ in range(count):
        code, kind = number(2), number(2)
        access = number(1)
        enabled = number(1)
        if code in control_codes:
            take(4)
            if number(1) != 0:
                raise ValueError("unsupported_control_form")
            if code in (0xd2c1, 0xd2c2):
                retained[code] = {"type": kind, "access": access, "enabled": enabled}
            continue
        value(kind)  # factory value
        current = value(kind, decode_string=include_liveview_url and code == 0xd278)
        form = number(1)
        if form == 1:
            for _ in range(3):
                value(kind)
        elif form == 2:
            for _ in range(2):
                values = number(2)
                if values > 4096:
                    raise ValueError("too_many_property_values")
                for _ in range(values):
                    value(kind)
        elif form != 0:
            raise ValueError("unsupported_property_form")
        if (include_liveview_url and code == 0xd278 and kind == 0xffff) or (code in SHOOTING_PROPERTIES and kind != 0xffff):
            retained[code] = {"type": kind, "access": access, "enabled": enabled, "value": current}
    if offset != len(data):
        raise ValueError("trailing_property_bytes")
    return retained


def liveview_url_from_properties(data, control_codes):
    prop = read_device_properties(data, control_codes, include_liveview_url=True).get(0xd278, {})
    return prop.get("value") if prop.get("enabled") in (1, 2) else None


def shooting_state(data, control_codes):
    names = {**SHOOTING_PROPERTIES, 0xd2c1: "half_press", 0xd2c2: "full_press"}
    return {names[code]: {"code": f"0x{code:04x}", **prop}
            for code, prop in read_device_properties(data, control_codes).items() if code in names}


def jpeg_dimensions(data):
    width, height, _ = jpeg_extent(data)
    return width, height


def jpeg_extent(data):
    """Find EOI structurally; Sony's image region may include bytes after the JPEG."""
    if not data.startswith(b"\xff\xd8"):
        raise ValueError("invalid_jpeg_markers")
    offset = 2
    dimensions = None
    in_scan = False
    while offset < len(data):
        if in_scan:
            marker_start = data.find(b"\xff", offset)
            if marker_start < 0:
                break
            offset = marker_start
        if data[offset] != 0xff:
            raise ValueError("invalid_jpeg_segment")
        while offset < len(data) and data[offset] == 0xff:
            offset += 1
        if offset >= len(data):
            break
        marker = data[offset]
        offset += 1
        if in_scan and (marker == 0 or 0xd0 <= marker <= 0xd7):
            continue
        if marker == 0xd9:
            if dimensions is None:
                raise ValueError("missing_jpeg_dimensions")
            return (*dimensions, offset)
        if marker in (0, 0xd8) or 0xd0 <= marker <= 0xd7:
            raise ValueError("invalid_jpeg_segment")
        if marker == 0x01:
            continue
        if offset + 2 > len(data):
            break
        length = int.from_bytes(data[offset:offset + 2], "big")
        if length < 2 or offset + length > len(data):
            raise ValueError("invalid_jpeg_length")
        if marker in {0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf}:
            if length < 8:
                raise ValueError("invalid_jpeg_dimensions")
            height, width = struct.unpack_from(">HH", data, offset + 3)
            if not (0 < width <= 4096 and 0 < height <= 4096):
                raise ValueError("invalid_jpeg_dimensions")
            dimensions = (width, height)
        in_scan = marker == 0xda
        offset += length
    raise ValueError("missing_jpeg_end")


def read_liveview_frames(url, peer_host, count, timeout, report=None):
    parsed = urlsplit(url)
    if (parsed.scheme != "http" or parsed.hostname != peer_host or parsed.username or
            parsed.password or parsed.fragment):
        raise ValueError("liveview_url_must_target_connected_camera")
    deadline = time.monotonic() + min(timeout, 20)
    connection = None
    report = report if report is not None else {}
    frames = report.setdefault("frames", [])
    report["frame_count"] = 0
    response = None
    try:
        statuses = report.setdefault("http_statuses", [])
        for attempt in range(3):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("liveview_deadline")
            connection = http.client.HTTPConnection(peer_host, parsed.port or 80,
                                                    timeout=min(remaining, 10))
            connection.connect()
            stream_socket = connection.sock
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("liveview_deadline")
            stream_socket.settimeout(remaining)
            connection.request("GET", (parsed.path or "/") + ("?" + parsed.query if parsed.query else ""),
                               headers={"Connection": "close"})
            response = connection.getresponse()
            statuses.append(response.status)
            if response.status == 200:
                break
            if response.status != 503 or attempt == 2:
                raise ValueError(f"liveview_http_status_{response.status}")
            response.close()
            response = None
            connection.close()
            connection = None
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("liveview_deadline")
            time.sleep(min(0.5, remaining))

        def exact(size):
            parts = bytearray()
            while len(parts) < size:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("liveview_deadline")
                stream_socket.settimeout(remaining)
                chunk = response.read1(size - len(parts))
                if not chunk:
                    raise EOFError("liveview_closed")
                parts.extend(chunk)
            return bytes(parts)

        for _ in range(count):
            header = exact(16)
            image_offset, image_size, focal_offset, focal_size = struct.unpack("<IIII", header)
            layout = report["last_frame_layout"] = {
                "image_offset": image_offset, "image_region_bytes": image_size,
                "focal_offset": focal_offset, "focal_bytes": focal_size,
            }
            if not (16 <= image_offset <= MAX_FRAME and 0 < image_size <= MAX_FRAME - image_offset):
                raise ValueError("invalid_liveview_image_extent")
            if focal_size and not (16 <= focal_offset <= MAX_FRAME and focal_size <= MAX_FRAME - focal_offset):
                raise ValueError("invalid_liveview_metadata_extent")
            total = max(image_offset + image_size, focal_offset + focal_size if focal_size else 16)
            frame = header + exact(total - 16)
            jpeg = frame[image_offset:image_offset + image_size]
            layout["starts_with_jpeg"] = jpeg.startswith(b"\xff\xd8")
            layout["ends_with_jpeg"] = jpeg.endswith(b"\xff\xd9")
            width, height, jpeg_size = jpeg_extent(jpeg)
            decoded = False
            try:
                from PIL import Image
            except ImportError:
                pass
            else:
                with Image.open(io.BytesIO(jpeg[:jpeg_size])) as image:
                    image.load()
                    decoded = True
            frames.append({"image_bytes": image_size, "width": width, "height": height,
                           "jpeg_bytes": jpeg_size, "trailing_bytes": image_size - jpeg_size,
                           "focal_bytes": focal_size, "decoded": decoded})
            report["frame_count"] = len(frames)
        return report
    finally:
        if response is not None:
            response.close()
        if connection is not None:
            connection.close()


def run_diagnostics(run, dd, property_codes, control_codes, vendor_version, peer_host,
                    timeout, events, liveview_frames, capture_once, half_press_ms=0):
    result = {}

    def control(stage, code, down):
        parameters = (code, 1) if (vendor_version or 0) >= 310 else (code,)
        return run(stage, 0x9207, parameters, struct.pack("<H", 2 if down else 1))

    def snapshot(stage):
        try:
            params = (0, 1) if (vendor_version or 0) >= 310 else (0,)
            data, _ = run("properties_" + stage, 0x9209, params)
            return shooting_state(data, control_codes)
        except (OSError, EOFError, ValueError) as failure:
            error = {"error": type(failure).__name__}
            if isinstance(failure, ValueError) and str(failure).replace("_", "").isalnum():
                error["reason"] = str(failure)
            return error

    if liveview_frames:
        preview = result["liveview"] = {}
        enable_attempted = False
        postview_attempted = False
        try:
            url = liveview_url_from_dd(dd)
            source = "dd_xml"
            if not url and 0xd278 in property_codes:
                params = (0, 1) if (vendor_version or 0) >= 310 else (0,)
                data, _ = run("get_liveview_properties", 0x9209, params)
                url = liveview_url_from_properties(data, control_codes)
                source = "device_property"
            if not url:
                raise ValueError("no_advertised_liveview_url")
            if 0xd312 in control_codes:
                postview_attempted = True
                since = time.monotonic()
                try:
                    control("enable_postview", 0xd312, True)
                    preview["postview_operation_result"] = events.control_result(0xd312, since)
                except (OSError, EOFError, ValueError):
                    preview["postview_enable_failed"] = True
            if 0xd313 in control_codes:
                enable_attempted = True
                control("enable_liveview", 0xd313, True)
            preview["shooting_state"] = snapshot("liveview_enabled")
            preview["url_source"] = source
            read_liveview_frames(url, peer_host, liveview_frames, timeout, preview)
        except (OSError, EOFError, ValueError, http.client.HTTPException) as failure:
            preview["error"] = type(failure).__name__
            if isinstance(failure, ValueError) and str(failure).replace("_", "").isalnum():
                preview["reason"] = str(failure)
        finally:
            if enable_attempted:
                try:
                    control("disable_liveview", 0xd313, False)
                except (OSError, EOFError, ValueError):
                    result.setdefault("liveview", {})["disable_failed"] = True
            if postview_attempted:
                try:
                    control("disable_postview", 0xd312, False)
                except (OSError, EOFError, ValueError):
                    preview["postview_disable_failed"] = True

    if capture_once:
        if not {0xd2c1, 0xd2c2}.issubset(control_codes):
            result["capture"] = {"error": "shutter_controls_not_advertised"}
        else:
            attempted = []
            capture = {"command_accepted": False, "release_errors": [], "half_press_ms": half_press_ms}
            snapshots = capture["shooting_state"] = {}

            snapshots["before_capture"] = snapshot("before_capture")
            since = time.monotonic()
            try:
                attempted.append(0xd2c1)
                control("half_press", 0xd2c1, True)
                if half_press_ms:
                    time.sleep(half_press_ms / 1000)
                snapshots["half_pressed"] = snapshot("half_pressed")
                since = time.monotonic()
                attempted.append(0xd2c2)
                control("full_press", 0xd2c2, True)
                capture["command_accepted"] = True
            except (OSError, EOFError, ValueError):
                capture["outcome"] = "uncertain_or_rejected_do_not_retry_automatically"
            finally:
                for code in reversed(attempted):
                    try:
                        control("release_full" if code == 0xd2c2 else "release_half", code, False)
                    except (OSError, EOFError, ValueError):
                        capture["release_errors"].append(f"0x{code:04x}")
            capture.update(events.capture_events(since, timeout=min(timeout, 10)))
            snapshots["after_release"] = snapshot("after_release")
            capture["outcome"] = ("capture_event_observed" if capture["captured_event_observed"]
                                  else "unconfirmed_do_not_retry_automatically")
            result["capture"] = capture
    return result
