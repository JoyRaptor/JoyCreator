# Paper sources

Original numerical materials authored for Joy Brush, plus (2026-10-06) photographic papers from the owner's own image generations (below). No third-party scans.

Canvases: existing periodic 3D yarn candidates (alternating over/under centrelines, three helical cylindrical strands, wrapped slubs and knots). Pulp: seeded NumPy fibre/formation model in this script. 1024 heights are reduced by 2x2 area averaging, then centred physical amplitude is applied without renormalisation. Packed slopes use a shared 0.099 range and toroidal Scharr sampling. Colours are neutral material bases, without an amber grade.

Rebuild: `python joybrush/tools/paper/prepare_test_surfaces.py <candidate-surfaces-directory>`; original canvas inputs remain in the owner’s ignored candidates/surfaces directory.

- `surface_canvas_linen.png`: candidate SHA256 1e9912adac9a07b7330100cf588b3b8c224c4be0b710f2e8d0129d42073bfa9e; physical span 0.3, texelPx 0.75.
- `surface_canvas_cotton_duck.png`: candidate SHA256 1b0265219659b6174f6781b24d057db427d2d37d7d718bac5ca3548d1c46c611; physical span 0.5, texelPx 1.0.
- `surface_canvas_jute.png`: candidate SHA256 5c24f8c8897237a4ae43a572d97103ca632cc3b9e6b4aef3e80fee962dc9a3f9; physical span 0.85, texelPx 1.25.
- `surface_pulp_factory.png`: prepare_test_surfaces.pulp(seed=810, rough=0.0); physical span 0.32, texelPx 2.0.
- `surface_pulp_handmade.png`: prepare_test_surfaces.pulp(seed=811, rough=0.9); physical span 0.9, texelPx 2.0.

## Launch material authoring

All additions are original numerical geometry/pigment, deterministic seeds in `build_launch_library.py`. Rebuild after the small test-set tool with the same candidate-surfaces directory. Creases are a wrapped jittered triangle sheet, full and flattened from the SAME geometry and smaller physical height/slope span. Rice opacity and plant chunks are separate pigment fields. Chalk dust is residue over common grit. Woven materials use the original geometry candidates. Every pictured look shares size, physical pitch, hex size, rotation and hash with its default surface; no baked light/shadows in albedo.

- `crumpled`: periodic numerical physical model; authored height span 0.75, slopeRange0.099, pitch1.5, rotationTrue.
- `crumpled_flattened`: periodic numerical physical model; authored height span 0.16, slopeRange0.099, pitch1.5, rotationTrue.
- `rice_fibres`: periodic numerical physical model; authored height span 0.14, slopeRange0.099, pitch2.0, rotationTrue.
- `sugarcane_pulp`: periodic numerical physical model; authored height span 0.38, slopeRange0.099, pitch2.0, rotationTrue.
- `construction_pulp`: periodic numerical physical model; authored height span 0.48, slopeRange0.099, pitch2.0, rotationTrue.
- `chalk_grit`: periodic numerical physical model; authored height span 0.52, slopeRange0.099, pitch0.75, rotationTrue.
- `parchment_skin`: periodic numerical physical model; authored height span 0.16, slopeRange0.099, pitch2.0, rotationTrue.
- `papyrus_strips`: periodic numerical physical model; authored height span 0.38, slopeRange0.099, pitch1.2, rotationFalse.
- `silk`: periodic numerical physical model; authored height span 0.16, slopeRange0.099, pitch0.7, rotationFalse.
- `fabric`: periodic numerical physical model; authored height span 0.42, slopeRange0.099, pitch1.0, rotationFalse.
- `cement`: periodic numerical physical model; authored height span 0.7, slopeRange0.099, pitch1.25, rotationTrue.

## Photographic papers (2026-10-06)

The owner judged the numerical looks above "early-90s 3D graphics" and supplied photoreal paper images they generated
themselves (ChatGPT image generation, owner-owned). They are converted by `joybrush/tools/paper/install_photo_papers.py`, which
runs `photo2paper.py` on each one:

1. De-light (the very lowest frequencies only; the paper's own mottling is kept).
2. Seamless by Moisan's periodic + smooth decomposition, so there is no blend band.
3. 1024² look as JPEG q92, with the mean measured on the decoded JPEG.
4. Surface HEIGHT from the same picture: the tooth band (≈5 doc px, ~0.25 mm) dominates a pulp band, rank-equalised to a uniform 0..1, with the relief span set per kind. Shipped as an 8-bit grey PNG (`packed: false`); the app packs the slopes at load with `SurfaceMaps.expand`.
5. FLUID map at a quarter of the resolution: R = absorbency/formation, G,B = fibre direction (structure tensor, double angle), A = pore capacity.

Physical numbers per kind (toothDepthMm, compliance, sizing, absorbency, capacity, wickSpeed, anisotropy) are starting values for the dry, wet and impasto engines.

The source images stay OUT of the repo: `tasks/joybrush/research/GBT texture sample image generations/` in the owner's folder.
Rebuild: `python joybrush/tools/paper/install_photo_papers.py "<that folder>" joybrush/assets/paper`.

| Look id | Surface id | Source image | Kind |
|---|---|---|---|
| construction_tan | construction_pulp | construciton-paper.png | pulp |
| construction_blue | construction_blue_pulp | construction paper-blue.png | pulp |
| cardboard | cardboard | cardbord01.png | board |
| chalkboard_black | chalk_grit | chalkbord-black-…11_08_28 PM.png | chalk |
| chalkboard_green | chalk_grit | the black chalkboard, recoloured dusty green | chalk |
| rice_cool | rice_fibres | ricepaper01-… | rice |
| rice_white | rice_white_fibres | ricepaper02-… | rice |
| rice_cream | rice_cream_fibres | ricepaper03-tan-… | rice |
| rice_lace | rice_lace_fibres | ricepaper04n-… | rice |
| sugarcane | sugarcane_pulp | thai-…11_08_59 PM.png | chunk |
| thai_kraft | thai_kraft_pulp | thai02-… | chunk |
| thai_rose | thai_rose_pulp | thai03-… | chunk |
