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
- **Rice paper:** filament fibres and fibrous clumps suspended in a thin translucent sheet. It is FLAT: the whiter areas are not raised,
  they are more OPAQUE (layered cobweb webbing). Cool white or warm cream. So the fibres live in the LOOK (opacity/whiteness), and the
  surface is nearly smooth, with only faint ridges along the thickest fibres. Reference images: owner's message of 2026-10-01 (two rice-paper photos).
- **Thai sugarcane pulp:** a warm, slightly pink-beige sheet with short straw-coloured fibres and small chunks of brown and grey-brown
  (unbleached plant bits) scattered through it. Mild cloudiness. A good "artisan pulp" look. Two reference photos, same message.
- **Downloads approved** (owner, 2026-10-01): free public-domain (CC0) scans from ambientCG / Poly Haven. Record each file's source URL and
  licence in `assets/paper/SOURCES.md`.

## Budget
Surfaces 512² RGBA PNG (~0.8 MB). Looks 512² RGB (or 1024² where the detail needs it). Whole library ≤ ~25 MB; WebP lossless if PNG runs over.
`texelPx` sets the physical size; the hex tiling hides the repeat.

## Order
1. Off-white (artisan pulp; already shipped as the first entry) · 2. Rice paper (cool + cream tints of one look) · 3. Sugarcane pulp ·
4. Pulp grades (handmade, factory) · 5. Chalkboards (black, green; two different dust looks over one chalk-grit surface) · 6. Tan construction ·
7. Canvases (fine linen, cotton duck, rough jute; rotation off) · 8. Blueprint · 9. Parchment · 10. Papyrus (rotation off) ·
11. Crumpled ×2 · 12. Cement, fabric, silk · 13. AMOLED black (flat #000000, light off).

## Done when
Each entry is in the catalogue and passes `ShippedCatalogueTest`. A contact sheet (`tools/paper/out/contact.png`: each paper lit, at 0.25×/1×/4×)
goes to the owner, and the owner picks/rejects on the phone once JB-9.06 + JB-9.07 land.
