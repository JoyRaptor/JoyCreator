#!/usr/bin/env python3
"""Extract the approved Canvas dabs without replacing Canvas resampling with Pillow.

Generate with --poc, --sheet, --backdrop, --node and --playwright paths.
Verify the committed asset pack without browser dependencies with --check.
Requires Pillow. The generator opens one headless Chromium instance and closes it
in finally; neither reference HTML nor its input images are modified.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys

from PIL import Image

PACK = Path(__file__).resolve().parents[1] / "assets/watercolour/poc25"
ROWS = [0, 246, 492, 734, 978, 1254]

CANVAS_EXPORT = r"""
const {chromium} = require(process.argv[2]);
(async () => {
  let browser;
  try {
    browser = await chromium.launch({headless:true, channel:'msedge'});
    const page = await browser.newPage({viewport:{width:1400,height:900}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(process.argv[1]);
    await page.waitForFunction(() => window.watercolorPOC?.ready);
    const result = await page.evaluate(() => ({
      dabs: watercolorPOC.dabCanvases.map(c => c.toDataURL('image/png').split(',')[1]),
      userAgent: navigator.userAgent
    }));
    if (errors.length) throw Error(errors.join('; '));
    result.browserVersion = browser.version();
    process.stdout.write(JSON.stringify(result));
  } finally {
    if (browser) await browser.close();
  }
})().catch(error => {console.error(error); process.exitCode=1;});
"""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def image_record(path):
    with Image.open(path) as image:
        return {"file": path.name, "pngSha256": digest(path.read_bytes()),
                "pixelsSha256": digest(image.tobytes())}


def check(output):
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["count"] == 25, "Expected all 25 approved samples"
    assert manifest["dabs"] == [f"dab_{i:02d}.png" for i in range(25)], "Dab order changed"
    assert manifest["width"] == manifest["height"] == 251
    assert manifest["channel"] == "red", "R8 values are grayscale coverage, not PNG alpha"
    assert manifest["transparentGuardPixels"] == 5
    hashes = {entry["file"]: entry for entry in manifest["checksums"]}
    for name in manifest["dabs"]:
        path = output / name
        with Image.open(path) as image:
            assert image.mode == "L" and image.size == (251, 251), name
            assert image.getbbox(), f"Empty sample {name}"
            for box in [(0, 0, 251, 5), (0, 246, 251, 251),
                        (0, 0, 5, 251), (246, 0, 251, 251)]:
                assert image.crop(box).getextrema() == (0, 0), f"Nonzero guard {name}: {box}"
        assert image_record(path) == hashes[name], f"Hash mismatch: {name}"
    backdrop = output / manifest["backdrop"]["file"]
    with Image.open(backdrop) as image:
        assert image.mode == "L" and image.size == (1254, 1254)
    assert image_record(backdrop) == hashes[backdrop.name], "Backdrop hash mismatch"
    print("PASS: 25 ordered 251x251 R8 dabs, all 5px guards zero, 1254x1254 backdrop, hashes")


def generate(args):
    poc, sheet_path, backdrop_path = [Path(p).resolve() for p in (args.poc, args.sheet, args.backdrop)]
    # Avoid silently extracting another sheet/version by checking the authoritative row recipe.
    html = poc.read_text(encoding="utf-8")
    if "const rows=[0,246,492,734,978,1254]" not in html:
        raise ValueError("POC extraction recipe changed; review before regenerating approved assets")
    for symbol, path in [("DAB_IMAGE", sheet_path), ("TEXTURE_IMAGE", backdrop_path)]:
        match = re.search(symbol + r"='data:image/png;base64,([^']+)'", html)
        if not match or base64.b64decode(match[1]) != path.read_bytes():
            raise ValueError(f"{path.name} differs from the POC's embedded {symbol}")
    with Image.open(sheet_path) as sheet:
        assert sheet.size == (1254, 1254), "Unexpected source sheet size"
    result = subprocess.run([args.node, "-e", CANVAS_EXPORT, poc.as_uri(), args.playwright],
                            check=True, capture_output=True, text=True, timeout=90)
    export = json.loads(result.stdout)
    assert len(export["dabs"]) == 25
    args.output.mkdir(parents=True, exist_ok=True)
    for i, data in enumerate(export["dabs"]):
        with Image.open(io.BytesIO(base64.b64decode(data))) as rgba:
            assert rgba.size == (251, 251)
            rgba.getchannel("A").save(args.output / f"dab_{i:02d}.png")
    with Image.open(backdrop_path) as backdrop:
        assert backdrop.size == (1254, 1254)
        # POC textureMask reads baseTexture[i], the source red channel, not luminance.
        # The visually gray source has minor unequal RGB values; retain R exactly.
        backdrop.convert("RGB").getchannel("R").save(args.output / "backdrop.png")
    manifest = {
        "format": "joybrush-watercolour-assets", "version": 1, "id": "poc25",
        "count": 25, "width": 251, "height": 251, "channel": "red",
        "meaning": "8-bit scalar coverage in grayscale PNG; upload to GL_R8, sample .r",
        "transparentGuardPixels": 5,
        "order": "row-major, top-left tight to bottom-right diffuse",
        "dabs": [f"dab_{i:02d}.png" for i in range(25)],
        "backdrop": {"file": "backdrop.png", "width": 1254, "height": 1254,
                     "channel": "red", "sourceChannel": "R", "repeat": True},
        "extraction": {"implementation": "authoritative POC extractDabs via Canvas 2D",
                       "rowBounds": ROWS, "contentThreshold": 8, "cropPadding": 4,
                       "noiseCorrection": "a <= 3 ? 0 : round((a - 3) * 255 / 252)",
                       "fitPixels": 239, "upscale": False,
                       "browserVersion": export["browserVersion"]},
        "sources": [{"file": p.name, "sha256": digest(p.read_bytes())}
                    for p in (poc, sheet_path, backdrop_path)],
        "provenance": "Owner-provided AI-generated textures; see ASSETS.md; no independent license assertion",
        "checksums": [image_record(args.output / name)
                      for name in [*[f"dab_{i:02d}.png" for i in range(25)], "backdrop.png"]],
    }
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    check(args.output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--output", type=Path, default=PACK)
    for key in ["poc", "sheet", "backdrop", "node", "playwright"]:
        parser.add_argument("--" + key)
    args = parser.parse_args()
    if args.check:
        check(args.output)
    else:
        if any(getattr(args, key) is None for key in ["poc", "sheet", "backdrop", "node", "playwright"]):
            parser.error("Generation requires --poc --sheet --backdrop --node --playwright")
        generate(args)


if __name__ == "__main__":
    main()
