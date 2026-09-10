# SPEC J — Cross-lane wiring & staleness audit — FINAL REPORT

**Build: GREEN.** `assembleDefaultDebug` = BUILD SUCCESSFUL (re-run after the last fix). All 8 required JVM harnesses green via `tools/jvm-harness/run-specj-suite.sh`: run-mesh 66/66, run-rotation, run-pinbudget, run-preview-parity, run-frame-parity, run-persist-lint, run-key 26/26, run-mask 47/47. All changes staged, nothing committed (per rules). `run-matte.sh` / `run-copy-lint.sh` are red for unrelated, pre-existing reasons (left alone per `_RULES_READ_FIRST.md`).

## The six questions

### 1. Does every drag end with "something changed"? — YES (FIXED)
Per-host, per-write verdict (all four hosts, every write method):

| Host | writeQuad | writeTranslate | writeSimilarity | writeRotation |
|---|---|---|---|---|
| `CornerPinTransformHost` | already ended in `onChanged` (L265) | **FIXED** — now ends L277 | **FIXED** — L309 | **FIXED** — L315 |
| `TextAffineTransformHost` | n/a (text has no quad) | **FIXED** — L190 | **FIXED** — L201 | **FIXED** — L207 |
| `PipAffineTransformHost` | already ended (L157) | **FIXED** — L166 | **FIXED** — L177 | **FIXED** — L183 |
| `SpineTransformHost` | ends in `bridge.onSpineTransformChanged()` (L169/183/189) — no change needed: the bridge IS the changed-notification (FaditorEditorActivity.java:24595-24602 → `requestGlPreviewResync()` + `transformOverlay.refresh()`, the same coalesced resync an image gesture asks for) |

**Before:** a host write updated the model but skipped the callback, so the GL preview kept the old frame until an unrelated event resynced — JoyRaptor's staleness reports. **After:** every write ends in the coalesced resync contract. javap-verified in the compiled classes.

### 2. Can you tap-select a PiP/spine overlay, and drag it on the first touch? — YES (FIXED)
All 41 mentions of the three transform ids audited (`transformItemId`, `transformPipClipId`, `transformSpineClipId`); every read is now either set-and-clear symmetric (enter/exit at 24362/24413/24447/24570/24653), an any-of-three gate (tick 10014, resync 25608, dispatch 31455), or a per-kind guard (24342/24351/24402/25111-25126). Two first-pass misses fixed:
- `selectAt` mid-gesture guard (FaditorEditorActivity.java:24976-25009) — **before:** a tap-select of a PiP/spine fell through to legacy layer selection, and dragging a keyframed PiP collapsed its X/Y keyframe tracks into static values. **after:** PiP/spine count as "overlay selected", keyframed tracks survive.
- `handoffGesture` (same region) — **before:** the first tap-drag on a PiP/spine was a dead drag. **after:** one gesture selects AND drags.

### 3. Do the on-screen handles show the frame under the playhead? — YES (FIXED)
The tick that can move a transform-mode object is the one playhead refresh (FaditorEditorActivity.java:10010-10014, gated on all three ids). The hosts were reading the raw clamped `lastPlayheadAbsoluteMs` while overlay surfaces render at the corrected clock — **fixed**: all three host clock lambdas now `() -> overlayClockMs(lastPlayheadAbsoluteMs)` (24387/24426/24460), `restoreOverlayTransform` (23800), `overlayVideoLayer` playhead refresh (25922), PiP handle-write refresh (24553). `syncAdjustmentPreview` corrects at entry; `refreshPipAfterHandleWrite` switched from up-to-3 synchronous plan rebuilds per touch event to the coalesced `requestGlPreviewResync()`. **Before:** handles lagged/rotated stale past the master clip end. **After:** handles render at the frame under the playhead.

### 4. Are all rotation controls dials, not sliders? — YES, ALL SIX+1 (FIXED — last one found late, see note)
- The five SPEC F object rows render `RotationDialView` (ObjectMenuSheet:818/847, PipDrawerTabs:282-297).
- **The drawer's LIVE mask tab still had a 0..360 rotation slider** (`PipDrawerTabs.java:746`, `faditor_mask_rotate` writing `cur.rotationDeg`) — found in the final sweep, after the first report. MaskAnimator deltas can store any winding; a windowed bar clamps the stored angle before the finger moves and can neither show nor author a turn past 360. **FIXED:** replaced with `maskRotateRow` — `RotationDialView` (raw degrees, countable rings), ‹◇› nudge steppers, and a tap-to-type prompt (`promptMaskAngle`) accepting −45/720-style winding. javap-verified in the compiled class.
- The `MaskKeyPanel` copy of the same row (line 138) was converted to a dial earlier; **honest note:** that panel's opener (`showMaskDialog`) currently has no caller — it was superseded by the drawer tab above. Fix kept (correct if the panel is ever re-wired); the reachable surface is now also compliant.

### 5. Double-tap routing — VERIFIED, no changes needed
Image → image drawer (via `showTextOverlayEditor` isImage routing, ~30355), text → text drawer, PiP → `showPipDrawerForObject`, spine → documented no-op. `exitTransformMode` (24649-24655) clears all three ids, `setHost(null)`, `setOnDoubleTap(null)`, restores `previewHandlesOverlay` — nothing left behind.

### 6. Mirror coverage — VERIFIED, no changes needed
All four image paths mirror: `CornerPinImageView` (`usesMatrix()` includes mirror flags, set at TextOverlayLayer:1231), GL `buildPip` (1518-1519), export `ImageOverlayDraw.draw` (286), GL blend (`ImageOverlayFrameOverlay:81`) + canvas export (`CompositeExportOverlay:775`) route through it. The mesh stamp ignores mirroring — a known hole **owned by SPEC H**; boundary confirmed: preview and export agree unmirrored, so they do not disagree.

## Device smoke test (Note 9, serial <note9-serial>, sandbox project "BundlingFontTest")
- Tap-select an unselected overlay: **PASS** (handles appear on first tap)
- Drag selected overlay, handles follow: **PASS**
- One-gesture tap-drag (select + continuous move, no stutter): **PASS**
- Handles exactly on the rotated quad at a paused frame: **PASS**
- Play/pause, preview re-render after idle: **PASS**
- No crashes in logcat during the whole session.
- Mask drawer on device: not surfaced via double-tap after three attempts on this build — the dial is **code + build + javap verified**, not screen-verified.
- Device input note (for the next lane): the display runs in an override mode — logical 1080×2220 @315dpi despite `wm size` reporting 1440×2960. All `input tap/swipe` coords must be logical; `screencap` PNGs are already logical pixels. FadCam's ambient "zzZ" screen renders with the panel off and reads as a live app; wake the panel before judging touches.

## Reported only (pre-existing, not changed, per scope)
- Uncorrected-clock reads at FaditorEditorActivity lines 4739 (play/pause edge), 13774-13799 (setData rebind), 25721 (sprite drawer), 26571, 29052 — edge cases outside this audit's fix bar.
- Mesh stamp mirror hole (SPEC H's).

**Files changed (all staged):** `FaditorEditorActivity.java`, `MaskKeyPanel.java`, `CornerPinTransformHost.java`, `TextAffineTransformHost.java`, `PipAffineTransformHost.java`, `PipDrawerTabs.java` + new `tools/jvm-harness/run-specj-suite.sh` (8-harness suite runner).
