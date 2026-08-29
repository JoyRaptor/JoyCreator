# SPEC — Quick wins: image-as-overlay in the toolbox, and video thumbnails in the picker

**Written:** 2026-08-29 · **For:** whichever agent frees up first · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim a lane named `SPEC_20260829_QUICK_WINS`. Rule 6
(never run gradle), the WORKING-TREE HAZARD (`git add` as you write) and the **no bare
`git commit`** corollary apply.

Two small, independent, long-owed items. Either can land alone. Neither is architectural,
which is exactly why they keep getting skipped — and why they are still annoying JoyRaptor.

---

## 1. Image as overlay belongs in the toolbox

JoyRaptor: *"Image add (as overlay) should be a toolbox button. It's very common, and image as
a clip should be less prominent."*

He is right and this is a reorder, not a feature. Adding a picture on top of what is
already there is the common documentary action; inserting a picture as its own segment of
the spine is the rare one. Today the rare one is easier to reach.

**What to do**

- Promote **Image (overlay)** to a top-level toolbox button, beside Add / Transition /
  Captions / Visualizer.
- Demote **image as clip** to a submenu or a long-press on the same button.
- Both paths already exist — find them and re-route the entry points. **Do not write a
  second image-insertion path.** Two ways to do one thing is the trap that caused three
  separate bugs on 2026-08-28.
- The long-press needs a discoverable hint (a small chevron, or a one-time tip). A hidden
  gesture with no affordance is the same as a deleted feature.

**Acceptance**

1. Screenshot the toolbox before and after.
2. Add an image as an overlay in ≤2 taps from the editor.
3. Add an image as a clip — still possible, and screenshot how you got there.
4. Both produce exactly what they did before. Compare a rendered frame each way.

---

## 2. Video thumbnails in the file picker

From `HANDOFF_20260828.md`: *"No video thumbnails in the file picker. Images preview,
videos do not, so choosing a clip is blind."*

Choosing between six clips named `FadRec_20260612_165216` and friends, with no pictures,
is guessing. JoyRaptor has 146 videos on the Note 9.

**What to do**

- Extract one frame per video for the picker grid. Use `MediaMetadataRetriever`
  `getScaledFrameAtTime` where available — it decodes at the size you ask for instead of a
  full frame you then throw away.
- Grab from **~10% into the file, not 0**. Frame zero of a real recording is very often
  black, a lens cap, or a hand reaching for the button — a grid of black squares is no
  better than no thumbnails.
- **Cache to disk** via `com.fadcam.ui.faditor.util.DurableCache` (`dir(context,
  "vidthumb")`), keyed on uri hash + size. Re-extracting on every scroll is what makes a
  picker feel broken.
- Extract on a **bounded background pool** (2–3 threads), newest-first, cancelling
  extractions for rows that scrolled away. Never on the main thread.
- Placeholder while loading, and a distinct **generic-video icon on failure**. A permanent
  spinner on a corrupt file reads as a hung app.

**Where to look:** `assetbrowser/AssetBrowserPanel.java` and whatever the editor uses to
pick a clip. Images already preview, so there is a working thumbnail path — **extend it
rather than building a parallel one.**

**Acceptance**

1. `adb devices` pasted.
2. Screenshot the picker with ≥6 videos, thumbnails visible.
3. Scroll a 100+ video folder fast, twice. Second pass is instant (cache hit). Report
   scroll smoothness honestly — say if it stutters.
4. A corrupt/zero-byte file shows the fallback icon, does not hang, does not crash.
5. Report cache size after 100 videos. If it is unbounded, add a cap and say what you chose.

---

## 3. Traps

- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
- `getSelectedClip()` silently returns `getClip(0)` when nothing is selected — on a music
  project that is the auto-blank black spacer. Use `clipUnderPlayhead()`.
- A value written and never read caused three bugs on 2026-08-28. If thumbnails do not
  appear, check that the adapter READS the cache before assuming extraction failed.
- `build.log` is UTF-16. Read its last line with `iconv -f UTF-16LE`, and check its **date**
  as well as its time — a twelve-hour misread happened on 2026-08-29.
