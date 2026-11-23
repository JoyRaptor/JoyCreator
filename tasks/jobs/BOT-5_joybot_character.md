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
- [x] The owner sees it play (clips sent 11-15), then land on `joy-creator`.

## 11-15 (later): the owner's nine new sets, loop styles, moments and choreography
Owner, 11-15: double point ("you the man"), salute (greeting; "understood" before a job), point left / right / down,
present right, painting (loop and ping-pong), bow (greeting, thanks, praise, job done), checklists; winks, blinks,
tongue and smiles held for nuance; "a lot of these are things that can have multiple uses" (typing for any writing,
the big list for any reading); "get loops to look less loopy by varying the frames that are next to each other";
painting "swiping one direction, or swiping, mirror, then swiping another direction, or ping-ponging"; "choreography
... squash and stretch, pacing, moving around ... mirror those sprite animations ... he can do a flip ... particle
effects ... when he's searching, he can be kind of searching around".
- [x] Sheet v3 (`art/Joybot-sprite-assets/aligned_v3_20261115`, README there): 348 frames, the new sets on home by least
      difference, the bow mirrored to face the chat, crop 466 to 490 source px so the lower cones fit (old cell numbers kept).
- [x] `joybot.animations.json` / `acts.json`: `play` (stutter / strokes / pingpong / loop), `uses` (moods, moments, words),
      `moves` (scale, hop, flip, drift with facing, sparks, mirror); `JoybotFilm` plays the styles and mirrors a play,
      `JoybotView` draws the moves and sparks, `JoybotActs` picks by words and moments from the data.
- [x] Director moments: greet after the flight lands, salute before a job (not twice within 20 s), bow on thanks or
      praise, bow or "you the man" when a job is done, cheer flourish (spin, double point, the confetti set), present
      new ideas (every 30 s at most), point at what he shows off, point down at the box when you tap into it; a moment
      plays to its end before the chat's next mood.
- [x] Cloud build (d259969c1); test tablet: the bow greeting after landing, flight 60 fps with no gaps.
- [x] The owner OKs the clips; landed 11-15 evening. (Point down at the box and the thank-you bow not caught on camera.)

## 11-15 midday: the owner's asks after watching it (do these before the arrival lands)
Told to the orchestrator in the owner's words; each is a requirement.
- [x] **Way back home.** He flies back into the button he came out of when the chat closes, not just out.
      *built in 641ce79e3 (cloud build compiles); not yet seen on a device:* `arrival/HomeFlight` (head first, thruster trail, a little toward us, tumbles small into the
      button, a pink ring) over the whole window; the drawer folds under him; a card first goes back to a full window in place.
- [x] **X and cog swap (full-screen phone mode at least).** After he flies out, the X sits where he came out (upper right,
      under the thumb that opened him) and the cog sits over Joybot. No colour backing behind either: remove it or make it
      fully transparent. Tapping X flies him straight back to where he started.
      *built in 641ce79e3 (cloud build compiles); not yet seen on a device:* X top right, moved onto the button he came out of (`ChatArrival.placeClose`); cog and speaker top
      left; borderless ripple only (`chat_round_ghost` deleted).
- [x] **Depth in flight.** Vary his scale through the loop-the-loops, so he sometimes seems to fly closer to us. On the
      ground, or scooting along it, he is exactly his resting size.
      *built in 641ce79e3 (cloud build compiles); not yet seen on a device:* `Flight.DEPTH` 0.3 by how far his heading turns back, smoothed along the path, faded out near the
      ground; `run-joybot-flight.sh` checks it (88 flights pass; deepest 1.24, his own size on the ground).
- [x] **Moods that mean something.** While he checks the help documents: a thinking animation and a *reading* one (looking
      in the handbook). Whenever he can't help or says he can't do something: the nervous animation.
      *built in 641ce79e3 (cloud build compiles); not yet seen on a device:* moods READ (`reading`, the book) and NERVOUS (`oops`: sweat, gritted teeth); a question
      alternates think and read; a help-book answer reads first; offline "can't", failed runs and replies that say they
      can't (`HelpBook.saysItCant`) are nervous.
- [x] **Soft top edge.** The conversation scrolls up into a very soft gradient that darkens upward, never a hard line.
      *built in 641ce79e3 (cloud build compiles); not yet seen on a device:* fading edge on the scroll + `chat_scroll_fade` carrying on `chat_header_fade`'s shade.
- [x] **Touch him.**
  - Asleep, tap or double-tap: he wakes with a scramble or an "oopsies".
  - Awake, tap: one of many different actions, and he moves around while doing it.
  - Asked "do a backflip": he does a backflip in the chat, then confetti or a bow.
  - A directional poke nudges him (he's a little guy).
  - A flick flings him round the screen: he bounces off the edges, comes back and settles.
  *built in 641ce79e3 (cloud build compiles); not yet seen on a device:* `JoybotTouch` (tap through the click, poke, flick by speed), `JoybotDirector.tapped / poked / flung`,
  `JoybotView.nudge / fling / say` (a word above his head); "oopsies!" on waking; tap moments from the data ("tap").
- [x] **Tricks on command** (owner via the orchestrator, 11-15): go to sleep; hop on one foot (springy squash and
      stretch); backflip; beg (built from the frames we have); roll over; shake, with little electric zaps.
      *Built in 5af3bf65b, not yet seen on a device:* `JoybotDirector.trick(bot, name)` with names `TRICKS` = sleep, hop,
      backflip, beg, roll-over, shake; `trickFor(text)` for typed commands (the chat does them instantly, offline). Agreed
      with the Joybot brain lane (BOT-7), which calls `trick()` from its own words. Beg = the confetti set's hands-clasped
      frame held, with bounces; roll over = the spin frames rolling right and back; shake = the nervous frames, shaking,
      with zaps drawn round him.
- [x] **How big before he looks blurry** (owner via the orchestrator, 11-15): measured, see the shared note
      `joybot-sharpness-20261115.md`. The app's pictures (240 px cells from the 4560 sheet, his height 181 px) stay sharp up
      to 92 dp on the Note 9 (dense), 90 dp on the tablet, 69 dp on the Fold; everywhere he is shown today (26-83 dp) is
      within that and uses the full sheet. Size (owner's caution: Play limit, about 20 MB for all new art): Joybot's
      pictures are 4.23 MB on joy-creator, 5.12 MB with this branch's nine new sets (+0.89 MB, all WebP); no big frames
      ship. The first-run intro is the owner's own work: no big pack here. A large moment, if ever, gets a double-size
      copy of only its own animations (about 0.3-0.5 MB each).
- [x] **Bug hunt A10 / A11** (Fable bug hunt, `claude/bughunt-20261115`), *fixed in 518f3e7d4, not yet seen on a device:*
      A10 lost work: over a room where work is edited the card is modal (no own window; the paused room under it can't be
      touched; a tap beside it closes the chat; it rides up for the keyboard); only the lobby and the Library keep the
      free-floating card. A11 two chats: `JoybotOpen.start` ignores a second open within 1.2 s or while a chat is open;
      a closing chat detaches only its own listeners; the chat in front re-attaches in `onResume` and shows what finished.
- [x] Cloud builds c9928b929 and fb351a417; Note 9 recordings: X flies him home, tricks (backflip), tap, poke and flick, the
      modal card; depth raised to 2.5 (up to about 2.8x his size) on the owner's word. Thinking/reading/nervous with a
      real AI not caught on camera (the tablet has no AI connected; the free model answered in 4 s).

## 11-15 evening: hover and body language (owner's picks on the hover picks page)
Owner: "Hover D (Alive), 12 dp above the seat ... every once in a while a thruster pulse"; oopsies, idea, showing and
celebrating Yes (idea and celebrating drawn larger: their art shrinks him); thinking paces further on a long think,
stops longer, wanders off to the side, hops back; talking is mostly typing his reply, the marble now and then;
celebrating hops in a circle, nearer and further. New art: taking notes (notepad, 9 frames) and a thinking pose.
- [x] Sheet v4 (art aligned_v4_20261115, README there): taking-notes cells 348-356, thinking-still 357; idea 1.25x,
      confetti 1.3x; moves.body, weight, rests in the data; the packer carries weight and rests.
- [x] JoybotBody (hover D with habits and puffs; body language per animation, blended); 12 dp hover: he lands on the
      seat with the impact, then lifts (Flight.withRise). PC tests: run-joybot-body.sh (all pass), run-joybot-flight.sh
      (88 pass, lifts to his hover).
- [x] Wiring: notes while you type; options (A/B/C) when he offers several ideas; the idea pop for a tip; a no bins the
      idea; the thinking pose mid-think; the brush now and then at rest; a job done sometimes celebrates.
- [x] Cloud build (b4afd06ba) compiles. Test tablet and Note 9 recordings (no errors; arrival 0-1 frame gaps): he floats 12 dp above the seat, reads at rest, notes while typing, discards on "no", backflip, brush, flies home; clips sent. A short think only starts his pacing: long thinks not yet seen on a device.
- [x] The owner OKs the clips (11-15 evening: "all the animation looks really great"), and asked one fix first:
      **a purple circle flashing behind him between actions.** It was his old line-art icon on its purple orb, shown
      for about 4 frames while the next animation's picture opened (only six of 40 are kept open): 16 flashes in the
      2.5-minute Note 9 clip, one frame pulled out and seen. Fix eac42073e: the director says a picture is opening
      (`Director.opening`), the view keeps him as he is (a finished film holds its last frame and keeps hovering),
      and before his first animation nothing shows; the line art stands in only if a picture fails to open
      (`JoybotView.pictureFailed`). Merged joy-creator (0325c729b, cloud build compiles), landed. Proof, the same
      frame-by-frame scan of his seat: Note 9 old build 16 flashes (39 frames), new build none in 2.8 min of arrival,
      16 taps, help, "no" and three tricks; test tablet old build 7, new build none (arrival and 16 taps).

## 11-20: idle steadied; the principles written down
Owner: the idle float looks great, but idle's frames "look a little bit jerky"; he guessed they were never aligned
by difference. Right: idle was one of my six sets, and its art carried a 13 px bob (whole body and rings) that
stepped up to 7 px a frame at 8 fps on top of his smooth hover.
- [x] Sheet v5 (`art/Joybot-sprite-assets/aligned_v5_20261120`, README there): each idle frame slid onto home by
      least difference, whole pixels. Frame to frame 3.6 px mean / 8 max -> 1.1 / 3 (his best sets: 1.0-1.1). The
      other five of mine checked: talk, listening, spin within 2 px; listen's head tilt and showcasing-video's raised
      screen are poses, left alone. Only `idle.webp` changes (5.29 MB in all, unchanged).
- [x] [Joybot animation: principles and toolkit](../joybot/JOYBOT_ANIMATION_PRINCIPLES.md), linked from AGENT_START
      and the BOT-2, BOT-7 and FX-1 cards.
- [x] Cloud build 9dcc073a2 compiles; landed. Proof is in the frames themselves (the measurements above, and
      `previews/idle_before_after.gif` in the v5 folder: the old head rises above a line, the new one stays on it).
- [ ] Not yet seen on a device: the Note 9 was PIN-locked and the tablet and Fold not connected (11-20). A head
      tracker for the recording is ready (counts 3 px jumps per second; old build at rest: 1-12 a second).

## What is left
- [ ] Load the owner's labelled sheet; check each mood on the phone. (Owner: the sheet, and OK the names.)
- [ ] Fly-to-corner and show-off moves.
- [ ] Guide the first run, then fly to his corner ([DOOR-3](DOOR-3_first_run.md)); the tour stays with him
      ("what would you like to see?" with intent-shaped choices).
- [ ] Owner idea, undecided: tap him for a quick voice ask, hold for the full chat.
- [ ] Replace the FadCam avatar toggle with Joybot (FadCam independence, [FREE-1](FREE-1_fadcam_inventory.md)).

## Specs, handoffs and evidence
- **[Joybot animation: principles and toolkit](../joybot/JOYBOT_ANIMATION_PRINCIPLES.md)** (11-20): read first for any Joybot motion work.
- [Plan A, "Joybot as a character"](../joybot/JOYBOT_PLAN_20261014.md), [INBOX 9-09 and 9-12](../INBOX.md).

## Files and traps
- `ui/lobby/JoybotView`, `JoybotFilm`, `ai/joybot/JoybotPulse`, `ui/lobby/arrival/**`. He ALWAYS animates, whatever the
  phone's animation setting (owner, 11-14): his flights and the chat's opening run on Choreographer, never on system
  animators (the owner's Note 9 has "remove animations" on, which makes a ValueAnimator jump to its end).
