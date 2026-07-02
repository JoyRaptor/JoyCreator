# User feedback 2026-07-02 (midday, relayed via JoyRaptor session while Basil asleep)

## A. BUG (active hand-test, Note 9): layer rows block scrubbing + selection
User's exact symptoms, testing the M10/M6 rows on device:
> "The two layers that have the options buttons (solo, lock, etc.) — I can't actually scrub through the
> timeline on them, so that's probably why I also can't select them. I can slide left/right in most of the
> project, including the overlays, and I can select the overlays. But I cannot select Layer 1 or the audio
> extracted from the video below it, nor can I scrub over them. I don't know if I can resize them by
> grabbing the trim edges — it doesn't look like I can click on them. Something is blocking."

Triage (orchestrator): consistent with the M6/M7 row-touch region CONSUMING all touches inside the row band
(M7 design: non-item touches fall back to "M6 scroll-only, touch consumed"). Horizontal drags over row
bodies never reach the timeline scrub path, and taps on these rows' items don't arm selection. This is the
same "surface overlap" root cause recorded in handoff (b760b40) as agreed-fix #2 (fix #1, clip-tap
seek-to-start, landed in 9edad8a). Expected fix shape: within the row band, taps hit-test items first
(select/trim-handles), horizontal drags NOT on an item (or not armed as an M7/M10 gesture) pass through to
timeline scrubbing; vertical drags keep scrolling the row region; long-press keeps M10 pickup.
STATUS: fix agent launched from the JoyRaptor session ~13:00. See handoff for outcome.

## B. FEATURE SCENARIOS (user's words condensed; treat as the spec of record)

### B1. Vector-shape masking on a layer
Scenario: screen recording (webcam bubble + text box) with ugly borders; user overlays a beautiful looping
animation (set to loop for a chosen duration) as a near-top layer, then cuts HOLES in that overlay to reveal
what's underneath: drag a little box over the webcam area, a helper slider rounds its corners to taste;
knock out a second window over the text-box area. Requirements:
- Multiple vector shapes per layer; each resizable, corner-roundness slider (sharp↔round).
- Each shape is additive or subtractive ("mask out part of my mask" to keep a notch).
- Implies: loop-for-duration support on overlay items.

### B2. Color key (chroma key)
- Select a clip → "add a key": key out black, green, or DRAG A SWATCH to sample a specific color from the
  video itself. "Really intuitive and minimal UI."
- Tool drawer: tolerance, fuzziness, offset controls for the key.

### B3. Video-as-alpha (track matte) with pairing UI
- Click a video → "set alpha" → tools → "video alpha" → declare it the ALPHA or the RECIPIENT → select the
  second video. Afterward, clicking either clip shows a DOTTED LINE connecting the corners of the two clips
  on the timeline. Both remain independently movable/trimmable; wherever one overlaps the other in time, it
  acts as the other's alpha mask.

## C. Orchestrator architecture read (for the next planning pass — do NOT build blind)
All three are one family: per-item compositing spec. Recommend ONE additive model extension
(`CompositingSpec { masks[], chromaKey{color, tolerance, fuzziness, offset}, matteRef{peerId, role} }` on
TimedItem/overlay items) evaluated by BOTH the preview compositor and export effects — spec once, not three
bolt-ons. Sequencing against PLAN_LAYERS_V2:
1. **Masks on BITMAP layers (image/sticker/text)** — preview = Canvas clipPath/PorterDuff on the existing
   View stack (M-COMP-1 architecture), export = apply while drawing in CompositeExportOverlay. LOW risk,
   buildable soon after M-EXPORT-1. Rounded-rect shape+slider UI mirrors existing on-canvas manipulation.
2. **Masks on VIDEO layers + chroma key** — require the GL wave (M-COMP-2 preview compositor +
   M-EXPORT-2-style custom GlEffects; shader precedent exists: GlTransitionExportEffect samples a second
   video already). Key preview on the master needs GL in the preview path → lands WITH M-COMP-2, not before.
   Swatch-pick = sample pixel from extracted playhead frame (cheap UI, can be built early).
3. **Track matte (B3)** — hardest; on top of M-COMP-2. Decoder budget (Note 9 = ~2 hw decoders) means live
   matte preview may exceed budget (master + overlay + matte = 3) → plan the fallback: STILL-frame matte in
   preview or PRE-BAKED matte intermediate (bake may be the primary mid-range strategy). Export via second
   EditedMediaItemSequence + custom GlEffect sampling both textures. Pairing/dotted-line UI + persistent
   matteRef by item id is straightforward timeline-renderer work. NOTE: mobile CapCut does not do arbitrary
   video track-mattes — this is a "surpass" feature (PLAN Part 9).
