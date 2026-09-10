# NEXT SESSION — paste everything below the rule into a fresh conversation

---

FadCam / Joy Creator — `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`.
Note the working directory may be the PARENT (`C:\+Projects\Screenrecorder`), in which case task
docs are at `FadCam/tasks/…`.

**Read `FadCam/tasks/LEDGER.md` FIRST.** It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. It exists because a whole
feature — masking/chroma-key — was built, shipped as an engine, and then LOST for weeks because
no checklist item was ever left unticked. Keep it current: when something lands, move it to §1
with its proof; never delete an entry to shorten the list.

Work as autonomously as you can. Give me one message confirming you understand the state and
what you intend to do, then get to work. **Prove things rather than asserting them.**

Last good commit: **`b3bd093`**. Tree is clean and builds.

## WHAT HAPPENED LAST SESSION (2026-07-29)

Three things landed, and then the whole §3g timing model got redesigned by the user.

1. **`da20e6d` — §3a: a matte peer stops being a PiP everywhere.** Two divergences, one cause.
   `LayerPreviewController` now separates *is this overlay visible at all*
   (`visibleOverlayVideoClips`, which the export VIDEO path needs because it resolves peers out
   of that list) from *does this show up as a PiP* (`renderableOverlayVideoClips`). The preview
   and the export overlay-AUDIO sequence take the renderable list. Before this, a matte peer with
   `overlayAudioEnabled` contributed **sound to the exported file** while its picture was hidden.
   Proof: `bash tools/jvm-harness/run-matte.sh` — 14 checks against the REAL model classes.
2. **`1bd6b26` — §3a: the soft-edges (feather) engine.** `CompositingSpec.maskFeather` 0…1;
   `clipCanvas` replaced by a `beginMask`/`endMask` bracket at all three call sites and DELETED.
   Feather 0 (every existing project) takes the identical `clipPath` path. Proof:
   `CompositingSpecTest` 12 → 30 checks. **The pixels are still unverified** — the slider that
   would exercise it is not built yet.
3. **`b3bd093` — §3g first sighting on a phone,** plus the measured tile finding below.

## §3g — VALIDATED, THEN REDESIGNED. THIS IS THE MAIN WORK.

The user drove it himself and said: *"animations per word and per letter look great! i tried all
styles carets worked well."* So the engine and the look are approved. Do not rebuild them.

**But the authoring model changes. His direction, binding, in LEDGER §3g:**

- The caption style panel is used for closed captions **and** text boxes, and must become
  **context-aware** — the two need different timing controls.
- **Timeline `▶` `◀` carets: TEXT BOXES ONLY.** Remove them for captions.
- **Text box:** carets are relative to **that line's display duration**. Both at the middle =
  halve that line; half the time coming in, half going out.
- **Captions:** no caret, no range finder on the timeline. A **range control in the style panel**,
  set once, applied to every caption line as a percentage of that line's own duration.
  His reason, and it is the whole point: captions ride long videos, and *"to get a fifty percent
  fade in, fifty percent fade out, I'm gonna be having to do a lot of dragging over perhaps a
  thirty minute clip. And that just won't do."*

**Start here — the model change, which I began and deliberately reverted so the tree would stay
green rather than hand you something that does not compile.** `Clip.captionAnimInMs`/`OutMs`
become `captionAnimInPct`/`OutPct`, floats 0…0.5, a fraction of each LINE. The reasoning is worth
keeping verbatim: the ms version had to be held in SOURCE ms by hand so a speed-adjusted clip
would not animate over the wrong span — the exact mistake §3g originally was. **A fraction has no
units, so it is correct in both bases by construction.** The 0.5 cap is the model, not a safety
rail: at 0.5/0.5 the entrance ends exactly where the exit begins, at every line length.

The consumers to follow through (all of them, there are not many):
`CaptionExportRenderer:146-148`, `CompositeExportOverlay:594`, `FaditorEditorActivity:15737-15793`
and `:20394`, `ProjectStorage:1342-1346` + `:1588-1593`, `EditorTimelineView` (the caret drag),
and `CaptionAnimator.zoneForSpan` / `caretFractionForZone` with its 145-check harness.
Old `captionAnimInMs`/`OutMs` keys on disk: read and ignore, documented — they exist only in the
sandbox, never shipped to a real project.

**Then the bigger half: there is NO text-box path into the caption style panel today.** I checked.
`caption_drawer` is opened only by `toggleCaptions()`; `tweakCaptionStyle`
(`FaditorEditorActivity:15407`) targets a video or audio clip and nothing else; `TextOverlayItem`
has its own `colorInt`/`fontFamily` and a keyframed transform, with no `CaptionStyle` and no
animation zones. So "caption styles also edit text boxes" is a BUILD, not a context branch. Tell
the user how big that is before you sink a session into it.

**One measured defect, still unfixed — the preset tiles do not read as distinct at 60dp.**
Four screenshots 0.4s apart are byte-identical, so the tiles are static poses, not animations.
Type vs Fade differ by **mean 1.08/255 over their glyph area, 2.9% of pixels differing by >8**.
Control on the same measurement: None vs Type = **26.38 mean, 17.7% differing** — the instrument
sees a real difference at ~24× the signal between Type and Fade. At 3× zoom the five presets ARE
distinguishable (Rise raises and shrinks the first A, Beam narrows it, Fade dims it); at 60dp the
label does all the work. Worth fixing while the picker is being touched anyway.

**Still unmeasured, and it is the cost centre:** whether **LETTER granularity holds frame rate on
a long phrase** (per-glyph layout in BOTH the preview and the export path). The user says it looks
great; nobody has put a number on it.

## AFTER §3g

**§3a (masking/chroma-key UI)** → §3e (two-stage AI reorder). §3a's two engine pieces are done
(above). What remains is the panel — a *Mask & Key* chip plus the object's long-press menu, two
tabs (Shape / Key), the eyedropper, and the feather slider that would finally exercise
`maskFeather` on real pixels. The four binding scope answers are in LEDGER §3a.

**One §3a finding that may change what v1 means, and it is the user's call:** *"image overlays in
v1"* has nothing to put a mask on. `LayerPreviewController.visibleImageItems` is documented
"always empty today — nothing can create an IMAGE track yet", and the overlay-`Clip`-with-a-still
route has no creation path either — the single PiP entry point (`FaditorEditorActivity:16925`)
builds its clip from a picked **video** URI. So that is *build the image overlay first*, not mask
plumbing. **§3d is CLOSED — measured, then deleted on the user's call (`bd2bd58`). Do not rebuild it.**

## DECISIONS THAT BELONG TO THE USER — do not guess

- **Which of the five unimplemented presets to build next** (MATRIX, UNSCRAMBLE, ODOMETER,
  MASK_WIPE, NEON_FLICKER). Each names its blocker in `CaptionAnimator.unsupportedReason` and
  returns identity, never an approximation. *(Still unanswered — asked twice.)*
- **Whether GHOST should get its blur.** `Transform.blurPx` is computed and consumed by NO
  renderer. The obvious fix is a trap: `BlurMaskFilter` is ignored on a hardware-accelerated
  canvas, so blurring the preview alone does nothing on screen while the export — drawing into a
  `Bitmap`, i.e. software — really would. Full reasoning on the field's javadoc.
  **Note:** the feather work (`1bd6b26`) hit this exact trap and solved it by blurring inside an
  ALPHA_8 Bitmap. That works there because the blurred thing is STATIC geometry. For GHOST it is
  every video frame, so it does not transfer — the cost is real.
- **Whether emphasis should be suppressed during an entrance** rather than multiplied into it.

## STILL NOT ADDRESSED

**Retrigger-on-value-change.** A timer wants a pop on each TICK, which is an EVENT ("the
displayed string changed"), not a function of elapsed time. `CaptionAnimator` has no notion of it.

## INSTRUMENTS LEFT IN THE BUILD ON PURPOSE — do not remove yet

- **`SEEKRANGE`** (`FaditorPlayerManager.seekTo` / `seekInClip`) — every clip-relative seek against
  the loaded window's real length. `ok=false` means the §2a defect is back. The `ok=true` lines are
  the probe's own positive control: if they vanish, the probe went blind.
- **`ENDEDNET`** — parked at ENDED with play still on. `next=N/M` with N < M means a park in the wild.
- **`KFALIGN` / `KFPROBE`** — the §3d hit rate, per project.
- **`PHDIAG`** + the `lastUp:` snapshot — §2b, a self-healing stranded drag-latch.

## HOW TO WORK HERE — rules that were each paid for in a bug

- **Prove it, never assert it.** Every fix in LEDGER §1 has a measured before/after.
- **Pair every check with a positive control.** Instruments that have lied here: a raw-bytes scan
  of an APK for a string (dex is COMPRESSED — extract and scan `classes*.dex`); `uiautomator dump`,
  which fails on an animating screen and leaves you reading a STALE `/sdcard/ui.xml` (delete it
  first and check the dump landed); and, new this session, **a too-long classpath**. javac and java
  both fail on it with "Argument list too long" on **stderr with no `error:` line**, so a grep for
  compile errors comes back clean and the output directory is silently empty — I read one "clean
  compile" that had never run. `tools/jvm-harness/run-matte.sh` checks for the `.class` file.
- **A green build proves nothing, and neither does gradle.** Paid for three times now:
  - A run that FAILED on a Windows file lock, followed by a plain re-run reporting BUILD
    SUCCESSFUL, packaged a PARTIAL dex set — the APK was missing `FadCamApplication` and the app
    crash-looped. **A dex scan for the symbol you just added does NOT catch this.** Always also
    scan for something that must ALWAYS be there. If a build fails on a file lock, delete
    `app/build/intermediates/javac/<variant>` and `.../dex/<variant>` first.
  - Gradle printed `compileDefaultDebugJavaWithJavac UP-TO-DATE` on runs that had just compiled.
  - **This session: a `.class` file carried an mtime OLDER than the compile pass that wrote it.**
    Do not reason about mtimes. **The best freshness control is a symbol you DELETED:** after
    removing `clipCanvas` the dex scan showed it ABSENT, which a stale dex could not show.
- **Do not pipe gradle through `Select-String`** — exit 255 on a run that succeeded. Capture to a
  variable (`$out = .\gradlew.bat … 2>&1`) and slice that.
- `adb logcat` without `-T` replays the whole ring buffer. Never `logcat -c`.
- Screenshots must be captured through the **Bash** tool (`adb exec-out screencap -p > f.png`) —
  PowerShell `>` corrupts binary. To judge a small UI detail, crop and upscale with
  `System.Drawing` (NearestNeighbor) — and to settle "do these two things look different", diff
  the pixels and **run the same diff against a pair that obviously differs** as the control.
- In Git Bash, `/sdcard/...` gets mangled into a Windows path — `export MSYS_NO_PATHCONV=1`.
- For a DETERMINISTIC playhead, use "Jump to time" (tap `time_current`, MOVE_END, 8× DEL,
  `input text 0012`, OK). `input swipe` distances fling and are not repeatable.
- `adb` is at `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
- Commit messages: `git commit -F <file>`, written WITHOUT a BOM
  (`[System.IO.File]::WriteAllText($p,$m,(New-Object System.Text.UTF8Encoding($false)))`).
- Strings are HARDCODED with `// TODO(strings)`. The extraction is frozen behind the rebrand
  (`road_map.md:49`). Follow that, do not "fix" it.

## THE PROJECT TO TEST §3g ON

`bb2a9deb-5651-4f12-93b1-512980185a14` — **"P0 control no image"**, 3 captioned clips. It is NOT
at the top of the Recent Projects list: the list sorts on the project's own `lastModified`, so it
shows as **"Jul 7, 6:56 PM"**, second from the bottom. Map rows to ids with
`run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`.
To reach the UI: select a captioned clip → **Captions** in the bottom toolbar → the drawer's
**Motion** row → `≡A` opens the preset popover. The carets only draw while the drawer is open.

Opening this project migrates it from inline `"transcripts"` to `"transcriptPool"` +
`"transcriptRefs"` and takes `"words"` 3 → 2. **That is the §1 transcript-pool dedup working**
(three refs, two unique lists, two clips share a source file), not data loss. It also reformats
project.json from minified to pretty-printed, 22.7KB → 63.7KB. Do not panic and do not "restore"
it. Note that `adb shell run-as … cat` adds CR to every line, so a pulled copy reads larger than
the file on the device — never push one of those back.

## DEVICE RULES — NON-NEGOTIABLE

- The Note 9 `<note9-serial>` is the sandbox and must be the ONLY phone attached.
- **If the Note 20 `<note20-serial>` appears, STOP all device work.** It holds the user's real
  45-minute project. (It was attached at the start of 2026-07-29 and all device work was blocked
  until it was unplugged. That rule earned its keep.)
- Installing the app kills whatever session is running — tell the user.
- **The Note 9 may have a human on it.** On 2026-07-29 the screen switched to Chrome mid-sequence
  and at least one injected tap may have landed in that browser instead of FadCam. Check the
  foreground activity (`dumpsys activity activities | grep topResumedActivity`) before and after a
  tap sequence, and stop if it is not FadCam.
- If adb drops the device, `adb kill-server; adb start-server` recovers it — then **re-check
  orientation**, because a rotation invalidates every tapped coordinate
  (`settings put system user_rotation 0`).
