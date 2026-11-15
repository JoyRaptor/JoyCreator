"""Check a Play app bundle before upload (SHOW-1).

Usage:  python -I tools/play/check_play_bundle.py app/build/outputs/bundle/playRelease/app-play-release.aab

Prints: size per module, every native library's 16 KB alignment (ELF PT_LOAD p_align), and whether the parts
the Play build must not contain are absent from the base manifest. Exit code 1 if anything fails.
"""
import struct
import sys
import zipfile
from collections import defaultdict

MUST_BE_ABSENT = [
    "MANAGE_EXTERNAL_STORAGE",
    "FadRecScreenshotAccessibilityService",
    "ScreenShotCaptureActivity",
    "PrivacyBlackActivity",
    "BIND_ACCESSIBILITY_SERVICE",
    "CalculatorIcon",
    "RedBinaryIcon",
    "NotesIcon",
]


def load_alignment(data):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    aligns = []
    for k in range(phnum):
        base = phoff + k * phentsize
        if struct.unpack_from("<I", data, base)[0] == 1:  # PT_LOAD
            aligns.append(struct.unpack_from("<Q", data, base + 0x30)[0])
    return min(aligns) if aligns else None


def main(path):
    ok = True
    z = zipfile.ZipFile(path)
    sizes = defaultdict(lambda: [0, 0])
    for info in z.infolist():
        mod = info.filename.split("/")[0]
        sizes[mod][0] += info.compress_size
        sizes[mod][1] += info.file_size
    print("Module sizes (compressed / raw MB):")
    for mod, (c, u) in sorted(sizes.items()):
        print(f"  {mod:20s} {c / 1e6:7.1f} / {u / 1e6:7.1f}")

    libs = [i for i in z.infolist() if i.filename.startswith("base/lib/") and i.filename.endswith(".so")]
    bad = []
    for info in libs:
        align = load_alignment(z.read(info))
        if align is None or align < 16384:
            bad.append((info.filename, align))
    print(f"Native libraries: {len(libs)}, 16 KB aligned: {len(libs) - len(bad)}")
    for name, align in bad:
        print(f"  NOT ALIGNED: {name} ({align})")
        ok = False

    manifest = z.read("base/manifest/AndroidManifest.xml")
    for needle in MUST_BE_ABSENT:
        present = needle.encode() in manifest
        print(f"  {'PRESENT (bad)' if present else 'absent'}: {needle}")
        ok = ok and not present
    print("RESULT:", "OK" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
