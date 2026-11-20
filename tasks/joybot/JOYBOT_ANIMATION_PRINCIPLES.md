# Joybot animation: principles and toolkit

Written 2026-11-20 by the Joybot sprites lane (BOT-5), at the owner's request: "when I start a new agent ... working
with making new interactions with Joybot, say tutorials or lobby work or anything like that, for it to have
everything that it needs."

**Read this before you make Joybot move anywhere**: the chat, the lobby, a tutorial, a room transition, Avatar
Studio. It is what took him from "pretty boring and lame" to "really alive and nice and well polished" (owner,
11-20), learned over many rounds with the owner. Part 1 is his bar, Part 2 the craft that meets it, Part 3 how to
work with him, Part 4 the tools and code, Part 5 checklists.

Related: [BOT-5 card](../jobs/BOT-5_joybot_character.md) (history, evidence), [BOT-7 card](../jobs/BOT-7_lobby_wizard_and_room_colours.md)
(his lobby brain), `tasks/STATE.md` (Joybot rows), the art's own README in `art/Joybot-sprite-assets/aligned_v5_20261120/`.

---

## Part 1. The owner's bar

Each line is a ruling of his (date in brackets). Treat them as requirements, not taste.

1. **He always animates. Never a still Joybot** (11-14). "He's a character that guides you. He needs to be
   animated." Never gate him on the phone's reduced-motion or animator-scale setting. Everything he does runs on the
   frame clock (Choreographer / `postInvalidateOnAnimation`), because Android ends system animators instantly when
   animations are off (the Note 9 test phone has them off).
2. **His art, not regenerated art** (10-14, 11-20). The owner draws Joybot. Build from his sheets and his finished
   look (`art/Joybot-sprite-assets/Polished character referances for generation/`). Regenerating or approximating
   parts loses what makes him cute. Never invent frames with an image model.
3. **Frames aligned by maximum black** (11-16, 11-20). His method in SpriteLab: put two frames in difference mode
   and slide one until the overlap is blackest, where the body mass is. Frame-to-frame steadiness decides whether
   a loop reads smooth or jerky. Idle was jerky for a week because its art carried a 13 px bob that stepped at
   8 fps (fixed in sheet v5). See 2.1.
4. **Motion on top of the frames** (11-13, 11-15). "Use the squash and stretch ... extra zest." Sprite frames are
   only the acting. Hover, drift, leans, hops, squash and stretch and depth are a second layer, smooth at 60 fps.
5. **Big, smooth, toward you** (11-15, 11-16). "Twenty percent bigger, those are rookie numbers": in flight he
   grows to 2 to 3 times his resting size (he comes at you), and "at 2 times, and maybe even 3 times, it's really
   hard to notice" any softness on the device (11-20). He travels far, in smooth arcs the eye can follow. Wind-ups
   are visible (a run-up before a kick), impacts get a spark like his landing flash, and a pull escalates (small
   tug, harder tug, then the whole side). Back on the ground he is exactly his resting size.
6. **Every expression has body language, WALL-E's Eve the model** (11-15). Shy and low when nervous ("the oopsies
   ... absolutely amazing"), pops up when he has an idea, paces when he thinks, gets up and slides over to show you
   something, hops round in a circle when he celebrates. He hovers 12 dp above his seat with Eve's drift ("Hover D,
   Alive") and an occasional thruster puff.
7. **Moods mean something** (11-15). While he checks help: thinking, then reading his handbook. When he can't help
   or says he can't: nervous. While you type: he takes notes. When he types a reply: typing (the marble from his
   chest only now and then: "a little bit distracting"). Several ideas on offer: the A/B/C options. Told "no": the
   idea goes in the bin. A job done: sometimes a celebration.
8. **Hands sell it** (11-16). Whatever he holds or pulls is pinned to his fists through the whole motion, and he
   turns with what he pulls. Carried things sit in his fists. For carrying something heavy his pick is `oops`.
9. **Touch him** (11-15). Asleep: a tap wakes him ("oopsies!"). Awake: each tap plays a different action and he
   moves while doing it. A poke nudges him; a flick flings him bouncing round the chat until he settles. Tricks on
   command: sleep, hop, backflip, beg, roll over, shake (with zaps).
10. **Never a glitch, never a technical error** (JoyBrush's three bars: an impatient artist, toddlers, the owner
    himself as a pro). A flash of the wrong picture between two actions reads as broken (11-16: the old line-art
    icon on its purple orb flashed for 4 frames while the next animation loaded). Hold what is on screen instead.
11. **Effects made of the same stuff; ambient extras subtle and cheap** (10-28, 11-12). His trail is a light trail
    of himself, his rings come off his own thruster, sparks come from where he lands. Ambient touches (the faint ring
    every ~1.1 s on his seat) stay faint and cost no frame rate.
12. **Where he lives** (11-14, 11-15). Lower left of the chat (lower right is the user's send button); speech
    bubbles have a tail pointing at him. He flies out of the button that opened the chat. The X sits where he came
    out, and closing flies him straight back into that button. The cog sits over him, no colour backing.
13. **Cuteness and continuous expression are the bar** (11-20, Avatar Studio). Lids that slide and tilt, eyes that
    swell and squash with overshoot: continuous acting, not one sprite per mood. Use every mouth he drew. Plasma
    arms are good; the ring emitter under him should lag and spring like a tail.

---

## Part 2. The craft

### 2.1 Frames: alignment and the sheet
- **Align every frame of an action to home** (cell 0) by least difference: slide by whole pixels, no resampling,
  then move the whole action together. Within an action keep the owner's own frame-to-frame alignment where he
  made one (`alignedBy` in `joybot.animations.json` says whose each set is).
- **Measure before you trust it.** For each pair of frames shown one after the other, find the slide that best puts
  the second on the first. A steady set measures about 1 px on average (his best: think 1.1, taking notes 1.0, idle
  after the fix 1.1). Anything that steps 5 px or more frame to frame at 8 fps will be seen. Then check visually:
  difference images before and after the slide (the body and rings should go black; only the face and hands may
  differ). A slide that lines up a tilted head but splits the base rings is wrong; peg by the body.
- **Don't align a different pose to home.** A set that holds a screen over his head (showcasing-video) can't match
  home; least difference there means nothing.
- **Sets drawn smaller get scaled to match** (the idea set 1.25x, confetti 1.3x): judge his eyes and body against
  home side by side.
- **Colour-match older matte sprites to the glossy ones** (hotter pink glow, bluer helmet, deeper yellow eyes,
  neutral face grey). New art in the old matte look gets the same grade.
- **Rests**: frames he may hold a while, like pausing to think (reading's look-up, taking notes' frames 4 and 9).
  Marked in the data (`rests`); the STUTTER style holds them 6 to 18 ticks.
- **Never ship a fallback that can show.** While the next animation's picture opens (6 of 40 are kept open) he
  keeps playing or holds his last frame, still hovering (`Director.opening`). The line-art icon appears only if a
  picture fails to open at all.

### 2.2 The body layer (`JoybotBody`)
- **Hover D**: 12 dp above the seat. Eve drift: x 2.6 dp on a 5.2 s sine, y 3.4 dp on 2.9 s, a 3 degree lean, a
  little squash. Calmer while reading or taking notes (0.6), working or typing (0.7), thinking (0.8).
- **Habits when nothing else is going on**: a glance, a dart, a re-hop, a thruster puff with a ring. Rare enough to
  surprise, never a rhythm you can count.
- **One kind of body language per action**, named in the data (`moves.body`): nervous, idea, think, talk, type,
  show-right / show-left / show-down, cheer, read, notes, work, paint, greet.
- **Blend every change over 350 ms.** Never snap from one pose to another. Leaving a far-off think hops him back
  over 650 ms with a 10 dp arc.
- **Thinking paces**: legs at 26 dp/s, stops of 0.7 to 2 s; a long think (over 6 s) stops longer (1.4 to 3.6 s) and,
  one time in three, wanders off to the side (90 dp), then hops back when it ends.
- **Celebrating hops in a circle**: x 18 dp on a sine, scale 1 + 0.16 cos (nearer and further), facing the way he
  goes, landings with squash and ground sparks.
- **Nervous**: 6 dp lower, 95% size, a fine shiver, tilted 4 degrees.
- **Idea**: pops up 12 dp with overshoot and a stretch, sparks once it peaks.
- **Squash and stretch on every new frame**: a small pop (3.5%) on each frame change, a bigger one (9%) when a new
  animation starts, over 240 ms, cosine-damped.

### 2.3 Flight and depth (`arrival/Flight`, `HomeFlight`, the GL stage)
- **Paths are the owner's**: six drawn paths plus a dive and a skim (`FlightPaths`), a different one each open;
  landings by rocket, plop or skim, never the same twice running; a double bounce on the seat, then he lifts to his
  12 dp hover (`withRise`, 320 ms).
- **Depth by scale** (`Flight.DEPTH` 2.5, up to about 2.8x): from how far his heading turns back, smoothed over
  time (4 ms samples, smoothed twice over ±70 ms) so the size never jumps; eased to 1 over the last 100 ms. Ground
  equals resting size. Big moments use a sharp 480 px pose (`assets/joybot/hi/home.webp`), because 240 px cells
  stay sharp only to about 92 dp on the Note 9, 90 dp on the tablet and 69 dp on the Fold.
- **Head first**: he leans and turns into the direction he flies.
- **Effects on the GL stage** (built from VisLab's `GlProgram` / `GlTarget`): a light trail of himself (hot pink to
  yellowish white, added like Screen), plasma push rings, sparks, glints, thruster light, a landing flash and heat
  haze. Then the chat's own Joybot takes over and the stage switches its GL off. It draws in about 3.3 ms a frame on
  the Note 9.
- **Going home**: lift (110 ms), a quadratic arc head first, a little toward us (1.14x), shrinking to 0.22x as he
  spins into the button, a trail, a pink ring.

### 2.4 Choosing what he does (`JoybotDirector`)
- **Moods** (`JoybotView.Mood`): IDLE, LISTEN, THINK, WORK, TALK, EXCITED, SHOW, OOPS, READ, NERVOUS. Ask for a
  mood, never for frames; the director picks the animation.
- **Variety**: several animations per mood, picked by `weight`, never the one he did last; random mirroring where
  the art allows; a flip chance.
- **Moments** are one-shot flourishes asked for by name: greet, on-it, thanks, done, you-did-it, cheer, confetti,
  present, tip, options, discard, notes, ponder, brush, point-left/right/down, bow, tap, wake, flung, dizzy, shake,
  roll. A flourish plays to its end, then he goes back to the current mood (or the next one asked for).
- **Way in and way out**: most animations have in and out frames (taking the notepad out, putting it away). Leaving a
  mood plays its way out first.
- **Idle has a life**: reading after 45 s, sleeping after 3 minutes; a paintbrush wave now and then (one in three
  rests, after 18 to 32 s); mid-think a thinking pose (after 5.5 to 9 s).
- **He checks before he promises** (BOT-7): no "On it" for something that can't run here; one plain sentence, the
  nervous look, and the nearest thing he can do.

---

## Part 3. Working with the owner

- **He is an artist and animator, not a programmer.** He judges motion as an animator (anticipation, arcs, staging,
  squash and stretch). Make the engineering calls yourself; explain them in what he will see.
- **Show options as a page he can pick from**: true-size samples, several directions, per-part choices, Yes / Tweak /
  No per item, and a line he can copy back. The hover picks page (11-15) is the model. His picks become the spec.
- **Record, don't describe.** Screen-record the Note 9 and the test tablet doing the thing; send the clips. He
  watches them and answers in voice notes (expect speech-to-text slips: "JoinBot" is Joybot).
- **Prove it frame by frame.** A flash, a jump or a jitter is measurable in the recording: scan his box frame by frame
  (colour share, position) and compare before and after. "No flashes in 2.8 minutes of 16 taps, help, a no and three
  tricks" convinces; "looks fine" does not.
- **Trust his eye.** When he says something reads wrong, find the measurable cause (the idle bob, the 4-frame flash)
  before changing anything; his guesses about causes are usually right ("the sprites were never aligned").
- **Report plainly**: what changed, what he will see, what is not checked yet. Never claim a device check you did
  not run.

---

## Part 4. Tools and code

### 4.1 Code map (`app/src/main/java/com/fadcam/ui/lobby/`)
| File | What it does |
|---|---|
| `JoybotView` | The character view: moods, the film view (frames + body layer + sparks, rings, zaps, words), touch reactions, tricks |
| `JoybotFilm` | One animation on a clock: styles LOOP, PINGPONG, STUTTER (rests), STROKES; in / loop / out; moves; body |
| `JoybotActs` | The catalogue (`assets/joybot/acts.json`) and the picture cache (6 open at a time) |
| `JoybotDirector` | Picks what he plays: moods, moments, chains, weights, idle life, tricks (`trick`, `TRICKS`, `trickFor`) |
| `JoybotBody` | The body layer: hover D, habits, body language per kind, blends |
| `JoybotTouch` | Tap, poke, flick |
| `arrival/ChatArrival` | The chat opening out of his button, his flight in, his seat, close and fly home |
| `arrival/Flight`, `FlightPaths` | Flight maths (paths, depth, rise); pure Java, tested on the PC |
| `arrival/HomeFlight` | The flight back into the button |
| `arrival/stage/*` | The GL stage: trail, rings, sparks, light |
| `arrival/CardFrame`, `Reveal`, `FrostView` | The tablet card and the reveal |
| `ui/faditor/ai/ChatAssistantActivity` | The chat: what he does when (help, typing, ideas, replies) |
| `TrickWords` | The phrases that ask for a trick (BOT-7; checked by the harness) |

### 4.2 Asking him to do something (from code)
- `actor.setMood(JoybotView.Mood.THINK)`: he goes into thinking (the way out of what he was doing first).
- `JoybotDirector.moment(actor, "thanks")` or `actor.playMoment("thanks")`: a one-shot flourish, then back.
- `JoybotDirector.trick(actor, "backflip")`: a trick; `TrickWords` / `JoybotDirector.trickFor(text)` match typed ones.
- `actor.say("oopsies!")`: a word pops above his head. `actor.nudge(dx, dy)`, `actor.fling(vx, vy, settled)`.
- `JoybotOpen.start(activity, button, intent)`: open the chat out of a room's Joybot button (lobby, Studio, TextLab,
  VisLab, SpriteLab, Library do).

### 4.3 The art pipeline
1. The owner's sets arrive as 3x3 or 8x2 sheets (about 418 px cells), usually in `art/Joybot-sprite-assets/`. Never
   change his folders; work in a new versioned folder (`aligned_v6_<date>`), copied from the latest.
2. Cut his cells at his own grid, place on home by least difference (Part 2.1), colour-grade if matte, scale if
   drawn small, onto the big sheet (`sheet/joybot.png`, 19x19 cells of 240 px; `sheet/half/` at 120 px).
3. Add or edit the animation in `joybot.animations.json`: `in`, `loop`, `out` (sheet cells), `fps`, `play`
   (loop / pingpong / stutter / strokes), `mood`, `general` (may be picked for the mood), `uses` (`moods`,
   `moments`, `words`), `moves` (`body`, `scale`, `hop`, `drift`, `flip`, `sparks`, `mirror`), `weight`, `rests`.
4. Pack: `python tools/joybot/pack_joybot.py "art/Joybot-sprite-assets/aligned_v5_20261120"`. It writes
   `assets/joybot/acts.json` and one WebP per animation (frame 0 always home). Never edit `acts.json` by hand.
5. Watch the size: his pictures are 5.29 MB today; the Play build allows about 20 MB for all new art. Big-moment
   copies at 480 px cost about 0.3 to 0.5 MB per animation, so keep them to the few poses that fly big.
6. Write the folder's README: what changed, measured before and after, what was left alone and why.

### 4.4 Tests and checks
- PC tests (no phone): `tools/jvm-harness/run-joybot-flight.sh` (88 flights end on the seat, upright, facing
  front; depth bounds), `run-joybot-body.sh` (body language bounds), `run-joybot-bench.sh` (his replies, with a
  fake model).
- Cloud: `.\tools\cloud-build.ps1 test <branch> joybot` (about 5 minutes); a debug build with
  `C:\Temp\cloud-build-latest.ps1 run <branch>` (then `fetch <runId>` if the download drops).
- Devices, through `tools/phone.ps1` only: **claim first** (`claim <name> <why>`, then `holds`; stop if you don't
  hold it), wake the screen and stop if a lock screen shows (never enter a PIN), press Home before installing (an
  unsaved drawing is lost on install), launch with `start` (never `adb shell monkey`), release when done. Note 9 =
  sandbox; the test tablet's recorder needs `--size 1480x924`. Never uninstall, never clear app data.
- Frame scans of a recording: crop his box, measure per frame (colour share for flashes; position for jumps),
  compare against an old build's recording made the same way.

---

## Part 5. Checklists

**Before showing the owner**
- [ ] Every new frame aligned to home by least difference; frame-to-frame jumps measured (about 1 px mean).
- [ ] Body language for the new action named in the data; blends in and out, no snaps.
- [ ] Runs with the phone's animations off (clock-driven).
- [ ] No fallback picture can show in between (open its picture before it is needed, or hold).
- [ ] Recorded on the Note 9 and the tablet; scanned for flashes and jumps; no errors in the log.
- [ ] Sharp at the size it is shown (240 px cells: about 92 dp on the Note 9); big moments use 480 px.

**Before landing**
- [ ] The owner OKed the clips.
- [ ] Cloud build compiles; Joybot test suites pass.
- [ ] BOT card, `tasks/STATE.md` row, `tasks/ROADMAP.md` row and `tasks/BOARD.md` updated.
- [ ] Merged the latest `joy-creator`; pushed; remote matches; the owner's main folder fast-forwarded.
