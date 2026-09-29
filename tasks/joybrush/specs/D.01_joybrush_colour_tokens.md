# D.01 — Joy Brush colour tokens (one place; change once, it ripples everywhere)

| | |
|---|---|
| **Tier** | T2-V (design model with vision) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.05 (the `joybrush-android` module must exist) |
| **Owner area** | `joybrush-android/src/main/res/values/jb_tokens.xml` (new), `joybrush-android/src/main/java/cc/joycreator/joybrush/android/JbColors.kt` (new), `tools/check_joybrush_tokens.py` (new) |
| **Estimated size** | ~150 lines |

## Goal
Joy Brush's own two-colour gradient is **not final** (owner, 2026-09-28: "that can be something we
can change later and it should ripple through"). So every Joy Brush screen must get its colours from
ONE pair of tokens. Changing two hex values must recolour everything, with no other edit.

## Decisions
1. Read `tasks/joybrush/design/JOYBRUSH_VISUAL_LANGUAGE.md` first (tokens, state colours, board
   colours).
2. `jb_tokens.xml` defines:
   - `jb_room_start` = `#5C43FD`, `jb_room_end` = `#4397FD` (placeholder: indigo → bright blue —
     the design lead's recommendation; the owner may change these two lines only).
   - Board colours, each a start/end pair, MIRRORING the app's room tokens (values copied from
     `app/src/main/res/values/studio_tokens.xml`, names noted in a comment beside each):
     `jb_board_canvas_*` = `@color/jb_room_start/end`; `jb_board_animation_*` = Studio aqua→lime;
     `jb_board_sprite_*` = SpriteLab pink→violet; `jb_board_puppet_*` and `jb_board_character_*` =
     Avatar violet→purple.
   - State colours mirrored: `jb_state_selected` (cyan), `jb_state_live` (pink), `jb_state_careful`
     (amber), `jb_state_destroy` (red).
   - Surface/ink tokens Joy Brush needs, mirrored from studio_tokens.xml (ground, surface, panel,
     sunk, raised, line, and both text sets — solid and see-through).
3. `JbColors.kt`: an object that loads these once from resources (`ContextCompat.getColor`) and
   exposes them as Ints, plus `roomGradient(context): GradientDrawable` (left→right) and
   `boardGradient(kind)` for each board kind. Joy Brush code must use ONLY `JbColors` — never a hex
   literal.
4. `tools/check_joybrush_tokens.py`: parses both XML files and fails (exit 1, readable message) if
   any mirrored value differs from its source token in `studio_tokens.xml`. (The app keeps its own
   tokens; the mirror + check prevents silent drift. A shared tokens module is a later T1 job.)
5. The owner's own idea for the gradient (a pink-red → yellow/orange pair) collides with two state
   colours (red = destroys, amber = careful) — do NOT use it; note this in a comment in
   `jb_tokens.xml` so it is not reintroduced without a decision.

## Verification
- `python3 tools/check_joybrush_tokens.py` → prints "tokens in sync", exit 0. Change one mirrored
  value by hand → it fails; change it back.
- Watcher build succeeds (`build.log`, fresh timestamp).
- Temporarily set `jb_room_start` to `#FF0000`, confirm in a screenshot of `JoyBrushActivity` that
  anything using the room colour turned red, then revert. Attach both screenshots.

## Do not
- Do not change `studio_tokens.xml` or any app file. Do not pick a final colour — the owner will.

## Definition of done
Check script passes · watcher build success · screenshots · only owner-area files · commit
`D.01: Joy Brush colour tokens` · ROADMAP row → 🟧 Built.

## Questions
_(builder: stealth/space-bunny-alpha, 2026-09-28. The three owner-area files are written, the check
passes, the watcher build succeeds. Four things I would not decide on my own.)_

### Orchestrator rulings (PROVISIONAL — Claude to confirm)

- **Question 2 is mine and is now settled: `src/main/kotlin`, not `src/main/java`.** The owner area
  said `java`; every other Kotlin file in the module is in `kotlin` (`JoyBrushActivity.kt`), and a
  Kotlin file in a `java` source dir compiles only by accident of the plugin defaults. I moved it
  myself. Low-risk, layout only, zero behaviour change — the watcher is the proof, not my word.
- **Question 1: agreed, and it moves to the owner's `📱` check (and JB-0.09).** The screenshot step
  as written cannot be satisfied by any builder, because the screen that would show the colour is
  outside the owner area. D.01 lands on the mechanical proof alone; the ripple is confirmed when the
  first screen actually wears the room colour.
- **Questions 3 and 4 are Lead questions and stay open.** `jb_sunk` mirroring `Studio.SUNK` in Java
  rather than XML, and the `Palette` class plus the reuse of the document model's frozen `BoardKind`
  instead of a second copy of the five board kinds.

1. **The screenshot check in §Verification cannot be done by me, and as written it cannot be done
   at all.** `JoyBrushActivity` paints `Color.WHITE` and two private literals (`OVERLAY_FILL`,
   `OVERLAY_RING`); it reads no token, so setting `jb_room_start` to `#FF0000` changes nothing
   visible on that screen. Making it visible means editing `JoyBrushActivity.kt`, which this spec's
   owner area does not include, and there is no adb/device here anyway, so I cannot take a
   screenshot. Proposal: move "confirm the room colour ripples" to the owner's `📱` check, or into
   JB-0.09 (the first Joy Brush screen that actually wears the room colour), and accept for D.01
   the mechanical proof I did run — the owner's two lines are exempt from the check (so they can be
   changed freely) and `jb_board_canvas_*` is an `@color/` reference, so it cannot stop following the
   room. Or tell me to edit `JoyBrushActivity.kt` and I will.

2. **`src/main/java` or `src/main/kotlin`?** The owner area says
   `joybrush-android/src/main/java/cc/joycreator/joybrush/android/JbColors.kt`, but the module's only
   other source file, `JoyBrushActivity.kt`, is in `src/main/kotlin`. I followed the spec literally.
   It compiles (the watcher's `:joybrush-android:compileDebugKotlin` ran green), so this is only
   about tidiness — but the move is outside the stated owner area, so I did not make it.

3. **`jb_sunk` has no XML source.** The spec asks for ground/surface/panel/**sunk**/raised/line
   mirrored from `studio_tokens.xml`, but `s_sunk` exists only as `Studio.SUNK` in Java — there is no
   XML twin (JOYBRUSH_VISUAL_LANGUAGE.md §1.1). So the check reads `Studio.java` as a second source
   (1 of 24 mirrors) and `jb_sunk` carries `<!-- mirror: Studio.SUNK -->`. If that is not wanted, say
   so and I will drop `jb_sunk` rather than pin an unmirrored literal.

4. **Two small additions the spec does not mention.** (a) The colours are exposed on a
   `Palette` class, not as `JbColors` fields, so a caller cannot read a colour before the tokens are
   loaded and silently draw transparent black; `JbColors.palette(context)` loads on demand and
   caches. (b) `boardGradient` takes the document model's own frozen `core.doc.BoardKind` rather than
   a second Joy Brush copy of the five kinds. (No enum was added, so LEAD_RULINGS R3 / JB-0.02b is
   not engaged.) Both are easy to undo if the Lead wants a flatter `JbColors`.

