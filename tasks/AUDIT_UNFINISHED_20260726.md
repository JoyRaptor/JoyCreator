# AUDIT: unfinished / deferred / half-landed work across all planning docs

**Run 2026-07-26 against HEAD `108cff9`** (BEFORE that night's transcript-sharing commit —
see the note on item 1.1). Method: sweep all ~80 docs in `tasks/` for unfinished-work
markers, then **verify each candidate against the code** rather than trusting the doc. Docs
are frequently stale in the "still says TODO but was built" direction, so Tier 5 lists
things that read as open and are actually done — do not re-scope those.

**Why this exists:** `PLAN_transcript_windowing.md` described a 5-step plan; steps 1, 2, 4
and 5 landed, step 3 never did, and no migration was ever written for projects the old
behaviour had already damaged. Nobody knew until a user hit it. This audit hunts every
remaining instance of that pattern.

> **UPDATE (same night, after this audit ran):** item 1.1's missing migration is now partly
> addressed — `TranscriptSharing` + `ProjectStorage.shareTranscriptsWithBackup` collapse
> per-clip forks and reconstruct legacy partitions (see `SPEC_TRANSCRIPT_SHARING.md`).
> **Step 3 (the panel) is still open**, and that spec argues it should NOT be implemented as
> originally written.

> ## STATUS BOARD (updated 2026-07-26 ~03:10)
>
> | item | state | commit |
> |---|---|---|
> | 2.3 preset crops in transitions | **EXPORT LEG CLOSED** — preset↔custom exports now bit-identical (0/798 frames) vs 235/798 before; regression control passed. **Preview leg NOT shipped: needs a user decision** (the live preview never renders a named preset crop at all) | `see §0z` |
> | 1.1 transcript windowing step 3 | **CLOSED** — built as the DECIDED navigator (current-clip highlight + tap-a-dimmed-word-jumps-to-that-clip), device-verified against blind offline predictions; also fixed the shared-instance highlight freeze at a split seam | `bd84402` |
> | 1.2 LAYER schema hole | **CLOSED** — stamp v11, offline-proved + device-verified | `d77daf3` |
> | 2.1 caption size in preview | **CLOSED** — device A/B 3%↔20%, plus the re-bind-blanks-captions bug found doing it | `eaff34b` |
> | 2.2 audio caption size persisted | **CLOSED** — device round-trip 0.15 in → 0.15 out | `eaff34b` |
> | 1.3 `layerId: null` | **CLOSED for the 4 named sites**; ~219 sibling reads still exposed, see the commit | `7a09eb6` |
> | 1.4 downgrade drill | **RAN 7/7 on device** — guard holds, dialog fires, file byte-identical, export works. Found one real gap: the undo-history sidecar was written for a read-only project | see below |
> | guard hygiene | zero-match now a hard failure in both python guards | `b8b6234` |
> | 2.7 neutral substrate | items **2, 3** (preview/eye, device A/B) and **8** (export frame diff) now RUN and pass; 4 re-confirmed. Items 5–7 still open (gesture injection drifts) | `c8cee20`, `9c8e8bc` |
> | 2.6 cross-type Z | **CLOSED** — acceptance 1, 2 and 4 all run on device. Z3's visual debt and Z4's frame-diff debt are cleared | see `SPEC_CROSSTYPE_Z.md` |
> | 2.5 PiP audio | **export leg PROVED** — fitted gain 0.993, corr 0.999, silent before the offset, and no audio track at all when not opted in. So "doubled audio" is ruled out. **Preview leg still UNVERIFIED (needs a human to listen); acceptance 4 still open (no dual-stream pair project exists)** | `cc1691a` |
> | 2.4 captions after a split | **CLOSED, and the audit had the direction backwards** — the EXPORT is correct (it windows per clip); the PREVIEW over-rendered the neighbouring clip's words. Observed on a real transcript, fixed, device-verified | `1a4bcc8` |
> | timer export (handoff §7) | **ANSWERED** — timer renders and counts in an export: 0:04/0:03/0:02/0:01 at the right times, 4/4 | `21f518a` |
>
> New reusable tooling: `tasks/export_ab_diff.py` (absolute-geometry export frame diff, with a
> `--check-asym` gate that refuses fixtures symmetric enough to hide a flip),
> `tasks/export_audio_probe.py` (fits a source's amplitude inside an export: 1.0 = once,
> 2.0 = doubled) and `tasks/schema_layer_stamp.py`.
>
> Next by the recommended order: the **Tier 3** items, then Tier 4. 2.3's export leg is closed
> (see §0z of the handoff; note the RE-SCOPED block's premise about the preview turned out to be
> false, and the preview leg is now a user decision, not a code task).
>
> ### TIER 3 RE-VERIFICATION (2026-07-26, a review pass over the current tree)
>
> Two Tier 3 items rest on premises that are no longer true. **I re-read these two sites myself
> and confirm them:**
> - **3.5 "old audio path still double-rendered" — PREMISE DEAD.** The legacy draw is behind a
>   mutual-exclusion guard, `if (!audioClips.isEmpty() && audioLayerTracks.isEmpty())`
>   (`timeline/EditorTimelineView.java:2218`), whose own comment names the "two audio bars" bug
>   it prevents. The legacy and unified paths cannot both run. The scary "63 call sites" figure
>   also counts consumers of `getSelectedAudioIndex()`, which was already turned into a shim
>   that derives the index from the layer selection — they are consumers of a migrated
>   accessor, not 63 sites to re-anchor. What remains is dead-code removal, not correctness.
> - **3.7 "overlapping items never migrated" — PREMISE MOSTLY DEAD.** Three load-time
>   migrations exist and run before the timeline view is wired: `migrateSpriteLayers()`,
>   `enforceNoOverlapTextLanes()`, `enforceNoOverlapVideoLanes()`
>   (`FaditorEditorActivity.java:1141, 1156, 1164`).
>
> **3.7's audio question — RESOLVED by reading, and the contradiction was only apparent.**
> Audio and text/video use DIFFERENT, deliberate strategies, which is why there is no
> `enforceNoOverlapAudioLanes` and why the :1164 comment still says audio is "covered":
> - text/sprite/PiP: separated onto ANOTHER LANE, at LOAD time, preserving their time
>   (`enforceNoOverlapTextLanes` / `enforceNoOverlapVideoLanes`, `Timeline.java:651, 701`).
> - audio: SHIFTED IN TIME to the nearest free slot on its own lane, at ADD time only
>   (`Timeline.resolveAudioOverlap:438`, called from `addAudioClip` :410/:424; the `false`
>   overload exists so undo/restore can reproduce an exact position).
>
> So the real residue is narrow: audio clips that were PERSISTED overlapping stay overlapping
> across a load. Consequence is **cosmetic/interaction only** — two blocks drawn in one row and
> ambiguous hit-testing. It is NOT data loss and NOT wrong output: export mixes every audio clip
> regardless of lane. **Deliberately not built.** A load-time audio pass rewrites `layerId`s and
> persists on the next autosave, i.e. it is a data-touching migration, and there is currently no
> device attached to verify it against a real project. It also is not obviously the right repair:
> audio's existing contract is "never overlap in time", so lane-splitting would contradict it.
> *(Correction to the coupling worry in the review pass: adding audio lanes could NOT newly
> expose the legacy `drawAudioTrack` branch. `audioLayerTracks` is fed from `getAudioTracks()`
> (`FaditorEditorActivity.java:10775` → `EditorTimelineView.java:537`), which returns ≥1 track
> whenever any audio clip exists, so the list cannot be emptied by ADDING lanes.)*
>
> **3.2 sub-claims — I re-read these three sites and confirm all three:**
> - `removedSpans` really are source-time and clamped to `[inPointMs, outPointMs]` at consumption
>   (`Clip.java:773-779`), so each split half excludes the other's spans by construction. That
>   sub-claim is a **non-issue — drop it.**
> - `offerDualStreamPairInsert` has exactly ONE call site (`FaditorEditorActivity.java:22671`,
>   inside the playhead-insert path). "Entry point B" genuinely does not exist. **VERIFIED-OPEN.**
> - The speed-mismatch trim guard (`:24173-24178`) does `FLog.w(... "trimming master only")` and
>   trims the master. So it is not silent in the LOG but is entirely silent to the USER.
>   **VERIFIED-OPEN, and the real gap is user-facing notice.** Note both remaining 3.2 items (a
>   linked badge on clip blocks, a user-visible mismatch notice) are UI-visible additions →
>   **user design decisions, not mechanical fixes.**
>
> **Still NOT independently verified — treat as a lead:** that 3.3 is worse than written because
> six `AIToolExecutor` apply sites record NO undo at all, while the one site using
> `EditActions.AddClipAction` is arithmetically correct and must NOT be "fixed".
>
> Everything else below is untouched and still open.

---

## METHOD NOTE — what this audit's instrument CANNOT see (added 2026-07-28)

This audit swept task docs for **unfinished checklist items**. That has a structural blind spot,
found the hard way: architecture stated as a PREMISE in prose is not a checklist item, so a
checklist sweep cannot see it.

The case that exposed it. `PLAN_transcript_windowing.md`'s problem statement says "the transcript
belongs to the SOURCE; each clip is a window over it". Its implementation STEPS were only about
not destroying words on split and windowing the display consumers — moving storage from clip to
source was never listed. Every listed step landed, item 1.1 was verified and closed, and the
architecture was still unbuilt: each clip keeps its own full copy, copies diverge, and
`TranscriptSharing` had to be written to collapse the forks afterwards. Missed on three separate
passes over the same doc, because nothing in it was ever unticked.

The second signal was louder and also invisible to a docs audit: `TranscriptSharing`'s own header
says *"The code has never quite worked that way."* Code comments are where this debt confesses,
and a doc sweep does not read code.

**Second instrument, cheap, worth re-running:** grep the codebase for the phrases where
implementation-vs-intent drift admits itself, then check each against whether anything tracks it:

    grep -rn -i "never quite\|does not yet\|known limitation\|should really\|not actually\|for now" app/src/main/java --include=*.java

Run 2026-07-28. One real finding (the transcript ownership above). Two candidates checked and
CLEARED, recorded so nobody re-investigates them: `FaditorProject.bookmarksMs` does not round-trip
through project.json but is deliberately persisted by a `BookmarkStore` sidecar; `BlendMode`'s
non-NORMAL values are reserved model capacity with no UI exposing them and no code setting them.

Distinct from the OTHER failure this audit had — entries 1.2, 1.3 and 1.5 were STALE, already
fixed and never updated. That one is solved by verifying each item against the code before
working it. These are two different failure modes and they need two different instruments.

---

## TIER 1 — DATA-LOSS RISK

### 1.6 ~~NEW (found + FULLY device-verified 2026-07-26)~~ **FIXED 2026-07-27 (`5b06c4a`)**: undo after an app restart does NOTHING
Not previously in this audit, and **no AI involvement** — this hits every user who edits,
closes the app, reopens the project and presses undo.

`UndoManager.HistoryEntry.snapshotBefore` is named, and documented, as "Project JSON snapshot
captured BEFORE this action was applied" (`UndoManager.java:87-89`). It actually holds the
state **after** that edit, because `recordAction` (`:187-204`) captures the snapshot at
RECORD time and the callers mutate the model first (e.g. the trim path reaches
`recordTrimMaybeMirrored` at `FaditorEditorActivity.java:24196` with the new values already
applied). In-session undo hides this completely, because `undo()` prefers `entry.action`
(`:298-301`) and the action replays a precise inverse. The snapshot is only consulted when
there is no action — i.e. **after a restart**, when history is reloaded from the sidecar as
snapshot-only entries — and then it restores the state that already includes the edit.

**PROOF (Note 9, project `74e36000`), two independent measurements plus a positive control:**
1. *Root cause, at the file level, no UI timing involved.* One trim
   (`Trim [0–1929] → [0–614]`), background to persist, then read the sidecar directly:
   the stored `snapshot` for that entry contains `clip0.outPointMs = 614` — the AFTER value.
2. *User-visible effect.* Force-stop → reopen (`Loaded 1 history entries from disk` /
   `Restored 1 undo history entries`) → press undo. Log:
   `Undone (snapshot): Trim [0–1929] → [0–614]`, `(undo=0, redo=1)`. The saved project still
   holds `outPointMs = 614`. The trim was NOT reverted.
3. *Positive control.* The undo mechanism demonstrably RAN (badge 1→0, redo 0→1, log line),
   so this is not "undo never fired"; and the same trim undone IN-SESSION (action path)
   correctly restores 1929 — measured earlier the same day.

**Risk:** SILENT WRONGNESS, and it is the everyday case (restart is normal). Undo reports
success and the badge moves, so the user believes the edit was reverted.
~~**VERIFIED-OPEN.** Not fixed here — see the note below; it is core-path surgery.~~
**FIXED 2026-07-27 in `5b06c4a`** — via the "recommended direction" below, but WITHOUT
touching the 18 sites. Instead of normalising each call site, a **deferred rolling baseline**
makes the ordering irrelevant: the manager keeps the project state as of the last edit and
hands it to each new entry as its `snapshotBefore`, recapturing it on the looper tick AFTER
the edit handler unwinds (`SnapshotRestorer.scheduleBaselineRefresh` → `autoSaveHandler.post`).
That post-handler tick sees the final post-edit state whether the site recorded before or
after mutating — so the 18 record-before-mutate sites and the structural actions that
snapshot live state in their own constructor are all left alone. Both alongside-hazards named
below are fixed too (undo/redo now peek-apply-then-pop and return `false` when nothing was
restored; the plain snapshot path invalidates BOTH stacks, not just redo).

**PROOF (the positive control this section demanded).** Note 9, project `302da9ac`, on a
**record-BEFORE-mutate** site (Rotate) — deliberately the case the naive fix would over-revert:
1. *File level.* The persisted sidecar entry `Rotate 0° → 90°` holds
   `clips[0].rotationDegrees = 0` — the PRE value. The old build stored the after value.
2. *User visible.* Force-stop → reopen (`Loaded 23 undo history entries`) → undo. Log
   `Undone (snapshot): Rotate 0° → 90°`, `(undo=22, redo=1)`, and the saved `project.json` is
   **byte-identical to the pre-edit file** apart from `lastModified`.
3. *Control.* The undone file still DIFFERS from the post-edit file in exactly
   `rotationDegrees`, so the comparison is not vacuously passing.

Plus `tools/jvm-harness/UndoManagerTest.java` — 34/34, every check paired with a positive
control (including an identity control that a throttle burst really did share one baseline
object and the entry before it did not).

**❌ THE OBVIOUS FIX IS WRONG — DO NOT SHIP IT.** The tempting repair is "undo entry *i* by
restoring entry *i-1*'s snapshot, since that IS entry *i*'s before-state". A full sweep of all
115 `recordAction` call sites refutes its premise: **mutate-before-record is NOT universal.**
- **18 sites record BEFORE mutating**, so their `snapshotBefore` is already a correct
  pre-state, and the "restore the predecessor" rule would revert one edit too many for every
  one of them. Examples: rotate (`FaditorEditorActivity.java:5884` records, `:5885` applies),
  flip (`:5915`/`:5919`), speed (`:5689`), clip + audio volume/mute (`:4426`, `:4429`,
  `:4463`, `:4467`), delete clip (`:24277`), delete audio clip (`:12604`, `:24386`), split
  audio (`:24169`), reorder (`:1801`, `:5511`), replace source (`:2945`), canvas preset
  (`:6597`), audio trim (`:1895`), remove video overlay (`:12633`).
- **It would break the AI checkpoint in both directions.** `recordAiCheckpoint` stores a true
  pre-AI state (and bypasses the throttle, so it is always on disk). Under the rule, undoing
  the edit AFTER an AI step would restore the AI entry's snapshot = the pre-AI state,
  silently discarding the whole AI edit — landing on exactly the violet rows the user is most
  likely to be looking at.
- **The predecessor is frequently absent or non-adjacent.** `SNAPSHOT_MIN_INTERVAL_MS`
  (1500ms) skips capture for closely-spaced edits, and the persist step drops entries with a
  null snapshot, so the reloaded stack is a SUBSEQUENCE of real history. The byte budget can
  also evict snapshot-only entries outright. "Restore the neighbour" would therefore revert
  several un-snapshotted edits in one tap while the row still names only one.
- One site is incoherent under EITHER rule: the linked-pair trim (`:24215`) stores
  master-after + partner-before.

**Recommended direction instead:** normalise the ordering per-site so the field name matches
reality — make every call site mutate BEFORE recording (the 18 above are the smaller, bounded,
individually-verifiable set). Two things must be fixed alongside, whichever way it goes:
`undo()` pushes to redo and returns `true` even when nothing was restored (silent success on a
null snapshot), and the plain snapshot path invalidates only the REDO stack, so once restores
stop being value-identical no-ops, undoing past a snapshot entry into an in-session entry will
mutate an orphaned object graph — the same hazard `invalidateActionsForProjectSwap` handles for
the AI path. Redo needs no change: `snapshotAfter` is already captured correctly.
Whatever is chosen needs its own positive control: undo a known edit after a restart and
assert the saved file returns to the PRE-edit value.

### 1.7 NEW (found + device-verified 2026-07-26): reloaded undo history came back INVERTED
Separate from 1.6 and now **FIXED**. `UndoManager.loadHistory` iterated the persisted entries
oldest-first but appended with `addLast()`, the opposite end from the `push()`/`pop()` that
`recordAction` and `undo()` use — so after a restart the OLDEST edit sat on top of the stack.
Measured on the Note 9: a trim followed by an AI step reloaded as `-1 Trim, -2 Added a title
card`, i.e. the history popup listed the timeline of events upside down and the nearest undo
was the oldest edit. Fixed by pushing on the same end the rest of the class uses; the loop's
own comment already said "so the most recent ends up on top", which is what it now does.

### 1.5 NEW (found 2026-07-26): the undo stack survives an AI reload that replaces the project
Not previously in this audit. When the AI assistant edits the project on disk, the editor
reloads it on resume and does **`project = reloaded;`**
(`FaditorEditorActivity.java:1245-1272`) — but the undo stack is never cleared or rebound
(`undoManager` is constructed once, `:1109`; no `clear()` in that block). Every entry left on
the stack still describes the DISCARDED object graph. `UndoManager.undo()` (`:283-315`)
prefers `entry.action` when it is non-null, so there are two distinct failure modes:

- **In-session entry** (has an `action`): `entry.action.undo()` mutates the ORPHANED
  `Timeline`/`Clip` objects. The undo/redo counters move, the screen does not. A silent no-op
  that reads to the user as "undo is broken".
- **Snapshot-only entry** (loaded from disk, `action == null`): restores `snapshotBefore`
  (`:302-307`), i.e. the pre-AI project JSON — **silently discarding the AI's edits**, and
  anything else that happened since. This is the data-loss-shaped one.

**REPRODUCED ON THE NOTE 9 (2026-07-26 ~18:33), with a positive control.** Project
`74e36000`, one in-session edit (`Trim [0–1929] → [0–617]`, undo=1):
- **Control — undo BEFORE any AI edit:** log `Undone (action): Trim [0–1929] → [0–617]`,
  undo 1→0, and the clip VISIBLY returns to its full length (the `0.6s` tape label
  disappears). So undo works and the instrument can see it working.
- **Bug — same action, undo AFTER an AI edit:** background the editor, fire the AI apply
  path, resume (log: `AI modified project on disk — reloading from storage`; the AI's
  overlay is on screen; **undo is still 1 — the stack was NOT cleared**), then press undo.
  Log says `Undone (action): Trim [0–1929] → [0–617]`, undo 1→0 — and **nothing changes on
  screen**. Ground truth: the project saved on the next `onPause` still has
  `clip0 outPointMs = 617`, i.e. the model was never touched. The AI's overlay also
  survives, so the undo reverted nothing at all.
No LLM is needed to reproduce: `ApplyEditsActivity` is **exported** (`AndroidManifest.xml:129`,
action `com.fadcam.APPLY_EDITS`) and calls `AIChatState.signalModified`, so the whole sequence
drives from adb —
`am start -a com.fadcam.APPLY_EDITS --es project_id <id> --es edit_script '<json>'`.
The **snapshot-path variant** (undo silently restoring the pre-AI project and discarding the
AI's work) is reasoned from `:302-307` and is **still UNVERIFIED** — it needs an entry with
`action == null`, i.e. history loaded from disk in a fresh session.
**Risk:** SILENT WRONGNESS (action path, CONFIRMED on device) + DATA-LOSS (snapshot path,
unverified). ~~**VERIFIED-OPEN.**~~
**CLOSED — fixed in `4f0eee6` one hour AFTER this repro, and the entry was never updated.**
Re-verified against the code 2026-07-27. The repro above is timestamped 2026-07-26 ~18:33;
`4f0eee6` ("an AI edit is now one labelled, undoable, violet step") landed 19:31 the same
evening and IS an ancestor of HEAD. `FaditorEditorActivity.java:1296-1303` now calls
`recordAiCheckpoint(...)` — capturing the PRE-AI state from the copy still held, BEFORE
`project = reloaded` — and then `invalidateActionsForProjectSwap()`, which nulls the action refs
that would otherwise mutate the orphaned graph (the CONFIRMED failure mode above). Audit 1.6's
fix added `resetBaseline()` after the swap (`:1323`) so the next user edit records a correct
pre-state, and made the snapshot path invalidate BOTH stacks.
**PROVED:** `tools/jvm-harness/UndoManagerTest.java` §4 — `4b` the AI entry's before IS the
pre-AI state, `4c` the aiOrigin flag round-trips, `4d` undo after a RESTART restores the pre-AI
state. 34/34.
**The user chose option 2 on 2026-07-27** ("one undo reverts the whole AI change") — which is
exactly what shipped, so no behaviour change was needed. Options 1 and 3 are moot.
~~**NEEDS A USER DECISION — three options, all with UX consequences:**~~
*(Retained below for the reasoning about why option 2 was the right shape.)*
  1. `undoManager.clear()` on AI reload. Minimal and honest, but throws away the user's undo
     history every time the AI touches the project.
  2. Capture a snapshot-only entry ("AI edits") BEFORE `project = reloaded`, reusing the
     existing `snapshotRestorer` machinery (`UndoManager.java:193-199, 294-296, 337-340`), so
     one undo reverts the AI's change. Nicest behaviour; needs a call on granularity.
  3. Leave it and document that AI edits are outside the undo model — in which case audit 3.3
     should be rescoped from "transitions get lost" to that statement, and the misleading
     comment at `AIToolExecutor.java:1460-1463` ("one undoable step") must be corrected.
~~Independent of the choice, `EditScriptApplier.applyReorderClips` (`:812-854`) should stop
mutating live `Transition` objects after `clearTransitions()` with no retained pre-state
(`:818-819` is a SHALLOW copy; `:850` writes `t.clipIndex`), which makes a part-way failure
unrollbackable. That part is mechanical.~~
**The mechanical half is DONE 2026-07-27.** `applyReorderClips` now captures a true pre-state
(the clip order plus each transition's ORIGINAL `clipIndex`) before touching the model, and
restores it from a `catch (RuntimeException)` before rethrowing — so a throw mid-rebuild can no
longer leave a half-reordered timeline with transitions carrying indices for an order that was
never finished. The three-way decision above is UNTOUCHED and still needs the user.

**NEW, noticed while doing that (2026-07-27), NOT changed — needs a decision.**
`applyReorderClips` silently DROPS any clip missing from `newOrder` ("clips not listed are
dropped", `:834`). That is deliberate for a well-formed script, but it means a truncated or
partially-hallucinated `newOrder` from the model deletes the omitted clips with no error and no
undo entry that can restore them (see 1.5 above — the undo stack does not survive an AI reload
intact). Suggested: refuse the op unless `newOrder` is a permutation of the existing clip ids,
or append the unlisted clips in their original relative order. Both change AI semantics, so
it is the user's call rather than a mechanical fix.

### 1.1 Transcript windowing: step 3 never landed, and no repair for damaged projects
`PLAN_transcript_windowing.md:39-59`. Steps 1 (`Transcript.java:51`), 2 (`partitionWords`
`Timeline.java:369` and `EditScriptApplier.partitionTranscripts:780` both have ZERO callers),
4 (`AIToolExecutor.java:737,986`) and 5 (`FaditorEditorActivity.java:21315`) all landed.
**Step 3 VERIFIED NOT DONE** — all 7 panel binds still pass the full transcript
(`FaditorEditorActivity.java:7886, 20617, 20632, 21069, 21084, 21219, 21236`); no
`bindTranscriptPanel` helper exists; `TranscriptPanelView.setTranscript:122` takes no
in/out. **No transcript migration existed** (grepped every `migrate*` in
Timeline/ProjectStorage). Live consequence: the exact regression baking was added to prevent
now ships — after a split the panel wraps ALL words sequentially, so each half shows the
other's words misaligned.
**Risk:** DATA-LOSS (historical) + SILENT WRONGNESS (current). **CLOSED `bd84402`.**
*(Migration since built. Step 3 landed 2026-07-26 — but NOT as "window the panel to the
clip": the user decided the panel should keep showing the WHOLE source and become a
NAVIGATOR. The "each half shows the other's words misaligned" symptom above was already
gone by then, because post transcript-SHARING every clip of one source holds the same
whole-source transcript; what was missing was the current-clip highlight and the
cross-clip tap. Both built and device-verified — see HANDOFF §0z.)*

### 1.2 `TrackKind.LAYER` shipped with NO schema bump — an older build silently rewrites lanes
`SPEC_NEUTRAL_SUBSTRATE.md:180-198` declared "Storage: FREE… No schema bump", relying on
`TrackKind.fromName`'s VIDEO fallback (`layers/TrackKind.java:64-70`). The safety argument
does not hold: `FaditorProject.SCHEMA_VERSION` is still **10** (`FaditorProject.java:30`), so
creating a LAYER lane never raises it, so the downgrade guard
(`ProjectStorage.java:2080-2085`, fires only when on-disk > running) **never trips**. An
older v10 build coerces `kind=LAYER` to `VIDEO` and re-serializes it that way on autosave.
Since kind decides emission phase = band position, and band position IS paint order after
cross-type Z, the round-trip permanently changes what paints over what in preview AND
export. Same shape as the seeded-lane pre-emption bug.
**Risk:** DATA-LOSS (irreversible lane-identity rewrite). ~~**VERIFIED-OPEN.**~~
**ALREADY CLOSED — re-verified against the code 2026-07-27.** The audit text above is STALE.
`FaditorProject.SCHEMA_VERSION` is now **11**, not 10 (`FaditorProject.java:38`, with the v11
rationale documented at `:29-37` citing this audit item), and the stamp is wired: the
serializer raises the stamped version to `def.getKind().minSchemaVersion()` for every
`LayerTrackDef` (`ProjectStorage.java:1738-1741`), and `TrackKind.minSchemaVersion()` returns
11 for `LAYER` (`TrackKind.java:84-86`). So a LAYER lane now stamps 11, an older v10 build's
downgrade guard DOES trip, and the coercion round-trip is refused rather than silently written
back. A repro exists at `tasks/schema_layer_stamp.py`. **Do not re-scope.**

### 1.3 Explicit `layerId: null` makes the loader drop the object
`handoff.md` section 2. All four deserializers do `if (obj.has("layerId")) …getAsString()` —
`ProjectStorage.java:1518, 2142, 2239, 2457`. `JsonNull.getAsString()` throws; in the sprite
path it swallows the whole sprite. Only reachable via hand-edited JSON today, but any future
writer that serializes nulls (repair tool, import path, AI-generated project) silently
deletes objects. Four `isJsonNull()` checks. ~~**VERIFIED-OPEN.**~~
**ALREADY CLOSED in `7a09eb6` — re-verified against the code 2026-07-27.** The line numbers
above are stale. A `hasValue(o, key)` helper (`ProjectStorage.java:1439-1441`) returns
`o.has(key) && !o.get(key).isJsonNull()`, and every `layerId` read now goes through it:
clip `:1633`, audio clip `:2289`, overlay `:2402`, sprite `:2633` (the last three are even
belt-and-braces, re-checking `isJsonNull()` after `hasValue`). The commit's own comment at
`:1425-1432` records that it was reproduced on device with a fixture. **Do not re-scope.**

### 1.4 Schema downgrade-guard drill has never been run end-to-end
`DRILL_SCHEMA_DOWNGRADE.md`; ship-blocker #3 in `road_map.md:31`. The guard is real and
reviewed; the 7-step drill was never executed (`road_map.md:208`). Reviewing the guard is
what surfaced "the refusal was completely silent, so the user loses a session's work" — the
doc itself argues review is insufficient. Note item 1.2 is a hole this drill as written would
NOT catch, because the guard never fires. **VERIFIED-OPEN.**

---

## TIER 2 — PREVIEW/EXPORT DIVERGENCE

### 2.1 Caption size slider does nothing in preview, everything in export
`DIAG_20260626.md:125`, open a month. `CaptionOverlayView.java:49` declares
`sizeFraction = 0.060f` and line 177 is its ONLY other use — no setter; `bindCaptionData`
(`FaditorEditorActivity.java:19446-19452`) never touches it. The control is fully wired
(`applyCaptionSize` writes model + undo + autosave) and export reads the model
(`CompositeExportOverlay.java:236, 267`). Net: the user sizes captions blind.
**VERIFIED-OPEN.**

### 2.2 Audio-clip caption size is never persisted
Same doc line. `ProjectStorage.java:1224` writes `captionSizeFraction` for CLIPS only; the
audio serializer writes styleId + centerX/Y (`:1702-1703`) but not size; reader has a clip
match at `:1427`, none for audio. Reverts to 0.060 on reload after changing the export.
**VERIFIED-OPEN.**

### 2.3 Named-preset crops ignored during transitions
`PERF_SPEC_LONGFILE_20260718.md` "F12 LIMIT RE-SCOPED", explicitly NOT ATTEMPTED. The
`custom` path is done; the preset path short-circuits at all three sites —
`FaditorEditorActivity.java:8958`, `:9186`, `gltransitions/GlTransitionFrameOverlay.java:216`.
A preset-cropped clip blends UNCROPPED then snaps at the cut, identically in both paths.
**Fixing only the export leg CREATES a divergence** — read the RE-SCOPED block, not the older
one-line note. **VERIFIED-OPEN.**

### 2.4 Captions after a split: export windows the transcript, preview does not
Consequence of 1.1. Export builds captions from `t.windowed(in,out)`
(`CompositeExportOverlay.java:233, 265`); preview binds the raw full transcript
(`FaditorEditorActivity.java:19450`). A phrase straddling a split boundary can render in
preview and be dropped in export. **VERIFIED-OPEN** (asymmetry confirmed; straddle trigger
reasoned, not observed).

### 2.5 PiP audio: authority verified, plumbing on both sides is not
`SPEC_PIP_AUDIO.md:128-145`, acceptance 2 and 4 open. All code exists and the decision layer
is device-verified across four gate states — but nobody has listened to a PiP or exported
one. Failure mode is doubled audio in an export the user may not re-check. **VERIFIED-OPEN.**

### 2.6 Cross-type Z built in both paths, proven in neither
`SPEC_CROSSTYPE_Z.md` Z3/Z4. Z1/Z2 simulation-proven over 11 projects; the
absolute-geometry A/B frame diff was never run. Their own recorded lesson is that symmetric
proofs miss flips — exactly what a two-bucket partition can get wrong. **VERIFIED-OPEN.**

### 2.7 Neutral substrate: 7 of 8 validation-queue items never run
`SPEC_NEUTRAL_SUBSTRATE.md:258-291`. Items 1 and 4 done; open are #2 preview renders all
payload types, #3 lane eye hides all types, #5-#7 the gesture half, and **#8 the export A/B
frame diff**. Weight: item 1 alone found a real device bug (`4eda119`) that code review had
missed twice. **VERIFIED-OPEN.**

---

## TIER 3 — SILENT WRONGNESS / FUNCTIONAL GAPS

- **3.1 Dual-stream webcam file has no segment rollover.**
  `feature-dual-stream-recording-spec.md:89`; `WebcamEncoderPipeline.java:45-47`. On a long
  recording the screen side becomes N files and the webcam one — pair auto-detect and every
  mirrored op assume 1:1. **VERIFIED-OPEN.**
- **3.2 Dual-stream Phase-4 deferrals.** Trim mirroring is same-speed only and silently
  one-sided across a speed mismatch; no linked badge on clip blocks; split mirror untested
  against per-half `removedSpans`; auto-detect offered only on playhead-insert. LIKELY-OPEN.
- **3.3 `EditScriptApplier` excluded from the transition-index audit.**
  `AUDIT_TRANSITION_INDEX_UNDO.md`. The `:927-928` double-shift worry IS settled (deliberate:
  two clips inserted for a b-roll span). What remains: none of these paths use the
  `snapshotTransitions()`/`restoreTransitions()` discipline the rest of the audit
  established. Same subsystem that produced the session's biggest bug. LIKELY-OPEN.
- **3.4 "Consolidate Project" never built.** `PLAN_asset_browser_v2_layers.md:38-58` calls the
  review/manifest screen "the safety feature — REQUIRED". Grep finds no packaging feature.
  Narrower than it reads: `project://` paths + copy-on-insert DID land, so new inserts are
  self-contained; only retroactive pack-up/portability is missing. UX GAP. **VERIFIED-OPEN.**
- **3.5 Old audio path still double-rendered.** `LANES.md:344-350`. Doc estimates "~9"
  `getSelectedAudioIndex` sites to re-anchor; there are **63**. Significantly under-scoped.
  **VERIFIED-OPEN.**
- **3.6 Legacy `AudioClip.waveform int[]`** — a known schema-adjacent migration with no plan
  and no owner (`Opencode-work.md:740-750`). Inert now; DATA-LOSS if attempted without one.
- **3.7 Overlapping items in existing projects were never migrated**
  (`PLAN_LAYER_GESTURE_CONTRACT.md:63-64`) while `SPEC_NEUTRAL_SUBSTRATE.md:145-154` asserts
  one-lane-one-track as an invariant. Legacy overlaps exist in a state the resolver assumes
  impossible. LIKELY-OPEN.

---

## TIER 4 — UX gaps with open checklists

`PLAYHEAD_KINEMASTER_20260719.md:96-110` (10-item device checklist, all unchecked; bookmark
persistence reads as unwired) · `PLAN_LOOP_PINGPONG.md:110-121` (7 unchecked acceptances
incl. export parity frame-compare; the failure drill was **attempted 0719 and ABORTED at
~90% staged** on human presence, recipe recorded, never re-run — failure mode is whole-player
blackout) · ~~`PLAN_LAYER_GESTURE_CONTRACT.md:136-138` (temp `ROWGESTURE` logging still to be
stripped)~~ **RESOLVED 2026-07-29 as KEEP — see the note below the list** ·
`feature-visualizer-studio-spec.md:128-170,186` (live-viz draw 4.5-6.25ms vs 4ms
budget; `VISUALIZER_STRIP_FRACTION` hardcoded) · `PLAN_G9_LINK_ENGINE.md:175,189,301,310` ·
`GL_TRANSITIONS_HANDOFF_20260718.md:153` · `PERF_SPEC_LONGFILE_20260718.md:125` ·
`LONGFILE_FEEDBACK_20260716.md:50,58,141-152` (ANR root-cause owed — evidence captured, never
diagnosed) · `FEEDBACK_20260620.md` (oldest, some may be stale) · `road_map.md:615-734`
(phases 5.x/7.x/8.3 NOT STARTED) · `SPEC_NEUTRAL_SUBSTRATE.md:78-86` (audio sub-drawer on
video/master rows — not built, own lane) · `SPEC_PIP_AUDIO.md:114-126` (three deliberate
gaps: preview plays at most ONE PiP's audio; no volume ENVELOPE on export; loop extension not
reflected).

**`ROWGESTURE` — RESOLVED 2026-07-29 as KEEP, and the item was asking for the wrong thing.**
Verified against the code: `LayerGestureController.ROWGESTURE_DEBUG` is `false` (`:1031`) and
every log site is gated behind it (`:895`, `:1037`, `:1042`), so the probe costs nothing when off.
Its own comment already records it as deliberately "LEFT IN (marked TEMP)" for a
device-in-the-loop hand-test. Stripping it would delete a re-enableable instrument from
`LayerGestureController` — the exact subsystem LEDGER §2b's open, self-healing drag-latch bug
lives in, a bug that has fired in the wild and never been caught. It belongs with the retained
instruments (`SEEKRANGE`, `ENDEDNET`, `KFALIGN`, `PHDIAG`), not on a cleanup list.
**Do not re-scope this as work.**

**Do not "fix" the ~34 `TODO(strings)` markers** — `road_map.md:44-48` freezes them behind
the rebrand / de-politicize decisions.

---

## TIER 5 — STALE DOCS (read as open, actually done — do NOT re-scope)

| Doc | Claims | Reality |
|---|---|---|
| `DIAG_assetbrowser_20260626.md:108-110` | MISSING state/badge/export-block "not started" | All three built; export IS blocked (`FaditorEditorActivity.java:9701-9707`) |
| `FEEDBACK_20260706_audio_and_delineation.md:41` | audio-only export not built | Built (`ExportService.java:175,253,289,335`) |
| `PERF_SPEC_LONGFILE_20260718.md:365` | speed-change gapless deferred | Landed `3de40a2` |
| `PERF_SPEC_LONGFILE_20260718.md` F11/F12/F13 | "Uncommitted" | Committed; only the F12 preset sub-case (2.3) is open |
| `LANE_BADGES_AND_PREVIEWS_SPEC_20260714.md:92` + `FEEDBACK_20260717…:283` | section 4.5 migration not done | Contradicted at `:54` in the same file; `Timeline.migrateTrackEyeLockToObjects():2057` exists |
| `gl_transitions_params.md:6`, `PLAN_filters_color_text_transitions.md:91` | GL transitions not built | Long since built and device-proven |
| `HANDOFF_NEXT_20260622.md:56`, `…0623.md:55` | clip opacity keyframes not implemented | Probably stale (shipped `96b99d5`) — worth one grep before re-scoping |
| `todo_asset_browser.md` | 23 unchecked boxes | Superseded by the v2 plan + shipped browser; historical |

---

## Recommended order

1. **1.1 step 3** (~30 min mechanical; route all 7 binds through one `bindTranscriptPanel`)
   — but see `SPEC_TRANSCRIPT_SHARING.md`: show the whole source with the current clip
   highlighted, NOT the windowing the plan specified.
2. **1.2 LAYER schema hole** — cheapest real data-loss fix here; then 1.4's drill validates
   both at once.
3. **2.1 + 2.2 caption size** — one setter, one bind call, two serializer lines.
4. **1.4 downgrade drill**, then the standing device-verify block (2.5, 2.6, 2.7).
5. **2.3 preset crop** — read the F12 RE-SCOPED block, not the older note.
6. **1.3** — four `isJsonNull()` guards.

---

## OVERNIGHT RUN 2026-08-04/05 — items re-verified and closed

**1.2, 1.3, 1.4 are STALE-OPEN in the headings above and were already closed.** Re-verified
2026-08-05: schema is now **v12** (not 10), the LAYER stamp is wired, `hasValue()` guards every
`layerId` read, and `DRILL_SCHEMA_DOWNGRADE.md` records a 7/7 PASS run on 2026-07-26 that found
and fixed the undo-history sidecar gap. **Do not re-scope.** The tier headings were not struck
through, only the body text, which is how they read as open.

**NEW — device survey, 2026-08-05.** All 11 sandbox projects carry **zero `trackDefs`**. Lanes are
entirely implicit from `layerId`, so the v11/v12 LAYER stamp path has never been exercised by a
real project. Not a defect — the orphan-lane branch in `getLayers()` is the supported path and
legacy ids already rely on it — but it means item 1.2's fix is unproven in the field rather than
proven safe. Worth one hand-made LAYER-def fixture before trusting it.

**NEW — FIXED `b524c1c`: the no-overlap invariant was enforced on LOAD only.**
`enforceNoOverlapVideoLanes()` runs at open but nowhere else, so an overlap created mid-session
survived the whole session and repaired itself silently on the NEXT open — which reads to the user
as the app moving their clip by itself. This is the concrete, in-the-wild instance of **3.7**
(legacy overlaps in a state the resolver assumes impossible): the resolver was right, it just was
not consulted after edits. Now enforced after a carry drop as well; idempotent, so clean drops pay
nothing.

**Related fix in the same commit:** a lane-occupancy check answered `Long.MAX_VALUE` ("infinite
room") when it could not identify the target lane. An UNKNOWN target must never resolve to an EMPTY
one in a check whose job is stopping data landing on other data.

**3.3 EditScriptApplier — RE-VERIFIED 2026-08-05, NOT A DEFECT. Do not re-scope.**
Two separate worries, both answered by reading the code:
- *Transition indices.* The applier IS disciplined. Insert calls
  `shiftTransitionsAfterInsert` (`EditScriptApplier:537`), split calls
  `shiftTransitionsAfterSplit` (`:744`), the b-roll span calls it TWICE because it inserts two
  clips (`:976-977`), replace-in-place correctly does NOT shift because the clip count is
  unchanged, and reorder hand-rolls a seam-pair remap with a full restore on failure
  (`:869-900`). `Timeline.addClip/removeClip` are raw list ops by design; the helpers are the
  caller's job and this caller does call them.
- *Snapshot discipline.* AI edits are not in-editor `EditAction`s. They apply, then
  `storage.save(proj)` + `AIChatState.signalModified` (`AIToolExecutor:526-528`), so they are
  undone from the WHOLE-PROJECT snapshot history, which serializes transitions with everything
  else. `snapshotTransitions()`/`restoreTransitions()` exists for the in-memory EditAction paths
  and would be redundant here.

## RUN 2026-08-05 (continued) — more stale items, and 3.4 CLOSED

**Also stale-closed, verified against the code today: 2.1, 2.2, 2.3, 2.4.**
- 2.1 `CaptionOverlayView.setSizeFraction` exists and its own doc cites this audit item.
- 2.2 the audio serializer writes `captionSizeFraction` (`ProjectStorage:1966`) and reads it (`:2535`).
- 2.3 `GlTransitionFrameOverlay:216` now reads "Named presets count too, not just custom".
- 2.4 preview binds `windowedCaptionsFor(clip)` → `full.windowed(in,out)`, the SAME call export
  makes. No asymmetry left, for clip and audio-clip paths both.

Running total of items in this audit that read as open and are not: **1.2, 1.3, 1.4, 2.1, 2.2,
2.3, 2.4, 3.3.** Eight. **Grep for the fix before scoping anything from this file.**

**3.4 — CLOSED today.** Both halves now exist: `ProjectIntegrity` (detect, `f163ee7`) and
`ProjectConsolidator` + a Settings row (fix, `2420349`, hardened in `35a8d06`). Device-proven:
4 files / 55MB copied, every reference rewritten to `project://media/…`, reopen reports
"INTEGRITY ok". Note the storage layer normalises to `project://` on save, so a consolidated
project is PORTABLE, not merely self-contained.

**3.6 — do NOT treat as dead code.** `AudioClip.waveform int[]` is still SERIALIZED
(`ProjectStorage:1928` write, `:2489` read). Removing it is a schema change with a migration,
not a cleanup. The audit's "inert" is true of the render path only.

**3.1 — confirmed genuinely open**, and it is a deliberate v1 limitation, not an oversight:
`WebcamEncoderPipeline`'s own class doc states segment rollover is intentionally not implemented
for the webcam file while `ScreenRecordingPipeline` does roll over. Fixing it is encoder work
(muxer recreation + timestamp continuity), not a patch.

### Genuinely open after today
3.1 / 3.2 dual-stream · 3.5 (**68** `getSelectedAudioIndex` sites, not "~9") · 3.6 (needs a
migration plan) · 2.5 / 2.6 / 2.7 (verification tasks — listening and export A/B frame diffs,
not code changes) · Tier 4 tail, incl. the long-file ANR root cause that was captured and never
diagnosed · loop/ping-pong, deliberately last per JoyRaptor.
