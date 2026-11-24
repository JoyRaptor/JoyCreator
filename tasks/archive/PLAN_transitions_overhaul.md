# Transitions Overhaul — investigation + plan (2026-06-20)

User verdict: "I haven't really got transitions to work — none of them." + resize snaps back + green
bars (should be blue) + no obvious remove. Vision: **animated, scrubbable A→B mini-previews** in the
drawer (same renderer driving the LARGE live preview — two birds), a **"+" badge** on parameter-rich
transitions (wipes), runs smoothly on a Note 20 Ultra.

## Current architecture (mapped this session)
- **Model:** `Transition{type, durationMs(clamped 100..2000), clipIndex(=seam), glTransitionId,
  paramOverrides}`. Types: fades, CROSS_DISSOLVE, WIPE_*, PUSH_*, RADIAL, LINEAR_MIRROR_WIPE, GLITCH,
  TV_CHANNEL, GL_SHADER.
- **Timeline band + handles:** `EditorTimelineView` draws the transition band at the seam + two trim
  handles. Resize → `onTransitionDurationChanged/Finished` → `setTransitionDuration` + save (persists OK).
- **Live preview (playback):** `FaditorEditorActivity.checkPlaybackProgress` detects when the playhead
  enters the **last `durationMs` of the outgoing clip** before a seam-with-transition → sets
  `transitionPlaybackActive` → `renderTransitionPreview(progress)` each tick →
  `TransitionPreviewOverlayView.renderColor/renderBitmap` (Canvas) OR `GlTransitionPreviewView.render`
  (GL shaders). Decodes the incoming frame via `decodeTransitionFrame` (MediaMetadataRetriever).
- **Scrub preview:** `updateScrubTransitionPreview` mirrors the same window detection from the seek pos.
- **Export:** `ExportManager` composites transitions (AlphaScale + overlays + GL effects). Separate path.

`TransitionPreviewOverlayView` itself is COMPLETE and correct (flips VISIBLE, draws wipe/push/radial/
mirror/glitch/tv/dissolve). So the rendering machinery exists — failures are upstream.

## Likely root causes (why "none work")
1. **Tiny, blink-and-miss window.** The preview only fires during PLAYBACK in the final ≤2s of a clip
   before a seam. If you add a transition and don't play *through* that exact seam, you see nothing.
   The user's mental model (rightly) is the **drawer card should demo it** and the seam should be
   **scrubbable** — neither is the current behavior.
2. **`decodeTransitionFrame` returning null** for the incoming clip → the bitmap transitions
   (wipe/push/dissolve/etc.) draw nothing (only the color fades would show). MediaMetadataRetriever is
   finicky with content://, remuxed, and fragmented sources. NEEDS a logcat repro to confirm.
3. **Resize felt like snap-back** — FIXED this session: `transitionDurationFromDragX` used `abs()`, so
   any drag only *grew* the duration and you could never shrink; once it hit the 2s cap it looked like
   it reverted. Now directional (outward grows, inward shrinks).
4. **Green handles** — FIXED: transition resize grips are now BLUE (vs green clean-cut clip edges).
5. **Removal** — `onTransitionDeleted`→`deleteTransition` + an inspector delete button EXIST, but the
   user couldn't find them. Make removal obvious: a clear "Remove" in the inspector AND drag-the-band-
   off-the-seam to delete.

## Target design (the user's vision — phased)
**Phase A — make the core transition actually visible + scrubbable (highest priority).**
- Unify on ONE renderer that takes (frameA, frameB, transition, progress) → composited bitmap. The
  existing `TransitionPreviewOverlayView` draw code is exactly this; extract it into a reusable
  `TransitionRenderer.compose(canvas/bitmap, frameA, frameB, transition, progress)`.
- Make the **seam scrubbable**: when the playhead/scrub is anywhere in the transition window, drive the
  composed frame by progress — already half-built in `updateScrubTransitionPreview`; verify it shows on
  scrub (not just play) and fix the decode path (cache both A & B frames; reuse the player's current
  frame for A when possible to avoid a second decode).
- Confirm `decodeTransitionFrame` works for the user's actual sources (logcat) — if it fails, fall back
  to the player's live TextureView bitmap for A and a MediaMetadataRetriever (path form) for B.

**Phase B — animated, scrubbable drawer mini-previews.**
- Each drawer card runs a tiny looping A→B animation using two bundled thumbnail images + the SAME
  `TransitionRenderer`. Cheap (small bitmaps, ~20fps, pause offscreen). A **"+" badge** corner-marks
  transitions with `paramOverrides`/direction options (wipes, push, GL with params).
- Drag a card onto a seam to add (already supported); the card's live demo sells it.

**Phase C — parity + polish.** Make sure preview ≈ export for each type; raise/relax the 2s cap if the
user wants longer transitions; expose wipe/push DIRECTION (the "+") in the inspector.

## ★ ROOT CAUSE FOUND + FIXED (2026-06-20, via logcat repro)
**Transitions were being stored on an invalid seam, so they never rendered/exported.** Logcat during a
scrub showed `seamTrans=null` at every position despite `totalTransitions=1`; the project.json had the
transition at **`clipIndex: 1` in a 2-clip project**. The valid seam range is `[0, clipCount-2]`
(clipIndex = LEFT clip of the seam), so `clipIndex=1` points to a seam *after the last clip* →
`getTransitionAtSeam` never finds it → renders nothing, and the seam-cross "snags".
- **Off-by-one in placement:** `resolveTransitionSeamAtPlayhead` and `insertTransitionAtTimelineDrop`
  returned the RIGHT-clip / insert index (e.g. `seg+1`), not the LEFT-clip index. FIXED both to the
  left-clip convention.
- **Permissive clamp:** `Timeline.addTransition` clamped to `[0, clipCount-1]` (allowed the bogus
  clipCount-1 seam). FIXED to `[0, clipCount-2]`. Because project LOAD routes through `addTransition`,
  this **self-heals existing orphaned transitions** (the user's clipIndex=1 → 0) on next open.
- Result: a transition added between two clips now lands on a real seam and renders on play/scrub.

## Done this session
- ✅ Blue resize handles. ✅ Directional resize (no more false snap-back). ✅ Minimap playhead off the
  minimap. (Plus the GL-effect picker, duration picker, type-exchange from the earlier pass.)
- ✅ **Seam off-by-one ROOT CAUSE fixed** (above) — transitions now land on real seams + self-heal.
- ✅ **Phase B — animated drawer previews (compile-green, VISUAL VERIFY).** New
  `player/TransitionRenderer` (stateless `compose(canvas, A, B, transition, progress, w, h)` for ALL
  types incl. fades/dissolve/wipe/push/radial/mirror/glitch/tv + a zoom-dissolve stand-in for GL) and
  `player/TransitionPreviewCardView` (loops a 1.5s A→B demo using two programmatic sample frames; pauses
  off-screen). `setupTransitionDrag` swaps each drawer card's static swatch for an animated preview of
  THAT transition; the preview is non-interactive so tap-to-add + long-press-to-drag still work. A "+"
  **badge** marks flexible transitions (wipes/push/GL). **VISUAL VERIFY:** open the transitions drawer →
  each card should animate its effect; "+" on wipes/push/GL.
  - TODO (Phase A wiring): point the LARGE live/scrub preview at `TransitionRenderer.compose` too (two
    birds) — currently the live preview still uses `TransitionPreviewOverlayView` (which draws only B
    over the held player frame). Unifying gives true A→B on scrub. Per-card scrub was dropped (it
    conflicts with tap/drag); the loop conveys the effect.

## Needs the interactive loop
The remaining transition bugs are reproduction-dependent (which source types fail to decode, exact
gesture for resize/remove). Most efficient with the user playing through a seam while I read logcat.
