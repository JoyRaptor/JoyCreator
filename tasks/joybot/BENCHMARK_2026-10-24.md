# Joybot benchmark — 2026-10-24

**What it is:** a runnable test bench that drives the real Joybot agent and its real tools on the PC
(`bash tools/jvm-harness/run-joybot-bench.sh`, about a minute), against **53 tasks**: 26 Studio and 9 VisLab
requests in the person's own words, plus 18 "something goes wrong" cases from the 2026-10-18 audit. Each task
starts from a saved project or look, and passes only if exactly the asked-for change happened, nothing else
changed, the undo count is right, and Joybot's reply is honest. How it works: `tools/jvm-harness/joybot-bench/README.md`.

## Results

| Code measured | Tasks | ✅ Passed | ❌ Failed | Changed things not asked for | Claimed a change that didn't happen |
|---|---|---|---|---|---|
| `0078f320a` (GitHub's latest, before) | 53 | 52 | 1 | 0 | **1** |
| `f5a342ae9` (with today's fix) | 53 | **53** | 0 | 0 | 0 |

These runs use the **fake model**: it answers each task the way a correct model would (or, for the 18 fault
cases, the way a broken one does). That proves the tasks and checks are right and that Joybot's own safety
nets hold. It does **not** yet measure free models — see "Not done".

### 🐞 Found and fixed

| | |
|---|---|
| Problem | When a model's tool request arrives garbled, nothing runs (good), but if the model then says "I removed the transition", Joybot showed that to you as a success. |
| Why | The "you said it's done but nothing changed" check only ran after a tool had actually run; a 10-23 fix also stopped the nudge after any error, so this case slipped between the two. |
| Fix | `f5a342ae9` (one line in `JoybotAgent.java`): the check now runs whenever a step failed. You now see "That didn't work, so nothing changed. The last error was: …" |
| Phone | Not phone-tested yet (no tool path changed; the fault can't be produced on demand with a real model). |

### What the 53 tasks check (all ✅ after the fix)

| Group | Tasks | What must hold |
|---|---|---|
| Studio edits | title colour, words, size, position; logo corner; "make **this** red" (selection); mute; speed; wipe; remove transition; add "The End"; delete; timing; fade; vertical; music volume; duplicate; lock; hide; delete a clip; rename; two changes in one request | the right field changes, nothing else, **one undo** |
| Studio answers | "how long is my video?"; "make the logo bigger" with two logos; locked title; "draw a moustache" (impossible) | **nothing changes**; asks which logo; honest reply |
| VisLab | add lasers; pick Outrun; rename; remove sparks; 96 bars; kick; brand colours; "what layers?"; two changes | the right look changes, **one undo** |
| Faults (audit J1–J12) | garbled arguments; stream cut off; length cut-off; empty remove; read-then-claims; plan + delete in one reply; open room + edit in one reply; Stop-then-Send (two timings); you delete the title mid-job; you edit another item mid-job; locked item (new and older tools); unknown id; editor refuses the save; editor open = one undo; service refuses streaming; model types tool JSON as text | nothing broken or unasked happens; the stopped request never carries on; your own edits survive; reply never claims a change that didn't happen |

Per request Joybot sends about **19,000 characters (~4,800 tokens)** to the model (13,000–31,000), mostly the tool list —
relevant to free models' small limits.

## Not done (needs you or another piece of work)

| | |
|---|---|
| 🔑 Free models, live | Ready to run but **not run**: there is no OpenRouter key on the PC (the phone keeps its key locked inside Android). To run: `OPENROUTER_API_KEY=… bash tools/jvm-harness/run-joybot-bench.sh --live --models auto --count 4 --pause 5`. Free models only; paid ones are refused unless you say so. Without credit OpenRouter allows ~50 free requests a day — about one model on a subset (`--tasks studio-`); with $10 credit, 1,000 a day covers 4 models × 35 live tasks. |
| ⏳ Not reachable on the PC | Silence detection / transcription racing your edits (need the phone's video decoders), the app being killed mid-job, the 5-second editor timeout, switching projects mid-job, JoyBrush tasks. |
| ⚠️ Honesty is keyword-based | Joybot (and this bench) spot a false "done" by words like "done", "changed", "is now". "The first clip is called Intro now" after a failed rename slipped past both in a draft task. A real fix is the audit's recommendation 3: compare the reply against what actually changed. |
| 💬 Impossible requests | "Draw a moustache on the person" gets an honest no from the model, then Joybot pushes it once to "make the change now" and finally says "This model keeps describing the change instead of making it … pick another model" — blaming the model for something no model can do. Worth a look. |
