#!/usr/bin/env python3
"""Seed the TV's photo info (the info card) from the originals of its wallpapers.

The copies on the TV (4K exports) carry no EXIF, so the card would know nothing about when or
where they were taken. This reads each original's EXIF (date taken with its offset, GPS, camera)
and hands it to the launcher as files/photo-info-seed.json; the launcher merges it in at its next
start, only filling gaps, and deletes it. Photos without GPS still get a place: the launcher looks
their file names up ("aoraki-mount-cook").

    tools/photo-info/seed.py [originals dir] [--tv 192.168.1.152:5555] [--dry-run]

Matches originals to the TV's photos by file name. Needs Pillow and adb; the launcher is a debug
build, so run-as can write its files.
"""
import argparse
import json
import os
import subprocess
import sys

from PIL import Image

Image.MAX_IMAGE_PIXELS = None  # our own originals, some over 100 MP


def degrees(values, ref):
    d, m, s = (float(v) for v in values)
    deg = d + m / 60 + s / 3600
    return -deg if ref in ("S", "W") else deg


def info_of(path):
    exif = Image.open(path).getexif()
    sub = exif.get_ifd(0x8769)
    gps = exif.get_ifd(0x8825)
    out = {}
    raw = sub.get(0x9003) or exif.get(0x0132)
    if raw and len(raw) >= 19:
        out["takenAt"] = raw[:10].replace(":", "-") + "T" + raw[11:19]
        offset = sub.get(0x9011)
        if offset and len(offset) == 6 and offset[0] in "+-":
            out["takenAt"] += offset
    if gps.get(2) and gps.get(4):
        lat, lon = degrees(gps[2], gps.get(1, "N")), degrees(gps[4], gps.get(3, "E"))
        if not (lat == 0 and lon == 0):
            out["lat"], out["lon"] = round(lat, 5), round(lon, 5)
    make, model = (exif.get(0x010F) or "").strip(), (exif.get(0x0110) or "").strip()
    # "NIKON CORPORATION" + "NIKON D750" is a NIKON D750; "Canon" + "Canon EOS 6D" a Canon EOS 6D.
    camera = model if (not make or model.lower().startswith(make.split()[0].lower())) else f"{make} {model}"
    if camera.strip():
        out["camera"] = camera.strip()
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("originals", nargs="?", default=os.path.expanduser("~/Developer/louisville/wallpapers/originals"))
    p.add_argument("--tv", default="192.168.1.152:5555")
    p.add_argument("--dry-run", action="store_true")
    a = p.parse_args()

    adb = ["adb", "-s", a.tv]
    on_tv = set(subprocess.run(adb + ["shell", "ls", "/sdcard/Pictures/Wallpapers"], capture_output=True, text=True, check=True).stdout.split())
    seed = {}
    for name in sorted(os.listdir(a.originals)):
        if name not in on_tv:
            continue
        try:
            info = info_of(os.path.join(a.originals, name))
        except Exception as e:  # one unreadable original should not stop the rest
            print(f"  {name}: {e}", file=sys.stderr)
            continue
        if info:
            seed[name] = info
    print(f"{len(seed)} of {len(on_tv)} photos on the TV have details in their originals "
          f"({sum('lat' in v for v in seed.values())} with GPS)")
    if a.dry_run:
        print(json.dumps(seed, indent=2))
        return
    subprocess.run(adb + ["shell", "run-as dev.sal.glass sh -c 'cat > files/photo-info-seed.json'"],
                   input=json.dumps(seed).encode(), check=True)
    print("pushed; the launcher merges it at its next start")


if __name__ == "__main__":
    main()
