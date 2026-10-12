# Watercolour shader gates

Run under the lead's browser/resource admission (one SwiftShader browser, closed in
`finally`; no phone access):

```powershell
$env:NODE_PATH = '<bundled node_modules directory>'
node joybrush/tools/watercolour_check.js --poc C:/Users/JoyRaptor/Downloads/Watercolor-POC/index.html
```

Append `--diagnostic --out C:/Temp/jb-watercolour-check` to save actual/reference
PNG pairs and `report.json`, and to isolate unpooled and pure-shape comparisons.
The original approved and isolated gates still run and still determine failure.
`full_resolution_reference` is an additional labelled diagnostic: an in-memory
copy of the POC uses pooling scale 1 instead of 0.5 and disables its placeholder
paper/grain. It diagnoses the plan's intentional full-resolution GPU treatment.
It never replaces or relaxes the original POC comparison and is not source POC
appearance parity. No reference file is written.
For a filtering/accumulation A/B, append `--dab-filter both --band-precision both`.
Defaults are `linear` and `half` to match the current production loader/pass;
the alternatives only affect this checker, and every case keeps the same DeltaE
threshold. Shader CPU/seam gates always use half-float deposition.

The checker reads the actual shader files and recursively expands the canonical
paper includes. It does not modify the reference HTML, settings, assets, core code,
or Android engine. The reference page is temporarily instrumented in memory to
render W1's actual POC paths. The W1 fixture supplies the exported stamp parameters,
so no duplicate placement/pressure/RNG implementation is involved.

Gates:

- Independent RGB-band source-over uses the real dab pass and a white shape.
- Canonical constant paper height isolates the CPU coverage/linear-light optical
  formula. Completed, unpooled and intermediate fade results allow at most one
  byte of framebuffer quantization error.
- The actual cached-image `jb_wc_mix.frag` is compiled with the finish vertex
  shader and compared against premultiplied RGBA interpolation at amounts 0,
  0.37 and 1 (plus out-of-range clamping). Cached inputs differ in both colour
  and alpha, including transparent/opaque endpoints; tolerance is at most one
  byte. Fixed-amount and source-over-alpha mutations must fail. This verifies
  the production fade pass separately from finish's analytical fade.
- Nine real dab-pass tiles versus one monolithic target require **zero byte
  mismatches** after finishing; strokes cross a tile corner at both zero and
  negative document coordinates at scale 1 and 2, using actual extracted dab alpha with both
  constant paper and the shipped packed Artisan paper (canonical hex reads).
  Empty neighbours have exactly the band's tile
  dimensions. Neighbour-discard, removed alpha buildup and removed optical colour
  concentration mutations must fail the corresponding assertions.
- With `--poc`, the actual 25-layer R8 assets and source wash render the three
  golden strokes. Both painted-only (union alpha > 0.02) and full-frame mean
  CIE76 DeltaE must be **less than 3**. Approved settings and paper/grain-disabled
  isolation are reported separately. Transparent results composite onto white
  for this comparison, without changing alpha used by the painted mask.

The approved comparison intentionally reports the paper difference: the source
uses its procedural placeholder paper and grain; the GPU test uses canonical
constant-height paper and no procedural grain. The isolation comparison disables
both in the POC and GPU to identify whether shape/optics/wash differ. It preserves
the same stamp sequence and uses the original wash at 1254px (the POC downsamples
it to 512px). Full-resolution GPU pooling and the POC's half-resolution pooling
also differ. None of these differences relax the threshold: a failing comparison
is a failure to investigate, not look parity.

Without `--poc`, the optical/seam gates run, but the report explicitly labels
perceptual parity **NOT RUN**. This checker does not certify phone speed or the
Android neighbour binding itself; the engine and Note 9 acceptance gates are
separate.

`commit_blend_check.js` also verifies the colour-varying RGBA stroke branch's
normal/multiply/screen premultiplied W3C formulas, erase precedence, source opacity
and layer opacity, including transparent and opaque endpoints. The ordinary
coverage/smudge branches with `u_rgbaStroke = 0` require bit-identical float
outputs to the shader at HEAD (or `--old-frag <saved pre-change shader>`). Run it
through the same browser admission; its `finally` always closes the browser.
