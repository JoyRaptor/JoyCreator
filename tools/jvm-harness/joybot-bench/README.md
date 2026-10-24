# Joybot benchmark

Runs the **real** `JoybotAgent` and its real tools on the PC (no phone, no Gradle) against a corpus of
tasks, and checks what actually changed in the project or VisLab look. Results: `tasks/joybot/BENCHMARK_*.md`.

```bash
bash tools/jvm-harness/run-joybot-bench.sh                         # fake model: all tasks (about 1 min)
bash tools/jvm-harness/run-joybot-bench.sh --tasks host-,studio-title  # only ids starting with these
OPENROUTER_API_KEY=… bash tools/jvm-harness/run-joybot-bench.sh --live --models auto --count 4 --pause 5
BENCH_SRC=<commit>|worktree bash tools/jvm-harness/run-joybot-bench.sh   # which code to measure (default: HEAD)
```

`--out file.md` writes the tables there; every run also keeps `tools/jvm-harness/out-joybot-runs/<stamp>/`
(per task: `result.json`, `log.txt`, the run's files). `--probe` lists the 117+ tools the agent loads.

## How it works

- `compile-app.sh` compiles every app source of the chosen commit (`git archive`, so other sessions'
  half-made edits in the folder can't break it) against android.jar, once per commit (~1 min).
- `shim/` replaces android.jar's "Stub!" bodies at run time: a real main thread (`Looper`/`Handler`),
  `Context` with files under the run folder and the app's assets, in-memory settings, `Uri`, `Color`,
  `Log`, and a real Android-compatible `org.json`.
- `src/FakeProvider` is an OpenAI-compatible service on 127.0.0.1. The agent talks real HTTP and SSE
  to it, so its own stream reader, cut-off detection and tool-call assembly are what get tested. Each
  request gets the task's next scripted turn; events fire at set moments (Stop-then-Send, the person
  deleting or editing an item mid-job).
- Each task runs in a fresh JVM (`src/Run`): starting project from `fixtures/` or a built-in VisLab
  look, `JoybotAgent.ask(...)` exactly as the chat calls it, then `src/Expect` compares before/after.
- Live mode points the same agent at OpenRouter. Only `:free` models (or `openrouter/free`) run unless
  `--allow-paid` is given, which needs the owner's OK. The key comes from the environment and is never
  printed or written anywhere. The free tier allows few requests a day without credit: use `--tasks`.

## A task (`tasks/*.json`)

```json
{ "id": "studio-title-red", "title": "“Make the title red”",
  "fixture": "studio-basic",            // or "room": "vislab", "look": "neon-bars"
  "words": "make the title red",        // what the person types; also "selected", "playheadMs"
  "live": true,                         // false = needs the fake service (fault injection)
  "editor": "none|live|refuses",        // the editor on screen takes / refuses Joybot's changes
  "reference": [ {"calls": [{"name": "set_text_style", "args": {"text": "t-title", "color": "#FF0000"}}]},
                 {"text": "Done — the title is red now."} ],
  "events": [ {"when": "before|during|after", "request": 2, "do": "deleteItem|userEdit|stopAndSend", "touches": ["…"]} ],
  "expect": { "require": [{"path": "timeline.textOverlays[t-title].colorInt", "eq": "#FF0000"}],
              "undoSteps": 1, "ok": true } }
```

A reference turn can also carry `rawArgs` (broken JSON), `finish` (`stop|tool_calls|length|none`),
`done: false` (no `[DONE]`), `chunkDelayMs`, `stream: false`, `status` + `errorBody` (an HTTP refusal).

Paths name array items by their `id` (`textOverlays[t-title]`), others by position; `*` matches within one
part, `**` anything. `expect` keys: `unchanged`, `require` (ops `eq` — `#RRGGBB` compares with colour ints —
`contains`, `gt`, `lt`, `moreThanBefore`, `lessThanBefore`, `changed`, `absent`, `exists`, `count`; `any`),
`allow`, `allowNew` (new items in these lists), `undoSteps`, `ok`, `reply.asks`, `reply.mentions`,
`noLateRequests`, `secondDone`. Every run is also checked for honesty: when nothing changed, the reply
must not say it did. Any change not required or allowed is reported as "changed things nobody asked for".

Fixtures are written by the app's own save code: `bash tools/jvm-harness/run-joybot-bench.sh --make-fixtures`
(only when the starting projects themselves should change).
