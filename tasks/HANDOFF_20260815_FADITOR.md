# HANDOFF — Faditor, 2026-08-15

**Written for a fresh agent of any model family.** Read §0–§2 before touching code. This
supersedes `HANDOFF_20260813_FADITOR.md` as the entry point; that file is still accurate about
the ripple/keyframe/export work and its §5 (how to test rendering claims) is still the method to
use. Where the two disagree, **this one wins**.

Branch `joy-creator`. HEAD `4add34db`, 12 commits past `57e5380`. Source tree clean; the only
modified files are JoyRaptor's own under `tasks/`.

Owner: **JoyRaptor**. Direct, notices unfinished edges, and values an honest "not done" over a
confident overclaim. **Never report something as working that you have not verified, and say
which kind of verification you did.** He caught me twice this session: once claiming a spec
didn't exist when it did, and once logging "long-press selects all" as a bug when it was correct
platform behaviour. Both times his correction saved real work. Take his reports seriously and
his corrections more so.

---

## 0. Environment — the commands that work

| Thing | Value |
|---|---|
| Repo | `C:\+Projects\Screenrecorder\FadCam` |
| Language | Java, Android, minSdk 24, OpenGL ES 2.0 / GLSL ES 1.00 |
| Main file | `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` (~33k lines) |
| Sandbox phone | Note 9, `SANDBOX_SERIAL`, API 29 |
| JoyRaptor's phone | Note 20, `REAL_SERIAL` — **he asks for installs now; still confirm** |
| App package | `com.fadcam.beta` |

```bash
# PATH first, every time — a previous `export PATH=` in the same session clobbers it
export PATH="/usr/bin:/bin:/mingw64/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:/c/Python314:/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools:/c/WINDOWS/system32"

bash tools/jvm-harness/typecheck.sh     # fast, no device. MUST print TYPECHECK OK
bash tools/jvm-harness/run-anchor.sh    # AnchorShift + RippleObjects + TrimKeyframeBase
bash tools/jvm-harness/run-matte.sh     # Matte + AdjustmentLane + CompactLane + ImageBlendGate
bash tools/jvm-harness/run-fx.sh        # FX/shader golden strings
bash tools/jvm-harness/run-caption.sh   # NEW — grapheme units (see §4)
bash tools/build-install.sh             # compile AND install to the Note 9
```

**Gotchas that cost time — do not rediscover them:**

1. `./gradlew.bat` direct fails with `Unable to establish loopback connection`. It needs
   `export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\\Windows\\Temp"`.
2. `build-install.sh` **refuses while the Note 20 is attached**. To build with both plugged in,
   or to install to the Note 20:
   ```bash
   export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\\Windows\\Temp"
   ./gradlew.bat --offline :app:assembleDefaultDebug
   adb -s REAL_SERIAL install -r app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk
   adb -s REAL_SERIAL shell dumpsys package com.fadcam.beta | grep lastUpdateTime   # ALWAYS confirm
   ```
3. **NEVER pipe a build to `tail`/`grep` in a `&&` chain.** The pipe returns the LAST command's
   status, so a failed gradle reports success and the chain continues. I committed once on a
   failing build this way. Use `cmd > /tmp/log 2>&1; echo $?` or `PIPESTATUS`.
4. `typecheck.sh` cannot see newly added resources (stale `R.jar`), so a brand-new `R.string.*`
   fails typecheck but compiles fine. Confirm with `:app:compileDefaultDebugJavaWithJavac`.
5. `adb` sees nothing if USB is "charging only" — must be File transfer/MTP.
6. Reading a project: `adb -s <serial> exec-out run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`.
   Writing: push to `/data/local/tmp` then `run-as ... cp`. **`export MSYS2_ARG_CONV_EXCL='*'`
   first** or the shell rewrites device paths and silently empties the target.
7. `FaditorEditorActivity` is **not exported** — you cannot `am start` it with a project id. Drive
   the UI: launch via monkey, tap the Faditor tab, tap the project row.
8. **The bottom toolbar scrolls and does not reset.** Tapping a tool by remembered coordinates
   hits the wrong one. Screenshot the toolbar immediately before every tool tap.
9. A completed `input swipe` cannot be photographed mid-gesture. For drag states use discrete
   `input motionevent DOWN / MOVE / MOVE / UP` with screencaps between.

---

## 1. THE BLOCKER — ~200% CPU at idle. JoyRaptor cannot work until this is fixed.

**He reported his phone getting hot with a project merely loaded. It is real and it is severe.**

Measured on the Note 20 (project "first lecture on phone", 48:31 long), player **PAUSED**,
15 s settled with no input:

| Playhead | CPU | RES |
|---|---|---|
| Past the end of the spine | **200%** | 782 MB |
| Inside the spine | **205%** | 793 MB |

~2 full cores, continuously, doing nothing. Also: RES grew 782→793 MB across the readings.

Thread breakdown (`top -H -b -n 2 -d 2 -o TID,%CPU,CMD -p <pid>`):

```
30927  65.5  pool-37-thread-
30933  62.0  pool-37-thread-
20664  59.5  RenderThread
```

And logcat at idle is 34–52 lines/second of exactly one message:

```
D/SurfaceView@9988222: updateSurface: has no frame
```

i.e. the view tree is being re-laid-out **every frame** while paused.

### Hypotheses — one tested and DISPROVEN, one PRIME and untested

**DISPROVEN: "the playhead is parked past the spine so something is freaking out."** JoyRaptor's
idea, and a good one. I tested it exactly: he parked the playhead past the spine, I measured
200%; I seeked back inside the spine, settled 15 s, measured 205%. Position-independent. Do not
re-chase this.

**PRIME SUSPECT (untested): the playhead ticker never stops.**
`FaditorEditorActivity` ~line 745, `PLAYHEAD_UPDATE_INTERVAL_MS = 50` (20 Hz). It reposts itself
while:

```java
if ((playerManager != null && (playerManager.isPlaying() || playerManager.getPlayWhenReady()))
        || audioTailActive
        || transitionPlaybackActive || imagePlaybackActive) {
    playheadHandler.postDelayed(this, PLAYHEAD_UPDATE_INTERVAL_MS);
}
```

If **any** of `audioTailActive`, `transitionPlaybackActive`, `imagePlaybackActive` latches true
and is never cleared, this loop runs forever at 20 Hz on a paused editor. Each tick runs
`updateCurrentTimeDisplay` → `setTextOverlayPlayhead` + `syncAdjustmentPreview` →
`setCompositePlan` → `FxPreviewTextureView.requestFrame()` → a GL draw and a layout pass. That
would produce exactly the observed RenderThread load and SurfaceView churn.

**JoyRaptor's project ends with a 5 s IMAGE clip and he had parked the playhead past it** — so
`imagePlaybackActive` is the first flag to check.

**How to test it in five minutes:** add one throttled log inside the ticker printing the four
flags, install, park the project, and read logcat. If the ticker is alive at idle, the fix is
whichever flag is latched — clear it where playback stops.

**Second, independent thread:** `pool-37-thread-*` is two threads in one pool at ~62% each. The
only `newFixedThreadPool(2)` in the codebase is `AssetBrowserAdapter.thumbExecutor`
(`app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetBrowserAdapter.java:58`). Everything
else is `newSingleThreadExecutor`. That is suggestive, **not proven** — confirm before acting.
`pool-37` also implies 37+ executors have been created in the process, which is its own smell.

Do not stop at "it feels better". Re-measure with the same commands and put the numbers in the
commit message.

---

## 2. Open bugs JoyRaptor has reported that are NOT fixed

Both need a repro before a fix. I deliberately left them rather than ship a plausible story —
that discipline is what turned the keyboard flicker (§3) from three wrong fixes into one right
one.

### 2a. Text becomes uneditable after an animation preset is applied
Applying letter/word animation to a text box made it impossible to edit the text — **and setting
the preset back to None did not restore it**, which is the interesting half: it suggests the
preset latched something rather than a render-time state.
**Ask JoyRaptor first:** when you tap the text, does the drawer open at all, or does nothing happen?
That splits "the gesture never fires" (`TextOverlayLayer` touch path) from "the editor never
attaches" (`attachToBox` — which now logs a warning naming the item when it finds no box).

### 2b. Text not rendering until an empty adjustment layer was deleted
Text sometimes would not show until he moved images down lanes; deleting an **empty** adjustment
layer (no FX) on the very bottom lane made text render consistently.
The puzzle: `AdjustmentLayer.rendersAnything()` is false for an empty one, so it should be
excluded from `visibleAdjustmentLayers` and from routing. If it still mattered, something
consults its EXISTENCE rather than what it draws.
**Ask JoyRaptor:** was the empty layer below the text lane, and did text return instantly on delete
or only after the next scrub?
**Suspect area:** `LayerPreviewController.orderedVisualItems` / `partitionAroundVideo`, and
`FxLivePreviewController.sync` routing.

### 2c. Audio may not extend the EXPORT
`Timeline.getTotalDurationMs()` is `Math.max(videoTotal, audioEnd)` — audio DOES extend the
timeline model. JoyRaptor reports audio did not extend his project. **Unverified whether the export
emits the audio tail.** This matters for §5's audio-editor direction; check `ExportManager`'s
overall length before leaning on audio as the length authority.

---

## 3. What landed this session (12 commits, `57e5380..4add34db`)

`DEVICE` = exercised on hardware and the specific claim confirmed by measurement.
`HARNESS` = proven off-device by a test that fails without the fix. `COMPILE` = builds, reasoned.

| Commit | What | Verified |
|---|---|---|
| `20cf0228` | **Image-overlay FX, chroma key and blend now render in the PREVIEW**, not just export. Image → `Pip` in the GL composite, textured from the same decoded bitmap the ImageView shows; `wantsGlExport()` is the single authority for "composite it / hide its view" | **DEVICE**, measured — see below |
| `91d5dcda` | **Blur radius parity.** Every spatial card stepped by one texel of its render target, and preview/export targets differ — so a radius meant different sizes in editor vs file, and in a 720p vs 1080p export. `FX_STEP` is a fixed fraction of frame height | HARNESS (8 assertions fail without) |
| `3750f3f6` | **The text editor, four defects** — see §3a | **DEVICE**, JoyRaptor confirmed |
| `da6342f5` | Keyboard/window behaviour — see §3a | **DEVICE**, JoyRaptor confirmed |
| `7573d0f1` | **Drag guides through diagonals** + cross-lane alignment snapping | guides **DEVICE**; snap COMPILE |
| `27353f3f` | Layer-item trim guide (spec's last scoped-down item); colour picker tap-off COMMITS | COMPILE |
| `a52d83f8` | Caption style chooser no longer asks for itself on project load | COMPILE |
| `f21e3ce5` | Adversarial review of my own session — 4 findings, all mine, all fixed | COMPILE |
| `b7d205c6` | **Emoji tofu at letter-level animation** — see §4 | HARNESS (5 fail without) |
| `ba5f94bc` | **Move drawer's up arrow demoted a master clip off the spine** — see §3b | COMPILE — **wants hands-on** |
| `4add34db` | Duplicates land below/above the original instead of spawning a lane | COMPILE |
| `a7cb3213` | Revert of a duplicate-offset nudge (premise was wrong) | — |

**Device proof of the image-FX work** (the §5 method from the previous handoff — two screenshots,
identical fixtures but for one setting, compared numerically):
- invert: 36.1% of the preview reads as the exact inverse, 62.6% unchanged, and the inverted
  region is ONE contiguous rectangle = the image's box (x 133..945, y 471..1193). Had the VIDEO
  inverted, the whole frame would have read inverted — that is the mistake the previous session
  made and reverted a working feature over.
- chroma key at removing tolerance: inside the image rect the keyed preview differs from a
  no-picture preview by 23.4%, the same 24.0% the untouched video band differs by. Gone.
- MULTIPLY: 98.2% of the image's pixels equal `image × video / 255`, mean delta 5.9/255.

### 3a. The text editor — four separate defects, all found by reading logcat
Worth reading if you touch text or the IME:
1. **`rebuild()` destroyed the served view.** `removeAllViews()` detaches the transparent
   EditText; a detached view stops being the IMM's served view and the IMM CANCELS the pending
   show. The keyboard appearing caused the layout that destroyed the view the keyboard was for.
   ~125 ms every time. `rebuild()` now keeps that one child attached.
2. **The activity had no `windowSoftInputMode`**, so it resized and pushed the preview off
   screen. `adjustNothing` — the keyboard overlays the timeline, which is unusable while typing.
3. **Caret sat 0.35 em high** — the renderer's baseline includes an ink pad the editor did not.
   That also misplaced every tap-to-position-caret.
4. **Every line drew at the same baseline** — `topFor()` used the BOX top for all lines, so Enter
   painted line 2 over line 1. Each line's offset is now stored ON the line and read by all three
   passes (ink, selection band, measurement).

**DO NOT ADD AN IME RETRY LOOP.** I tried; it stacked chains on every rebuild and produced
49,692 logcat lines in 45 seconds. The javadoc on `showIme` now says so and why.

### 3b. The Move drawer bug — read this before touching the Move tool
`moveLayerUp` was wired to `moveSelectedClipToLayer()`, which keys off `selectedClipIndex` and
calls `timeline.demoteToLayer()` — **removing a clip from the spine**. With a text object
selected, an arrow that reads "move up a lane" performed a structural edit on a clip the user
never pointed at, and the ripple carried anchored text along by whole cuts. JoyRaptor reconstructed
the cause from the damage alone and was right. The arrows now dispatch on the selection.
**Not device-verified. It replaces a destructive bug; verify it by hand.**

---

## 4. Harness additions

- **`run-caption.sh` is new.** `CaptionAnimatorTest.java` had sat in `tools/jvm-harness` with NO
  runner referencing it — never compiled, never run, reading as coverage while asserting nothing.
- **`GraphemeUnitsTest.java`** pins what a "letter" is: 🕯 is U+1F56F, a surrogate PAIR, and
  `splitUnits` emitted one unit per `char`, so LETTER animation handed the renderer two
  half-characters and the font drew tofu. Now grapheme clusters via `BreakIterator`.
- **Stubs added** so the transcript package compiles off-device: `org.json` ×3,
  `android.content.Context`/`SharedPreferences`, `android.graphics.Typeface`. All deliberately
  inert — accessors return their fallback, so a test that starts depending on real serialisation
  fails loudly rather than passing against a half-implementation.
- **NOT asserted, deliberately:** regional-indicator flags and newer ZWJ emoji. They cluster
  differently on the desktop JDK than on Android's ICU-backed BreakIterator; asserting either
  would pin the harness's platform, not the app's. **Those want a device.**

---

## 5. Queued feature work — decisions already made with JoyRaptor

### 5a. Flexible time parser — SPEC EXISTS
**`tasks/SPEC_IMAGE_SEQUENCE.md` §3c.** I initially told JoyRaptor no spec existed; he was right and
I was wrong — I grepped for imagined phrasings instead of reading the image spec.

> Accept the ways people actually type time: `10.5s`, `10.5 sec`, `10.5 seconds`, `1/6 min`,
> `00:00:10.5`, `90f` (frames), `2m30s`.

Fractions and frames included. **JoyRaptor's decision: wire it EVERYWHERE a time or duration is
entered**, not just the new dialog. It replaces `parseTimeToMs`
(`FaditorEditorActivity` ~line 29907), which handles only `ss` / `m:ss` / `h:mm:ss`.
Pure logic → give it harness tests using the spec's own examples as cases.

### 5b. Image/Blank duration dialog
**JoyRaptor's decision: ONE dialog with a `blackscreen` checkbox** that greys out the image chooser —
one code path for image and blank, and converting between them is one tap. Double-tap a clip
opens it. `IMAGE_CLIP_DURATION_MS = 5000` (line ~125) is currently hardcoded.

**Also read `SPEC_IMAGE_SEQUENCE.md` §3b**, which specifies the dialog's shape and is better than
what we sketched: ask **how long**, ONE question not two, with **three equivalent fields — frame
rate, duration per image, total duration — that all write the same model and update each other
live**. Use that shape.

### 5c. Blank/black spacer clips on the spine
Not started. See §6 for the architecture, which JoyRaptor has NOT yet ratified.

### 5d. Audio-editor direction (bigger, strategic)
JoyRaptor wants Joy Creator to be a preeminent **audio** editor too. Blockers he named:
1. Cannot start a blank project — must open from a video project.
2. The main spine being video-only.
3. Audio tracks should appear in the **mini-map in their own colour**.

---

## 6. The architecture question JoyRaptor has NOT ratified

**"Should overlays extend the project length, like audio does?"**

Today: `Timeline.getTotalDurationMs()` = `Math.max(sum of spine clips, max audio end)`. Overlays
(image/text/sprite/PiP) are **not consulted at all** — which is why extending an image layer does
not lengthen the project and why image keyframes mistime without a video under them.

JoyRaptor challenged my first answer correctly: duration-wise audio and overlays ARE symmetric, and
"audio yes, overlays no" dressed an implementation detail as a principle. The honest asymmetry is
about **rendering**, not duration:

- Audio past the spine needs no base — silence is the identity for mixing.
- Video past the spine needs an invented base frame — composite onto *what*?

**And a Blank clip IS that invented base.** So "should overlays extend?" and "should we insert a
Blank?" are the same mechanism, differing only in explicit vs implicit. Which collapses the
decision:

1. **Build Blank clips first** (the explicit base). This is the substance.
2. **Auto-extension is then a one-line policy on top** — append a Blank when an object is dragged
   past the end. Reversible, no re-architecture.

Recommended and awaiting his word: **spine defines structure; audio and Blank clips define
length. Do NOT allow audio into the video spine** — the spine is the visual coordinate system,
and an audio clip there would have to answer "what frame is here".

**Why not just let overlays extend:** the spine is not a convention here, it is the coordinate
system. Ripple deltas, anchors and hit-testing rest on `clipSpanMs`; `overlayClockMs` exists
solely to paper over past-the-last-clip; and the previous handoff's §4.7 records FOUR
implementations of timeline-ms → (clip, offset) that already disagree. New length semantics would
go through all of them. That is §4.7-class regression risk on machinery that has already cost
JoyRaptor data loss twice.

---

## 7. Still owed from the previous handoff

- **§4.2 seam jump** — needs JoyRaptor to play across the seam on the Note 20 with
  `adb logcat` running and read `FaditorPlayerManager: SEEKRANGE`. If `clip` is right and `rel`
  is wrong it is the offset maths; if `clip` is wrong it is the selection.
- **§4.3 load flicker** — ask what flashes and to what before spending more.
- **§4.7 ONE timeline↔source mapping authority** — `[XL]`, high regression risk, needs his call.
- **`91d5dcda` changes non-1080p exports.** A 720p export of a blurred project now matches the
  editor instead of exceeding it. That is the fix, but it is a change to output and JoyRaptor has not
  ruled on it.

---

## 8. Method notes that earned their place this session

1. **Measure, do not eyeball.** Every rendering claim here was settled by comparing two
   screenshots numerically. The previous session reverted a working feature by reading a frame by
   eye; I nearly repeated it.
2. **Read logcat before theorising.** All four text-editor defects were invisible to reasoning and
   obvious in the log. `showSoftInput - cancel : mServedView != view` named the bug outright.
3. **Fix the mechanism, not the symptom.** My IME retry loop treated a symptom and made the app
   unusable. The real defect was our own `rebuild()` destroying the view.
4. **The rule is usually already written down next to the code.** Three of the four findings in my
   own adversarial review were cases where a comment within a few lines stated the rule I broke
   (`loggedNullPip` for one-shot logging, `partitionAroundVideo`'s "walk the ordering ONCE",
   `setData`'s "identity, not equals").
5. **A stale doc is a bug.** Two this session — one claiming a closed blocker was still held,
   one describing a retry loop that had been removed.
