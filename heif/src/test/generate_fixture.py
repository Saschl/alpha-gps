"""Generate synthetic HEVC 10-bit 4:2:2 HEIF fixtures; requires ffmpeg with libx265."""
import struct
import argparse
import subprocess
import tempfile
from pathlib import Path


def box(kind, data):
    return struct.pack('>I', len(data) + 8) + kind.encode() + data


def full(kind, data, version=0):
    return box(kind, bytes([version, 0, 0, 0]) + data)


def boxes(data):
    pos = 0
    while pos + 8 <= len(data):
        size, kind = struct.unpack_from('>I4s', data, pos)
        assert size >= 8
        yield kind, data[pos + 8:pos + size]
        pos += size


def child(data, kind):
    return next(payload for name, payload in boxes(data) if name == kind)


parser = argparse.ArgumentParser()
parser.add_argument('--preview', action='store_true', help='Generate a 2400x1200 screen-preview fixture')
args = parser.parse_args()
width, height = (2400, 1200) if args.preview else (128, 64)

with tempfile.TemporaryDirectory() as tmp:
    mp4 = Path(tmp) / 'red.mp4'
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                    f'color=c=red:s={width}x{height}:d=1', '-frames:v', '1', '-pix_fmt', 'yuv422p10le',
                    '-c:v', 'libx265', '-x265-params', 'lossless=1:log-level=error:repeat-headers=0',
                    '-tag:v', 'hvc1', str(mp4)], check=True)
    data = mp4.read_bytes()
    sample = child(data, b'mdat')
    node = data
    for kind in (b'moov', b'trak', b'mdia', b'minf', b'stbl', b'stsd'):
        node = child(node, kind)
    hvc1 = child(node[8:], b'hvc1')
    hvcc = child(hvc1[78:], b'hvcC')
    assert hvcc[16] & 3 == 2  # chroma_format_idc = 4:2:2
    assert hvcc[17] & 7 == 2  # bit_depth_luma_minus8 = 2
    assert hvcc[18] & 7 == 2

make = b'Alpha GPS test\0'
tiff = b'II' + struct.pack('<HIH', 42, 8, 1) + struct.pack('<HHII', 271, 2, len(make), 26) + bytes(4) + make
exif = bytes(4) + tiff
ftyp = box('ftyp', b'heic' + bytes(4) + b'mif1heic')
for rotation in ((False,) if args.preview else (False, True)):
    properties = [box('hvcC', hvcc), full('ispe', struct.pack('>II', width, height)),
                  full('pixi', bytes([3, 10, 10, 10])),
                  box('colr', b'nclx' + struct.pack('>HHHB', 1, 13, 6, 0))]
    if rotation:
        properties.append(box('irot', bytes([1])))
    iprp = box('iprp', box('ipco', b''.join(properties)) +
               full('ipma', struct.pack('>IHB', 1, 1, len(properties)) +
                    bytes(0x80 | i for i in range(1, len(properties) + 1))))

    def metadata(offset):
        return full('meta', full('hdlr', bytes(4) + b'pict' + bytes(12) + b'\0') +
                    full('pitm', struct.pack('>H', 1)) +
                    full('iloc', bytes([0x44, 0]) + struct.pack('>H', 2) +
                         struct.pack('>HHHII', 1, 0, 1, offset, len(sample)) +
                         struct.pack('>HHHII', 2, 0, 1, offset + len(sample), len(exif))) +
                    full('iinf', struct.pack('>H', 2) + full('infe', struct.pack('>HH', 1, 0) + b'hvc1red\0', 2) +
                         full('infe', struct.pack('>HH', 2, 0) + b'Exifmetadata\0', 2)) +
                    full('iref', box('cdsc', struct.pack('>HHH', 2, 1, 1))) + iprp)

    image = ftyp + metadata(len(ftyp) + len(metadata(0)) + 8) + box('mdat', sample + exif)
    name = 'red-422-preview.hif' if args.preview else ('red-422-10bit-rotated.hif' if rotation else 'red-422-10bit.hif')
    output = Path(__file__).parent.parent / 'androidTest' / 'assets' / name
    output.write_bytes(image)
