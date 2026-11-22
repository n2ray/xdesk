"""Generate the three fixed RFB control advertisements. Media offers use assets/media-profile.json."""
from pathlib import Path
import struct


def control():
    # MIT Rootshell MessageWriter and AppleDisplayConfiguration. One 60 Hz mode.
    mode = struct.pack(">4IdI", 1920, 1080, 1920, 1080, 60, 0)
    display = struct.pack(">H120sIIffIIHHIH", 156 + len(mode), b"XDesk", 1, 0,
                          344, 194, 1920, 1080, 0, 0, 7, 1) + mode
    mask = bytearray(32)
    for event in [0, 2, 3, 20, 21, 30, 31, 32, 34, 35, 73]:
        mask[event // 8] |= 128 >> (event % 8)
    viewer = struct.pack(">BBHH7I", 33, 0, 62, 1, 2, 6, 2, 0, 26, 3, 1) + mask
    request = struct.pack(">BBHHHII", 18, 0, 1, 1, 1, 1, 0x0a000001)
    return {"viewer": viewer + request,
            "display": struct.pack(">BBHHHI", 29, 0, len(display)+8, 1, 1, 0) + display,
            "encodings": struct.pack(">BBH3i", 2, 0, 3, 1103, 1010, 1011)}


if __name__ == "__main__":
    for name, data in control().items():
        (Path(__file__).parent / "assets" / name).write_bytes(data)
