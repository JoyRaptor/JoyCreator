# Brief — build one action family for Joybot's registry (gap F2)

You are building ONE family of Joybot actions. Design: `tasks/joybot/ACTION_REGISTRY_SPEC_20261015.md`.
**The template to copy is `app/src/main/java/com/fadcam/ui/faditor/ai/actions/TextLookActions.java`** — read it
fully first, then `ActionSpec.java`, `Args.java`, `ActionResult.java`, `ActionError.java`, `StudioCtx.java`,
`StudioActions.java` in the same folder.

## Hard rules

1. **Create exactly ONE new file**: `app/src/main/java/com/fadcam/ui/faditor/ai/actions/<Family>Actions.java`,
   package-private `final class` with `static void register(@NonNull ActionRegistry<StudioCtx> r)`.
   Do NOT edit any existing file (not StudioActions, not StudioCtx, not the editor, not the model). If you need a
   new id resolver, write it as a private static helper in YOUR file. The lead adds your register line.
2. **Only expose what a person can already do in the UI.** For every action, find the editor code that does the
   same thing (mostly `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` — huge, use Grep, never
   read it whole) and mirror its model calls EXACTLY, including companion calls (e.g. seeding a zone, clamping,
   re-sorting a list, marking a lane). Note the editor line you mirrored in a one-line comment. If the model has
   a field but nothing in the UI or the renderer/export reads it, do NOT expose it — list it in your report.
3. Handlers change the `ctx.project` model only. **Never** save, never touch views, never call the editor, never
   use Android UI classes. Return `ActionResult.changed(summary, touchedIds)` or `ActionResult.unchanged(...)`
   for READ actions. Throw `ActionError("plain words")` for anything the person/model must fix — errors list the
   valid choices (ids + names) so the model can ask.
4. Words: `title` and summaries are plain person words ("The music is quieter under your voice."), no jargon, no
   ids in summaries. `help` tells the model WHEN to use it. `where` = path through the real UI.
5. Typed params: use `.id()`, `.choice()` (ENUM with every allowed value), `.number(min,max)` with the SAME ranges
   as the UI sliders, `.color()`, `.flag()`, `.text()`, `.param(name, Type.TIME_MS, …)` for times. Ranges come
   from the UI code, not guesses.
6. Prefer one action with several optional params over many tiny ones (one request = one undo step).
   Tool names: `^[a-z][a-z0-9_]{0,63}$`, must not clash with existing tools — check the `case "…":` labels in
   `app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java` (~line 95) and the other files in `ai/actions/`.
7. Java 8 syntax + minSdk 24: no `String.join`, no `java.nio.file`, no `List.of`, no `var`, no text blocks, no
   switch expressions. Lambdas / method refs are fine.
7b. If you edit with a script, keep LF line endings: Python reads/writes BYTES (never text-mode `'w'` on
   Windows — it writes CRLF and breaks the source-reading harness; see tasks/lessons.md).
8. NEVER run Gradle. Never commit. Do not run `tools/jvm-harness/typecheck.sh` (the lead runs it once for all
   families — parallel runs clobber each other). You may run `bash tools/javacheck.sh <your file>` if it exists.
9. Re-read your file once at the end and check every method you call really exists with that signature (Grep for
   `public .* methodName(` in the model class). A wrong signature breaks the whole app build.

## Report (under 350 words)

- File created, tool names + one line each.
- For each action: the editor line(s) it mirrors.
- Fields you deliberately did NOT expose and why.
- Anything you were unsure of (signatures you could not confirm).
