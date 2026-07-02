# Feature Spec: Content-Aware B-Roll Matching (for Claude Code)

**Status:** Phase 0 + Phase 1 implemented (2026-06-19, autonomous pass) — pending device build/verify.
- **Phase 0 DONE:** `INSERT_BROLL_CUTAWAY` in `EditScriptApplier` performs Decision 3's four steps
  atomically (split×2 via the shared `splitClip` helper, replace span video with muted b-roll,
  add original span audio to `audioClips` at `offsetMs=atMs`). Guardrails enforced in the validator
  (1.5–8 s, not in first/last 2 s, single 1× clip, no removed spans in target). Depends on the
  `SPLIT_CLIP_AT_TIME` work from the narrative spec (also done).
- **Phase 1 DONE (read-only):** `suggest_broll_placements` tool — calls the model with the transcript
  + b-roll catalog, filters results through all Decision-5 guardrails (duration, edges, ≤2 uses,
  asset must exist), maps assetName→assetUri, returns enriched candidates. No mutation.
- **Phase 2 TODO:** confirm→apply UI (the AI can already emit `INSERT_BROLL_CUTAWAY` via
  `apply_edit_script`). **Phase 3 (vision tagging) not started.**

**Original status:** Ready to implement
**Depends on:** `tasks/HANDOFF.md`, `docs/project-schema.md`, and **`SPLIT_CLIP_AT_TIME`** from `feature-narrative-reorder-spec.md` §3 (implement that op first, or pull it forward standalone — it's small).
**Read first:** Section 0 of `feature-ai-generated-slides-spec.md` applies here too.

---

## 1. Goal

"Look through the asset bucket for the most appropriate b-roll and put it where it would best go." Given a transcript-aware timeline and a folder of supplementary footage (already scanned by `AssetScanner`), let the AI choose moments that would benefit from a visual cutaway, pick the best-matching available clip for each, and insert it — **while the original narration audio keeps playing underneath**, which is the actual documentary technique being asked for, not just "swap the video."

---

## 2. Architecture Decisions (Locked)

1. **v1 placement mode is "cutaway" only — not picture-in-picture.** A true overlay/PiP b-roll (b-roll in a small box over the talking head) requires multi-layer video tracks, which `HANDOFF.md` §6 lists as **near-term, not-yet-built roadmap work**. Don't attempt overlay-mode b-roll before that lands — it'll be fighting a missing foundation. Cutaway mode (b-roll fully replaces the visible frame for a span, original audio continues) needs no new track system — see Decision 3.

2. **v1 asset matching is filename/folder/duration + transcript context — no vision model required to start.** `AssetScanner` already classifies and probes assets (`HANDOFF.md` §3.8); for many users, filenames and folder names already carry real signal ("b-roll/sunset_timelapse.mp4", "cutaways/whiteboard_diagram.mp4"). This is cheap and ships fast. **v2** (Section 6, flagged, not built in this pass): generate a short AI visual description per asset from an extracted thumbnail frame via a vision-capable OpenRouter model, cached so it's a one-time cost.

3. **Cutaway mechanics reuse the existing `audioClips` track — this is the key insight that avoids needing new compositing infrastructure.** A cutaway is:
   - Split the main clip at the cutaway's start and end (`SPLIT_CLIP_AT_TIME` ×2), producing `[before] [cutaway-span] [after]`.
   - Extract the **original** audio of `cutaway-span` as a new `audioClips` entry (same `sourceUri`, same `inPointMs`/`outPointMs`, positioned via `offsetMs` to line up exactly where `cutaway-span` sits on the timeline) — `audioClips` is already decoupled from the video track in the schema, this is exactly what it's for.
   - Replace `cutaway-span` in the `clips` array with a new `Clip` pointing at the chosen b-roll asset, `audioMuted: true` (so any audio the b-roll file has doesn't compete with the narration), trimmed to fit the span's duration.
   - Net result: visually you see the b-roll, audibly the original narration is uninterrupted. No new schema fields needed beyond what already exists.

4. **Mirror the existing detect-then-confirm pattern**, same as the reorder spec: `suggest_broll_placements` is read-only and produces candidates with a stated reason per suggestion; nothing is inserted until confirmed.

5. **Guardrails on suggestions**, enforced in the tool, not just the prompt: no cutaway under 1.5s or over 8s, none in the first/last 2 seconds of the recording, no single asset used more than twice in one project (avoid the AI leaning on one convenient clip repeatedly).

---

## 3. Schema Additions

No project-schema changes. `AssetItem` (app-internal model, not part of project JSON) gets two new optional fields:

| Field | Type | Description |
|---|---|---|
| `aiDescription` | string\|null | Short AI-generated visual description (v2, Section 6). Null until tagged. |
| `aiTags` | array\|null | Short keyword tags (v2). Null until tagged. |

Store the tag cache as a **sidecar file co-located with the scanned directory** (e.g. `.faditor_asset_tags.json` inside the pinned asset dir), keyed by `uri + fileSize + lastModified`, **not** inside project JSON — the same asset bucket can be reused across multiple projects, and tags describe the asset, not any one project. This follows the existing precedent of `pinnedAssetDir`/`assetDirHistory` being directory-level concerns.

---

## 4. New EditScript Operation

```json
{
  "type": "INSERT_BROLL_CUTAWAY",
  "atMs": 64000,
  "durationMs": 3500,
  "assetUri": "content://.../sunset_timelapse.mp4"
}
```
Applier performs exactly the four steps in Decision 3, atomically.

---

## 5. New / Touched Code

| File | Change |
|---|---|
| `AssetItem.java` | Add `aiDescription`, `aiTags` (nullable). |
| `AssetScanner.java` | On scan, load the sidecar tag cache if present; don't block scanning on tagging (Phase 0 ships with the fields always null). |
| New: `com.fadcam.ui.faditor.assetbrowser.AssetTagger.java` | (Phase 2 / v2 only) thumbnail extraction (reuse the `MediaMetadataRetriever` already used for duration probing) + OpenRouter vision call + sidecar cache read/write. |
| `EditScript.java` / `EditScriptApplier.java` | Add `INSERT_BROLL_CUTAWAY`, implemented via two `SPLIT_CLIP_AT_TIME` calls + one `audioClips` insert + one clip replacement, per Decision 3. |
| `AIToolExecutor.java` | New tool `suggest_broll_placements` (read-only, Section 6 below) and the confirm/apply handler. |

---

## 6. The Matching Contract

### 6.1 System prompt template for `suggest_broll_placements`

```
You are a documentary editor choosing supplementary b-roll footage.

INPUT
- The narration transcript with timestamps.
- A catalog of available b-roll assets: filename, duration, and (if
  available) a short visual description.

TASK
Identify moments in the narration that would benefit from a visual
cutaway — the narrator describes something visually demonstrable,
references a concrete object/place/process, or a long static stretch
would benefit from a visual break. For each, choose the single
best-matching available asset. Do not suggest the same asset more than
twice. Never suggest a cutaway shorter than 1.5s or longer than 8s, and
never inside the first or last 2 seconds of the recording.

OUTPUT
Return ONLY a JSON array, no commentary:
[
  { "atMs": 64000, "durationMs": 3500, "assetUri": "...", "reason": "..." }
]
```

`reason` is shown in the confirmation checklist UI so the user can sanity-check each suggestion at a glance, not just accept/reject blind.

### 6.2 Tool behavior

1. Pull transcript text + timestamps for the active clip(s).
2. Pull the current asset catalog from `AssetScanner` (filenames/durations/`aiDescription` if present).
3. Call the model with the prompt above.
4. Filter results through the guardrails in Decision 5 (drop anything that violates duration/position/repeat limits rather than trusting the model to have obeyed them).
5. Surface as timeline markers + checklist with reasons — **no mutation yet.**
6. On confirmation, emit one `INSERT_BROLL_CUTAWAY` per accepted suggestion.

---

## 7. Implementation Phases

### Phase 0 — Cutaway mechanics, no AI, filename-only matching
- Implement `INSERT_BROLL_CUTAWAY` per Decision 3, depending on `SPLIT_CLIP_AT_TIME`.
- Test via the headless `ApplyEditsActivity` path with a hand-picked asset and a hand-written EditScript.
- **Done when:** applying the op produces a project where the original narration audio plays gaplessly across the cutaway span while the video visibly cuts to the b-roll asset, verified by inspecting the resulting `clips`/`audioClips` JSON and a sample export.

### Phase 1 — Suggestion generation (filename/context matching, read-only)
- Implement `suggest_broll_placements` per Section 6 using filenames/folders/durations only (no tagging yet).
- **Done when:** running it on a project with a populated, sensibly-named asset folder produces plausible, guardrail-compliant suggestions with reasons, no project mutation.

### Phase 2 — Confirm → apply
- Wire confirmation to emit `INSERT_BROLL_CUTAWAY` ops for accepted suggestions.
- **Done when:** confirming suggestions correctly inserts cutaways end to end, matching Phase 0's done-when check, driven by the AI instead of a hand-written script.

### Phase 3 (v2, separate pass — don't start until Phase 0–2 are solid) — Vision tagging
- `AssetTagger`: extract one thumbnail per video/image asset, one OpenRouter vision call per untagged asset, write to the sidecar cache.
- Feed `aiDescription`/`aiTags` into the Section 6 prompt's asset catalog instead of filenames alone.
- **Done when:** scanning a folder once populates descriptions for all assets, and a second scan with no new files makes zero additional OpenRouter calls; suggestion quality on an asset folder with generic/unhelpful filenames visibly improves over Phase 1.

### Not in this spec
- Picture-in-picture / overlay b-roll mode — blocked on multi-layer video tracks (`HANDOFF.md` §6 near-term roadmap). Revisit once that lands.

---

## 8. Constraints Carried Over

Same as the other two specs: validated EditScripts only, no unsolicited comments/commits, compile check after each phase, update `HANDOFF.md` and `project-schema.md` after each phase.
