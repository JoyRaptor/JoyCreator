# Joybot audit fixes — 2026-10-18 (Joybot lane, Claude)

Answers `joybot.md`. Every finding was checked against the code before fixing; all 12 were real.
Compiled (typecheck 1013 sources) and the JVM suites pass (IdeaEngine 381, ActionRegistry 43,
VisLabEdit 51). **Not yet phone-tested:** the Note 9 was not connected when this landed — run the
checks at the bottom before calling these done.

| # | Fixed how | Where |
|---|---|---|
| J1 | Unreadable native arguments never run (tool gets "nothing ran, send it again"). A reply that was cut off (`finish_reason: length`, or a stream with neither `[DONE]` nor a finish reason) runs none of its calls. `remove_transitions` with no `kind`/`seams` now refuses instead of removing all. | `JoybotAgent` native loop + `Completion.cutOff()`; `AIToolExecutor.toolRemoveTransitions` |
| J2 | Silence detection and transcription re-read the project when they finish and change only their clip; a clip deleted, swapped (different file) or re-trimmed (silences) meanwhile gets a conflict error — the old project is never saved back. | `AIToolExecutor.toolDetectSilence`, `toolGenerateTranscript`, `sameSource` |
| J3 | Each request and each button job has its own stop switch; a new request never un-stops an old one. "Running" is set when the worker starts it and cleared only by itself; `LIVE` tracks queued + running. | `JoybotAgent.Ask.cancelled`, `LIVE`, `cancel(key)`; `JoybotJobs.LIVE` |
| J4 | One conversation per project (or per room with none open): `JoybotAgent.history(conversationKey)`. A run owns its history; progress events go only to the chat showing that conversation; the chat's message log, busy check and Stop are per conversation. | `JoybotAgent.conversationKey/history`, `Listener.conversationKey()`, `post(key, …)`; `ChatAssistantActivity.chatKey`, `MESSAGE_LOGS` |
| J5 | After `propose_plan`, a navigation, or a confirm card, the rest of that batch is not run; each skipped call gets a "Not run: …" answer so the history stays valid. The text fallback now also stops on navigation. | `JoybotAgent` native loop `stopReason` |
| J6 | A save counts as a write only when it succeeded; failures are counted. StudioActions returns an error on a failed save; the agent turns ANY tool whose save failed into an error (covers the 45 older tools at once). | `JoybotStorage.save/saveFailures`; `StudioActions.execute`; `JoybotAgent.runTool` |
| J7 | Main-thread hand-offs that haven't started within 5 s are withdrawn and can never run late. While the editor is on screen Joybot never writes the file behind it (only if the editor closed meanwhile) and never reads a possibly older file. | `JoybotStorage.onMain/load/save` |
| J8 | "Changed" = a real project write or an app-wide change (VisLab…), not "a tool ran". The "you only described it" nudge now also covers VisLab; reads no longer switch the honesty checks off. | `JoybotAgent.Run.changedSomething()`, `AppActions.changes()` |
| J9 | What is SENT is bounded: earlier requests' long tool results shortened to 600 chars, and past 60k chars the oldest whole requests are left out. The kept conversation and this request's own steps are untouched. | `JoybotAgent.Run.bounded/shortened` |
| J10 | The job file is saved right after each step's tool answers (not only at the next step), so a kill there resumes knowing the calls ran. `JobFile.write` uses AtomicFile (no gap with no file). | `JoybotAgent` loop; `JobFile.write` |
| J11 | Retiming uses the FINAL start/end: a floating video's or sound's length = end − start; its existing end is a no-op (no more 1 ms collapse); lengthening a floating video uses the source after its out point and refuses past the footage or on a looped clip. The reply quotes the bounds read back from the project. | `AIToolExecutor.toolSetItemTime` |
| J12 | One lock rule for every edit, typed or older: an item (or anything on a locked row) named by id in the arguments is refused with "ask whether to unlock". Only `set_layer_flags` (Lock/Unlock itself) is exempt. | new `actions/ItemLocks`; `StudioActions.execute`; `AIToolExecutor.executeTool` |
| extra | Provider quirks are remembered per model, not per address (one tool-less model no longer switches tools off for all). `look` no longer refuses a text-only model on OpenRouter, where the free router's eyes are borrowed for that step. | `JoybotAgent.quirksFor`, `look` |

## Not done here (bigger than a fix — proposed as their own pieces of work)

- Rendered observation (`look` sees the composed frame, not the source clip), VisLab/JoyBrush pictures.
- JoyBrush command adapter (drawing, boards, layers, frames as tools).
- A common document adapter with revisions / operation IDs / commit receipts (true exactly-once recovery).
- A measured free-model task benchmark (the 90 % target).
- Recorder: the toolbar's Visualiser label staying "On" after the overlay closes (recorder.md) — the
  recording-toolbar owner's fix; reported to the VisLab lane.

## Phone checks owed (Note 9)

1. Studio: "make the title red" → one change, one undo; ask again → still works (history per project).
2. Start a long request, tap Stop, send another at once → the first never continues.
3. Open Joybot on project A, start something, open project B's Joybot → B's chat shows nothing of A.
4. Lock a text, ask Joybot to move it → refused with "ask whether to unlock".
5. Floating video at 0–10 s: "make it end at 10 s" → unchanged (not 1 ms).
6. VisLab: "hit harder on the kick" → still one change, one undo.

## Phone checks, Note 9 (2026-10-23/24)

| Check | Result |
|---|---|
| 1 Studio change + one undo | ✅ "FONT TEST red" in one step; the card's Undo restored the exact colour |
| 2 Stop then Send | ✅ the stopped request never continued; the new one ran alone. Stop landing mid-tool not reproduced (the tool finished first) |
| 3 One chat per project | ✅ project B's chat showed none of A's run; A's finished run was waiting in A's chat ("While you were away…") |
| 4 Locked text | ✅ after 4 fixes (c473b0171, 9304277c2): refuses and asks; "yes" unlocks + moves; one Undo restores both |
| 5 Floating video "end at its current end" | ✅ out point stayed 4069 ms (not 1 ms); the reply quoted the real bounds |
| 6 VisLab change + one undo | ⏳ not run: a `gradlew -t installDefaultDebug` watcher (started 08:13 on 10-24) reinstalls the app on every file change, which closed VisLab mid-check. Run it when builds are quiet |

Bugs found by these checks and fixed: a free model unlocked a locked item on its own (unlock now needs the person's own
words or "yes"; `PersonSaid`); the J9 history budget counted the system message and dropped every earlier turn; a
refusal triggered the "make the change now" nudge and an empty reply then said "Done."; the refusal told the model
never to unlock, so it refused even after the person's yes. Also `tools/build-install.sh` now refuses to install over
another lane's phone lock.
