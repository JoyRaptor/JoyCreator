# Code review, 2026-11-11: Studio export, the timeline clock, the editor

By Claude Fable 5.2 (lead) with three read-only scouts (Claude Sonnet 5.5). Source: `joy-creator` at
`5fe9c0c36`. Oils, watercolour and the Atlas are another session's and were not reviewed.

**How to read this.** A scout's finding is a lead, not a fact. The State column says what the lead did with
it: **fixed** (the lead read the code, agreed, fixed it, and says how it was proved), **confirmed** (the lead
read the code and agrees; not fixed yet), or **scout** (not yet checked by the lead: verify before acting).
Line numbers are as of the source commit and drift.

## A. The timeline clock (job STU-5: "one authority for timeline time to source time")

The scout found every place that turns a time on the timeline into a time in a source file, and worked the
arithmetic where two of them give different answers. The one-line summary for the owner: **a transition
makes the export shorter than the timeline the editor shows, and only some things were corrected for
that.** Pictures laid over the video were corrected (ledger §2d); music, voiceover, fades and volume curves
were not.

| # | Where the clocks disagree | What a person sees | State |
|---|---|---|---|
| D5 | The export overlaps two clips at a transition, so everything after is earlier in the file by the transition's length. Overlays, text and captions are shifted to match (`editorTimeOffsetFor`). Music and voiceover on audio lanes (`buildLaneAudioSequence`) and the sound of a picture-in-picture (`buildOverlayAudioSequence`) are not | After two 0.6 s transitions, a voiceover placed at 10 s plays 1.2 s late against the picture in the exported file | **fixed 11-12** (see "The fix" below). Preview side traced: the editor plays every clip whole and draws the blend over the seam, so its sound is placed on the editor's clock and agrees with its picture; the file now places sound the same way its pictures are placed. Note 9 A/B: beep 18.643 s, now 13.243 s (the predicted 5.400 s earlier) |
| E2 | The same shortening leaves the export short of the timeline's length, and the "tail filler" pads it with black to make up the difference | A project with transitions ends with black frames as long as its transitions added together | **fixed 11-12**: the filler runs to where the project's end lands in the file. Note 9 A/B: 20.000 s with 5.4 s black, now 14.600 s, none. The export sheet shows that length |
| D6 | In the export, a clip's fade, opacity keys, position keys and volume curve are read from the start of the exported piece. After a transition that piece starts part-way into the clip, so they are read early by the transition's length | A 1 s fade-out on a clip that follows a 0.6 s transition never reaches black; a fade-in pops | **fixed 11-12**: the piece tells them where it starts on the clip's own clock (video pass and the separate sound pass of long and sound-only exports). Note 9 A/B, red clip with 1 s fades: was black at its first own frame and 70% at its last, now 60% (151/252) and 6/252 |
| E5 | The piece of a clip that plays during a transition gets the clip's plain volume only: no volume curve, no audio effects | A clip that fades its sound out into a transition does not fade there | scout |
| E6 | The repeats of a looped clip play at full volume whatever the clip's volume is | A clip at 30% volume with "loop after": the loops are loud | **fixed 11-11**: the repeats get the clip's volume (not its curve). Built; not heard on a phone |
| D4 | The sound of a picture-in-picture divides by its speed twice when working out where the next one starts | With two picture-in-pictures and the first not at normal speed, the second one's sound is late or early | **fixed 11-11** (see C) |
| D3 | A picture-in-picture at a speed other than normal: its window length and its sound follow the speed, its picture does not | Picture and sound of the same picture-in-picture show different moments | scout |
| D1 | Preview captions apply the clip's speed to a position that is already in source time | In the editor, captions on a clip at 2x run ahead of the sound, more and more; the export is right | **fixed 11-11**: the lead re-derived it (the same position is divided by the speed two lines away to get timeline time). Built; not seen on a phone |
| D2 | Caption-style keys are placed in source time; opacity, position and volume keys in timeline time | On a clip at 2x, a style key and an opacity key set at the same number fire 1.5 s apart | scout; needs an owner ruling on which is meant |
| D7 | Tapping exactly on a cut: the seek picks the earlier clip, the selection picks the later one | One frame off at every cut | scout |
| D8 | "Cut" spans inside a clip shorten it for Joybot and the split guard but not for the timeline, the view or (as far as the scout found) the export | Joybot thinks a clip with cuts is shorter than the timeline shows | scout; needs an owner ruling on whether cuts shorten the timeline |
| D9 | Lengths are cut off to whole milliseconds in some places and rounded in others | Each split of a speeded clip can lose 1 ms | scout |
| D10, D11 | Seek units inside the gapless player for loop repeats at a speed other than normal; the export clamps a clip to its source length and the preview does not | Not worked through | scout, marked "likely" by the scout |

**The fix, 2026-11-12 (Claude Opus 5.5, continuing this report).** `model/TimeMap` is the one answer
to "where does editor time t land in the file": `t` less, for every seam, how far `t` is past the seam
point, capped at that transition's clamped length. The incoming clip's head (played under the blend)
folds onto the blend's end, so the map never runs backwards. `editorTimeOffsetFor` is now its
`offsetBeforeClip` (same sum, checked against the old loop on 400 random timelines); the transition
clamps moved into it unchanged. Users: audio lanes, picture-in-picture sound, the tail filler, a
range export's cut points inside a part (`ChunkRun.compOf`, which was a straight line per part), the
clip-local clock of a piece after a transition (`clipLocalMsAt`: opacity and fade, spine placement,
volume curve), and the export sheet's length. Proof: `tools/jvm-harness/run-timemap.sh` 826 checks
(pins the §2d device numbers, 5501 to 4901 and 13726 to 13126); Note 9 A/B of one fixture project
(`stu5-timemap-20261112` on the sandbox: ten 2 s stills, nine 0.6 s transitions, the last a red still
with 1 s fades, a 1 s beep at editor 18.6 s), exported as `stu5-A.mp4` (11-11 build) and `stu5-B.mp4`
(this build) in the Joy Creator folder; every number matched a prediction written down before the
run. **Still open from this section:** E5 (and its picture twin: during the blend both clips show
without their fade or opacity, in the preview and the file alike), D2, D3, D7 to D11; the editor's
playhead during a transition shows the file's clock (`getOutputPositionForCurrentPlayback`) and jumps
at the end of the blend, a display quirk not traced further.

**Recommended shape of the fix** (the scout's, and the lead agrees): one small class with no Android in it,
`model/TimeMap`, that every reader asks: where on the timeline a clip starts, which clip a time falls in
(the later clip owns a cut), clip time to source time with one rounding rule, and timeline time to file time
(subtracting the transitions before it). Tests that pin today's numbers come first. Order: the preview
captions and the seek-at-a-cut rule (display only), then rounding, then the export's fades and volume curves,
then audio lanes and the black tail **together, compared on a phone**, then cuts last.

## B. Export

| # | Finding | State |
|---|---|---|
| E1 | A long export in parts (12 minutes and up) fails with "Part N came out the wrong length" when a part holds a looped or frozen clip, after doing the work: the expected length leaves the loop out (`rangeExpectedMs`), while the exact figure is already known (`chunkCompDurs`) | **fixed 11-11**: each part is checked against the length it was planned at, and the plan measures the last part from where it really starts. Built; no 12-minute export run on it yet |
| E7 | When copying the finished file into a chosen folder fails part-way (disk full), the cut-off file stays under the finished name and the export still reports "completed" | **fixed 11-11**: the cut-off file is removed, and the whole export is moved out of the temporary folder into the app's own exports folder. It still reports "completed" (with that place): telling the person the copy failed is left |
| E8 | Cancel during "Preparing" does not tell the editor, and the export can start anyway | scout |
| E9 | An out-of-memory or a missing native library in the "joining" or "loudness" thread leaves the export stuck "exporting" for good (only `Exception` is caught) | scout |
| E10 | Cancel during "Joining" does not stop the join, which runs on for minutes | scout |
| E11 | The frozen frame for "hold the last frame" is kept under the clip's id only: trim the clip and export again, and the old frame is used; and it is the nearest key frame, not the last frame | **fixed 11-11**: named by the moment and the source, and the exact frame is asked for first. Built; not seen on a phone |
| E12 | A reversed piece (ping-pong) is written straight to its final name; an export killed mid-way leaves a cut-off file that every later preview and export trusts | **fixed 11-11** (see C) |
| E13 | Parts of an export are reused from the cache without checking whether a source picked from the gallery changed | scout |
| E14 | A bad snapshot makes the export service stop without ever showing its notification, which Android punishes with a crash | scout |
| E15 | Nothing checks free space before a long export | scout (the lead found the same for recording) |
| E16 | The loudness pass can delete the finished export if two renames in a row fail | **fixed 11-11** (see C) |
| E17 | An animated picture layer copies a full-frame picture for every frame of the export and never frees the copies until the end (about 240 MB a second at 1080p) | **fixed 11-12, but the premise was narrower than written.** Confirmed in `ImageOverlayFrameOverlay` (a new copy per animated frame), and found the same in three more passes: `SpriteBlendGlEffect` (a new full-frame picture every frame, even off screen), `TextFxGlEffect` (styled text) and `GlTransitionFrameOverlay` (every GL transition; its `release()` also never freed its texture). All four now hand over two reusable pictures in turn (`OverlayFramePair`, the pattern `PipFrameOverlay` and `CompositeExportOverlay` already shipped) and a fixed empty picture when there is nothing to draw. Today's routing sends plain pictures to `GlImageOverlayEffect` and bent ones to the mesh path, so `ImageOverlayFrameOverlay` now serves only the mesh fallback: the review's "likeliest cause of long exports slowing down" does not hold for it. Note 9, 48 s project with a blended sprite and a bent picture, before and after: 33 s both, memory peak 332 vs 312 MB, all 1,426 decoded frames identical (the sprite animates in both). **Long exports do not slow down on this build:** a 9 min 20 s single-pass export (34 clips, a GL transition at every join, a sprite, a title and a picture across the whole length; just under the 12-minute point where exports split into 5-minute parts) took 461 s, 40 to 55 s per tenth of the file from first to last with no upward trend, export-process memory flat at 200 to 260 MB (peak 308), phone up to 61 °C; GL transitions animate (24 different frames in 0.8 s). So the 09-22 slowdown is not reproduced; nothing here shows these copies were its cause |
| E19 | During a GL transition the export draws no overlays at all: the transition piece skips every overlay pass (`assembleClipVideoEffects`: `if (!isTransitionItem && overlayW > 0 ...)`), so titles, pictures and sprites blink off for the length of each transition in the file | **confirmed 11-12** in the code and seen in the Note 9 long export above (the picture and the star sprite gone for the crosswarp, back after). Older than the 11-12 changes; the preview side not checked. Not fixed |
| E18 | Each clip's source is looked up about six times per build, each with a 64 KB read; the build runs once per part | scout |

## C. The editor

| # | Finding | State |
|---|---|---|
| R1 | "Extract audio" puts every sound sample of the clip in a list of boxed numbers: several hundred MB for a five-minute clip, an out-of-memory crash | **fixed 11-11** |
| R2 | While playing, every 50 ms tick asks Android for the audio devices and two system properties to estimate the sound delay (the measured value is never filled in) | **fixed 11-11** |
| R3 | The timeline view's verbose logging switch is ON (`VLOG = true`; its own comment says constant-false): every touch and playhead step builds a string, runs four pattern matches and writes two logs | **fixed 11-11** |
| R4 | Every undo and redo throws away and rebuilds all the audio players, even for a colour change | scout |
| R5 | The "is every source still there" check reads the project's lists on its own thread while the opening editor is still changing them, with nothing to catch a failure: a rare crash on open | **fixed 11-11** |
| R6 | "Extract audio" mutes whichever clip is selected when it finishes, not the clip it took the audio from | **fixed 11-11** |
| R7 | The Consolidate result is shown without checking the editor is still open | **fixed 11-11** |
| R8 | After Joybot or another screen changes the project, the editor swaps the project in but does not rebuild the audio players | **fixed 11-11**: it rebuilds them, as undo and redo do. Built; not heard on a phone |
| R9 | Merging a follow-up change into the top undo step crashes when that step came from disk or from Joybot (it has no action to call) | **fixed 11-11** |
| R10 | Small ones: a player that fails to build shifts every later audio player onto the wrong clip; a failed undo restore still says "Undid"; a picture-data cache never refreshed after a trim; a temporary file left behind when an import fails | scout |

Proof for the fixed rows is in the commit that fixed them and in [SAFE-1](../jobs/SAFE-1_work_loss.md).

## Not reviewed yet

The rest of the editor screen (about 45,000 lines of drawers and sheets), the timeline view's drawing and
touch code, the compositor and player in full, Joybot and the AI tools (31,000 lines), transcription,
sprites, puppets and Avatar, VisLab beyond its saving, the Library and lobby, recording, and all of
JoyBrush outside its save lane.
