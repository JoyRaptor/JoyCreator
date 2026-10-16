# Joy Creator action registry — design (gap F2, 2026-10-15)

**Owner's goal:** Joybot can do anything a person can do, anywhere in the app — one button, a typed request, a
recorded routine, or a whole headless job. **Problem:** 66 hand-written `case "tool":` branches can't scale to
every drawer in every wing, and they each describe themselves in loose text. **Answer:** one list of actions,
each declared once, read by everything.

## What reads the list

| Reader | Uses |
|---|---|
| Joybot's model (native tools, F1) | name, help, typed parameters → exact JSON Schema per action; validation errors in plain words |
| JSON-in-text fallback | the same, rendered as the numbered tool text |
| Idea buttons / offline runs (`DirectRunner`) | `Idea.Step {tool, args}` is already an action call — it runs through the same door |
| Recipes (F7) | a recorded routine is a list of action calls |
| Help + "show me how" (F8) | title, help, `where` (the path through the real UI) |
| Director mode review (F3) | each call's summary + the ids it touched (Show me / revert) |
| Safety | `effect`: READ, EDIT, DESTRUCTIVE (confirm first), PAID (price + consent), NAVIGATE |

## One action = a spec + a handler

**Spec** (pure Java, tested off device — `ai/actions/ActionSpec`):
- `tool` — the API name, `^[a-z][a-z0-9_]{0,63}$` (e.g. `set_text_font`)
- `title` — person words ("Change the font"); `help` — when to use it, for the model and the help book
- `wing` + `family` — STUDIO/text_look, SPRITELAB/cells… (grouping, rooms, routing)
- `params` — typed: STRING, INT, NUMBER, BOOL, ENUM(values), ID(kind: clip/text/image/sprite/sheet/audio/layer/any),
  TIME_MS, COLOR (#RRGGBB / #AARRGGBB), STRING_LIST, ID_LIST; required/optional, min/max, help, example
- `effect` — READ / EDIT / DESTRUCTIVE / PAID / NAVIGATE; `offline` — runs with no network
- `where` — "Studio › tap a text › Style › Font" (help + show-me-how)

**Handler** (Android side): `ActionResult run(ActionContext ctx, Args args)`.
- Works on the project MODEL it is handed (`ctx.project()`); **never saves, never touches views**.
- Returns `ActionResult`: `changed`, `summary` (person words), `detail` (for the model), `touchedIds`.
- Throws `ActionError("plain words")` for anything the person/model must fix ("No text layer called that").

**Host** (who runs it): loads the project once (`JoybotStorage` — live editor or file), validates args against the
spec, runs the handler, saves once if `changed` (the editor on screen adopts it as one undo step, merged per
request — existing behaviour). Later the editor's own buttons can call the same handler on their live project
between their undo brackets; that migration is optional and per family.

**Legacy bridge:** every current `AIToolExecutor` tool is registered on day one as a spec with its doc text and
open parameters, and a handler that calls `executeTool` (which loads/saves itself — flagged `selfCommitting`). Each
family is then rewritten natively at its own pace; nothing breaks in between.

## Ids and names

Every ID param takes an id **or a name** ("Cat 2", "hello 2") — the host resolves names with one helper, and
ambiguity is an `ActionError` listing the candidates ("Two text layers are called 'Title' — which one: …").
Kinds come from one lookup across clips, overlay clips, text/image overlays, sprites, visualizers, adjustment
layers, audio clips.

## Build order

1. Core (spec, params, schema, validation, registry, dispatcher) + harness tests.
2. Bridge all existing tools; `JoybotAgent` and `DirectRunner` call the dispatcher. No behaviour change.
3. First native family: **text look** (font, colour, size, outline, shadow, glow, background, animation preset)
   — the owner's "change the font" example. Phone-test.
4. Grunt briefs: one per family from FEATURE_GAPS (audio ducking, transforms, looks, organisation, captions,
   transitions, project care, then other wings). Each brief = specs + handlers + tests, following family #3.

## Two hosts (added 2026-10-16)

| Host | Context | For | Families |
|---|---|---|---|
| `StudioActions` | `StudioCtx` — one loaded project; saved once after the action; the editor adopts it as one undo step | everything done TO a project | text look, transitions, audio, captions, transform, organise, slides, looks, inspect, export, project care, sources (+ motion, sprites on video, clip shape, visualizer — 10-16) |
| `AppActions` | `AppCtx` — the app + the open project id (may be null); nothing to save; `ctx.route` names a room to open | app-wide: rooms, the Library, starting things | navigate (`open_room`), library (10-16) |

`AIToolExecutor.executeTool` asks Studio, then App, then the old switch. NAVIGATE actions never touch the screen from
the worker: they set `ctx.route`; the chat takes it (`AppActions.takePendingRoute`) and calls its `route()`.
A new app-wide family = one file `<Family>Actions.java` with `register(ActionRegistry<AppCtx>)` + one line in
`AppActions.build()`; the family brief applies, with AppCtx in place of StudioCtx.
