# SPEC — Preview performance: stop re-rastering what has not changed

**Written:** 2026-08-29 · **For:** an external agent (suggested: the AUDIO_SYNC_TRUTH lane,
in a FRESH session — this is a different subsystem and old context buys nothing) ·
**Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim a lane named `SPEC_20260829_PREVIEW_PERF`. Rule 6
(never run gradle — save and read `build.log`), the WORKING-TREE HAZARD (`git add` each file
as you write it) and the **no bare `git commit`** corollary are not optional.

**Read `tasks/FINDING_20260829_GL_ANIMATED_GAP.md` before you start.** It is the survey this
spec comes from and it names the exact lines.

**You are an implementer, not a reporter.** Every performance number here must be measured
on a device with `adb devices` pasted beside it. On 2026-08-28 an agent invented timings for
an unplugged phone; on 2026-08-29 another misread a build timestamp by twelve hours. **A
number you did not measure is worse than no number at all.**

---

## 1. Why this exists

JoyRaptor: *"we got close but I think we can make substantial performance improvements across
the board, export, live play, etc."* Export already went 15 m 00 s → 1 m 38 s. This is the
preview side, and it has two defects with the same shape: **work repeated every frame that
only needed doing once.**

There is a bonus: fixing the second one also closes the last real hole in the
Photoshop-parity stack — an animated caption or title that sits under a blended image
currently vanishes from the composite.

---

## 2. Defect one — the cache key includes the clock (fix this first)

`FxLivePreviewController.buildBelowBlendBitmap()` (~line 754) builds a full-frame bitmap of
the static text/sprite that sits below a blending image. It caches it. The key is:

```java
cachedBelowW == videoW && cachedBelowH == videoH
    && cachedBelowPlayhead == playheadMs        // <-- this
    && cachedBelowIds.equals(visibleIds)
    && cachedBelowProjectDuration == timeline.getTotalDurationMs()
```

`playheadMs` changes every tick, so the cache **never hits during playback**. Every tick
allocates a fresh `ARGB_8888` bitmap at video resolution (~8.3 MB at 1080p), clears it, and
re-rasterises every glyph — about 20 times a second, to produce a pixel-identical result.

**Fix:** key on the CONTENT, not the time. Build a cheap content signature from what the
bitmap actually depends on and compare that:

- each item id, plus
- the **rendered string** (`TextBoxRenderer.textAt(item, playheadMs, totalDuration)` — this
  is why the playhead crept into the key: a countdown or timecode text genuinely changes
  with time, and that is the ONLY reason), plus
- the pose actually used (`animatedOpacity`, `animatedSizeFraction`, `animatedCenterX/Y`,
  `animatedRotation`), plus the style fields the rasteriser reads, plus `videoW/videoH`.

Concatenate into a `long` or `String` hash. Static text produces the same signature every
tick and rasters ONCE for the whole project. Time-dependent text re-rasters exactly when its
string changes, which is what was wanted all along.

**Do not simply delete `playheadMs` from the key.** That would freeze a countdown at its
first value — a correctness bug traded for a speed win, and a subtle one nobody would notice
for weeks.

---

## 3. Defect two — animated content is dropped from the composite

Same method, ~line 770:

```java
if (tto.isAnimated()) continue;                                                // text
if (!sso.getKeyframes().isEmpty() || sso.getFrameTrack().size() > 1) continue;  // sprite
```

The reason is recorded honestly at line 748: a per-frame full-frame raster measured ~17 ms on
the Note 9 against a 16.6 ms budget.

**The measurement is right and the conclusion does not follow.** The four properties that
make an item "animated" here are read at lines 797–820: `animatedOpacity`,
`animatedSizeFraction`, `animatedCenterX/Y`, `animatedRotation`.

**Every one of those is a quad transform, not a pixel change.** And
`FxPreviewTextureView.Pip` **already carries exactly these fields** — `cx, cy, halfW, halfH,
rotationDeg, alpha` — and already uploads them per frame for PiPs. The 17 ms was measured
for the wrong strategy.

### What to build

Raster the item's GLYPHS once, at its **authored** size and zero rotation, into its own
texture. Then per frame, position/scale/rotate/fade the quad.

1. **A texture cache** — suggested `compositor/OverlayTextureCache.java` (NEW). Keyed on
   everything that changes the PIXELS and nothing that changes the POSE:
   - **in the key:** item id, rendered string, font key, colours, outline/shadow, pill,
     the authored (unanimated) size, and the raster resolution.
   - **NOT in the key:** `centerX`, `centerY`, `rotation`, `opacity`, `sizeFraction`.
     If any of those five appear in your key, you have rebuilt the bug this spec exists to
     remove. **This is the single most important line in the spec.**
   - LRU, bounded — suggest 16 entries or ~64 MB, whichever binds first. Evict on
     project close. An unbounded texture cache on a 200-title project is an OOM.
2. **Raster at the authored size, not the animated size.** A title that scales 0.2→2.0 must
   raster once at its authored size and be scaled by the quad. Rastering at the animated
   size re-rasters every frame — the original bug wearing a hat. Raster at a modest
   supersample (1.5–2×) so a scale-up does not go soft, and say in your report what factor
   you chose.
3. **Feed the quad per frame** through the existing `Pip` path. Do not invent a second
   transform pipeline; if you need a field `Pip` does not have, add it there.
4. **Remove the two `continue` guards** so animated items join the composite.
5. **Keep the Canvas path as the fallback**, behind one predicate, for anything the texture
   cache cannot serve. One predicate, one place — the "two answers to one question" trap
   caused three bugs on 2026-08-28.

### Where this legitimately stops

An item whose PIXELS change every frame — a per-character typewriter reveal, a per-word
karaoke highlight, an animated gradient fill — still needs a per-frame raster and is still
subject to the 17 ms wall. **That is a real limit; document it rather than pretending it
away.** Note in your report exactly which animation types now ride the texture path and
which do not.

---

## 4. Files

**Claim exactly these:**

```
app/src/main/java/com/fadcam/ui/faditor/compositor/OverlayTextureCache.java   (NEW)
app/src/main/java/com/fadcam/ui/faditor/compositor/FxLivePreviewController.java
app/src/main/java/com/fadcam/ui/faditor/compositor/FxPreviewTextureView.java
app/src/main/java/com/fadcam/ui/faditor/compositor/LayerPreviewController.java
```

**`FaditorEditorActivity.java` is NOT on this list and you do not need it.** That file is
contended by two other lanes. If you believe you need it, post on `LANES.md` and wait rather
than taking it.

**Out of scope:** the export path (it composites offline, where a per-frame raster is a
throughput cost, not a dropped frame — a separate question), the audio graph, and the
visualizer. On the visualizer specifically: `FINDING_20260829` speculates it would be cheap
in GL and **explicitly marks that as unmeasured inference**. Do not act on it.

---

## 5. Acceptance — measured, or it did not happen

1. **Build.** `build.log` last line `BUILD SUCCESSFUL`, mtime newer than your last edit.
   Paste both, and paste the **date** — a twelve-hour misread happened on 2026-08-29.
2. **Device.** `adb devices` pasted. No device → report §5.1 and §5.6 only, and say plainly
   that the rest is owed. That is an acceptable outcome.
3. **Defect one, measured.** Instrument `buildBelowBlendBitmap` with a raster counter. On a
   project with static text below a blended image, play 30 s. Report rasters BEFORE and
   AFTER. **Expect ~600 before and 1 after.** If "after" is not in single digits, the
   signature still contains something time-varying — find it.
4. **Countdown still counts.** A text item with time-dependent content must still update.
   Screenshot it at two different playhead positions showing two different strings. **This
   is the check that catches the tempting wrong fix in §2.**
5. **Defect two, measured.** A project with an animated title under a blended image.
   BEFORE: the title is missing from the blend. AFTER: it is composited at its real z and
   animates. Two screenshots. Plus frame timing (`adb shell dumpsys gfxinfo com.fadcam.beta
   framestats`, or a `SystemClock` log around the composite) before and after. **Report the
   number even if it got worse** — a regression found honestly is a useful result.
6. **No leak.** Play a 3-minute project with several animated overlays. Report heap before
   and after (`adb shell dumpsys meminfo com.fadcam.beta`). The texture cache must be bounded;
   a monotonic climb means eviction is not working.
7. **Preview still matches export.** Export 15 s containing an animated overlay under a
   blend and compare a frame against the preview at the same time. They must agree.
   **If they now disagree, this spec has made things worse and must not land.**

---

## 6. Traps carried forward

- A value written and never read caused three bugs on 2026-08-28. If your texture cache
  never hits, check that something READS the signature before assuming your key is wrong.
- A child pushed outside its parent is clipped by it. If an overshoot animation gets cropped
  at the quad edge, the arithmetic is probably right and the bounds are the problem.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
- The export runs in its own process (`com.fadcam.beta:export`) and survives app restarts.
  `am force-stop` before timing anything.
