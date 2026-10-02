# R11 — Adaptive brush competition and implementation runway

2026-10-02 · Brush specialist research · Read-only audit of base d6110150 plus currently shared working tree.

This report informs the owner's requested small collection of excellent adaptive instruments. It does not claim hands-on comparison, phone approval, completion of roadmap rows, or access to competitors' proprietary implementation. Existing R3 and R5 cover most engine research; this adds focused acceptance criteria and corrects the blueprint's novelty claim. No builds were run for this report.

## Official evidence

- Infinite Painter's [Stroke controls](https://docs.infinitestudio.art/painter/brushes/settings/stroke/) document Tilt Offset: brush growth can be displaced relative to the stylus tip, positive for pencils. Tilt is measured from upright to horizontal. Pressure, velocity and tilt can drive size and opacity.
- Its [Texture controls](https://docs.infinitestudio.art/painter/brushes/settings/texture/) document Gradation: a tilted pencil darkens toward its head tip and fades toward the barrel. Texture depth controls which parts deposit; texture can remain fixed, rotate with the stroke or warp along it.
- Its [Head controls](https://docs.infinitestudio.art/painter/brushes/settings/head/) document Depth: alpha acts as a height map so light pressure engages fewer bristles, heavy pressure more. Head orientation can follow stylus lean.
- Its [Paint controls](https://docs.infinitestudio.art/painter/brushes/settings/paint/) separate Mix-in (undercolour replacing carried colour), Dilution (blending), Blur (averaging versus individual pixel transfer), Pull (carry persistence), and Wet paint (moving transparency too). Pressure or tilt can control Dilution.
- Krita's [Color Smudge engine](https://docs.krita.org/en/reference_manual/brushes/brush_engines/color_smudge_engine.html) distinguishes Smearing (copying a previous-position patch) from Dulling (colour averaging with brush-shape preservation). Smearing is recommended for impasto oil with dense spacing. Thickness uses a separate height map to keep simulated lighting out of colour mixing.
- Krita's [Sensors](https://docs.krita.org/en/reference_manual/brushes/brush_settings/tablet_sensors.html) distinguish pressure, tilt direction, tilt elevation and drawing direction. Elevation is 0 degrees horizontal, 90 degrees upright, opposite to Joy's tilt-from-upright convention.

**Correction to JOYBRUSH_BLUEPRINT §1 row 2:** a tilt-aimed pencil gradient is not unique against Infinite Painter. Our proposed advantage is a long, point-anchored side contact with controllable hard edge, gradual pressure-dependent tooth engagement and uninterrupted transitions between detail and shading. This is a design target, not an established competitive win.

## Four creative instruments, not a brush catalogue

Names below describe proposed roles, not finished presets. Each keeps one useful default size and changes its contact through pen use.

| Instrument | Owner's references consolidated | Desired contact and character |
|---|---|---|
| Existing Sable / Ink family | Krita inkP 01 generic pen, inkP 03 dip pen flat, inkP 20 flatbrush; existing pen and sable work | Upright/light contact for detail, average pressure for expressive line, leaned/pressed belly for wide shapes. Keep existing fine ink and physical sable behaviors rather than replacing them with a generic stamp. |
| Long-contact Pencil | Krita charcoal pencil medium/thin; Infinite Painter Proko pencil, Shaded | Fine point stays at pen. Around 45 degrees, a long side reaches out along lean; stroke crosswise to lean gives broad shading, parallel travel can remain narrower. Far body catches paper faintly; pressure increases deposit and produces a firm near edge while preserving controllable soft grades. |
| Dry Bristle | Krita dry textured creases, dry brushing, RGBA 01 thick-dry, RGBA 06 rock, texture impressionism; Infinite Painter Sketchy | Light tip gives sparse scratches, ordinary contact textured paint, tilted belly broad dry masses. Stable stroke-space bristle clumps combine with document-space paper tooth; pressure engages more contact without moving the paper grain. |
| Mixing Flat Paint | Krita wet bristles rough, wet bristles, wet paint plus, wet textured soft, RGBA 02 thickpaint; Infinite Painter Flat oil, Palette knife, Old oil, Palette roller | Edge/detail, flat middle contact and broad belly; controlled carry of undercolour while laying selected colour. Knife/roller inspiration informs broad planar contact, not extra instruments by default. Optional spatial pickup preserves pre-stroke undercolour fragments; it is not within-stroke paint displacement. |

Krita Shapes square is a contact shape for flat paint or a stamp utility. Elemental webs and sketching-1 chrome thin suggest optional sketch/connective effects and should not turn the launch shelf into a novelty catalogue. Adjust dodge belongs to a utility/adjustment operation. Infinite Painter Wet paper and light wash inform the future dedicated Wash instrument; paper pooling alone does not implement watercolor transport.

Retain existing utility presets: Marker, Soft air, Eraser, Fill pen, Smudge. Nudge awaits Push rendering. Utility brushes must not be counted as additional creative instruments when describing the four-instrument direction.

## Code findings and hard limits

- `TuftStroke.kt` already distinguishes point, trailing bristles and a tilt-displaced belly. Preserve the point anchor, lean convention and travel damping from that model when designing Pencil; a solid pencil should not inherit sable's flexible bristle lag.
- `GrainMath.kt` already has a tilt gradient and canvas grain. New long contact needs matching geometry, bounds and coverage across CPU replay, GPU stamps and the PC lab; increasing size alone gives a centred blob rather than the requested pencil side.
- `SmudgeStroke.kt` samples a 7x7 footprint into one coverage-weighted RGBA mean from the layer as it was at stroke start. It intentionally does not sample the stroke's own new deposits. `Smudge.kt` carries one colour under the existing blueprint constraint. The original carry path can produce blending and colour carry but loses spatial fragments. This specialist run adds optional per-pixel `texturePickup` against the pre-stroke canvas: `PaintPickup.kt` preserves carried alpha and weights sampled straight colour by source alpha. It retains pixel variations without a second stored reservoir. The GPU paint commit path is implemented by the parent agent and requires its own verification. This still cannot propagate new deposits within a stroke and does not implement impasto, water transport or a physical oil solver. Do not add separate pickup/reservoir stores casually; respect the existing one-colour contract.
- The base includes JB-9.08: `GrainMath.paperEffectiveHeight` combines travel-facing slopes with coarse-height wet response. This is directional deposition and pooling, not liquid advection, drying or bloom. Use the document's surface, bite and scale authority; never bake paper appearance into a brush-owned moving texture.

## Roadmap status and evidence still needed

Statuses are a snapshot of ROADMAP.md, not new approvals. Existing build claims below were read, not rerun here.

| Rows | Recorded position | Remaining evidence or implementation |
|---|---|---|
| 1.01–1.05c | Tip/grain/dynamics/scatter and shader wiring built or reviewed | Adaptive contact must retain deterministic replay and CPU/GPU coverage agreement; 1.05c still names Note 9 pressure/lean check. |
| 1.06 | Smudge built; Note 9 check owed | Cross coloured stripes, test carry versus averaging, transparent edges, first contact and doubled-back stroke behavior. |
| 1.06b | Draft; Push geometry/format exist | Stitched tile snapshot and GL displacement pass are unbuilt. Demonstrate cross-tile displacement, undo/save and absence of seams before claiming push. |
| 1.07 / 1.30 | Default utility presets built; owner sign-off outline | New creative presets need real rendered acceptance sheets and Note 9 stylus tuning. Built does not mean owner-approved. |
| 1.20 | PC lab built; shared shader execution reported | Wire any adaptive footprint into the same shader source; exercise pressure/tilt transitions and preserve deterministic seeded comparison images. |
| 1.21 | Phone hot reload built | Confirm current selected preset reload and validity feedback on phone; tuning speed is not brush-quality proof. |
| 0.12 / 0.30 | Prediction draft; phone lag check outline | Real latency and thermal/performance measurements on Note 9, not laptop estimates. |
| 6.01 | Outline: commit-time watercolor look | Edge darkening, granulation and bloom implementation plus controlled before/after render evidence. JB-9.08 does not complete this row. |
| 6.02 | Outline: wet-lite flow | Flow restricted to wet area, pause while pen down, save/undo determinism and target-device cost. |
| 6.03 / 6.30 | Outline: adaptive wash and owner check | Tip/middle/belly wash tuning after 6.02; phone acceptance remains separate. |
| 8.01 / 8.01b | ABR built; real-file fix Ready | Four real ABR cases and comparison against reference reader; original synthetic tests prove internal consistency only. |
| 8.02 | Procreate built; real-file check owed | Actual brush/brushset files, per-preset refusal and warnings, rendered behavior versus supported mapping. |
| 8.03 | MyPaint built; review MAJOR recorded | Resolve documented opaque/hardness input-curve loss against specification and real brushes; do not claim fidelity from fixture parsing. |
| 8.04 / 8.04b | Krita built; bundle inflate built; real-file check owed | Real KPP/bundle decoding and unsupported-engine warnings. User-listed names alone do not expose each preset's parameter values. |
| 8.05 | Outline probe | Supply real file corpus; report imported/warned/refused per brush. Empty-folder skip is not competitive evidence. |
| 9.03b / 9.07 / 9.10 | Ready audit/UI/library work | Complete their own acceptance before changing statuses. |
| 9.08 / 9.09 | Directional/wet deposit and controls built | Note 9 paper interaction remains owed. Compare same contact on smooth/rough paper and across document scales. |
| 9.11 | Core half built; importer half stopped | Pixel decoder, texture storage budget, height polarity and real assets remain unresolved; texture-to-surface helper alone is not importer wiring. |

## Acceptance plan for this specialist run

1. Freeze the stylus convention: upright=0, 45 degrees=pi/4 from upright; derive lean from azimuth with missing-sensor fallback. Verify all quadrants and angle wrapping.
2. At one fixed preset size, render pressure 0.08/0.25/0.55/0.9 at tilt 0/30/45/65 degrees. Include parallel/perpendicular travel, reversed lean, stationary pressure change and one continuous point-to-side-to-point stroke.
3. Pencil sheet: near edge firmness increases with pressure; far body retains faint broken tooth at light pressure; smooth pressure changes must not shift the point anchor or jump width. Compare real contact extent, not just nominal brush diameter.
4. Paper sheet with the paper specialist: matching strokes on smooth, rough and tinted looks; same document position on repeated passes; positive/negative tile borders and scale/zoom changes. Paper display and export parity remain the paper authority's responsibility.
5. Flat paint sheet: alternating red/blue stripes, short/long carries, bare/opaque canvas and reversals. Distinguish the mean-colour carry baseline from optional spatial texture pickup. Verify both CPU and GPU stripe retention, source-alpha weighting and empty-source fallback. Within-stroke propagation remains outside this implementation.
6. Run targeted math/format tests and real shader compilation/rendering for changed paths; replay/save/undo checks where geometry affects the live engine. Finally perform Note 9 responsiveness and feel checks. Do not promote owner-only statuses automatically.

**Review outcome:** focused design direction and remaining gates identified. Competitive superiority and full roadmap completion remain unproven until rendered comparisons and target-device trials exist.

## Owner's wrap-up direction (October 2)
The owner explicitly permits engine changes needed for realistic colour interaction. Proko is the raster pencil feel target; Concepts Soft Pencil and Waterful are the vector references; Rebelle is the oil benchmark; Expresii is the watercolour benchmark. Procreate must also inform comparison. Tilt coordinates remain radians from upright (0 upright, pi/4 diagonal, pi/2 horizontal); this choice of coordinates does not rank an engine's sophistication.

Procreate documents pressure, tilt, azimuth and optional barrel roll as brush inputs, and separate paint/smudge behavior: https://help.procreate.com/procreate/handbook/brushes/paint-smudge-erase . Its documented wet-mix/rendering controls distinguish blending behavior and brush loading: https://help.procreate.com/procreate/handbook/5.2/brushes/brush-studio-settings . Rebelle documents oiliness-linked average smudge colour and adjustable mixing in its bristle Paint panel: https://escapemotions.com/products/rebelle/manual/8/interface/panel-brush-creator/bristle-brushes/paint/ . Expresii's deformable brush and fluid simulation are the watercolour target, beyond the current paper wet-deposit response: https://www.expresii.com/about-us.html and https://www.expresii.com/userguide.html . None of these comparisons establishes that the automated Joy brushes beat their feel; that requires the owner's stylus check.