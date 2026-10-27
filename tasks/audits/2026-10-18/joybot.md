# Joybot adversarial audit — 2026-10-18

Scope: current working source at HEAD `24fc23790baf109d6bb5b6526539fe0c52cf7a85`; Joybot agent loop, typed and legacy tools, editor handoff, recovery, observation, and application integration. Application source was not modified. Findings below are source-traced defects and capability gaps, not claims of device reproduction.

Validation: existing `ActionRegistryTest` compiled with Java 17 into a unique `%TEMP%` directory and ran **43 passed, 0 failed**; existing `IdeaEngineTest` likewise ran **381 passed, 0 failed**. No Gradle, device, external model, or phone performance tests were run. The existing JVM harness has no references to `JoybotAgent`, `JoybotStorage`, or `JobFile`; these passing tests do not cover the lifecycle, persistence, or batching defects below. The watcher log ended in `BUILD SUCCESSFUL in 12s` and `Waiting for changes to input files...`, timestamp 2026-10-18 10:03:50; this audit did not trigger or claim a new application build.

All paths below are absolute, rooted at `C:/+Projects/Screenrecorder/FadCam/`.

## Overall assessment

Joybot has substantial working foundations: a shared action registry, argument validation for migrated tools, paged timeline inspection, a room-aware context summary, tool-family loading, native tool calls and text fallback, background jobs, undo grouping, and real VisLab editing actions. It is not yet a complete model-independent harness. The most serious problems are boundary failures in the host: malformed calls can become broad destructive edits, background results can overwrite newer work, cancellation is shared across requests, and conversations are shared across projects. Better models reduce the frequency of bad calls but cannot repair these host-level guarantees.

## Prioritized defects

### J1 — P1: malformed native arguments turn a targeted edit into a default destructive call

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotAgent.java:470-476`.

On JSON parse failure, the native-call loop catches the exception, replaces the original arguments with `{}`, and executes the tool anyway. This is dangerous for legacy tools because their definitions have empty `properties` with `additionalProperties=true` (`JoybotAgent.java:1246-1250`) and they do not pass through typed argument checks.

Concrete trigger: a model/provider truncates a targeted `remove_transitions` argument string, for example `{"seams":[2`, after successfully streaming the function name. The parser substitutes `{}`. `toolRemoveTransitions` defaults `kind` to `all`, has no seam filter when `seams` is missing, then removes every transition: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java:2345-2367`. `TransitionActions` registers `set_transition` and `list_transitions`, so `remove_transitions` remains reachable through this legacy method. `read()` also ignores `finish_reason` and accepts an EOF without a terminal chunk (`JoybotAgent.java:862-955`).

Impact: a partial/malformed free-model or network response can silently widen an edit to the entire project.

Fix: malformed arguments must produce a structured validation error with no handler execution. Validate every legacy tool through an explicit schema. Require completed tool output before execution; report truncation separately and retry with the original request intent.

### J2 — P1: background analysis can overwrite newer user work or resurrect a deleted clip

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java:604-605`.

`detect_silence` loads a detached project at line 576, does asynchronous media analysis, then saves that entire old project from its callback at line 605. Closing chat intentionally lets the user return to Studio while this work continues. `JoybotStorage.save` serializes the detached result and passes it to `live.adopt` (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotStorage.java:72-83`); the editor blindly `swapInProject(next)` with no revision check or patch merge (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java:2190-2205`).

Reproduction schedule: start silence detection; close chat; move/trim/delete an item or add a layer in Studio; let analysis finish. The old whole-project snapshot replaces those newer changes. The single Joybot worker does not serialize against user edits on the main thread.

The transcription method attempts a reload but has another destructive fallback: if the user deletes its target clip during transcription, `if (target == null) { fresh = proj; target = clip; }` restores the original whole project before saving (`AIToolExecutor.java:534-546`). This resurrects the deleted clip and loses intervening edits.

Fix: commit a narrowly scoped result on the main thread against the current document revision and stable target ID. If the target is gone, return a conflict, never revive an old project. Match source URI and trim revision as well, because the old analysis may no longer describe the current clip. Use the same compare-and-apply path for every tool.

### J3 — P1: pressing Stop then Send can resume the stopped request

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotAgent.java:272-278`.

Cancellation is one static `cancelled` flag; `ask()` immediately sets it to false and overwrites `current` before queueing the new request. `stopWorking()` cancels the agent but immediately re-enables Send (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/ChatAssistantActivity.java:2756-2764`). A long tool already in progress is documented to finish first.

Reproduction schedule: request A starts a long transcription or silence tool; press Stop; send B before that tool completes. B resets A's flag. A then reaches its next cancellation check, sees false, and continues its old tool chain. A's `finally` then unconditionally sets `current=null` (`JoybotAgent.java:301-307`), hiding queued/running B from lifecycle checks. History and visible progress can also interleave. `JoybotJobs` has the same global cancellation pattern for button jobs.

Fix: cancellation and HTTP calls belong to immutable request IDs and per-run tokens. Set running state only when the worker begins that request. Clear it only with identity comparison. A new request must never clear a previous request's cancellation. Keep a separate queued/running/cancelling/completed UI state.

### J4 — P1: opening chat on a second project replaces the first project's running conversation

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/ChatAssistantActivity.java:204-205`.

Every run and chat uses the same static `JoybotAgent.HISTORY` (`JoybotAgent.java:93`, `ChatAssistantActivity.java:163`). Opening chat for a different project calls `resetConversation()` which clears that list (`ChatAssistantActivity.java:169-172`), even while the first job is running in the background. The old run then continues appending to and reading the new project's conversation (`JoybotAgent.java:398`, `463`, `482`, `970`). The busy guard checks `running.projectId == projectId` only (`ChatAssistantActivity.java:244-248`), so project B can also send while A remains active. Progress listener callbacks check only whether the activity is gone; only final delivery checks the project ID (`ChatAssistantActivity.java:1121-1166`).

Reproduction schedule: start a multi-step request in A; leave it running; open B; open Joybot and send a request. A's next model turn combines B's conversation with A's tool target and current-project context. A's text/tool events can appear in B's chat. This is both wrong-document behavior and cross-project context contamination.

Fix: history, system prompt, pending routes, listener events and request queues must be scoped by conversation/document/request ID. A run should own its history snapshot independently of whatever chat UI is open.

### J5 — P1: a batched plan proposal does not stop later edits before approval

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotAgent.java:467-485`.

Native calls execute in one loop. `propose_plan` sets `planShown=true` and tells the model to stop, but the host does not inspect that flag until after all remaining calls in the batch have executed. A response containing `[propose_plan(...), delete_clip(...), ...]` therefore changes the project before the user taps Go ahead. This is especially relevant when using models that do not reliably obey the textual planning instruction.

The same late check applies to `navigated` (`JoybotAgent.java:485-490`): later calls after `open_room` continue targeting the original project. There is no host-level preflight barrier for the whole batch.

Fix: planning, navigation and confirmation outcomes must be terminal results for that batch. Stop dispatch immediately, and mark later tool calls skipped with explicit tool responses so conversation protocol remains valid. Preserve remaining requested work as a pending continuation only when appropriate.

### J6 — P2: failed saves are reported as successful edits

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/actions/StudioActions.java:118-122`.

`storage.save(proj)` returns a boolean, but `StudioActions.execute` ignores false, signals modified and returns the action's success text. `JoybotStorage.WRITES` increments before any save succeeds (`JoybotStorage.java:72-83`), so the agent also counts a failed attempt as a write and can save it as a successful recipe call. Legacy methods such as `toolRemoveTransitions` and transcription also ignore save results.

Trigger: storage save failure, an unresolved project save guard, failed file replacement or other write error. The user gets a success claim while the edit is absent or not durable; dependent steps continue against missing changes.

Fix: treat unsuccessful persistence as a failed commit, increment committed-write counters only after success, and return structured `changed`, `persisted`, `revision`, `error` fields. Only publish modification events, review records, recipes and success UI for a committed result.

### J7 — P2: the live-editor timeout leaves a stale write queued after falling back to disk

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotStorage.java:99-107`.

`onMain` posts an editor operation, waits five seconds, and returns null on timeout without cancelling the queued runnable. `save()` then writes the project to disk (`JoybotStorage.java:79-83`), but the original adopt runnable can still run later and replace the editor's state. A timed-out read similarly falls back to a possibly older file while the live editor exists. Android painting/media/layout work can delay the main thread; timeout is explicitly part of this implementation.

Trigger: queue editor adoption behind main-thread work lasting longer than WAIT_MS. Tool completion/persistence can race with the still-queued adoption and subsequent user edits.

Fix: never fall back to independent disk writes while a live editor owns the document. Give the operation a token/state checked atomically on the main thread; a timed-out or superseded operation must not later adopt. Return a retryable conflict and preserve the sole document owner.

### J8 — P2: a successful read tool disables the safeguard against claiming an unperformed edit

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotAgent.java:525-555`.

`runTool` sets `toolRan=true` for ordinary reads as well as edits (`JoybotAgent.java:598`). The no-change nudge runs only when `!toolRan`; the later honesty check runs only when `lastError != null`. A model can call `get_project_state`, `list_items` or `vislab_describe`, then say it made the requested edit, and neither check catches it. In VisLab `ask.projectId` is null, so the nudge is disabled anyway, and successful VisLab edits never increment the Studio-specific write counter.

Concrete model response: user asks to make a title red; the model calls `get_project_state` then replies "I changed the title to red." This returns `finish(true, ...)` without a write. The current unit suites test keyword intent detection, not this execution sequence.

Fix: track read calls, validated mutations, external side effects and committed changes separately. Verify requested postconditions against a document revision. Host-generated summaries should describe actual committed change records, especially for weaker models.

### J9 — P2: history is unbounded even though only the current-project summary is capped

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotAgent.java:970-1018`.

Every message is re-sent every round. `CONTEXT_CAP=14000` limits only the appended project summary (`JoybotAgent.java:1159-1165`), not HISTORY, transcript tool output, native call arguments or returned raw look JSON. `get_transcript` emits every word (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java:2906-2926`). History is also copied and rewritten into `job.json` every round.

Trigger: ask for long transcripts, raw scene descriptions, or keep iterating over a long editing session. Each later tool step retransmits all previous outputs. Smaller/free model context limits are eventually exceeded; latency, token usage and memory grow even when the next task is small. Family routing reduces tools but does not bound this growth. There is no context-budget recovery path here.

Fix: apply a model-aware total token budget, tool-result paging/references, compact old turns at complete tool-call boundaries, and retain goals/current IDs/commit ledger outside model prose. Pin user constraints and current state; retrieve detailed transcript sections on demand.

### J10 — P2: recovery checkpoints cannot distinguish an already committed tool from one that never ran

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotAgent.java:412`.

The job file is saved before a completion/tool round. Mutating tools persist their results, then tool messages are added to in-memory HISTORY, and only the next round saves that history. A process death between the project write and that next save leaves the project changed but the durable conversation saying it was not yet changed. Restore simply restores the old history and asks the model to carry on (`JoybotAgent.java:1451-1457`). Non-idempotent calls such as split/add layer can be issued again. Fresh context may help a model infer what happened, but there is no reliable exactly-once host guarantee.

`JobFile.write` also deletes the old file before renaming the replacement (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JobFile.java:166-174`), leaving a crash window with no recovery file. Requests from VisLab have no project ID (`VisLabActivity.java:222-223`) and are not checkpointed at all (`JobFile.java:68`).

Fix: durable per-call operation IDs, a write-ahead intent/commit ledger tied to document revisions, and idempotent mutation endpoints. Recover by checking actual commit receipts before replay. Use atomic replacement or Android AtomicFile. Support app-level/VisLab document IDs as well as Studio projects.

### J11 — P1: retiming video/audio can collapse footage or commit different bounds than reported

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java:2401-2402`.

`set_item_time` is advertised as setting absolute start/end for any timeline item. For overlay video, it sends `end - oldStart` to `Clip.mapToSourceMs` then changes the start. That mapping returns -1 at or beyond the current visual duration (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/model/Clip.java:435-439`); `setOutPointMs(-1)` then clamps to `inPointMs + 1` (`Clip.java:1177-1178`). Even repeating an overlay's existing end therefore collapses it to a 1 ms trim: source duration 10 s, overlay start 0, call `{id: overlayId, endMs: 10000}`. This path lacks the spine branch's guard against mapping the end boundary. The tool still reports that it runs to 10 s.

When both bounds are supplied for audio, the new out-point is based on `end - oldOffset`, then offset is changed (`AIToolExecutor.java:2415-2417`). Example with sufficient source footage: an audio clip at 0 s, request start 5 s/end 10 s; it becomes 10 s long at offset 5 s and actually ends at 15 s while the response says 10 s. Overlay video has the same old-start/new-start duration mismatch when its requested end remains inside the mapping range. The returned summary prints requested variables rather than measured committed bounds (`AIToolExecutor.java:2423-2425`).

Fix: define move versus trim semantics explicitly, derive desired duration from the final start/end, map exclusive end bounds with an endpoint-aware mapping, and validate source limits before mutation. Return actual post-edit bounds. Add tests for unchanged end, moving both edges, extending, speed/cuts/loops, and no-op retiming.

### J12 — P2: agent edits bypass object locks that the user's editor respects

Primary location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java:2403-2404`.

The generic retime tool directly sets a text object's time range without checking its lock or its row's lock; the normal editor explicitly refuses to expose that operation for locked objects (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java:28862-28866`). Typed transform actions similarly resolve a layer by ID and call setters without a lock check (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/actions/TransformActions.java:108-120`, `139-184`). The model setters do not enforce locks, so this is not rescued at the model boundary. In contrast, duplication explicitly rejects locked items (`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/actions/OrganiseActions.java:124-125`).

Trigger: lock a title/logo in Studio, then ask Joybot to move/retime it or broadly adjust layers. The tool mutates the protected object without an unlock operation. A locked object's ID can also be referenced directly by a less capable model.

Fix: put shared item/row lock policy in the common command boundary, with an explicit unlock command only when the user's request calls for it. Audit all typed and legacy actions against the same UI edit policy rather than relying on each handler to remember it.

## Capability and UX matrix

| Area | Reachable capabilities | Gaps relative to complete user/API parity |
| --- | --- | --- |
| Studio inspection | `get_project_state`, paged `list_items`, detailed `get_item`, `find_words`, timeline context and selection/playhead captured when chat opens | No versioned observation/event stream; playhead and selection remain intent extras throughout a run; user changes are not conflict checked |
| Studio edits | Broad clip, text, captions, sound, transitions, grade, transforms, motion, layers, source, sprite and export actions; shared typed registry for many families | Mixed typed and legacy contracts; no uniform mutation receipt, transaction, preview/diff or idempotency; action list does not automatically prove UI parity |
| Seeing the result | Attachment vision; numbered sprite sheets; image sequences; image assets; source video frames | `look(playhead)` is the source clip frame, not the rendered composition. Titles, crop, grade, overlays and effects are absent. No rendered VisLab or JoyBrush observation |
| VisLab | Current look name/context; list/read looks; layer types and knobs; add/remove/reorder layers; palette and music links; full look JSON; save/rename/delete/revert; floating visualiser toggle | No visual/audio result verification; generic agent change tracking/recovery/recipe capture is tied to Studio writes, so it misses app-level edits; no shared Studio visualiser-document integration yet |
| JoyBrush | Navigation to JoyBrush and help text | No registered drawing/document/canvas/board/layer/stroke/selection/frame commands; no paint document IDs or observation surface in agent context. The separate joybrush-android tree has no Joybot/ChatAssistant integration references. A smarter plugged-in agent cannot fill this gap |
| Capture | Open Capture room and help for screen/webcam recording; `vislab_float` can toggle the visualiser | No recorder start/pause/stop/settings/status tools in the registered host. MediaProjection and Android permission screens require explicit user handoff; this can be represented as a pending capability rather than a fake success |
| Cross-room tasks | Navigate/open library item/new Studio project | Navigation closes chat and ends native request; no durable continuation carrying stable document handles across rooms. Text fallback does not apply the same navigated stopping check as native calls |
| Planning/review | Plan cards, larger approved-plan budget, Studio change review and grouped undo | Batch can cross a plan barrier; reviews use Studio snapshots rather than uniform document commits; app-level/VisLab changes do not enter that Studio ledger |
| Cancellation | Stop HTTP; tools finish between steps; background worker survives closing chat | Shared flags and queued jobs break isolation; long analyses do not have per-call cancellation/late-result invalidation; timeout paths can still publish late results |
| Models | OpenRouter/custom compatible Chat Completions endpoints; tools/text fallback; provider-parameter adaptation; one free-router fallback | This is a Chat Completions client, not a general pluggable external-agent protocol. Provider/model quirks are cached by URL, so one tool-incompatible model can disable native tools for later capable models at that URL (`JoybotAgent.java:1173-1206`) |
| Free-tier robustness | Family routing and `more_tools`, typed validation, plain intent/help rules, honesty nudges | No measured success-rate benchmark; defaults can be destructive, read-only calls bypass honesty logic, context grows indefinitely. A claim of 90% task completion is not supported by these unit tests |

### Observation detail

`C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotEyes.java:223-257` uses `MediaMetadataRetriever` on the source URI. `playhead` walks the spine and calls that function (`JoybotEyes.java:264-277`). It does not call the editor compositor. Consequently "is this title readable?", "does the picture cover the face?", "did you put the visualiser behind the text?", and "did the painting actually change?" cannot be verified through the present observation tool. This is a missing capability, not a claim that the documented source-frame tool itself is broken. It is essential to the user's intended harness.

The intended text-only-model vision handoff is also incomplete: `look()` refuses an OpenRouter model marked `Vision.NO` (`JoybotAgent.java:721-724`) before the later `borrowEyes` fallback can obtain a sight. That fallback can help an already attached image, but not this normal look path. A normal multi-model harness should let a vision-capable helper return observations to the selected reasoning model.

## Recommended implementation order

1. **Make the command host reliable before adding more model prompts.** Reject malformed calls; terminal plan/navigation barriers; request-local cancellation; conversation/document scoping; committed-save receipts. These are required even with very capable models.
2. **Introduce a common document adapter.** Stable `{room, documentId, revision, selection, playhead}` observation plus `list`, `inspect`, `applyPatch(expectedRevision, operationId)`, `render`, `undo`, and event subscription. Implement Studio and VisLab adapters first; expose JoyBrush's real document/stroke/layer/frame command model through the same seam. Keep UI gestures and agent calls on the same command bus.
3. **Separate observation from mutation and verification.** Render the real composition at a specified timestamp/revision, capture VisLab output with deterministic time/audio inputs, and capture the painting/board/selection state. Attach semantic object IDs alongside pixels. Re-observe after mutations and compare actual postconditions.
4. **Journal operations rather than trusting chat history for recovery.** Persist intention, target revision, result and changed IDs in the same publication boundary as the document mutation. Make retries idempotent. Preserve a pending cross-room task through navigation and resume it when the requested document is available.
5. **Give weak models smaller, repairable decisions.** Fully typed schemas for legacy tools; explicit units and stable IDs; bounded outputs; structured error categories; a targeted schema-repair retry; capability discovery at the active document; total token budgeting. Expose the requested and committed diff to the user for larger changes.
6. **Measure the 90% free-tier target.** Build a task corpus across Studio/VisLab/JoyBrush with initial documents and expected postconditions. Include ambiguous references, unknown IDs, lock/hidden state, wrong units, malformed/truncated JSON, read-then-false-success, network EOF, Stop-then-Send, deleted target mid-job, concurrent user edits, process death after commit, and provider changes. Report task success, unintended edits, recovery success, latency and token usage per model. Unit helper pass counts are useful but cannot substitute for these end-to-end outcomes.

## Tests that would catch the highest-risk regressions

- Fake streaming provider emits `remove_transitions` with malformed arguments: handler must never run and transition count must remain unchanged.
- Native reply batches `propose_plan` then a write: no write before Go ahead; later calls receive a skipped response.
- Fake editor changes revision during silence analysis: commit preserves those edits or returns conflict; deleted clip remains deleted.
- Stop request A during a long tool, send B immediately: no later A calls, no A progress in B, and `current` stays B.
- Switch from project A to B during A: independent histories and no wrong-project tool dispatch/events.
- Fake storage save returns false: action reports failure; write/recipe/review counters remain unchanged.
- Main-thread operation times out: no late adoption after rejection and no independent live-document disk fallback.
- Kill after a non-idempotent mutation commit but before history save: resume must use the commit receipt, not repeat the edit.
- Source-frame versus composite render test with a visible title and image overlay: agent observation must contain both at the specified revision.
