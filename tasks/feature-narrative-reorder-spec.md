# Feature Spec: AI Narrative Reordering (for Claude Code)

**Status (2026-07-17):** COMPLETE — Phases 0–2 + visual KEEP/DROP card + atomic structural apply
(see §7b), and Decision-5 boundary→silence snapping now implemented in `apply_narrative_proposal`
(`AIToolExecutor.snapBoundaryToSilence`: nearest gap within 300ms → cut at its midpoint,
monotonicity-guarded). Remaining: optional timeline proposal-marker band (§7b item 3, non-blocking)
and the visual-verify with an AI key.
- **Phase 0 DONE:** `SPLIT_CLIP_AT_TIME` + `REORDER_CLIPS` added to `EditScript`/`EditScriptApplier`.
  Split partitions `removedSpans` + transcript words by time (Decision 2); children get controllable
  ids via new `Clip(Clip, String)` ctor. `REORDER_CLIPS` drops omitted clips and drops transitions
  whose flanking clips are no longer adjacent, reindexing the rest (Decision 3). Test via
  `apply_edit_script` with a hand-written script.
- **Phase 1 DONE (read-only):** `analyze_narrative_structure` tool in `AIToolExecutor` (returns a
  KEEP/DROP/order proposal; no mutation). Boundary→silence snapping (Decision 5) deferred — TODO.
- **Phase 2 TODO:** confirmation UI in chat that turns an accepted proposal into the concrete
  SPLIT×N + REORDER script (the AI can already emit it via `apply_edit_script`).

**Original status:** Ready to implement
**Depends on:** `tasks/HANDOFF.md`, `docs/project-schema.md`. Independent of the generated-slides spec — build in either order.
**Read first:** Section 0 of `feature-ai-generated-slides-spec.md` applies here too (read `HANDOFF.md`, `project-schema.md`, and verify real class/method names before implementing — this spec proposes names based on the handoff doc).

---

## 1. Goal

Given one long, continuous recording (e.g. a 50-minute lecture) already split by silence/filler removal, let the AI propose — and the user confirm — a **reordered, trimmed sequence** that makes the strongest possible case: drop weak or redundant stretches, reorder the rest for clarity and impact. This is the "organize the track into clips sorted to best convey the strongest points of my argument" part of the original vision.

This is a structurally bigger operation than anything the AI does today (it changes clip order and count, not just properties of existing clips), so it gets a stricter UX rule than other AI tools: **propose first, apply only on confirmation.** See Section 2.4.

---

## 2. Architecture Decisions (Locked)

1. **No new clip type.** A long recording is just one `Clip`. Reordering it for narrative structure means: split it into several `Clip` objects (same `sourceUri`, different `inPointMs`/`outPointMs` — already a supported pattern per the "linked instances" note in `HANDOFF.md` §4), then reorder/drop entries in the `clips` array. Zero schema changes to `Clip` itself.

2. **Reuse the existing split-clip primitive.** The editor already splits a clip at the playhead when inserting an asset mid-clip (`insertAssetAtPlayhead`, `HANDOFF.md` §3.8). Find that logic in `Timeline.java`/`FaditorEditorActivity.java` and expose it as a validated EditScript op (`SPLIT_CLIP_AT_TIME`, Section 4) rather than writing new split logic. It already needs to handle partitioning `transcripts[].words[]` and `removedSpans` between the two resulting clips correctly — confirm it does; if the existing path only handles the no-removedSpans/no-transcript case, extend it.

3. **Transitions are positional (`Transition.clipIndex` = a seam between array positions), not id-based** (`HANDOFF.md` §3.12, §5.2). Reordering invalidates any transition whose seam no longer sits between the same two clips. Generalize the rule already established for clip deletion ("adjacent transitions are removed when a clip is deleted") to reordering: **any transition whose two adjacent clips are not adjacent in the new order is dropped**, not silently misapplied to the wrong seam. Do not attempt to "carry" a transition through a reorder in this pass — that's a correctness trap, not a feature.

4. **Mirror the existing detect-then-confirm UX pattern**, not a new one. `toggleSilenceDetect()` already does this for silence gaps: first tap detects and shows yellow candidates, second tap (or explicit re-trigger) applies them (`HANDOFF.md` §3.11). Narrative reordering follows the same shape:
   - **Propose** (read-only, no project mutation): AI returns a structured chunk/order/drop proposal, surfaced as timeline markers + a checklist in the AI chat status UI (per the user's stated preference for visual feedback before big changes — `HANDOFF.md` §2).
   - **Apply** (on confirmation only): the proposal is translated into a concrete `SPLIT_CLIP_AT_TIME` + `REORDER_CLIPS` EditScript and applied atomically.
   - This op should never auto-apply without confirmation, unlike smaller single-property edits — it's the most destructive thing the AI can do to a project.

5. **Boundary snapping.** Chunk boundaries proposed by the AI are word-timestamp-precise, which is rarely a clean cut point. Snap each proposed boundary to the nearest already-detected silence gap (reuse `toolDetectSilence`'s output) within a small tolerance (suggest 300ms); if no silence gap is nearby, leave the boundary as-is rather than guessing.

---

## 3. New EditScript Operations

### `SPLIT_CLIP_AT_TIME`
```json
{ "type": "SPLIT_CLIP_AT_TIME", "clipId": "...", "atSourceMs": 184500 }
```
Splits one `Clip` into two at the given source-time point. Both children keep `sourceUri`. `transcripts[].words[]` and `removedSpans` partition by time range. Replaces the original clip in-place in the `clips` array with the two children, consecutively.

### `REORDER_CLIPS`
```json
{ "type": "REORDER_CLIPS", "newOrder": ["clipId7", "clipId2", "clipId9", "..."] }
```
The resulting `clips` array is exactly this list, in this order. **Any clip id from the current array not present in `newOrder` is deleted.** This single op handles both "reorder" and "drop weak segments" atomically — don't add a separate `DELETE_CLIP` op for this use case. Apply the transition-invalidation rule from Decision 3 as part of this op's validation/application, not as an afterthought.

---

## 4. New / Touched Code

| File | Change |
|---|---|
| `EditScript.java` / `EditScriptApplier.java` | Add the two op types above. `REORDER_CLIPS` application: rebuild `clips` array, drop orphaned transitions, reindex any remaining `Transition.clipIndex` values to match new positions. |
| `Timeline.java` | Expose the existing split-at-point logic as a clean method `Clip[] splitClipAt(Clip clip, long atSourceMs)` if it isn't already factored out from the asset-insert path. |
| `AIToolExecutor.java` | New tool `analyze_narrative_structure` (read-only — see Section 5). New handler for the confirmation/apply step that builds the `SPLIT_CLIP_AT_TIME`×N + `REORDER_CLIPS` EditScript from an accepted proposal. |
| `ChatAssistantActivity.java` / status UI | Render the proposal as timeline markers + a checklist (reuse the existing checklist/progress UI already built for AI job status, per `HANDOFF.md` §3.1), with an explicit confirm action before anything is applied. |

---

## 5. The Analysis Contract

### 5.1 System prompt template for `analyze_narrative_structure`

```
You are an editorial assistant restructuring a single continuous recording
into the clearest, most persuasive sequence of segments.

INPUT
A transcript as a list of words with start/end times in milliseconds
(source time), already excluding any spans already marked removed
(silence, false starts).

TASK
1. Segment the transcript into coherent chunks. Each chunk is one complete
   thought, point, or sub-topic. Prefer boundaries at natural pauses; never
   split mid-sentence.
2. For each chunk, write a one-sentence summary of the point it makes.
3. Decide KEEP or DROP for each chunk. Drop only chunks you're confident
   make the argument weaker by their presence — redundant restatements,
   off-topic tangents, false starts that survived silence detection. Be
   conservative: when unsure, keep it.
4. Propose an order for KEPT chunks that makes the strongest, clearest
   case. This may differ from recording order — lead with the strongest
   point, group related points, build to a conclusion.

OUTPUT
Return ONLY a JSON array, no commentary:
[
  { "startMs": 12000, "endMs": 47000, "summary": "...", "keep": true, "order": 1 },
  { "startMs": 47000, "endMs": 81000, "summary": "...", "keep": false, "order": null }
]
"order" is a 1-based rank among kept chunks only; dropped chunks get null.
```

### 5.2 Tool behavior

1. Pull the active transcript's words, minus already-removed spans.
2. Call the model with the prompt above.
3. Snap each `startMs`/`endMs` to the nearest detected silence gap (Decision 5).
4. Return the proposal to the UI as candidates — **do not mutate the project here.**
5. On confirmation, emit `SPLIT_CLIP_AT_TIME` at every surviving boundary, then one `REORDER_CLIPS` reflecting `keep`/`order`.

---

## 6. Implementation Phases

### Phase 0 — Split/reorder primitives, no AI
- Expose `SPLIT_CLIP_AT_TIME` and `REORDER_CLIPS` as validated EditScript ops.
- Test via the existing headless `ApplyEditsActivity` path with a **hand-written** EditScript (manually craft JSON splitting one sample clip into three and reordering them).
- **Done when:** applying the hand-written script produces correct playback order; transcripts and `removedSpans` partition correctly between split children; any transition touching a disrupted seam is cleanly dropped, not corrupted or misplaced.

### Phase 1 — Proposal generation (read-only)
- Implement `analyze_narrative_structure` per Section 5, boundary snapping, candidate surfacing in chat UI as timeline markers/checklist.
- **Done when:** running it on a real transcript produces a sensible, previewable proposal with zero project mutation.

### Phase 2 — Confirm → apply
- Wire the confirmation action to build and atomically apply the concrete EditScript from Section 3.
- **Done when:** confirming a proposal restructures the project correctly and the result plays/exports end to end.

### Phase 3 (polish, non-blocking)
- Storyboard-style preview of the proposed new order before commit, given this is the single most destructive AI operation in the app.

---

## 7. Constraints Carried Over

Same as `feature-ai-generated-slides-spec.md` §7: validated EditScripts only, no unsolicited comments/commits, compile check after each phase, update `HANDOFF.md` and `project-schema.md` after each phase.

## 7b. Phase 2 — Confirm→Apply: implementation-ready design (2026-06-20)

Captured during an autonomous pass while the build watcher was down (so this is a precise plan to
execute with a live compiler, not yet code). Verified against the real code this session:

**Current state (read this first).** `AIToolExecutor.toolAnalyzeNarrativeStructure` (line ~593) and
`toolSuggestBrollPlacements` (~624) already return the proposal **as chat text** (a JSON array inside
a human-readable string). The apply path already exists end-to-end: the AI can emit
`apply_edit_script` with `SPLIT_CLIP_AT_TIME`×N + `REORDER_CLIPS` (narrative) or `INSERT_BROLL_CUTAWAY`
(b-roll) — those ops are implemented and validated in `EditScriptApplier` (§3.19). So Phase 2 is
**purely a confirmation UX layer**: surface the proposal visually and gate application behind an
explicit user tap, instead of trusting the model to immediately call `apply_edit_script`.

**Decision: make the proposal a first-class, machine-parseable result, not free text.**
1. Change the two tools to return a tagged, parseable payload the chat layer can detect, e.g. prefix
   the JSON with a sentinel line `@@PROPOSAL:narrative@@` / `@@PROPOSAL:broll@@` followed by the raw
   JSON array (keep a human summary line for transcript readability). This avoids re-parsing English.
2. In `ChatAssistantActivity`, after a tool result returns, detect the sentinel and instead of (or in
   addition to) printing text, render a **proposal card** reusing the existing AI-status checklist UI
   (`HANDOFF.md` §3.1 — checklist + progress + the status panel already built). One row per chunk:
   `KEEP/DROP` toggle + the one-line summary + the time range (narrative); or `atMs · asset · reason`
   (b-roll). Include a primary **"Apply"** button and a **"Discard"** button. Nothing mutates until
   Apply. This mirrors the `toggleSilenceDetect()` detect-then-confirm shape (Decision 4) — reuse that
   mental model and, where possible, its candidate-rendering code.
3. **Optional but recommended:** also push the proposed chunk boundaries to the timeline as markers
   (the editor already draws transition bands / removed-span bands in `EditorTimelineView`; add a
   lightweight "proposal marker" band keyed off the chunk boundaries) so the user sees the cut points
   in context before confirming. If this proves heavy, ship the checklist-only version first.
4. **Apply action** builds the concrete EditScript locally in `ChatAssistantActivity`/`AIToolExecutor`
   (do NOT round-trip the model again — the user already edited the KEEP/DROP/order in the card):
   - Narrative: for each surviving boundary emit `SPLIT_CLIP_AT_TIME {clipId, atSourceMs}`; then one
     `REORDER_CLIPS {newOrder:[…kept child ids in chosen order…]}`. The child ids are deterministic
     via the `Clip(Clip,String)` ctor (§3.19) — generate them the same way the applier does so the
     `REORDER_CLIPS` list matches the post-split ids. **Confirm the id scheme** in `EditScriptApplier`
     before wiring (the split op assigns child ids; REORDER must reference those exact ids).
   - B-roll: emit `INSERT_BROLL_CUTAWAY` per accepted row (already guardrailed in the tool).
   - Apply atomically through the existing validated `apply_edit_script` path (same code the AI uses),
     so undo/redo and persistence come for free.
5. **Boundary→silence snapping (Decision 5, still TODO):** before emitting splits, snap each boundary
   to the nearest detected silence gap within ~300ms (reuse `toolDetectSilence` output); skip if none
   near. Can land with Phase 2 or as a fast-follow.

**Files to touch (Phase 2):** `AIToolExecutor.java` (sentinel-tag the two proposal strings + a local
`buildNarrativeScript(proposalJson, edits)` / `buildBrollScript(...)` helper), `ChatAssistantActivity.java`
(detect sentinel → render proposal card from the existing checklist UI → Apply/Discard), optionally
`EditorTimelineView.java` (proposal marker band). **No schema change.** Compile-check after, then
device-verify: run `analyze_narrative_structure` on a real transcript, toggle a DROP, tap Apply,
confirm the timeline reorders and plays/exports.

**Risk note:** the trickiest correctness point is matching the `REORDER_CLIPS` ids to the ids the
`SPLIT_CLIP_AT_TIME` ops produce. Pin this down against `EditScriptApplier`'s split id logic FIRST;
everything else is straightforward UI plumbing over already-working ops.

### Id scheme — PINNED (2026-06-20, verified against `EditScriptApplier`)
- `SPLIT_CLIP_AT_TIME` (`applySplitClipAtTime`): the **first** half keeps `firstClipId` (default =
  the original clip's id); the **second** half gets `secondClipId` (default = a random UUID). So to
  chain splits deterministically you MUST pass BOTH `firstClipId` and `secondClipId` on every split —
  otherwise the second-half id is random and unknowable to the script author.
- To split one clip O into N chunks at boundaries b1<…<b(N-1): split O at b1 → `{firstClipId:c0,
  secondClipId:r1}`; split r1 at b2 → `{clipId:r1, firstClipId:c1, secondClipId:r2}`; … last split
  r(N-2) at b(N-1) → `{clipId:r(N-2), firstClipId:c(N-2), secondClipId:c(N-1)}`. Resulting chunk ids
  c0…c(N-1) are then listed (kept ones, in chosen order) in `REORDER_CLIPS.newOrder`.
- `validateSplitClipAtTime` requires `atSourceMs` strictly inside the CURRENT clip's trim (100ms
  margin); since each split runs on the shrinking "rest" clip, ascending boundaries satisfy this.

### Atomic multi-op apply — BLOCKER FOUND **AND FIXED** (2026-06-20)
- **Was:** `EditScriptApplier.validate()` validated ALL ops against the ORIGINAL unmutated project, so
  a `[SPLIT×N, REORDER]` script always failed validation — split #2 referenced an id split #1 creates;
  `REORDER` referenced chunk ids that didn't exist yet. This made atomic apply impossible.
- **Now:** `apply()` detects structural scripts (`hasStructuralOps` = contains SPLIT/REORDER) and
  **validates by SIMULATION** — deep-copies the project via the canonical `ProjectStorage`
  `toJson`/`fromJson` (same path as undo snapshots), runs validate→apply for each op sequentially on
  the copy, and only if the whole sequence passes does it replay the ops on the live project. Non-
  structural scripts keep the exact original static path (zero behavior change). Falls back to static
  validation if no `context` is set. **Compile-verified green + installed (2026-06-20).** This unblocks
  the Phase 2 apply step — the chat confirm handler can now emit one `[SPLIT×N, REORDER]` script and
  apply it atomically through the existing `apply_edit_script`/`EditScriptApplier` path.

### Apply step — IMPLEMENTED as a validated tool (2026-06-20)
Rather than have the model hand-craft N splits with matching ids (the correctness trap), the apply is
now a single validated tool: **`apply_narrative_proposal`** (`AIToolExecutor` #30). Input
`{clipId, chunks:[{startMs,endMs,keep,order}]}`; it builds `[SPLIT×(N-1), REORDER]` with the pinned id
scheme (piece ids `clipId__nr<i>`, intermediate `clipId__rest<k>`), preserves other clips
(newOrder = pre-clips + kept-by-order + post-clips), guards against emptying the clip / bad boundaries,
and applies atomically via the structural-simulation path. So the **confirm→apply is conversational**:
the AI proposes (read-only `analyze_narrative_structure`), the user says "apply" (optionally "drop
chunk 3"), the AI calls `apply_narrative_proposal`. Compile: javac-evidenced green (a 2nd daemon OOM
crash hit post-javac before install — confirm on watcher restart).

### Visual KEEP/DROP card — DONE, compile-green + installed (2026-06-20). VISUAL VERIFY.
The richer **visual** confirmation card is now built. `analyze_narrative_structure` /
`suggest_broll_placements` prepend a machine-parseable sentinel to their result —
`@@PROPOSAL:narrative@@{"clipId":…,"chunks":[…]}` / `@@PROPOSAL:broll@@{"cutaways":[…]}` — while keeping
the human-readable text (so the conversational apply path and transcript readability are unchanged). In
`ChatAssistantActivity`, tool results route through `addToolResultMessage()` → `renderProposalCard()`,
which brace-matches the JSON (tolerates pretty-printed/newline payloads) and builds an interactive card
in `messagesContainer` (programmatic, no XML — matches the existing bubble style): a `CheckBox` per
chunk (`m:ss–m:ss  summary`, pre-checked = keep) / per cutaway (`m:ss  asset — reason`), plus **Apply**
and **Discard** buttons. Apply rebuilds the `{clipId,chunks}` / `{cutaways}` args from the checkbox
state and calls the existing validated `apply_narrative_proposal` / `apply_broll_proposal` tool on the
background executor (atomic structural-simulation apply → undo/redo/persist for free; the editor reloads
via `AIChatState.signalModified`). Any parse failure falls back to a normal bot bubble (zero
regression). The system-prompt text now tells the model a card is shown and to WAIT for the user's tap.
**VISUAL VERIFY:** needs an AI key + a transcript clip — run `analyze_narrative_structure`, confirm the
card renders, uncheck a chunk, tap Apply, confirm the timeline reorders/drops correctly.

### Still TODO (optional)
Timeline proposal markers (draw the proposed chunk boundaries as a band in `EditorTimelineView`);
boundary→silence snapping (Decision 5). Neither blocks the feature.

## 8. Out of Scope Here

- Content-aware b-roll placement — separate spec (`feature-broll-matching-spec.md`), which depends on `SPLIT_CLIP_AT_TIME` from this spec.
- Multi-source reordering across an already fully-assembled multi-clip-with-transitions timeline is technically supported by `REORDER_CLIPS` as written, but Phase 0–2 here should be tested and tuned against the single-long-recording case first — that's the actual use case driving this spec.
