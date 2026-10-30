FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

Last good commit: 28000aa. Tree clean, builds, APK installed on the Note 9 and verified.
Sandbox project bb2a9deb restored to md5 eb3d16b8 (byte-identical to its pre-session state),
re-opened with 0 fatal exceptions.

## WHAT LANDED LAST SESSION

`28000aa` — **MASK_WIPE shipped**, third of the five presets, with the caption path, the
text-box path, the picker tile and the export path all wired to one new channel.

Two findings that change how you should plan the rest:

1. **MASK_WIPE does NOT hit the text-box `TextView` wall, and the previous handoff predicted
   it would.** The wall blocks ODOMETER because ODOMETER needs TWO clipped glyph rows in one
   slot — one view holding one string cannot draw two things. MASK_WIPE needs ONE thing shown
   IN PART, and `View.setClipBounds` does exactly that, in the view's own coordinate space, so
   it composes with the view's transform the same way the export's `clipRect`-after-matrix
   does. **The wall is about drawing two things, not about clipping.**
2. **A blocker note can UNDERSTATE the cost as well as overstate it.** UNSCRAMBLE's overstated
   the work; MASK_WIPE's ("needs a per-unit clip rect") was accurate about the requirement — it
   really did need a third output channel — and misleading about how much work that was. Keep
   re-deriving them against the drawing loops; that rule is now paid for twice, in both
   directions.

Also fixed in passing: **preset labels now have one authority**, `CaptionAnimator.presetLabel`.
Three switches spelled them out with a `default: Preset.name()` fallback — MATRIX shipped a
session showing a bare `MATRIX`, and the text-box row would have shown `Mask_wipe`. The harness
now fails any label containing an underscore, equal to the enum constant, or fully upper-case.

## WHAT TO DO NEXT, IF NOT TOLD OTHERWISE

**NEON_FLICKER** — the last declared-but-unbuilt preset, blocker "needs stroke/glow modulation".
Re-derive it against the drawing loops before believing that note.

Both caption renderers already do a stroke pass in `paintWord` (`style.outline`, stroke width
`fontPx * 0.08`), so the stroke half looks like a fourth channel on `Transform` in the same shape
as `revealFrac` — one field, one shared helper, consumed at all four surfaces. **The GLOW half is
where the trap probably is:** glow on the preview is a `setShadowLayer`, and `BlurMaskFilter` /
software blur is exactly the hazard documented on `Transform#blurPx`, which no renderer consumes
and which is a standing decision the user owns. **Do not quietly implement glow via blur** — if
NEON_FLICKER needs it, that is the GHOST-blur question and it belongs to the user. A
stroke-and-alpha-only flicker that leaves the glow alone may well be the honest v1; say so
explicitly rather than shipping an approximation.

Prediction on record, from the MASK_WIPE session: **NEON_FLICKER should not touch the text-box
wall either**, since it modulates paint rather than geometry. If that holds, **ODOMETER is the
only preset left that actually needs the text-box canvas renderer**, which narrows the open
scope question below.

## OPEN DECISIONS THAT BELONG TO THE USER — do not guess
 - **ODOMETER's scope.** Still open; the user said *"skip odometer for now i dont know how to
   answer"*, so it was NOT started. It is now a NARROWER question than when it was asked: since
   MASK_WIPE reached text boxes without a canvas renderer, option (b) buys ODOMETER alone rather
   than the whole remaining preset set. Recommendation on record: (a) captions-only behind a new
   `allowedPresets` gate now, (b) the text-box canvas renderer later as its own funded piece.
 - Whether GHOST should get its blur. `Transform.blurPx` is computed and consumed by NO renderer;
   the field's javadoc explains why blurring the preview alone would do nothing while export
   really would. **NEON_FLICKER may walk straight into this — see above.**
 - Whether emphasis should be suppressed during an entrance rather than multiplied in.
 - Retrigger-on-value-change: a timer wants a pop on each TICK — an EVENT, not a function of
   elapsed time. `CaptionAnimator` has no notion of it.

## NOT PROVED, AND NOT CLAIMED
 - **The EXPORT path has never been pixel-proved for ANY preset**, MASK_WIPE included. Both
   export renderers go through the same evaluator and the same shared helpers as the preview and
   the harness pins the arithmetic, but no exported file has been looked at. This is the oldest
   standing gap in §3g and it applies to all eight implemented presets equally.

## INSTRUMENTS LEFT IN THE BUILD ON PURPOSE — do not remove
 - SEEKRANGE, ENDEDNET, KFALIGN/KFPROBE, PHDIAG, ROWGESTURE_DEBUG — see LEDGER.
 - `tasks/matrix_predict.py` — the MATRIX predictor. Read its docstring first.
 - `tasks/maskwipe_predict.py` — predicts a text box's reveal fraction in closed form from the
   on-screen time chip. Reusable for any preset whose output is a pure function of progress.
 - `tasks/maskwipe_tiles.py` — measures the picker tiles' ink extent and peak ink across a
   screenshot burst. This is the instrument that separates a MASK from a FADE, and NONE is its
   built-in control.

## HOW TO WORK HERE — rules each paid for in a bug
 - Prove it, never assert it. Pair every check with a positive control.
 - **CHECK YOUR POSITIVE CONTROL FIRST.** A dex scan has now read zero-for-everything THREE
   times for three different reasons: a Windows drive-letter colon eating an awk field, a
   partial dex, and — last session — **`strings` not being installed in this Git Bash**. Use
   `cat classes*.dex | grep -a -o -F -- "sym" | wc -l`. A scan whose control reads zero is a
   broken scan, not a stale artifact.
 - **A FAILING `javac` LEAVES THE OLD .class FILES AND THE HARNESS THEN REPORTS A CONFIDENT PASS
   ON STALE BYTECODE.** Delete the output dir or check the compiler exit before believing it.
 - **Measure the harness count by running it, not by reading the ledger** — it has gone stale
   twice. It is 298 as of `28000aa`; confirm by stashing if you quote a delta.
 - A green build / gradle UP-TO-DATE proves nothing. **Gradle failed with 100 cascading
   `cannot find symbol` errors last session and then succeeded on an immediate re-run with no
   source change** — so a single build failure here is worth re-running before debugging.
 - THE STRONGEST FRESHNESS CONTROL IS A SYMBOL YOU DELETED — but verify a "still present" hit
   before concluding staleness (a substring collision once faked a stale dex).
 - The strongest DEVICE freshness control is behavioural: each new preset makes the picker grow
   a tile. Six → seven → eight → nine so far. No symbol scan can fake that.
 - Two occurrences of a CONFOUNDED observation are not two pieces of evidence.
 - Literal non-ASCII in a Java CHAR/STRING literal breaks the harness build (windows-1252 vs
   UTF-8 files). Build such constants from code points. Comments are fine — only literals matter.
 - Do not pipe gradle through Select-String (exit 255 on success). Capture to a variable.
 - Screenshots via Bash (adb exec-out screencap -p > f.png), never PowerShell >.
 - **`MSYS_NO_PATHCONV=1` is needed for the `/sdcard` argument but then breaks the `/c/...` LOCAL
   argument, so `adb push` needs a WINDOWS-style local path in the same command.** Getting this
   wrong truncated `project.json` to 0 bytes last session — the redirect ran before the failing
   `cat`. It also stops `/tmp` being translated for git.exe, so `git commit -F /tmp/msg` fails.
 - **`project.json.bak` is the APP's own backup and it holds the PRE-WRITE state** — it was the
   byte-exact `eb3d16b8` restore target that made the truncation above a non-event. Do not
   "clean it up".
 - To edit a project.json directly: adb push to /sdcard, then
   adb shell "cat /sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"
   — run-as cannot read /sdcard directly. Force-stop the app first. Strip CR from run-as cat.
 - **Picking a preset in the caption drawer WRITES project.json immediately** — no Close & Save
   needed. Scrubbing and playback still do not.
 - All three audioClips ids regenerate on EVERY save — background noise in any diff. A
   structural deep-diff that ignores them is worth the twenty lines; it turned "did my edit do
   anything else?" into "exactly one difference in the file".
 - **Recent Projects rows are labelled with the SOURCE MEDIA FILENAME, not the project name** —
   four rows read "FadCam_20260621_145132". Identify the row by matching its displayed timestamp
   against the `lastModified` you dumped.
 - When looping over project ids with adb, redirect stdin (< /dev/null).
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
   Gradle needs $env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP
   Build task is **assembleDefaultDebug** (there is no assembleBetaDebug).
 - Commit messages: git commit -F <file>, written WITHOUT a BOM.
 - Strings are HARDCODED with // TODO(strings) — frozen behind the rebrand, don't "fix" it.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 <note9-serial> is the sandbox and must be the ONLY phone attached.
 - If the Note 20 <note20-serial> appears, STOP all device work — it holds the user's real
   45-minute project.
 - **LAUNCH WITH `adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity`. NEVER
   `monkey` — it calls `thawRotation()` and turns the user's rotation lock OFF.** See LEDGER §5.
   Last session ran an install, a force-stop and three `am start` launches and
   `accelerometer_rotation` stayed 0 throughout, which independently agrees with that finding.
 - The human tripwire still stands: if `accelerometer_rotation` goes to 1 when YOU did not cause
   it, or `dumpsys power`'s `mLastUserActivityTime` shows input you did not inject, STOP and ask.
 - Installing the app kills whatever session is running — tell the user.
 - If adb drops the device: adb kill-server; adb start-server.

Work as autonomously as you can. Prove things rather than asserting them. Update the
ledger as you go — it is the only thing that survives between sessions.
