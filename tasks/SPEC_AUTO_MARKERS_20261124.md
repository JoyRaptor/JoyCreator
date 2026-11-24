# Auto-markers: moments Detect found become timeline marks (FOLD-1 piece, spec before building)

Written 2026-11-24 by the website lane from the code on `joy-creator` (`7266fa755`). **A spec only: nothing is built.** It is the
"own small spec before building" that [FOLD-1](jobs/FOLD-1_fold_fadcam_features_in.md) asks for. Owner decisions are listed at the end.

## What the person gets
They filmed something long with **Detect** (Smart Detection) switched on: a person walked in, a dog barked, something moved. They put that
recording in Studio. A small chip over the timeline says **"Detect found 12 moments in this recording"** with a button **Add as marks**.
One tap lists the moments (a picture, what it was, when, how sure); they tick the ones they want, or take all; Studio drops a named mark
at each (e.g. "Person 0:42") and one Undo takes them all back. They jump from mark to mark instead of scrubbing an hour of footage.

It is **offered, never automatic**: nothing is added to a project the person did not ask to change.

## What exists today (checked in the code)
- **The moments:** `forensics/data/local/entity/AiEventEntity` (Room table `ai_event`): `media_uid`, `event_type`, `class_name`, `start_ms`, `end_ms`,
  `confidence` / `peak_confidence`, `bbox_norm`, `thumbnail_ref`, `status`, `media_missing`. `AiEventDao` already has
  `SELECT * FROM ai_event WHERE media_uid = :mediaUid ORDER BY start_ms ASC`.
- **Which recording a clip is:** `MediaAssetEntity` has `media_uid`, `current_uri`, `display_name`, `duration_ms`. A Studio clip carries its source uri
  (`Clip.getSourceUri()`), so the join is `current_uri == clip uri` (the file can have moved: `media_missing` and `current_uri` are kept up to date by the
  indexer, so match by uri first, then by display name plus duration within 1 s).
- **The marks:** bookmarks are a list of timeline times (`project/BookmarkStore`, a sidecar file per project); named marks and In/Out regions are STU-3's
  (`ui/faditor/markers/**`, `TimelineMarksPopup`; the whole thing undoable, saved with the project). Joybot's `add_marker` is held until marks join undo (STU-3 card).

## What to build (small, in this order)
1. **`AutoMarkMapper` (pure Java, no Android):** input: the clip (source in / out ms, speed, reverse flag, timeline offset) and a list of
   `(startMs, endMs, label, confidence)`; output: `(timelineMs, label)` for each moment that falls inside the trimmed part, at the moment's start (or its
   midpoint if it started before the trim). Moments outside the trim are skipped and counted ("3 more are in parts you cut"). Speed and reverse change the
   mapping; a clip used twice gets marks for each use. Host test with planted faults (wrong offset, ignored trim, reversed clip) that must fail first.
2. **`MomentsReader` (Android, small):** reads the events for a clip's recording through `AiEventDao` off the main thread; null when the forensics
   database is empty or the file is unknown; never throws.
3. **The chip and the list sheet** in Studio's marks area, in the sheet language (`SheetKit`), with the part-preview rule (a small picture per moment from
   `thumbnail_ref`), hover labels, and wording that says **moments / Detect**, never "events", "evidence" or "forensics".
4. **Commit:** one undo step that adds the chosen marks through the STU-3 API; marks are plain marks afterwards (the person can rename or delete them).
5. **Joybot:** a help topic (`detect_marks`), a tip, and an idea button "Add the moments Detect found" when the open project has a clip with moments.

## Edge cases the spec settles
- No moments, or Detect never ran on that file (imported video, an old recording): no chip at all.
- A recording whose file is missing (`media_missing`): no chip; the clip itself already shows its missing state.
- Very many moments (a day of motion): the list groups by minute and offers "Only the best 20" (highest confidence first); the default tick is the best 20,
  never hundreds of marks.
- The clip is trimmed, split or moved after the marks are added: the marks stay where they are (they are the person's now); adding again de-duplicates marks
  within 0.5 s that carry the same label.
- The person's privacy: everything is read from the phone's own database; nothing is sent anywhere.

## Proof needed before it ships
- Host: `AutoMarkMapper` tests (trim, speed, reverse, two uses, de-duplication, the 20-best cap) that fail on planted faults.
- Cloud build; then on the Note 9: record 30 s with Detect on and a hand waved in front, put it in Studio, see the chip, add the marks, undo, redo, reopen.
  (That needs the phone and a short real recording: owed.)

## Owner decisions
1. **Offered or automatic?** This spec says offered. If the owner wants the marks added the moment a detected recording is dropped in, say so (a setting would then be needed).
2. **Which moment kinds?** Person, animal, vehicle, motion, sound: all of them by default, or only what the person picked when recording?
3. **Chapters:** should clusters of moments (a busy 2 minutes) become one region instead of many marks? (A later step; marks first.)
