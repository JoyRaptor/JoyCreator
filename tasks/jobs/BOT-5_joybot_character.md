# BOT-5: Joybot's character, and Joybot as the guide

**Milestone:** M2 (with the front door)  ·  **State:** ⛔ waits on the owner's labelled sprite sheet and on DOOR-3  ·  **Owner:** open  ·  **Checked:** 2026-10-28

## Why it matters (for the user)
Joybot is the face of Joy Creator: he greets you, shows you around once, then lives in his corner. That is
the "iPhone fans like it" part of the front door.

## Done when
- [ ] His moods play the owner's animations; he flies to his corner and beside new pictures.
- [ ] First run: he guides, then takes up residence; tapping him later offers the tour again.

## Where it stands (verified 10-28)
Moods (idle, listen, think, work, talk, excited, show, oops) and places are built and played with two
stills; a sheet loader exists and maps animations by name. The owner is drawing the sheet.

## 11-14: Report this reply
- A flag on Joybot's answers saves the reply and question to `Joybot/Reports/` and opens a prefilled email (Play's AI-content rule, [OWNER_CLICKS](../store/OWNER_CLICKS.md) section 6). Contact address is the stand-in `studio@joycreator.cc`.
## 11-13: the sheet arrived and is wired (not yet built or seen on a phone)
- The owner's sheets were aligned and packed: `art/Joybot-sprite-assets/aligned_v2_20261113/` (untracked art) holds the big sheet,
  `joybot.animations.json` (edit this) and previews. `python tools/joybot/pack_joybot.py <that folder>` remakes `assets/joybot/`.
- `JoybotActs` (catalogue + picture cache), `JoybotDirector` (which animation, way in / out, idle drift, flourish, job hints),
  `JoybotFilm` (intro-then-loop, body box, end action), `JoybotView` (Director hook, sway / bob / squash, flight lean).
- Branch `claude/joybot-sheet-20261113`. **Builds.** Test tablet 11-14: idle seen (bob, blink, lower left, speech tail). The arrival spin was 
  skipped (the chat changes his mood several times as it opens); fixed, rebuilt, installed, **not re-seen**: the tablet locked itself.
  Next: record arrival, a change of action and the return; then every mood on the Note 9.
- Owner, 11-14: he ALWAYS animates (never a still for the phone's animation setting); lower LEFT is his corner; answer bubbles point at him.
- Owner asks still open: a pointing frame for "show me where" (he is generating pointing and display gestures);
  about 22 free cells on the sheet for the actions left off (presenting-player, organizing-files, greeting).

## 11-15: the arrival, building the owner's picks
Picks (design page `tasks/design/UI-Joybot-chat-arrival-options.html`, owner 11-15): direction 2; full sheet on a phone; no bar,
big JOYBOT title; title out of the button; drawer opens out of the button; flight: his 6 paths plus Claude's dive and skim (7);
final approach any of rocket / plop / skim; his seat at the start of the bottom row; double-bounce landing; later opens: the whole
show, a different path every time; tablet: floating card; frost on.
Owner's settings (Joybot settings, "how the chat opens"): drawer opening (out of the button / curtain / in his wake; tablet
default curtain), title (out of the button / with the drawer / letters land after him; tablet default with the drawer), later
opens (whole show / shorter / quick hop), phone framing (full sheet / inset card, resizable), tablet floating card (resizable,
placeable, shorter than the screen so the button shows), frost.
Owner, 11-15: effects are GL (VisLab's plumbing), not stock Android; the turn is a texture flip or a turn drawing, never a
squeeze of the container; afterimages must sell as a light trail.
Build steps (branch `claude/joybot-sheet-20261113`):
- [x] 7th path packed into `assets/joybot/paths.json` (`tools/joybot/pack_paths.py`, both SVGs).
- [x] Settings: `ui/lobby/JoybotArrival` (defaults by `WindowSize.isExpanded`: tablet curtain + title with the drawer + card;
      phone out of the button + full sheet); a different path and approach each open (`nextPath`, `nextApproach`).
- [x] Chat drawer (`res/layout/activity_chat_assistant.xml`, `ui/lobby/arrival/ChatArrival`): no bar, round close / settings,
      big JOYBOT (one view a letter), seat at the start of the bottom row (`SeatDrawable`), reveals (button / curtain / wake,
      `Reveal`), title transitions, closes the way it opened; card (`CardFrame`, `CardGrip`): move by the title, size by the
      edges, remembered; once he has landed the window becomes the card so the screen beside it stays usable.
- [x] GL stage (`ui/lobby/arrival/stage/`, shaders `assets/joybot/stage/`): built on VisLab's `GlProgram` / `GlTarget`, one
      small program per effect; flight maths `Flight` (checked on the PC: all 7 paths x 3 approaches end on the seat upright,
      no NaN), light trail (duotone, Screen), rings, sparks, glints, thruster light, landing flash, heat haze from a picture of
      the screen taken as it opened (`JoybotOpen`, PixelCopy) with the drawer drawn over it as far as it has opened; hands over
      to the chat's own Joybot on the seat, keeps a calm glow 1.6 s, then shuts its GL down. Calm rings on the seat after that
      come from `JoybotView` itself (no GPU while the chat sits still).
- [x] Settings rows (`ArrivalSettings`, in Joybot's settings): drawer opening, title, later opens, phone card, frost.
- [x] Openers pass their button (`JoybotOpen.start`): lobby, Studio, TextLab, VisLab, SpriteLab, Library (no button: top right).
      JoyBrush (`JoyBrushActivity.kt`, JoyBrush lane) still opens without one: he comes from the top right there.
- [x] Cloud build (fa1c34c77, 9272e643a: both compile). Test tablet 11-15 01:24, screen recordings: the card drops like a curtain at
      the right under the button (button still in sight), the title comes with it, he flies out of the button with his light
      trail, rocket landing with the braking rings and the landing flash, lands on his seat, "Something else?" after him, calm
      ring under him; the window then becomes the card (840x1644 px at 2088,172) and a swipe beside it turned the lobby dial
      (STUDIO to TEXTLAB); dragging the title moved it; it reopened where it was left, on a different path, with a skim landing
      (sparks and rings along the floor); the close button closed it. No errors in the log.
      Found: Frost never showed: this tablet has Android's window blur off (`mBlurEnabled=false`, no SurfaceFlinger blur), as
      Samsungs commonly do, so Frost is now a CPU blur of the screen picture taken at open (`FrostView`, 52d26da17), like
      Studio's drawers. The old corner grip sat over Send: replaced by edge sizing (`CardGrip`).
- [x] Frost and edge sizing seen on the test tablet (52d26da17): the lobby behind the card blurred and lined up with the real
      one after a move and a resize; dragging the left and right edges sized the card; Send untouched. The tablet's missing
      blur is the device, not a setting: `disable_window_blurs` unset, no reduce-transparency, no battery saver, but
      `ro.surface_flinger.supports_background_blur` unset (`mBlurEnabled=false`).
- [x] Note 9 (Android 10, 622f9cd94), full sheet: opens as a circle out of the button, JOYBOT grows out of the button, frosted
      lobby behind, he lands on his seat with his ideas after him. Frost measured (4 opens each, no screen recording; gfxinfo
      counts the lobby behind too, which keeps animating): **Frost on** janky 2.2 / 2.0 / 1.4 / 1.8 %, p50 8-9 ms, p90 11-12 ms,
      p99 20-23 ms; **Frost off** janky 1.2 / 21.3 / 5.7 / 1.0 %, p50 7-9 ms, p90 9-24 ms, p99 15-31 ms (noisy; the GL stage had
      no gaps in the 21 % run, so it was the lobby). The blur itself: 3.2-3.5 ms once per open, off the main thread. The GL
      stage: 239-319 frames per arrival, draw+swap 3.0-3.7 ms average, no gap over 20 ms except the first open after an
      install (one 67 ms gap, shader compile). CPU zone 34.8-37.4 C throughout. Verdict: Frost costs no frames; kept on.
- [ ] The owner sees it play (clips sent 11-15), then land on `joy-creator`.

## What is left
- [ ] Load the owner's labelled sheet; check each mood on the phone. (Owner: the sheet, and OK the names.)
- [ ] Fly-to-corner and show-off moves.
- [ ] Guide the first run, then fly to his corner ([DOOR-3](DOOR-3_first_run.md)); the tour stays with him
      ("what would you like to see?" with intent-shaped choices).
- [ ] Owner idea, undecided: tap him for a quick voice ask, hold for the full chat.
- [ ] Replace the FadCam avatar toggle with Joybot (FadCam independence, [FREE-1](FREE-1_fadcam_inventory.md)).

## Specs, handoffs and evidence
- [Plan A, "Joybot as a character"](../joybot/JOYBOT_PLAN_20261014.md), [INBOX 9-09 and 9-12](../INBOX.md).

## Files and traps
- `ui/lobby/JoybotView`, `JoybotFilm`, `ai/joybot/JoybotPulse`, `ui/lobby/arrival/**`. He ALWAYS animates, whatever the
  phone's animation setting (owner, 11-14): his flights and the chat's opening run on Choreographer, never on system
  animators (the owner's Note 9 has "remove animations" on, which makes a ValueAnimator jump to its end).
