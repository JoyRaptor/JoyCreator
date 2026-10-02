# Stylus feel-check — the owner's hour

The brushes were designed by someone who knows how they are meant to feel. This is the sequence that
lets the owner judge them, with every coordinate already worked out so no time is spent hunting for
buttons. **Nobody but the owner can do this hour.** An agent can drive the phone to any state and
screenshot it; it cannot feel a nib.

## READ THIS FIRST — the phone lies about its size

The Note 9 reports **physical 1440x2960**. It runs in an **override: logical 1080x2220 at 315 dpi**,
so `displayMetrics.density = 1.96875` and **every `adb input tap` coordinate below is in 1080x2220
space.** Taps written against 1440x2960 miss by 25%. This is recorded three times in the repo and has
already cost one lane a whole night (`tasks/POLISH_CHECKLIST.md:192-211` has the post-mortem).

```
$adb = "C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
```

**Never `adb uninstall`** — the working drawing lives in the app's external files directory and goes
with it (`JoyBrushActivity.kt:1466`).

## Which app to use: the standalone brush-test build

For a pure brush-feel hour, use **`cc.joycreator.joybrush.brushtest`** ("Joy Brush Test"). It is the
production `JoyBrushActivity` with its own application ID and its own drawing storage, so trying a
brush cannot touch a real drawing and needs no video editor or native media libraries behind it.

```
adb install -r -d <path>/testapp-debug.apk
adb shell am start -n cc.joycreator.joybrush.brushtest/cc.joycreator.joybrush.android.JoyBrushActivity
```

If you would rather go through the real app, it is the same Activity with different storage:
`com.fadcam.beta` (lobby → 6th room, "JOY BRUSH"), and it opens straight there with
`adb shell am start -n com.fadcam.beta/cc.joycreator.joybrush.android.JoyBrushActivity`.
Coordinates below are identical in both.

**After every `am start`, sleep 4 s.** `root.dispatchTouchEvent` swallows every touch while the
drawing loads (`JoyBrushActivity.kt:334-337`), and a tap in that window is silently eaten.

## The one thing adb cannot do for you

`adb shell input swipe` injects a **FINGER**, and a finger reports **pressure 1.0 and tilt NaN**
(`MotionEventSamples.kt:43-44`). So:

- **Every drawing stroke below is a real S Pen stroke made by hand.** The taps are for navigation.
- Worse, once a pen has been seen, **fingers stop drawing and start navigating** —
  `fingersNavigate = penSeen` (`JbCanvasView.kt:715-724`). There is no mode toggle. If you tap the
  canvas with a finger before lifting the pen, you leave a dot; after, you pan. **Do not tap the
  canvas at all — draw on it.**
- Multi-touch is impossible with `input tap`, so **undo must use the button**, not the two-finger tap.

Take a screenshot after every step (`adb exec-out screencap -p > shot.png`, then Read it). If a tap
seems to do nothing, the panel probably did not open where you expected — screenshot before re-tapping.

---

## COORDINATE TABLE (logical 1080x2220, default layout)

| what | tap |
|---|---|
| HOME | 52, 52 |
| **UNDO** | **131, 52** |
| REDO | 210, 52 |
| GUIDES | 780, 52 |
| PIN | 859, 52 |
| **LAYERS** (opens the layer column) | **938, 52** |
| MORE (⋯) | 1017, 52 |
| strip: BRUSH | 43, 655 |
| strip: SMUDGE | 43, 734 |
| strip: ERASER | 43, 813 |
| **strip: SIZE** | **43, 892** |
| strip: SWATCH (colour) | 43, 971 |
| strip: OPACITY | 43, 1050 |
| recent-colour hair | 98, 650…1028 |
| column: ＋ layer | 1021, 154 |
| column: layer cell 1 | 1021, 268 |
| column: layer cell 2 | 1021, 410 |
| **column: PAPER cell** | **1021, 517** (2 layers) / **1021, 375** (1 layer) |
| ⋯ menu: Save a copy… | 711, 158 |
| ⋯ menu: Open… | 711, 237 |
| ⋯ menu: Recent drawings… | 711, 316 |
| ⋯ menu: Export PNG… | 711, 395 |
| ⋯ menu: Put everything back | 711, 637 |
| Paper sheet: Done | 1013, 1389 |
| Paper sheet: Background row | y 1506, x = 93 + 130k |
| Paper sheet: Surface row | y 1686, x = 93 + 130k |
| Paper sheet: Show / Bite / Scale | 540, 1915 / 1994 / 2073 |
| Paper sheet: Light / Include in export | 206, 2152 / 718, 2152 |
| colour picker wheel | 284, 903 |
| size slider (after tapping SIZE) | 311, 928 |

**Reset the layout first** (one tap, worth doing): ⋯ (1017, 52) → **Put everything back** (711, 637).
The tool strip's side and position, and the layer column's open state, **persist between sessions**
(`PREF_STRIP`, `PREF_LAYERS_OPEN`), so a previous session can leave the strip on the right or the
column already open, and every x above would be wrong. For a truly clean run, clear app data.

---

## THE SEQUENCE

### 0. Prepare (2 min, agent or owner)

```powershell
& $adb shell am force-stop cc.joycreator.joybrush.brushtest
& $adb shell am start -n cc.joycreator.joybrush.brushtest/cc.joycreator.joybrush.android.JoyBrushActivity
Start-Sleep -Seconds 4
& $adb exec-out screencap -p > "$env:TEMP\jb-feel-00-launch.png"
```

Pick a paper that shows grain — the brushes deposit into it, and a flat white sheet hides the
difference between brushes. Open the column (**938, 52**) and tap the paper cell, then in the sheet
tap a Surface with real relief (**y 1686**, x 93 for Artisan pulp) and a non-white Background
(**y 1506**). **Done** (1013, 1389). Re-screenshot to confirm.

### 1. Pencil — the four-angle feel check (the core of the hour)

Pick **Pencil**. The drawer's second tap opens it: **tap BRUSH (43, 655) twice** — the first tap takes
the tool up, the second opens the brush drawer. The brush list is the right column, **x ≈ 648**;
**Pencil is row 3, y ≈ 1607** when the drawer opens on "All". Screenshot to confirm before tapping a
row, because the shelf you land on depends on which brush you had active.

Set size once and leave it: tap **SIZE (43, 892)** → the slider appears at ≈(311, 928). Pick about
the **third** of the way along. **Done** by tapping outside the panel (which also swallows the tap).

Now, **four strokes with the pen, same size, ~200px long, in the empty middle of the canvas:**

| # | what to do | what it is testing |
|---|---|---|
| 1 | **Upright, light pressure**, one slow pass | Does the grain read at all? Is it too faint? |
| 2 | **Upright, firm pressure** | Does it darken *and* get *rougher*, or just darker? |
| 3 | **Lean ~45°**, light then firm in the same pass | **The main event.** Tilt should widen the mark and pick up *directional* streaks along the lean, not just get fatter. |
| 4 | **Lean the other way**, same pressure | Streaks must follow the lean. If they don't change direction, directional grain (JB-9.08) is not reaching the tip. |

**Then shade** — the reason the lean matters. Short back-and-forth strokes at 45°, building tone
slowly, then **lean back upright and add the final highlights**. Highlights only look right if the
grain has direction; a directional grain that does not rotate will show it here more clearly than any
single test stroke. Judge: does the tone build smoothly or does it band? Do highlights sit *in* the
grain?

### 2. Flat Paint — pickup across two colours (the hardest thing here)

Pick **Flat Paint**, set a **large** size (it is about carrying material, not about detail — try
80-120px), and make **two fat separate marks first**: one pure red, one pure blue, with clear empty
paper between them. Screenshot to confirm they are not touching.

Then **one slow drag through both, in a single stroke**. Flat paint picks up material at pen-down, so
the mark should **carry red into the blue and blue into the red, and leave a visible trail of the
first colour on clean paper**. This is the single most demanding behaviour in the brush set and the
one least likely to work. Note specifically: where it crosses, is the blend *physical* (material
dragged along) or is it just alpha fading? Does it run out of paint partway?

Repeat with the pen **reversed in lean**, and once more at **firm pressure** — pickup should not
depend on tilt, and if it does, that is a finding.

### 3. Erase

Tap **ERASER (43, 813)** (one tap — it activates; a second opens the eraser brushes). **Draw straight
back over the red/blue pickup stroke with the pen.** Check: does it erase to clean paper, to a smear,
or to a faint ghost? Erase at a **smaller** size than you drew with (drag **SIZE** down) and at a
**larger** one (drag up) — both are worth seeing, and the eraser has its own size.

### 4. Undo / redo — four times, and this one has a trap

Tap **UNDO (131, 52)**. Verify each of these on screen:

1. The erase is gone.
2. The pickup stroke is gone. **Red and blue marks must BOTH still be there** — if undo removes only
   part of a stroke that crossed two colours, that is a bug worth reporting immediately.
3. The shading is gone.
4. **REDO (210, 52)** brings the shading back, and redo again brings the pickup stroke back **whole**.

The trap: **a single tap on the canvas is not a stroke and does not enter the history.** Do not tap
the canvas to "make a test mark" and then count on undo — if you did, one undo will appear to do
nothing. The buttons start greyed (alpha 90) until a stroke commits.

### 5. Save, then reopen (this must survive a real drawing)

Two separate things, and the difference matters.

**Autosave is automatic, 30 s after the last stroke**, and also on backgrounding. Force it rather
than waiting:

```powershell
& $adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 2
& $adb shell am start -n cc.joycreator.joybrush.brushtest/cc.joycreator.joybrush.android.JoyBrushActivity
Start-Sleep -Seconds 4
```

**Everything must be back**: the four pencil strokes, the shading, both colour marks, and the paper
you chose. Screenshot and compare against step 2. This is the check that matters most for the app's
reliability — it is the thing a person loses work to.

Then explicitly: ⋯ (1017, 52) → **Save a copy…** (711, 158) → pick a location in the system dialog.
Confirm the toast **"Copy saved"**. Then ⋯ → **Recent drawings…** (711, 316) and reopen it. A copy is
written to your chosen file and does not disturb the working drawing.

*If a tap on the canvas ever feels like it "ate" a stroke, do not fight it — screenshot and re-read
the coordinate table. And if the paper sheet is open when you draw, that is fine: it is deliberately
non-modal, so the canvas still draws under it.*

## What is NOT being tested here

Zoom, the frame/animation strip (removed on this line), the reference picture, guides, and the
four-finger chrome hide. Rotation and zoom are gesture-only and need two fingers on glass. Those are
separate checks with their own owner.
