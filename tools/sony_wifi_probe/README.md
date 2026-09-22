# a6700 PTP/IP proof probe

This diagnostic tool checks the first Wi-Fi protocol gate before enabling the app's
remote screen. It uses the connection envelope observed in `Creators.apk` and
returns a sanitized packet summary; it does not print Wi-Fi credentials or raw
camera data.

1. Start the camera's smartphone Wi-Fi connection and join it manually from the
   computer. Leave Creators' App disconnected so it cannot own the session.
2. Find the camera's IP address from the camera display or the joined network.
3. Run `python3 tools/sony_wifi_probe/probe.py --host CAMERA_IP --output /tmp/a6700-ptpip.json`.

The default port `15740` comes from Creators' App 3.5.0 and can be overridden.
The probe opens command and event sockets, then requests standard PTP DeviceInfo.
It reports supported operation codes and a short Sony initialization capability
summary. It does not print the camera's serial number or other DeviceInfo strings.
Without `--negotiate`, it does not perform Sony SDIO negotiation, shutter or
live view. A successful DeviceInfo response confirms only the transport and
first protocol exchange; the later camera-specific stages still need on-device
evidence.

After the operation list shows the Sony initialization commands, run
`python3 tools/sony_wifi_probe/probe.py --host CAMERA_IP --timeout 30 --negotiate`.
This optional mode requests the DD and DID descriptions, selects the remote
session mode from DID server version, runs Sony's three connect stages and
extended-info query, and closes the session. It sends no shutter, live-view or
setting command. Output includes response codes, data sizes, property/control
codes and whether DD contains a live-view URL, never the raw XML, URL or camera
identifier. Stop Creators' App before running it so both clients do not compete
for the same remote session.

For the active shutter and preview test, select still-photo, single-shot drive mode
and manual focus, then run:

```bash
python3 tools/sony_wifi_probe/probe.py --host CAMERA_IP --timeout 15 \
  --liveview-frames 3 --capture-once --half-press-ms 500
```

This deliberately attempts one photo. The flags imply `--negotiate`. Omit
`--capture-once` and `--half-press-ms` for a preview-only test. The optional pause
tests half-press timing; it is not a confirmed camera requirement. Default is zero.
Both shutter buttons are released on failure, and a capture is never retried
automatically. The photo should stay on the camera; the probe does not transfer
captured photos.

Paste `negotiation.diagnostics` and report whether a photo was actually taken.
`command_accepted` means only that the camera returned success. A missing capture
event leaves the outcome unconfirmed. Shooting diagnostics include only selected
numeric properties (focus, drive, restrictions, card/save status) and shutter-control
availability, before capture, after half-press and after release. `0xc203` is a
property-change event, not capture confirmation. The probe does not change camera
settings to clear a restriction.

Preview output includes dimensions, frame offsets, JPEG length and trailing region
bytes. JPEG validation follows markers through the image, tolerating bytes after
the end marker inside the advertised region. It retains failed-frame layout in the
report without printing image data. If Pillow is installed, it also decodes each
JPEG; otherwise `decoded: false` means only the structural checks ran. No preview
images, property strings, URLs or credentials are written to disk or printed.

Preview startup enables postview first when advertised, waits up to one second for
its vendor result, then enables live view. Both are disabled on exit. A preview
HTTP `503` retries the GET at most twice, with 500ms pauses and one overall deadline;
`http_statuses` records each response. Other HTTP errors and frame errors are not
retried. No shutter command or control enable is replayed. Preview-only runs also
report selected numeric shooting properties after enabling the stream.

Regression tests (including a simulated camera on loopback):

```bash
python3 -m unittest discover -s tools/sony_wifi_probe -p 'test_*.py' -v
```
