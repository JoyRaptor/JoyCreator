# Approved proof-of-concept watercolour assets

This is the owner's approved **25-sample** brush set, retained at its native
extracted size. It is not a generated 16-sample alternative or an upscale.

## Provenance

The owner supplied `Watercolor-daubs-clear.png` and
`Watercolor-backdrop-texture.png` for this brush experiment. The integration plan
`tasks/joybrush/specs/WATERCOLOUR_LINE_BRUSH_PLAN_20261012.md`, W2, identifies these
as AI-generated textures for the owner and authorizes extraction into app assets.
The generation service, model, prompt, creation date, and an independent asset
license have not been provided. No third-party license or public-domain claim is
made here. This note records the supplied provenance; it does not invent rights.

Source filenames and SHA-256 hashes are recorded in `manifest.json`. Both source
PNGs were checked byte-for-byte against the images embedded in the authoritative
POC `index.html` before extraction. The POC and original files are read-only.

## Representation

- `dab_00.png` through `dab_24.png`: 251 x 251, 8-bit **L grayscale** PNGs. The
  scalar is the exact alpha channel from the POC's extracted Canvas dabs. Store
  this scalar in `GL_R8` array layers and sample `.r`; these PNGs do not have a
  separate PNG alpha channel. Zero means transparent coverage, 255 opaque.
- Array order is row-major, starting at the source's upper-left solid sample and
  ending at its lower-right diffuse sample. The files have at least five zero
  pixels on every outer edge, preventing clipped or leaking sample boundaries.
- `backdrop.png`: 1254 x 1254, 8-bit L, the original source's **red channel**
  without resizing. POC `textureMask` also reads red, not luminance. Although the
  supplied image looks gray, some RGB channels differ by small values; averaging
  them would change the mask. Contrast and inversion belong to the renderer,
  not the stored image. Repeat this tile in document coordinates with the
  deterministic offset per stroke.

The dabs preserve the POC's exact Canvas 2D resampling. The extraction uses row
bounds `[0,246,492,734,978,1254]`, content alpha >= 8, four-pixel crop padding,
alpha noise correction `a <= 3 ? 0 : round((a-3)*255/252)`, fit at most 239 pixels
without upscaling, and centered placement into a 251-pixel canvas. Pillow only
extracts the exported alpha into L; it does not approximate Canvas interpolation.

## Reproduction and gate

`joybrush/tools/watercolour_assets.py` opens one headless Edge/Chromium instance
using Playwright and closes it in `finally`. Supply explicit `--poc`, `--sheet`,
`--backdrop`, `--node`, and `--playwright` paths to regenerate; `--output` defaults
to this directory. Generation requires the approved extraction recipe and
matching embedded source images before doing any work.

The committed asset gate needs only Python and Pillow:

```text
python joybrush/tools/watercolour_assets.py --check
```

It verifies all 25 layers, order, grayscale mode, dimensions, nonempty samples,
**all four complete five-pixel guards**, backdrop dimensions, file hashes, and
decoded-pixel hashes. The generated manifest records the extraction browser
version. This verifies source assets only; GPU appearance and phone performance
are separate W3/W5 gates.
