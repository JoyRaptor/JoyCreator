# STUDIO PLAN — the Studio lead's working order (started 2026-09-24)

Owner's mandate: the Studio (editor) is mine as UI/UX lead. Anything outside the Studio goes to
`tasks/INBOX.md` for another agent. Build **primitives that compose**, not isolated tricks (the
After Effects lesson): each feature below says what it builds on and what it unlocks.

Status: ✅ done+committed · 🧪 committed, needs a phone check · 🔨 in progress · ⏳ queued

## P0 — broken or regressed (blocks real work)
| # | What | Owner | Status |
|---|---|---|---|
| 1 | Keyboard pops up when grabbing a text bar on the timeline (drawer follows selection → text drawer always raised the IME). Now: only a new box, a double-tap on the words, or a tap on the words raises it. | me | 🧪 e785916b |
| 2 | Text span selection lost (transform surface ate in-box touches). Surface steps aside on the **Text tab only**; handles return on Transform/Effects/Lanes and on close. | me (review of text-repair pass) | 🧪 e785916b |
| 3 | Text animation style doesn't refresh until tapping away. Adopted text-repair pass: keyboard down + playhead into the entrance so the pick is visible. Default granularity = per letter (model default; the staged ProjectStorage hunk makes old projects per-letter too — owner's ruling). | text lane → me | 🧪 e785916b (ProjectStorage hunk still staged, not mine) |
| 4 | Bend: bent photo vanishes (stamp buffer never resized → fixed); dots didn't follow a keyframed picture (fixed; 24dp revert); text never had the bend seam (wired); pinch re-syncs text handles. | helper agent + me | 🧪 f284540d |
| 5 | Promoted clip lane: "tap to expand audio" vs drawer. On device: tap selects, double-tap opens the Video overlay drawer (its header has the sound-strip toggle), ▾ collapses/expands the lane, 🔊 mutes. Need the owner to say which gesture he tried. | me | ❓ |
| 6 | Captions: preview / icon / export disagree. Export was right; the preview's GPU path had Y unflipped and drew captions under images — fixed by the export lane (17cb9d25). Icon staleness → me. | export lane + me | 🧪 / ⏳ icon |

## P1 — the look (owner's direct asks)
| # | What | Builds on | Status |
|---|---|---|---|
| 7 | Drawer see-through: 65% → **50%** default, plus a "Drawer see-through" slider in the Settings tool; every drawer + transcript panel repaint live. | ObjectDrawer.Kit.drawerFill | ✅ f284540d |
| 8 | Frost (real blur) as a **compact header toggle** like pass-through/lock — never a solid fill. Note: the old Frost never blurred anything. Real blur over live video = capture the video surface behind the drawer (PixelCopy/TextureView.getBitmap), downscale-blur, draw as the drawer background, ~15 fps. | ObjectDrawer header toggles | ⏳ |
| 9 | Slider rows (only sliders — owner): `[icon] [title] [slider] [tap-to-type value] [◇ key]`, one key→icon map in PipDrawerTabs. Still to adopt it: AudioDrawerTabs, FxPanel, PuppetDrawerTabs, MaskKeyPanel, ColorGradePanel, AV-sync row. | PipDrawerTabs.propRow | ✅ 20ac05e6 (rest ⏳) |
| 10 | Puppet (grey/colour man) toggle shows only when the selected object HAS puppet keys. | selection | ⏳ |
| 11 | Sprite tape colour = SpriteLab pink gradient. | ObjectPalette.SPRITE | ⏳ |
| 12 | AMOLED true black — already `s_ground #000000`; keep. | — | ✅ |
| 13 | Export icon — another agent replaced it; owner approves. (Uncommitted `ic_export_studio.xml` in tree — that agent should commit it.) | — | ✅ owner-approved |

## P1b — moving fast (owner, 2026-09-24)
| # | What | Status |
|---|---|---|
| 18 | **Two fingers anywhere on the PREVIEW move/scale/rotate the SELECTED object** (not the timeline — two fingers there zoom/scroll it). First finger off the box is held 150ms; a second finger turns it into a pinch on the selection and cancels selecting the other object. Rotation dead-zone 7°. adb can't send two fingers: owner must test. | 🧪 needs owner |
| 19 | **The magnet** (right of play): tap = all snapping on/off; hold = Snapping panel (master + Timeline edges / Beats / Canvas centre / Rotation, strength where honoured, rotation step 5/15/45/90°). One class: tools/SnapSettings. | ✅ |
| 20 | Snap still to wire on the NEW transform surface: canvas centre/edges and **other objects** alignment guides (the old handles had them; the new tool doesn't), + strength for Beats/Canvas. Then add OBJECTS to the panel. | ⏳ |
| 21 | **Linking / null objects** — design spec first (tasks/SPEC_20260924_LINKING.md): parent in space and/or time, nesting, a Null object with a pivot, a touch-friendly alternative to the pick whip, clear "linked to X · unlink" in drawer/timeline/preview, unlink one vs all, multi-select move. Spec agent was cut off by the usage limit — restart. | ⏳ |
| 22 | Export UI from the export lane's hooks: queue (drop the "already running" early return, "Queue export" label, overlay BEFORE the snapshot write), **Range export** (Whole project / Range with in/out), **Draft (fast)** preset = 720p + low. | ⏳ |
| 23 | MeshGlSource: a mirrored AND bent picture renders unmirrored (positions and UVs both flipped; likely `vUv = aUv`). Preview + export share it. | ⏳ |

## P2 — finish and prove
| # | What | Status |
|---|---|---|
| 14 | Text box sizing modes on one cycling chip: **Auto width** (grows with text) → **Wrap at width** (drag side handles to set width, text wraps) → **Fit** (text scales to the box). Makes larger blocks of text easy. | ⏳ |
| 15 | Phone checks owed: empty film frames, sprite frame-key drag, transport shrink-to-fit (Note 20 — coordinate), visualizer hold drawer, all of P0. | ⏳ |
| 16 | Drawer sweep: every item in every drawer (text, image, video overlay, sprite, visualizer, captions, audio, film) — honest controls, hover labels, one-press undo, tokens not hex, helpers not copies. | ⏳ |
| 17 | Gesture matrix on device: tap / double-tap / hold / hold-drag / swipe × every object kind, timeline and preview. | ⏳ |

## P3 — primitives that compose (design first, then build in this order)
1. **Path object** — its own object on the timeline, points animatable like any property. Made
   by a pen/shape tool: presets (circle, arc, rect, line) plus stylus freehand that is
   **simplified on release** with the same reducer the sprite motion recorder uses to turn
   recorded bars into clean keys (same idea, different axes). *Unlocks:* 2, 4, 5.
2. **Text on a path** — a text box can point at a Path object (like masks point at objects).
   Inside/outside of a circle for badges; an arc is a slice of a circle so arcs are free;
   keyframe the start offset. Because the path is its own object, animating the path
   re-flows the text automatically.
3. **Per-letter channels** — tracking (kerning), per-letter stretch/squash X/Y, offset along
   path. *Unlocks:* much richer per-letter animation presets (the existing preset engine
   gains channels, not special cases).
4. **Paths as masks and motion paths** — the same object feeds the mask system and an
   object's position track.
5. **Rigging that feels like puppetry** — bones by dragging one pin onto another (hierarchy
   inferred from drag direction and nearness to the body centre), IK when you grab a
   hand/foot, automatic smooth weights, a small influence from head to body, secondary
   tweaks (shoulder, elbow) layered without cluttering the timeline, everything simplified to
   clean Bezier keys. **Research first** (how Moho, Toon Boom, Spine, Rive, Cascadeur, and
   Adobe Character Animator make it easy) → spec → build. Needs mesh/pin work (P0 #4)
   to be solid before it starts.
