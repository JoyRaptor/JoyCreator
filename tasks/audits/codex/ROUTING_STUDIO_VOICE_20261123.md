# Codex Studio audio branches, routed 11-23 (beside ROUTING_ROUND4)

| Branch | Verdict | Proof and limits |
|---|---|---|
| `codex/audio-studio-voice` | **Landed** (Claude, 11-23) | A new "Studio Voice" chip in the audio Clean tab: one tap, cached, cancellable, never swaps the clip on failure or cancel; Undo and Revert restore the original. Additive: the only edit to existing flows is that Fix audio now shares one swap routine. Host cache test passes (second tap on the same selection ran zero decodes, also after a restart). Cloud: jb-core 3020, jb-androidkit 1375, studio 65, joybot all pass; jb-android 606 pass (two earlier runs hit a download hiccup, third clean); APK build ok. NOT done: installing on a phone, listening, power/heat on a long clip. A clip that is already cleaned silently ignores a second tap. |
| `codex/note9-studio-voice-20261119` | **Superseded, not landed** | The earlier prototype of the same feature; `audio-studio-voice` contains it, rebased. |
| `codex/suite-performance-audit-20261119` | **Landed (documents only)** | The 20-finding cross-suite audit and its card; no code. Roadmap, state and lessons rows merged beside later edits. |
