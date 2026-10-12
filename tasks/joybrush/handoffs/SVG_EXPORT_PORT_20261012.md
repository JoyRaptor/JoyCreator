# SVG export scoped port — 2026-10-12

Original implementation and geometry tests are the vector specialist's work from
`origin/vector/svg-export`, commit `2aa7730cb67f4d252c67383c5f46655d4faf57ff`.
The eleven new production/test files under `core/vector/svg` were copied from that commit;
no branch merge or conflict resolution was performed. Preserve that attribution when integrating.

Port adjustments:
- Explicitly refuse asset-rendered watercolour: its PNG tips, masks and pooling need image export;
  a procedural filled outline would give the wrong appearance. Future GPU cached PNG export is
  the appropriate adapter, not a silent flattened approximation.
- Connect current `LineStyle`/`LineShaper.dashRuns` once, retaining taper and width scaling.
- Add tests for actual brush dash settings, watercolour refusal, deterministic XML and exactly one
  path per exportable recorded stroke. `SvgXmlTest` writes `core/build/vector_svg_sample.svg` when run.

Original acceptance coverage includes dense and fitted outlines against the production dab raster
at 4 samples per document pixel (IoU >= .99 for supported convex tips), hairpin holes, fit error,
one path with multiple dash subpaths, scatter approximation reporting, eraser refusal, blend,
opacity, XML escaping and pixel-slab sequencing. Spiky nonconvex tips are explicitly sweep-only
and have a separate no-missing-area gate; they are not claimed to satisfy .99 IoU.

Compilation and test execution remain the root builder's responsibility. No SVG export UI is
introduced here; this is the pure export core and its tests.
