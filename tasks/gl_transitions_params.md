# GL Transitions — fetched files + extracted parameters

**Status (2026-06-19):** all 26 `.glsl` files are in `app/src/main/assets/gl_transitions/`
(verified: each declares `vec4 transition(...)`; MIT headers present). The Java side
(`GlTransitionShaderLoader`, `GlTransitionShaderProgram`, `GLTransitionCatalog`,
`GlTransitionPreviewView`) is NOT built yet — see `PLAN_filters_color_text_transitions.md` §4.

The wrapper template binds `fromTex`, `toTex`, `progress`, `ratio` for every shader. The extra
tunable uniforms below were extracted directly from the shader sources (name + author's default).
Suggested slider ranges are best-guess starting points — confirm visually on device once wired.

| File | Uniform | Default | Suggested slider range |
|---|---|---|---|
| CrossZoom | strength | 0.4 | 0.0 – 1.0 |
| DefocusBlur | blurSize | 0.02 | 0.0 – 0.1 |
| Drop_Zone_Flicker | frameRate | 24.0 | 8 – 60 |
| Drop_Zone_Flicker | rgbOffset | 0.014 | 0.0 – 0.05 |
| Drop_Zone_Flicker | blockAmount | 0.72 | 0.0 – 1.0 |
| Drop_Zone_Flicker | ghostAmount | 0.62 | 0.0 – 1.0 |
| Drop_Zone_Flicker | redCyan | 0.58 | 0.0 – 1.0 |
| Drop_Zone_Flicker | scanline | 0.075 | 0.0 – 0.2 |
| FilmBurn | Seed | 2.31 | 0.0 – 10.0 (random seed) |
| GridFlip | size | ivec2(4) | 2 – 10 (per axis) |
| GridFlip | pause | 0.1 | 0.0 – 0.5 |
| GridFlip | dividerWidth | 0.05 | 0.0 – 0.2 |
| GridFlip | bgcolor | vec4(0,0,0,1) | color picker |
| GridFlip | randomness | 0.1 | 0.0 – 1.0 |
| Overexposure | strength | 0.6 | 0.0 – 1.0 |
| Radial | smoothness | 1.0 | 0.0 – 2.0 |
| StereoViewer | zoom | 0.88 | 0.5 – 1.0 |
| StereoViewer | corner_radius | 0.22 | 0.0 – 0.5 |
| burn | color | vec3(0.9,0.4,0.2) | color picker |
| burn0 | burnColor | vec3(1.0,0.5,0.0) | color picker |
| colorphase | fromStep | vec4(0,0.2,0.4,0) | advanced |
| colorphase | toStep | vec4(0.6,0.8,1,1) | advanced |
| cube | persp | 0.7 | 0.0 – 1.0 |
| cube | unzoom | 0.3 | 0.0 – 1.0 |
| cube | reflection | 0.4 | 0.0 – 1.0 |
| cube | floating | 3.0 | 0.0 – 10.0 |
| polar_function | segments | 5 | 2 – 12 |
| powerKaleido | scale | 2.0 | 1.0 – 5.0 |
| powerKaleido | z | 1.5 | 0.5 – 3.0 |
| powerKaleido | speed | 5.0 | 0.0 – 10.0 |
| ripple | amplitude | 100.0 | 0 – 300 |
| ripple | speed | 50.0 | 0 – 150 |
| swap | reflection | 0.4 | 0.0 – 1.0 |
| swap | perspective | 0.2 | 0.0 – 1.0 |
| swap | depth | 3.0 | 0.0 – 10.0 |

**No extra params (plain dissolve/warp — only progress/ratio):**
zoomInOut, tangentMotionBlur, HSVfade, Dreamy, crosswarp, heart, BookFlip, InvertedPageCurl,
Fold, SimpleFlip.

**Note on `// = value` vs `/* = value */`:** both comment styles appear (e.g. `burn.glsl` uses
`/* = ... */`). The spec (§4.3) says hand-declare these for the curated set rather than regex-parse;
this table is that hand-declaration, done from the actual sources.
