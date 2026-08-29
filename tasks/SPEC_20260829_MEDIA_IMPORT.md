# SPEC — Media import: make the app's own browser the picker

**Written:** 2026-08-29 · **For:** an external agent · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim `SPEC_20260829_MEDIA_IMPORT`. Rule 6 (never run
gradle), the WORKING-TREE HAZARD (`git add` as you write) and the **no bare `git commit`**
corollary apply.

**This supersedes §2 of `SPEC_20260829_QUICK_WINS`.** That spec asked for video thumbnails
in `AssetBrowserAdapter`. Correct target, wrong surface — see §1.

---

## 1. The correction that makes this spec exist

JoyRaptor: *"when I go to add a video, it doesn't use the internal file chooser that we have
kind of built out, but it uses the default Android file explorer, which is often fine, but
in this case it isn't."*

He is exactly right, and it is worse than "not used":

| Fact | Evidence |
|---|---|
| Every video-add path goes to the SYSTEM picker | `FaditorEditorActivity` lines 2502, 3649, 35964, 35987 — all `videoPickerLauncher.launch(openDocumentIntent("video/*"))`, i.e. `ACTION_OPEN_DOCUMENT` |
| Images too | `imagePickerLauncher` on the same pattern |
| **`AssetBrowserPanel` is constructed by NOTHING** | a repo-wide grep finds references only from itself and `AssetItem` |

So the app has a media browser — grid, scanner, duration probing, a Glide-backed adapter —
that **no code path can reach.** Adding thumbnails to it, as QUICK_WINS asked, would have
improved a screen JoyRaptor cannot open. Verify the orphan claim yourself before you start; if
something does construct it, say so and adjust.

That is why the drawer "didn't work quite right" and got abandoned: it was built before
layers existed, never finished, and quietly fell out of the app.

---

## 2. What to build

### 2.1 The app's own picker, as the default

Tapping **Add → Video** (or Image) opens the internal browser, not the system explorer.

It must show, per item: **a thumbnail**, the filename, the duration, and the date. That is
the whole reason JoyRaptor cannot choose — `FadRec_20260612_165216` and five siblings, with no
pictures, is guesswork among 146 files.

**Keep a "Browse files…" row that opens the system picker.** It is the escape hatch for
anything outside the scanned folders (Drive, an SD card, a download). Removing it would
trade one frustration for another, and `ACTION_OPEN_DOCUMENT` is also the only way to get
a persistable permission for a file outside the app's own storage — see §2.4.

### 2.2 Video thumbnails (the part QUICK_WINS got right)

`AssetBrowserAdapter:213` loads video URIs through Glide, which cannot pull a frame from a
SAF `content://` on these devices. Replace that branch:

- `MediaMetadataRetriever.getScaledFrameAtTime` where available — it decodes at the size
  you ask for instead of a full frame you discard.
- Grab at **~10% in, not 0**. Frame zero of a real recording is usually black, a lens cap,
  or a hand reaching for the button. A grid of black squares is no better than no grid.
- Cache to `DurableCache.dir(ctx, "vidthumb")`, keyed on **uri + file size + thumb px** so a
  replaced file invalidates itself.
- A bounded pool (2–3 threads), newest-first, cancelling extractions for rows that scrolled
  away. Never on the main thread.
- Placeholder while loading; a distinct **generic-video icon on failure**. A permanent
  spinner on a corrupt file reads as a hung app.
- Cap the cache (suggest ≤50 MB or 500 files) and **say in your report what you chose and
  what it measured at after 100 videos.**

`AssetScanner` already probes durations on a pool with its own cache — extend that pattern,
do not build a second one beside it.

### 2.3 Multi-select

The system picker allows selecting several files. The internal one must too, or this is a
downgrade for anyone assembling a sequence. Selected items get a numbered badge, and they
are added **in selection order**.

### 2.4 Permissions — the trap that will bite

`ACTION_GET_CONTENT` URIs **cannot be persisted**; the code already knows this
(`FaditorEditorActivity:21661`) and uses `ACTION_OPEN_DOCUMENT` so it can call
`takePersistableUriPermission`. Your browser must end up holding an equally durable
reference for every file it hands back, or projects will open with dead media after a
reboot — which is the exact class of bug the relink catalog exists to paper over.

For files the app scanned in its own directories this is straightforward. For anything
else, route through the system picker and take the persistable permission. **State plainly
in your report which categories of file end up with a durable reference and which do not.**

### 2.5 Do not resurrect the drawer as it was

`AssetBrowserPanel` was a drag-things-into-the-project drawer, built before layers existed.
JoyRaptor stopped using it: *"too fiddly and didn't work quite right."*

**Build a picker, not a drawer.** Open it, choose, it closes, the media is added. Drag-and-
drop into a timeline that now has layers is a genuinely harder interaction and it is not
what is being asked for here. Reuse the panel's scanner, adapter and item model; do not
inherit its interaction design.

---

## 3. Files

```
app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetBrowserAdapter.java
app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetBrowserPanel.java
app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetScanner.java        (extend)
app/src/main/java/com/fadcam/ui/faditor/assetbrowser/VideoThumbnailCache.java (NEW)
app/src/main/java/com/fadcam/ui/faditor/AddAssetBottomSheet.java
app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java   (picker launch sites ONLY)
```

⚠️ **`FaditorEditorActivity` is contended.** Check `LANES.md` and take it only when the
holders read IDLE. Everything in §2.2 is fully disjoint — **land that first**, so the work
is banked whatever happens with the contended file.

**Out of scope, deliberately:** project bundling / media consolidation (its own spec —
copying a project's media and fonts into one folder so projects survive a reinstall), and
repairing the relink catalog. Both are real and both are next; doing them here would make
this unreviewable.

---

## 4. Acceptance

1. **Build.** `build.log` last line `BUILD SUCCESSFUL`, mtime **and date** newer than your
   last edit. Paste both.
2. **Device.** `adb devices` pasted. None attached → report §4.1 and stop, honestly.
3. **Read `tasks/DEVICE_CONTROL_RUNBOOK.md` before attempting any UI navigation.**
   `uiautomator dump` returns a null root on these phones; the loop is
   screenshot → read pixels → tap by coordinate → screenshot. An agent that skipped this on
   2026-08-29 reported 41/41 BLOCKED because it could not get past the splash screen.
4. Add → Video opens the internal browser. Screenshot, ≥6 videos, thumbnails visible.
5. Choosing one adds exactly the clip it depicted. Screenshot the timeline after.
6. Scroll a 100+ video folder fast, twice. Second pass instant. **Report scroll smoothness
   honestly — say if it stutters.**
7. A corrupt or zero-byte file shows the fallback icon: no hang, no crash.
8. Multi-select three videos; all three added, in the order selected.
9. "Browse files…" still opens the system picker and still works.
10. **Reboot the phone, reopen the project.** Media still resolves. This is check 4.5 in
    importance and the one most likely to be skipped.
11. Cache size after 100 videos, and the cap you chose.

---

## 5. Traps

- `getSelectedClip()` returns `getClip(0)` when nothing is selected — on a music project
  that is the auto-blank black spacer. Use `clipUnderPlayhead()`.
- A value written and never read caused three bugs on 2026-08-28. If thumbnails do not
  appear, check the adapter READS the cache before assuming extraction failed.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
- `build.log` is UTF-16 — `iconv -f UTF-16LE`, or `tr -d '\000'` per the runbook.
