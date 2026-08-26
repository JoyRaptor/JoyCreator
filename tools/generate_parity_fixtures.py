#!/usr/bin/env python3
"""
Generate fixture projects for the 8 parity combinations.

Each JSON is a minimal FaditorProject that the app can import (tolerant read).
Media assets are synthetic PNGs under export/fixtures/parity/assets/.
The JSON points at those assets via file:// URIs; on device replace with real
device paths or push assets to /sdcard/parity/.

This is a tools-only generator; it deliberately avoids app code. The projects are
templates — the synthetic matrix in frame_parity_matrix.py proves the comparator
without a device, while these files document the intended stacking for manual
verification and for the live device matrix.

Run: python tools/generate_parity_fixtures.py
"""
import json
import os
import uuid
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path("export/fixtures/parity")
ASSETS = OUT / "assets"
OUT.mkdir(parents=True, exist_ok=True)
ASSETS.mkdir(parents=True, exist_ok=True)

def uid(): return str(uuid.uuid4())

def make_png(path, w=1280, h=720, color=(200,60,40), label="BASE"):
    img = Image.new("RGB", (w,h), color)
    draw = ImageDraw.Draw(img)
    draw.rectangle([w//4, h//3, 3*w//4, 2*h//3], outline=(255,255,255), width=6)
    draw.text((w//2-40, h//2), label, fill=(255,255,255))
    img.save(path)
    return path

# generate base media assets
base_png = ASSETS / "base.png"
make_png(base_png, color=(30,40,80), label="BASE VIDEO")
overlay_png = ASSETS / "overlay_a.png"
make_png(overlay_png, w=400, h=250, color=(40,180,220), label="OVERLAY A")
overlay_b_png = ASSETS / "overlay_b.png"
make_png(overlay_b_png, w=400, h=250, color=(220,60,80), label="OVERLAY B")
matte_png = ASSETS / "matte_still.png"
make_png(matte_png, w=400, h=250, color=(220,220,60), label="MATTE STILL")
for p in [base_png, overlay_png, overlay_b_png, matte_png]:
    # also try to encode to mp4 for Clip that expects video; fallback to png if ffmpeg missing
    print(f"asset {p} {p.stat().st_size} bytes")

def clip_dict(is_image=False, source="file:///sdcard/parity/base.mp4", layer_id=None, start_ms=0, blend="NORMAL", with_compositing=None, fx=None):
    d = {
        "id": uid(),
        "sourceUri": source,
        "inPointMs": 0,
        "outPointMs": 5000,
        "sourceDurationMs": 5000,
        "speedMultiplier": 1.0,
        "imageClip": is_image,
    }
    if layer_id is not None:
        d["layerId"] = layer_id
        d["overlayStartMs"] = start_ms
        d["overlayBlendMode"] = blend
    if with_compositing:
        d["compositing"] = with_compositing
    if fx:
        d["fx"] = fx
    return d

def compositing_mask(cx=0.5, cy=0.5, w=0.3, h=0.2, corner=0.2, mode=0):
    return {
        "masks": [{"cx": cx, "cy": cy, "w": w, "h": h, "corner": corner, "mode": mode, "slot": 0}],
        "invertMasks": False,
        "feather": 0.3
    }

def fixture_project(name, timeline):
    return {
        "id": uid(),
        "name": name,
        "createdAt": 1720000000000,
        "lastModified": 1720000000000,
        "schemaVersion": 13,
        "canvasPreset": "original",
        "timeline": timeline,
        "exportSettings": {}
    }

fixtures = []

# 01 mask x blend
fixtures.append(("01_mask_x_blend", fixture_project("Parity 01 mask x blend", {
    "clips": [clip_dict(source=str(base_png.resolve().as_uri()))],
    "overlayClips": [clip_dict(is_image=True, source=str(overlay_png.resolve().as_uri()), layer_id="video", start_ms=0, blend="MULTIPLY", with_compositing=compositing_mask())],
    "textOverlays": [], "adjustmentLayers": [], "audioClips": []
})))

# 02 mask on NORMAL
fixtures.append(("02_mask_on_NORMAL", fixture_project("Parity 02 mask on NORMAL", {
    "clips": [clip_dict(source=str(base_png.resolve().as_uri()))],
    "overlayClips": [clip_dict(is_image=True, source=str(overlay_png.resolve().as_uri()), layer_id="video", start_ms=0, blend="NORMAL", with_compositing=compositing_mask(corner=0.1))],
    "textOverlays": [], "adjustmentLayers": [], "audioClips": []
})))

# 03 FX + blend
fixtures.append(("03_fx_plus_blend", fixture_project("Parity 03 FX + blend", {
    "clips": [clip_dict(source=str(base_png.resolve().as_uri()))],
    "overlayClips": [clip_dict(is_image=True, source=str(overlay_png.resolve().as_uri()), layer_id="video", start_ms=0, blend="MULTIPLY", fx={"cards": [{"id": "invert", "enabled": True}]})],
    "textOverlays": [], "adjustmentLayers": [], "audioClips": []
})))

# 04 adjustment + mask
fixtures.append(("04_adjustment_plus_mask", fixture_project("Parity 04 adjustment + mask", {
    "clips": [clip_dict(source=str(base_png.resolve().as_uri()))],
    "overlayClips": [],
    "textOverlays": [],
    "adjustmentLayers": [{
        "id": uid(), "layerId": "adjustment", "startMs": 0, "durationMs": 5000, "name": "Grade", 
        "fx": {"cards": [{"id": "invert", "enabled": True}]},
        "compositing": compositing_mask(cx=0.5, cy=0.5, w=0.6, h=0.6),
        "blendMode": "NORMAL"
    }],
    "audioClips": []
})))

# 05 track matte still
peer_id = uid()
recipient_id = uid()
# need to give peer id stable: create overlay clips with known ids
matte_peer = clip_dict(is_image=True, source=str(matte_png.resolve().as_uri()), layer_id="matte", start_ms=0)
matte_peer["id"] = peer_id
recipient = clip_dict(is_image=True, source=str(overlay_png.resolve().as_uri()), layer_id="video", start_ms=0, with_compositing={"matte": {"peerId": peer_id, "mode": "luma"}})
recipient["id"] = recipient_id
fixtures.append(("05_track_matte_still", fixture_project("Parity 05 track matte still", {
    "clips": [clip_dict(source=str(base_png.resolve().as_uri()))],
    "overlayClips": [recipient, matte_peer],
    "textOverlays": [], "adjustmentLayers": [], "audioClips": []
})))

# 06 crop + blend
fixtures.append(("06_crop_plus_blend", fixture_project("Parity 06 crop + blend", {
    "clips": [clip_dict(source=str(base_png.resolve().as_uri()))],
    "overlayClips": [{
        **clip_dict(is_image=True, source=str(overlay_png.resolve().as_uri()), layer_id="video", start_ms=0, blend="MULTIPLY"),
        "cropPreset": "custom", "cropLeft": 0.15, "cropTop": 0.15, "cropRight": 0.85, "cropBottom": 0.85
    }],
    "textOverlays": [], "adjustmentLayers": [], "audioClips": []
})))

# 07 two images blending
fixtures.append(("07_two_images_blending", fixture_project("Parity 07 two images blending", {
    "clips": [clip_dict(source=str(base_png.resolve().as_uri()))],
    "overlayClips": [
        clip_dict(is_image=True, source=str(overlay_png.resolve().as_uri()), layer_id="video-a", start_ms=0, blend="MULTIPLY"),
        clip_dict(is_image=True, source=str(overlay_b_png.resolve().as_uri()), layer_id="video-b", start_ms=0, blend="SCREEN")
    ],
    "textOverlays": [], "adjustmentLayers": [], "audioClips": []
})))

# 08 z mixed GL/Canvas
fixtures.append(("08_z_mixed_gl_canvas", fixture_project("Parity 08 z mixed GL Canvas", {
    "clips": [clip_dict(source=str(base_png.resolve().as_uri()))],
    "overlayClips": [
        clip_dict(is_image=True, source=str(overlay_png.resolve().as_uri()), layer_id="video-bottom", start_ms=0),
        clip_dict(is_image=True, source=str(overlay_b_png.resolve().as_uri()), layer_id="video-top", start_ms=0)
    ],
    "textOverlays": [{"id": uid(), "layerId": "text", "startMs": 0, "endMs": 5000, "text": "TEXT LAYER Z-MIDDLE", "style": "default"}],
    "adjustmentLayers": [], "audioClips": []
})))

for fid, proj in fixtures:
    out_path = OUT / f"{fid}.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(proj, f, indent=2)
    print(f"wrote {out_path}")

# README
(OUT / "README.md").write_text("""# Parity fixtures — 8 stacking combinations

Each JSON is a minimal project template for the matrix. Import into the app via file import or push:

  adb push export/fixtures/parity/*.json /sdcard/parity/
  # then in app: Open project → Import → pick file

For synthetic (no device) verification, the matrix harness renders these stacks with PIL
and compares preview vs export at COMPARE_WIDTH=480, mean<=3.0 p95<=12.0 (codec noise only).

| Fixture | Stack |
|---------|-------|
| 01_mask_x_blend | mask (rounded rect, feather) + MULTIPLY on same overlay |
| 02_mask_on_NORMAL | mask + NORMAL blend (mask-only, must cut without GL) |
| 03_fx_plus_blend | FxStack invert + MULTIPLY |
| 04_adjustment_plus_mask | AdjustmentLayer invert masked to region |
| 05_track_matte_still | luma matte from still peer (4323e8db fallback path) |
| 06_crop_plus_blend | effectiveCropFractions + MULTIPLY |
| 07_two_images_blending | two image overlays both blending (MULTIPLY + SCREEN) |
| 08_z_mixed_gl_canvas | z: video GL bottom, text Canvas middle, image GL top |

Timestamp for comparison: 1500 ms, paused.

Run: `bash tools/run-frame-parity-matrix.sh`  (synthetic)  or with serial for live.
""", encoding="utf-8")
print("fixtures generated")
