# JB-9.10 — The launch paper library (looks + surfaces)

| | |
|---|---|
| **Tier** | T2 (taste) + T3 owner judgement |
| **Status** | 🟦 Ready, specialist's own row (paper specialist, 2026-10-01) |
| **Builder** | Claude (paper specialist) picks, packs and catalogues. **Codex makes candidates**: looks by image generation, surfaces by 3D height renders (PAPER_DISPATCH.md). Taste and the final say stay with the specialist, then the owner |
| **Depends on** | JB-9.01 layout (pack.py already matches), JB-9.04 catalogue |
| **Owner area** | `joybrush/tools/paper/*`, `joybrush/assets/paper/*` |

## Goal
Owner P8/P9: the launch list in R10 §7, each convincing as analogue, with no seam or loop, good at every zoom.

## Owner reference notes (2026-10-01)
- **Rice paper:** filament fibres and fibrous clumps suspended in a thin translucent sheet. It has subtle relief independent of brightness: whiter areas need not be raised,
  they are more OPAQUE (layered cobweb webbing). Cool white or warm cream. So the fibres live in the LOOK (opacity/whiteness), and the
  surface has gentle relief authored separately from the look. Translucency does not map 1:1 to thickness, and embedded dark pulp chunks do not imply recesses. Reference images: owner's message of 2026-10-01 (two rice-paper photos).
- **Thai sugarcane pulp:** a warm, slightly pink-beige sheet with short straw-coloured fibres and small chunks of brown and grey-brown
  (unbleached plant bits) scattered through it. Mild cloudiness. A good "artisan pulp" look. Two reference photos, same message.
- **Downloads approved** (owner, 2026-10-01): free public-domain (CC0) scans from ambientCG / Poly Haven. Record each file's source URL and
  licence in `assets/paper/SOURCES.md`.

## Budget
Surfaces 512² RGBA PNG (~0.8 MB). Looks 512² RGB (or 1024² where the detail needs it). Whole library ≤ ~25 MB; WebP lossless if PNG runs over.
`texelPx` sets the physical size; the hex tiling hides the repeat.

## Order
Owner priority update (2026-10-02): **crumpled paper, slightly crumpled then flattened paper (subtle), and physical brush-response surfaces come first**. The two crumples are two different physical strengths, not merely two decorative looks. Preserve smaller height/slope variation for the flattened version; lowering relief lighting alone is insufficient. Image generation remains paused.

1. Off-white (artisan pulp; already shipped as the first entry) · 2. Rice paper (cool + cream tints of one look) · 3. Sugarcane pulp ·
4. Pulp grades (handmade, factory) · 5. Chalkboards (black, green; two different dust looks over one chalk-grit surface) · 6. Tan construction ·
7. Canvases (fine linen, cotton duck, rough jute; rotation off) · 8. Blueprint · 9. Parchment · 10. Papyrus (rotation off) ·
11. Crumpled ×2 · 12. Cement, fabric, silk · 13. AMOLED black (flat #000000, light off).

## Done when
Each entry is in the catalogue and passes `ShippedCatalogueTest`. A contact sheet (`tools/paper/out/contact.png`: each paper lit, at 0.25×/1×/4×)
goes to the owner, and the owner picks/rejects on the phone once JB-9.06 + JB-9.07 land.

## Questions

2026-10-02 Physical-surface packing correction: pack.py currently uses PIL convert("L") on the unsigned16 candidate maps, clipping most values to white rather than scaling16-bit heights to8-bit. Fix the CLI conversion, preserve authored amplitude, and match SurfaceMaps' minimum slope range0.001 for flat surfaces. Existing crumple candidates remain unsuitable (rounded/pebbly instead of convincing folds); do not catalogue them just because they are high priority. This continuation corrects the physical-data pipeline without new image generation.

2026-10-01 Paper specialist continuation: add only the already specified AMOLED black (flat #000000, smooth, light off) so the Paper sheet has a second background without new image generation or candidate selection. The remaining library is still unselected; rice/sugarcane/canvas candidates stay for the owner's priority review after functional integration.

2026-10-01 Codex: Paused per the owner's incorrect/unclear-spec rule. PAPER_DISPATCH requires generated candidates to be left in the MAIN folder's ignored `joybrush/tools/paper/candidates/` for the specialist, but the current owner instruction permits work ONLY in `%TEMP%/jb-<row>`. Should candidates be generated and handed off from `%TEMP%/jb-9.10/joybrush/tools/paper/candidates/`, or is copying finished candidates to the main ignored folder an explicit exception? No candidates were generated or catalogue selections made before resolving that required handoff location. This is the last row in the Codex lane.

**Specialist answer (2026-10-01):** candidates are NOT code and do not go through git. Write them directly to the absolute path
`C:/+Projects/Screenrecorder/FadCam/joybrush/tools/paper/candidates/looks/` and `…/candidates/surfaces/` (git-ignored). That is the one
exception to "work only in your worktree", and it needs no Gradle. File names: `<paper>_<n>.png` (e.g. `rice_cool_1.png`). Add a
`candidates/NOTES.md` saying how each was made (prompt or 3D setup). The owner's reference photos are next to it in `reference/`
(study only, never ship).

2026-10-01 Owner correction and promotion: Codex now heads paper/texture work while the specialist is out of usage. Rice paper must not be perfectly flat; brightness/opacity/dark inclusions are not height proxies. Use neutral D65 white balance, with material warmth and no global amber grade. Candidate generation is paused at the owner's request; prioritize general paper functionality before adding library entries. The 27 look candidates and eight geometry height candidates are raw material only; generation prompts do not prove seamlessness, and several repeat checks identify joins needing repair. No further images planned until requested.

2026-10-02 The owner explicitly requests a small ready-to-test canvas/pulp set plus custom background colour. As acting paper specialist, select the three existing canvas geometry candidates and two deterministic pulp grades; neutral image-free looks expose their default surfaces in Background as well as Surface. No AI generation. Custom-colour preview support is a separate JB-9.07 backend continuation; the Lead retains UI ownership.

2026-10-02 Owner requests full completion of all paper/background/surface specs and all listed materials, coordinating with the Lead. Structural detail in the visual look must correspond to the physical surface (chalk grit, folds, weave), with seamless periodic source data and matching sampler metadata. Pigment, translucency and embedded dark chunks remain independent where physically appropriate. This authorizes full numerical material authoring beyond the small test set; do not launch extra AI candidate batches. Full/Subtle crumples must be convincing facets/creases, not rounded noise. Spec completion includes measured verification and explicitly separate phone acceptance.
