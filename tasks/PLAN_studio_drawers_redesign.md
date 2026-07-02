# Studio Drawers Redesign + Asset Auto-Population (2026-06-20 user feedback)

Captured verbatim-in-spirit from a detailed user feedback round. The user sketched a visualizer layout
(see chat). Build these as sequential compile-verifiable chunks; all are device-VISUAL-VERIFY.

## A. Visualizer Studio Phase 3 — REDESIGN as a top drop-down drawer (NOT a blocking dialog)

**Problem:** the current `showVisualizerStylePicker` is a `MaterialAlertDialog` that blocks the video.
**Want:** a drop-down drawer from the TOP (like the transitions drawer), non-blocking, with a live sample.

**STAGE 1 DONE (2026-06-20, compile-green + installed. VISUAL VERIFY):** `showVisualizerStylePicker` no
longer builds a dialog — it populates a new non-blocking top drawer (`@id/visualizer_drawer` overlay in
the root FrameLayout, slides down via translationY, `showVisualizerDrawer()`), close button + swipe-up
dismiss on `visualizer_drawer_header`. Because the canvas stays visible below, style/colour/sensitivity
changes preview LIVE on the real on-canvas visualizer (no separate sample widget needed). The existing
controls (justify/mode/mirror toggles, colour swatches, sensitivity slider, preset list) are reused as-is
inside the drawer. **STAGE 2 (still TODO):** the 3-column layout (left icon column, centre style-type
Rolodex carousel, right gradient Rolodex carousel) + the weighted "settle" 3D carousel motion — best done
with a stable device for visual iteration. Decouple type vs gradient on the instance (needs type/gradient
overrides like the colour override) so the two carousels are independent.

Layout (compact; leaves the video visible):
- **Top row:** sensitivity slider on the left; to its RIGHT, room for 2–4 small buttons for future
  features (e.g. a **"+" to save a preset** → preset browser; a "peaks that hit and fall" timing toggle;
  etc. — just leave the slots, don't build them yet).
- **Left column:** vertical ICONS replacing the current 3 text toggle buttons — justify, mode, mirror.
  Tapping one instantly applies to the center sample.
- **Center column:** vertical CAROUSEL of visualizer style TYPES (the shapes). The center item is the
  live sample; scrolling changes the type and the sample updates instantly. Shows ~3 at a time, or up to
  ~6 if the user drags the drawer handle to expand. Top/bottom items fade opacity.
- **Right column:** vertical CAROUSEL of GRADIENTS (and solids too, now). Scrolling instantly applies the
  gradient to the center sample. Circular swatches.

**Carousel "Rolodex" motion (applies to BOTH center types + right gradients):**
- 3D feel: swatches GROW as they approach center, SHRINK/recede as they move away (scale by distance).
- Layered behind (z-order/overlap) so it reads as a Rolodex barrel turning.
- Easing: as an item moves IN toward center its motion EASES OUT; as it moves away (further down) it
  EASES IN. (i.e. slow near center, accelerate away — logarithmic-ish.)
- Opacity fades toward top/bottom.
- Settling: vertical swipes have WEIGHT and "settle" into the center groove smoothly — NOT a hard snap.
  Think a smooth damped settle, not a spring-snap.
- (Implementation note: a `RecyclerView` with a custom `LinearSnapHelper` + an `OnScrollListener` that
  scales/alphas children by |distanceFromCenter|, plus a decelerate/overshoot-free settle. Or a custom
  View. Start simple: snap + scale/alpha by center distance; refine the easing/weight after device look.)

**Gradients regression:** the user feels gradients looked "more handsome" before and now everything looks
solid. Verify the built-in `assets/waveform_styles/*.json` still define gradientStart/End and that the
preset list isn't collapsing them to solids. The new per-visualizer color override intentionally clears
the gradient (solid) — that's only when a swatch is picked; presets should keep their gradients. Put
solids INTO the right gradient carousel alongside gradients.

## B. Visualizer SAVE button → human-readable style file in the pinned folder — DONE (2026-06-20)
- **DONE, compile-green + installed. VISUAL VERIFY.** `WaveformStyleIO.saveToPinned()` writes a
  pretty-printed `<id>.waveform.json` (the EFFECTIVE style = preset + the instance's colour/sensitivity
  overrides, via `applyOverrides().copy()`) into the pinned SAF folder; `loadUserStyles()` scans the
  folder for `*.waveform.json` and the picker auto-appends them to the style list (invisible when none).
  A "Save look to my folder" button in the visualizer drawer triggers it (toasts to pin a folder first if
  unset). Users can hand-edit hex/params in the file and drop it back — it re-loads. This is section B +
  the **visualizer half of section D** (auto-populate from pinned folder).

## C. Transitions drawer overhaul
- ~~**Vertical space:** cover the top bar~~ **DONE (2026-06-20):** `showTransitionPanel` hides
  `editor_top_bar` while open (reflows the drawer up), restores it on close.
- **No dismiss button:** ~~swipe UP to dismiss~~ **DONE:** swipe-up fling on `transition_panel_header`
  dismisses (close X kept as fallback). PULL DOWN for a 2nd/3rd row — partial: GL effects now live in a
  **static second row** below the basics (`populateGlTransitionCards` inserts a 2nd HorizontalScrollView)
  so the 36 cards aren't one endless scroll; the gesture-driven pull-to-reveal is still TODO.
- ~~**Mirror-wipe preview:** square growing~~ **DONE:** `TransitionRenderer.mirrorWipe` now clips only
  height (full width) → a horizontal BAR opening from the centre line, not a centred square.
- ~~**GL shaders NOT nested:**~~ **DONE (2026-06-20):** `setupTransitionPanel` hides the single
  `transition_gl_shader` card and `populateGlTransitionCards()` appends one card per
  `GLTransitionCatalog.entries()` (26) to `transition_card_row`, each with a live A→B preview of that
  shader + tap-to-insert + long-press-drag (localState `"gl:"+id`). `insertTransitionAtPlayhead` /
  `insertTransitionAtTimelineDrop` gained a `glId` param. Compile-green + installed. VISUAL VERIFY.
- ~~The user has "more interesting A and B frames" to use for the preview cards~~ **DONE (2026-06-20):**
  `design-assets/icons/A-frame.png` (jungle panther) + `B-frame.png` (desert camel) copied to
  `res/drawable-nodpi/transition_frame_a|b.png`; `TransitionPreviewCardView.loadSample()` decodes +
  centre-crops them (synthetic blue/orange frames kept only as a decode-fail fallback). Dark↔light +
  jungle↔desert makes every transition obvious. Compile-green + installed. VISUAL VERIFY in the drawer.

## D. Auto-populate drawers from the pinned assets folder (transitions, asset, visualizer) — DONE (2026-06-20)
Cross-cutting, high-delight: scan the user's PINNED folder and append RECOGNIZED assets to the relevant
drawer automatically. All compile-green + installed. VISUAL VERIFY.
- ~~**Transitions drawer:** `.glsl` files~~ **DONE:** `GlExternalTransitions.scanAndRegister()` reads each
  `*.glsl` (sanity-checks for a `transition()` entry point), registers the body with
  `GlTransitionShaderLoader` (new static EXTERNAL registry; `wrap()` keeps the shader's own uniforms for
  externals), and `populateGlTransitionCards()` appends a card (id `user_<file>`). `ExportManager` re-scans
  in its export pre-pass so export resolves them too. ~~Limitation: external shader params default to 0~~
  **RESOLVED (2026-06-21):** `GlExternalTransitions.parseParams()` reads the gl-transitions
  `uniform float NAME; // = VALUE` convention; `GlTransitionShaderLoader` stores them
  (`registerExternalParams`/`getExternalParams`) and `GlTransitionShaderProgram.setParamUniforms` applies
  them when there's no catalog entry — so a dropped-in shader with params renders at its intended defaults.
- ~~**Visualizer drawer:** saved styles~~ **DONE** (section B — `loadUserStyles` of `*.waveform.json`).
- **Asset drawer:** already scans the folder (`AssetScanner`) — parity OK.
- Invisible when the folder has none of a type (no empty-state clutter). Lets users drop in GL transitions
  from external libraries off the internet, or hand-edited visualizer styles.

## Build order (suggested)
1. Visualizer drawer shell (top drop-down replacing the dialog) + move existing controls into the
   3-column layout (icons / type carousel / gradient carousel) + sensitivity on top. (compile + look)
2. Carousel Rolodex motion polish (scale/alpha/easing/settle). (device iterate)
3. Visualizer Save button → pinned folder; auto-populate visualizer drawer from folder. (D, partial)
4. Transitions drawer: cover top bar + swipe-up dismiss + pull-down rows; mirror-wipe bar; GL flat list.
5. Auto-populate transitions drawer from pinned `.glsl`; asset-drawer parity.

## Status (2026-06-20, after several autonomous build bursts)
**DONE + compile-green + installed (all need ON-DEVICE VISUAL VERIFY — see checklist below):** A/B preview
frames; GL transitions as 26 individual cards; mirror-wipe = horizontal bar; transitions drawer covers top
bar + swipe-up dismiss; back-button + mutual-exclusion close the drawers; Visualizer Stage 1 non-blocking
top drawer (live preview); Visualizer Save to pinned folder + auto-load; external GL transitions from
pinned `.glsl`.
**Visualizer Stage 2 — DONE + device-verified (2026-06-21):** `buildVisualizerRolodex()` replaces the old
vertical layout with the 3-column Rolodex: TOP row (tune icon + sensitivity slider + 💾 save); LEFT vertical
icon toggles (justify/mode/mirror); CENTRE vertical carousel of style THUMBNAILS; RIGHT vertical carousel of
gradient/solid swatches. Both carousels are RecyclerViews wired by `setupRolodex()` — `LinearSnapHelper`
snaps to centre, an `OnScrollListener` scales (1.0→0.55) + fades (1.0→0.35) children by |distance-from-centre|
(`scaleRolodexChildren`), and on IDLE the centred item (`rolodexCenteredPosition`) applies live (style →
`putStyle`/invalidate; swatch → colour/gradient override). A faint centre "groove" band marks the selected
slot; carousels start scrolled to the current style/swatch. Compact (~30% height), video visible below.
VERIFIED ON DEVICE: drawer shows 3 columns with the barrel scale effect; scrolling the centre carousel shifts
+ re-scales the thumbnails and snaps.
**Polish DONE + verified (2026-06-21):** `scaleRolodexChildren` now uses a **smoothstep** curve (scale
1.0→0.5, alpha 1.0→0.72) so items stay large near centre and shrink/fade faster toward the ends (the weighted
"settle" feel), plus `translationZ` for layered-behind depth. Added a **grab bar** to the visualizer drawer
(`visualizer_drawer_grab`) with **swipe-up dismiss** (shares the header's fling GestureDetector) — verified
on device (swipe-up closed the drawer). **REMAINING (minor):** tap-video-to-dismiss (deferred — no clean
single player-tap hook without regressing play/pause; the X + header/grab swipe-up all dismiss); optional
drag-to-expand height; drop the title/X entirely per the sketch (kept as fallback for now).
**STILL TODO:** transitions pull-down-for-more-rows gesture.

**Gradient selection — DONE (2026-06-20, compile-green + installed. VISUAL VERIFY).** The drawer now has a
**gradient swatch row** (8 presets — aqua/fire/sunset/purple/mint/pink/mono/gold — + a clear ring), scrollable,
mutually exclusive with the solid-colour swatches. New `WaveformOverlayInstance` gradient override
(`setGradientOverride`, clears solid colour and vice-versa) + `WaveformStyle.withGradientOverride()`, applied
in `applyOverrides` (preview + export parity), persisted in `ProjectStorage` (`gradStart`/`gradEnd`,
backward-compatible). The preset list is relabeled "Style". This restores rich gradients (the user felt they
looked handsomer) and is the functional core of the right-hand "gradient carousel" — the Rolodex MOTION is
the remaining visual polish.

### ON-DEVICE VERIFICATION CHECKLIST (run when next in the Faditor editor with a 2+ clip project)
1. **Transitions tool** → drawer slides from top and **covers the top bar** (AI/pin/close/export hidden);
   **swipe up on the header** dismisses it; **back button** also closes it.
2. Transition cards show the **panther (A) → camel (B)** preview animating; **mirror-wipe** reveals as a
   horizontal BAR (not a growing square).
3. Scroll the row right → **all 26 GL transitions** appear as individual cards (Cross Zoom, Burn, Kaleido,
   …), each tap-to-insert. If a `.glsl` file is in the pinned folder, a `user_<name>` card appears too.
4. **Visualizer** (tap an existing visualizer overlay) → opens the **non-blocking top drawer** (video
   stays visible below); changing colour swatch / sensitivity / style updates the **live** on-canvas
   visualizer. Close via X / swipe-up / back. Opening transitions closes it (and vice-versa).
5. **Save look to my folder** → writes `*.waveform.json` to the pinned folder (toast). Reopen the picker →
   the saved look appears in the list. Open the file → human-readable JSON with editable hex colours.
6. (If a custom GL `.glsl` was added) insert it + **export** → confirm it bakes (params default to 0).
