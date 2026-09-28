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
