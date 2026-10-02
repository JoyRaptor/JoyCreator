# Paper sources

Original numerical materials authored for Joy Brush. No third-party scans or AI-generated look images in this test set.

Canvases: existing periodic 3D yarn candidates (alternating over/under centrelines, three helical cylindrical strands, wrapped slubs and knots). Pulp: seeded NumPy fibre/formation model in this script. 1024 heights are reduced by 2x2 area averaging, then centred physical amplitude is applied without renormalisation. Packed slopes use a shared 0.099 range and toroidal Scharr sampling. Colours are neutral material bases, without an amber grade.

Rebuild: `python joybrush/tools/paper/prepare_test_surfaces.py <candidate-surfaces-directory>`; original canvas inputs remain in the owner’s ignored candidates/surfaces directory.

- `surface_canvas_linen.png`: candidate SHA256 1e9912adac9a07b7330100cf588b3b8c224c4be0b710f2e8d0129d42073bfa9e; physical span 0.3, texelPx 0.75.
- `surface_canvas_cotton_duck.png`: candidate SHA256 1b0265219659b6174f6781b24d057db427d2d37d7d718bac5ca3548d1c46c611; physical span 0.5, texelPx 1.0.
- `surface_canvas_jute.png`: candidate SHA256 5c24f8c8897237a4ae43a572d97103ca632cc3b9e6b4aef3e80fee962dc9a3f9; physical span 0.85, texelPx 1.25.
- `surface_pulp_factory.png`: prepare_test_surfaces.pulp(seed=810, rough=0.0); physical span 0.32, texelPx 2.0.
- `surface_pulp_handmade.png`: prepare_test_surfaces.pulp(seed=811, rough=0.9); physical span 0.9, texelPx 2.0.
