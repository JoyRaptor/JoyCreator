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
| D5 | The export overlaps two clips at a transition, so everything after is earlier in the file by the transition's length. Overlays, text and captions are shifted to match (`editorTimeOffsetFor`). Music and voiceover on audio lanes (`buildLaneAudioSequence`) and the sound of a picture-in-picture (`buildOverlayAudioSequence`) are not | After two 0.6 s transitions, a voiceover placed at 10 s plays 1.2 s late against the picture in the exported file | confirmed in the export code; the preview side not traced. Changes what exports contain: needs a device comparison first |
| E2 | The same shortening leaves the export short of the timeline's length, and the "tail filler" pads it with black to make up the difference | A project with transitions ends with black frames as long as its transitions added together | confirmed. Same root as D5 |
| D6 | In the export, a clip's fade, opacity keys, position keys and volume curve are read from the start of the exported piece. After a transition that piece starts part-way into the clip, so they are read early by the transition's length | A 1 s fade-out on a clip that follows a 0.6 s transition never reaches black; a fade-in pops | confirmed in the export code |
| E5 | The piece of a clip that plays during a transition gets the clip's plain volume only: no volume curve, no audio effects | A clip that fades its sound out into a transition does not fade there | scout |
| E6 | The repeats of a looped clip play at full volume whatever the clip's volume is | A clip at 30% volume with "loop after": the loops are loud | **fixed 11-11**: the repeats get the clip's volume (not its curve). Built; not heard on a phone |
| D4 | The sound of a picture-in-picture divides by its speed twice when working out where the next one starts | With two picture-in-pictures and the first not at normal speed, the second one's sound is late or early | **fixed 11-11** (see C) |
| D3 | A picture-in-picture at a speed other than normal: its window length and its sound follow the speed, its picture does not | Picture and sound of the same picture-in-picture show different moments | scout |
| D1 | Preview captions apply the clip's speed to a position that is already in source time | In the editor, captions on a clip at 2x run ahead of the sound, more and more; the export is right | scout (worked through by the scout; the lead has not re-derived it) |
| D2 | Caption-style keys are placed in source time; opacity, position and volume keys in timeline time | On a clip at 2x, a style key and an opacity key set at the same number fire 1.5 s apart | scout; needs an owner ruling on which is meant |
| D7 | Tapping exactly on a cut: the seek picks the earlier clip, the selection picks the later one | One frame off at every cut | scout |
| D8 | "Cut" spans inside a clip shorten it for Joybot and the split guard but not for the timeline, the view or (as far as the scout found) the export | Joybot thinks a clip with cuts is shorter than the timeline shows | scout; needs an owner ruling on whether cuts shorten the timeline |
| D9 | Lengths are cut off to whole milliseconds in some places and rounded in others | Each split of a speeded clip can lose 1 ms | scout |
| D10, D11 | Seek units inside the gapless player for loop repeats at a speed other than normal; the export clamps a clip to its source length and the preview does not | Not worked through | scout, marked "likely" by the scout |

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
| E11 | The frozen frame for "hold the last frame" is kept under the clip's id only: trim the clip and export again, and the old frame is used; and it is the nearest key frame, not the last frame | scout |
| E12 | A reversed piece (ping-pong) is written straight to its final name; an export killed mid-way leaves a cut-off file that every later preview and export trusts | **fixed 11-11** (see C) |
| E13 | Parts of an export are reused from the cache without checking whether a source picked from the gallery changed | scout |
| E14 | A bad snapshot makes the export service stop without ever showing its notification, which Android punishes with a crash | scout |
| E15 | Nothing checks free space before a long export | scout (the lead found the same for recording) |
| E16 | The loudness pass can delete the finished export if two renames in a row fail | **fixed 11-11** (see C) |
| E17 | An animated picture layer copies a full-frame picture for every frame of the export and never frees the copies until the end (about 240 MB a second at 1080p) | scout: the likeliest cause of long exports slowing down |
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
| R8 | After Joybot or another screen changes the project, the editor swaps the project in but does not rebuild the audio players | scout (marked "likely") |
| R9 | Merging a follow-up change into the top undo step crashes when that step came from disk or from Joybot (it has no action to call) | **fixed 11-11** |
| R10 | Small ones: a player that fails to build shifts every later audio player onto the wrong clip; a failed undo restore still says "Undid"; a picture-data cache never refreshed after a trim; a temporary file left behind when an import fails | scout |

Proof for the fixed rows is in the commit that fixed them and in [SAFE-1](../jobs/SAFE-1_work_loss.md).

## Not reviewed yet

The rest of the editor screen (about 45,000 lines of drawers and sheets), the timeline view's drawing and
touch code, the compositor and player in full, Joybot and the AI tools (31,000 lines), transcription,
sprites, puppets and Avatar, VisLab beyond its saving, the Library and lobby, recording, and all of
JoyBrush outside its save lane.
