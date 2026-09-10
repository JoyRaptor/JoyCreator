FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

**Supersedes NEXT_SESSION_PROMPT_20260730g.md.** Tree clean at `e66d5b7`. Harness 298/0.
Sandbox `bb2a9deb` restored and verified to md5 **`82d8342d`**, 11 projects, rotation lock 0.

## ⚠ THE ONE THING TO DO FIRST

**THE NOTE 9 IS UNPLUGGED.** `adb devices` is empty — it dropped mid-session, right after the
last install. It is NOT the Note 20 appearing, so there is no safety stop; the phone just needs
reattaching. **Nothing below can be verified until it is back.**

**NEON_FLICKER IS BUILT, INSTALLED, AND HAS NEVER BEEN LOOKED AT.** That is the first job.
It is in the installed APK (dex-scanned: control `FadCamApplication`=3, `NEON_FLICKER`=1,
`glowPx`=3) but no human or camera has seen it render. Put a text box on GHOST's neighbour in
the picker, give it a preset and a 25% in-zone, and watch the entrance. **Look for:** does it
read as a tube striking or as a fault; is the glow visible at all on a caption (that surface is
completely unseen); does anything pop at the moment the in-zone ends (it must not — both
channels are supposed to land on identity at p=1).

## WHAT LANDED THIS SESSION

1. `17a6254` — **GHOST's blur ships**, on text boxes, preview AND export. This was a recorded
   USER DECISION with a condition attached, and the condition was a number, so it was measured
   rather than argued.
2. `e66d5b7` — **NEON_FLICKER built** (see above; unverified).

**The GHOST measurement, because the method matters more than the number.** The spec recorded
the price as "`LAYER_TYPE_SOFTWARE` on the overlay, which costs every frame of playback". That
was pessimistic on two counts, both found by reading the painters rather than trusting the note:
the preview draws each text box in its **own `TextBoxView`**, so only a blurring box pays, and
it pays only while on screen. A project with no GHOST box pays nothing.

Measured with the layer type chosen from a **flag file**, so one build produced both arms and
build-to-build variance could not contaminate the delta:

| box | HARDWARE | SOFTWARE | delta |
|---|---|---|---|
| 1041x564 | 193.5us | 591.0us | +397.5us (+205%) |
| 1080x1031 | 201.0us | 566.0us | +365.0us (+182%) |

~0.4ms absolute, about **2.4% of a 16.7ms frame**. Not "noticeable lag", so the user's sanctioned
divergence was not needed and was not taken. **Recorded as a LOWER BOUND** — it times the inside
of `onDraw` and excludes the layer's own bitmap allocation and upload.
Device-proved to actually draw: `tasks/screenshots/ghost_blur_preview_ramp.png` — soft early in
the entrance, sharpening as it settles, **with `PICKERTEST` (a non-GHOST box in the same frame)
staying sharp throughout as a free positive control.**

## FIVE HARNESS ERRORS THIS SESSION, each of which produced a confident wrong reading

Recorded because every one of them looked like a finding first.

1. **A JSON-injected preset is INERT.** `TextBoxRenderer` requires `inZone>0||outZone>0`, and the
   25% zones are seeded by the PICKER, not by the model default. Setting only `textAnimPreset`
   in project.json gave two identical sharp arms; I was one step from filing "blur does not work".
   **Always set `textAnimInPct`/`textAnimOutPct` too.**
2. **Whole-window jank was the wrong instrument.** First arms read 40.9% vs 84.5% because
   playback ends on its own and my "pause" tap restarted it, so the arms alternated state. The
   question was what the OVERLAY costs — time `onDraw` directly and group windows by box size.
3. **A per-instance counter never reached its window** because `TextBoxView`s are recreated
   constantly (8 instantiations in one 10s arm). Aggregate statically.
4. **Cyan glow on a teal carpet showed nothing.** The mechanism was fine; the colour was not.
   Pick a probe colour that cannot exist in the scene.
5. **Non-ASCII in a Java string literal breaks the harness build** — already documented in this
   repo, and it still cost the NEON_FLICKER invariant test, which was written and then REVERTED.
   Re-add it in pure ASCII: identity at p=1, non-identity mid-way, determinism, unit-keying,
   alpha within 0..1. The harness is unchanged at 298/0.

## THE FIVE NEXT THINGS, in order

1. **SEE NEON_FLICKER** (above). Then re-add its harness invariants in ASCII.
2. **The text-box TIMING control — still the biggest user-visible gap, and still needs the
   user's word.** A user can pick a preset on a text box but cannot change its seeded 25%
   in/out zone. The `▶ ◀` carets are built and parked
   (`EditorTimelineView.setCaptionAnimHandlesVisible` has no caller) but are bound to
   MASTER-CLIP geometry, so pointing them at a layer item is a SECOND GEOMETRY, not a re-target.
   **The user's stated instrument is the carets.** A slider pair in the Edit-text dialog is much
   cheaper and strictly better than today's nothing. **The question was put to them this session
   and not answered — ask again before building.**
3. **ODOMETER.** Designed, not built, and its blocker is GONE (text boxes got a canvas renderer
   in `a247b5c`). **Fix the spec first** — the user caught a real flaw: fillers must roll a
   SEQUENCE, not MATRIX's scramble. Then build; it is now the last unimplemented preset.
4. **The picker thumbnail under-advertises.** `TextAnimPickerPopover` deliberately omits blur
   "to match" the fact that nothing drew it. Text boxes now DO. Small, and it keeps the tile
   honest — which is the whole reason the tile is generated from the evaluator.
5. **The preview-side sprite drift** — opening and closing a project silently moves a rig-driven
   sprite, and it ACCUMULATES. Recorded by an earlier session, still unfixed, and it is the only
   open item that quietly corrupts a user's project.

Then: captions still ignore `blurPx` (separate decision — one shared view for all words, so a
different cost profile), baseline editor jank 33.7%, `9d7fef2b`'s `startMs` = 2^61−1, and the
six irrecoverable emoji in `values/strings.xml`.

## HOW TO WORK HERE — rules each paid for in a bug
 - Prove it, never assert it. Pair every check with a positive control **and check the control
   can discriminate.** A previous session's headline bug hid behind a "control" visible at every
   possible clock value.
 - **A green build proves nothing — scan the APK**, control first:
   `cat classes*.dex | grep -a -o -F -- "sym" | wc -l` with `FadCamApplication` (=3) as control.
 - **A green harness proves nothing if javac failed** — `java` will happily run the STALE
   `.class` files and print the old pass count. Check javac's output, not just the total.
 - ⚠ **THE PROJECT LIST HAS A MULTI-SELECT MODE WITH A DELETE BUTTON UNDER THE COORDINATES YOU
   ROUTINELY TAP.** Exit with the `X` (~990,632); re-verify 11 projects. Screenshot before
   tapping any list you have not just looked at.
 - **Verify focus between navigation steps** (`dumpsys window | grep mCurrentFocus`).
 - **LAUNCH WITH `am start -n com.fadcam.beta/com.fadcam.SplashActivity`. NEVER `monkey`** — it
   calls `thawRotation()` and turns the user's rotation lock off.
 - `MSYS_NO_PATHCONV=1` is needed for `/sdcard` args, or prefix with `//sdcard/...` — plain
   `/sdcard` becomes `C:/Program Files/Git/sdcard` and the push silently lands nowhere.
 - Windows Python needs `C:/...` paths, never `/c/...`.
 - To edit a project.json: force-stop, `adb push` to `//sdcard`, then
   `adb shell "cat //sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"`.
   Strip CR from `run-as cat`. **Restore to `82d8342d` and verify by md5 when done.**
 - `bc` is NOT installed. Time things from device log timestamps.
 - Do not pipe gradle through Select-String. Build task is **assembleDefaultDebug**.
 - Screenshots via Bash (`adb exec-out screencap -p > f.png`), never PowerShell `>`.
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
   Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`
 - Commit messages: `git commit -F <file>`, no BOM.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 `<note9-serial>` is the sandbox and must be the ONLY phone attached.
 - If the Note 20 `<note20-serial>` appears, STOP all device work — it holds the user's real
   45-minute project.
 - The human tripwire stands: if `accelerometer_rotation` goes to 1 when YOU did not cause it,
   or `dumpsys power` shows input you did not inject, STOP and ask.
 - Installing the app kills whatever session is running — tell the user.

Work as autonomously as you can. Prove things rather than asserting them. Update the ledger as
you go — it is the only thing that survives between sessions.
