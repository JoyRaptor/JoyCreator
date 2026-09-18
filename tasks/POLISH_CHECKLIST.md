# Lobby · Studio · Welcome — the finish list

Every item here is JoyRaptor's own instruction from the 2026-09-17/18 session, kept
in the repo because this conversation has been compacted twice and both times a
compaction lost a spec I then rebuilt from memory and got wrong.

**The specs are files, not memory.** Before changing any of these three screens,
open the mockup:

| Screen | Mockup | Section |
|---|---|---|
| Lobby | `design/marquee.html` | §01 The lobby, §02 The bottom third |
| Welcome / first run | `design/marquee.html` | §03 First run — the two changes |
| Studio | `design/studio-final.html` | The screen, The transport row at 2× |
| Palette | `design/catalog.html`, `design/kit.html` | — |

(The mockups live in the session scratchpad `design/`. Serve them with
`python -m http.server` and open in a browser — a `file://` tab renders as a
static snapshot that page tools cannot read.)

---

## A. Theme system — "dozens of colors, not hundreds"

Audit of the editor, 2026-09-18:

| | |
|---|---|
| Distinct hand-typed colours | **373**, used **1,856** times |
| Of those, greys | **188** — 103 used exactly once |
| Uses going through a named token | **21** (~1%) |
| Greys meaning "this control is off" | **11** |
| Colours meaning "this is selected" | **3** |
| Material green `#4CAF50` | ~200 uses, 31 files |
| Studio lime `#97FE8B` | **0** uses |

- [x] `res/values/studio_tokens.xml` — one name per MEANING, never per shade
- [x] `Theme.FadCam.FaditorEditor` points at tokens (fixes 112 dialogs + every
      Material slider/switch/ripple in one line — they had no colour code to grep)
- [x] Greens → `s_go` / `studio_action_pill`. Convert BY MEANING in passes:
      selected, then on/armed, then modified, then go, then audio-identity
- [x] 188 greys → the 5 ground + 4 ink tokens
- [x] `#4DD0E1` (27 uses) — a second near-identical cyan; collapse into `s_armed`
- [x] `#F43F8E` playhead → the Capture gradient
- [x] Delete `timeline/TimelineView.java` + `timeline/SegmentTimelineView.java` —
      referenced by nothing, still carry white playheads and green selection

## B. Studio

> **Done 2026-09-18.** 373 chrome colours became 28; the transport row, lanes,
> minimap, playhead and tapes are all to spec. Object tapes now draw as adjacent-pair
> gradients from the Swatch Room wheel.


- [x] Transport row: undo/redo beside play; **every other tool works inward from
      the outside**, so a gap falls between the tools and the undo history
- [x] Undo/redo dark ONLY when there is no history. With history → white or a
      slightly-off white. (Mic on the right is the reference for "off".)
- [x] Alternating lanes: one set fully black, the other **visibly** brighter —
      currently both read black unless the phone is at high brightness
- [x] Audio lanes are lighter grey than the other lanes; make them agree
- [x] Minimap spine: **50–60% shorter** (it predates the rest of the minimap)
- [x] Minimap selection range: currently white and only over the spine. Make it a
      **1px cyan frame spanning the minimap's full (variable) height**
- [x] Playhead body + the long vertical pin: **full Capture gradient, vertically**
- [x] Slice preview: a **1px amber dashed line** on the playhead, spanning ONLY the
      selected object/clip/audio's vertical range, drawn OVER the pink playhead.
      Answers "what exactly will Slice cut?" — it sits over the OBJECT, not the clip
- [ ] Playhead line is **2.5dp** (answered). KineMaster's is thinner — open question
      whether to reduce; the slice dashes are 1 physical px as specified
- [x] Try the time chip as a **trapezoid** — `\00:01.127/` — echoing the chip slant
- [x] Chip solid + rounded, time punched out
- [x] Film spine reads as film on near-black (rail lifted, holes darkened, cut edge)
- [x] Image + sprite blocks: no header glyph, the image IS the badge
- [x] Canvas selection box cyan over a dark halo; handle role colours untouched
- [x] Contextual tool row — built. Context sorts ONLY the unpinned section, so pinned
      tools never move. Device-verified: selecting an audio object left everything to
      the left of the divider byte-identical and swapped the right-hand section to
      Extract audio / Sound / Clean.

## C. Lobby

- [x] Sheared gradient chips, centred, gap closed 30%
- [x] Archivo / IBM Plex bundled and wired
- [x] Joybot: real art, white on the indigo disc, hover + smile on push and scroll
- [x] Breathing room: shorter hero, air under the wordmark, STUDIO 34sp
- [x] Re-checked against `marquee.html` §01/§02: hero meta is now
      `N LAYERS · M:SS · AGO` as specified, and the hero swap has the 2px blur
- [x] Rooms still route to the six legacy tabs — and those tabs are now Joy Creator
      too, via the theme rather than via a pass over each one. Device-verified on
      Smart Detection, Recordings, Faditor and Settings.

## D. Welcome / first run

- [x] Rebuilt from `marquee.html` §03 — seven cycling PHRASES, dark field with one
      soft glow, 3000/560/260ms, "Start creating" + "Support"
- [x] Language screen deleted — the system already knows the locale
- [x] Typewriter, Arabic greeting cycle and the toggle-knob avatar deleted (~350 lines)
- [x] Verified on device against the mockup side by side
- [x] Screen 3 "Before we start" — copy already matched; ticks now turn aqua and a
      disabled primary swaps its FILL rather than fading the gradient to mud

## E. Joybot — where he is going

Owner, 2026-09-18: his icon is white, but at large sizes and in chat he becomes an
**animated claymation avatar**. Two real spritesheets are being organised. The
intended entrance: *he greets you as claymation, then flies up into the corner and
flattens into the white icon.*

- [x] `JoybotView` hosts a FRAME SEQUENCE via `JoybotFilm` + `playThenFlatten`, with
      every existing call site unchanged. Awaiting the real spritesheets — the cell
      grid and the authored fps are constructor arguments, not guesses.
- [x] Two stills registered on his HANDS — scaled to a common hand span the smiling
      head is 246×295 vs the neutral's 231×319, wider and shorter at the same area,
      which is squash and stretch. Anchoring the crown destroys that

## F. Standing rules (do not relearn these)

- Greys fade a dense surface; colour guides the eye
- Old black, flat, pills, rounded; frosted needs a toggle for precise work
- One saturated control per screen
- A dead knob is worse than a missing one
- Never animate a control used 100×/day (undo, play, split, keying, the playhead)
- Motion curves are already correct and match emilkowalski's STANDARDS.md exactly:
  ease-out `(0.23,1,0.32,1)`, ease-in-out `(0.77,0,0.175,1)`, drawer `(0.32,0.72,0,1)`,
  press scale 0.97, UI under 300ms — first-run/explanatory may exceed it
- Never `adb uninstall`; never resolve a merge conflict; never run gradle directly
- Owner is `JoyRaptor` in every tracked file — never his real name

---

## G. Session of 2026-09-18 — what changed and what is still open

### Fixed, device-verified

| | |
|---|---|
| Palette | 373 chrome colours → **28**, and now CENTRAL: 1,036 token references, 10 literals left (all identity tables). Editing `studio_tokens.xml` changes the app. |
| Token values | Realigned to the Studio mockup's own spec table (Sprite Lab's ramp) — I had drifted a few points per channel and invented a parallel ramp. |
| Carousel | Rebuilt as a dial: scale for size, Archivo's `wght` axis for weight, translationX for packing, one shared baseline, all caps, snap with momentum. |
| Hero | Drag turns the dial; tap enters. Three stacked bugs fixed — see commit `a90e05c4`. |
| Navigation | Back goes to the lobby (3 sites). All three fragment loops now include position 6; restore defaults to the lobby and calls `handleTabSelected`. |
| Selection | Cyan ring + 28% halo, per spec. Object colour is a FILL, state colour is a RING. |
| Reduced motion | Keeps opacity, drops travel and scale — the javadoc said this; the code did not. |
| Contextual tool row | Built. Context sorts ONLY the unpinned section; pinned tools never move. |
| Joybot | Now the AI icon in the Studio and the chat, replacing the earlier robot. |
| Minimap spine | 11dp slot, 1.5dp insets → 8dp usable. 7dp left a 1dp band that could not show a loading meter, a transcribe bar or a silence gap. |

### Fixed, device-verified — second half of the session

| | |
|---|---|
| The legacy rooms | Done, and done ONCE. `Theme.FadCam.JoyCreator` resolves every value to a token and became the default. colorHeading alone is referenced 314× across 57 layouts, so the theme was the only place this could be changed once. The earlier attempts failed because there are TWO `applyTheme` methods in MainActivity and the no-arg one — the one `onCreate` calls — wrote "Crimson Bloom" back to prefs in its else branch. |
| Four more theme chains | FullscreenPreview (its DEFAULT arm was Red, so it caught everything unlisted), VideoPlayer and ImageViewer (fell to `Base_Theme_FadCam`, the old purple), and About — whose four eight-branch ladders became one `aboutAccent()`, because every branch was rebuilding by hand a value the theme already holds. |
| The greys | 343 sites in 92 files now name a token; distinct literals in layouts+drawables 281 → 242. Rules are in the commit — the first pass would have mapped 180 white icon paths onto `s_line`. |
| Welcome slide | The 500px hole between the cycling line and the price is gone: the glow takes whatever the words do not, rather than a 45/55 weight split against copy that is shorter than 55% of the screen. |
| Permissions slide | The finished state was the primary pill at alpha 0.5 — half-alpha over black halves the gradient toward black AND halves the near-black label on it, so the sentence saying everything worked was the least readable thing on screen. Done is not unavailable: outline, full opacity, aqua ink. The off-state battery label went #52525B → #8A8A94 (2.6:1 → 5.4:1). |
| "Before we start" | Centred rather than pinned 120dp from the top; it ended at 68% of the screen with the last third empty. |
| What's New | No longer the last step of the intro. A brand-new user was handed FadCam's changelog — FadCam red, FadCam Pro, an offer that expired in December 2025 — with nothing to catch up on. Still reachable from the Home sidebar. |
| Story Board empty state | The tan #C49A7C disc was the only warm thing left on any screen. An empty state guides nothing. |
| Marquee right edge | Fades rather than cutting "AVATAR" mid-letter. Left edge untouched: it is the gutter. |
| Language change | `new Configuration()` is every field at its DEFAULT, including fontScale 1.0, and it was being handed straight to `updateConfiguration`. Picking a language silently reset the user's enlarged system text. Now built from the configuration in force. Two sites. |

Font scale checked at 1.3 and 2.0: honoured (1.3 looks unchanged because Android
compresses large display text non-linearly — that is the platform, not us), and
the lobby holds at 2.0 with ellipsis rather than overlap.

### Studio Final §01/05 is now fully built

The finding it rates MAJOR reads: *"Thirty tools, seven visible, nothing saying so. The row
scrolls and gives no sign of it. A user who never swipes it believes the editor has seven
tools."* Its fix has two halves and both now exist.

The **26dp edge fade** shipped earlier tonight, so the row reads as continuing. The **live
count** is the other half, and it is the half that turns "there is more" into a number.

Verified on device: the pill reads **23 more**, and that is computed, not written down. The
view hierarchy reports 8 tool cells inside the viewport; 8 + 23 = 31, which is the 30 tools
plus the edit chip. The drawing's own mock says *"⋯ 23 more"*.

Three things about how it is built, each deliberate:

- **It sits ON the fade, not at the end of the row.** A counter you have to scroll to reach
  cannot tell you there is something to scroll to — which is the exact failure being fixed.
- **Counted from geometry, not from the tool list.** The list does not know what the viewport
  can show. A cell is hidden when its left edge is past the scrolled right edge, recounted on
  scroll and on layout so it survives a reorder, a selection retarget, or a rotation.
- **It disappears at zero.** A pill reading "0 more" takes the space and tells you nothing.

Tapping it opens the all-tools drawer — the same surface the swipe-up gesture opens.

### The density, and why it matters more than any single measurement

```
Physical size 1440x2960 @ 420dpi  =  548dp wide
Override size 1080x2220 @ 315dpi  =  549dp wide      <- how it actually runs
The design records assume               390dp
```

**His phone is 40% wider in dp than the records are drawn for.** Anything specified
in dp lands proportionally smaller on his screen than in the drawing. That is why
the hero is specified as a SHARE of the window rather than a height: a proportion
survives the difference and a dp does not.

I got this wrong twice in one night. First by dividing pixel bounds by 2.75 instead
of 1.969, which reported every transport control at half its real size. Then by
"correcting" that with a forced `wm density 420` — which gives 411dp, a width this
phone never runs — measuring a comfortable 30dp gap above the floor, concluding the
157dp hole was an artifact, and reverting the recents thumbnail to the drawn 60dp.
The hole is real at 548dp. The thumbnail is back at 84dp with the arithmetic in its
javadoc. **Restore `wm density 315` after any experiment.**

### Transport row, final — measured on the sandbox at 548dp, 2026-09-18

Read off a live `uiautomator` dump, not computed from the layout. Widths are the
delegate rectangle (half the gap to each neighbour); heights are the container.

| control | touch target | drawn |
|---|---|---|
| soft snap | 51.3 × 44.2dp | 30 × 30 |
| select mode | 44.2 × 44.2dp | 30 × 30 |
| ripple mode | 48.8 × 44.2dp | 30 × 30 |
| undo | 45.7 × 44.2dp | 30 × 30 |
| play | 51.8 × 51.8dp | its own size |
| redo | 135.6 × 44.2dp | 30 × 30 |
| voiceover | 44.2 × 44.2dp | its own size |

**Nothing is under the minimum on either axis.** Two last defects closed it.

`btn_voiceover` measured **30dp** — the smallest target in the editor. It is a direct
child of the RelativeLayout rather than of `transport_left` or `transport_right`, so
the delegate builder that grows the rest of the row never saw it. Its box is now 44dp;
the glyph is still 18sp, so it looks the same and only the ripple is bigger.

Then the vertical axis, which I had not checked at all: **a touch delegate cannot reach
outside the view that holds it.** An event that never lands on the container is never
offered to it, so `growTransportTargets` setting `bottom = row.getHeight()` was only ever
going to be as tall as the row — and `wrap_content` around a 30dp glyph measured
**42.2dp**, capping every delegate in the row no matter how wide it was made. Both
groups now carry `minHeight="44dp"`. Nothing moved: they are centred in a row the play
button already stretches to 52dp, and their children are centred inside them.

Two further fixes got it here. **Relink now appears only for media that is actually
missing**, which is what Studio Final §05 says — my first pass showed it for any
selection, and at 411dp that put five controls back under the minimum. And the
delegate builder now skips **non-clickable** children: this row separates its groups
with weighted Spaces, and a Space has real width, so handing one a delegate pointing
at something untappable left the gap as dead as before. Skipping them took ripple
from 38.5 to 55.9dp.

Soft snap was the last holdout at 37dp, because it is the first control in its
container and the container begins where the 46dp time label ends — so there was no
gap on its left to claim. I had written that shortfall off as the right one to accept,
since it is the least-pressed control in the row. That was wrong: the lever was not the
container but the button. `layout_marginStart="14dp"` on `btn_soft_snap` makes its own
left gap, and the delegate takes half of it. **51.3dp, no label narrowed.**

### The lobby, measured on the sandbox at 548dp

26 clickable nodes. **All 26 carry an accessible name** — the count I claimed earlier,
now read off a live dump rather than off the source.

Five failed the size floor, and they were all the same control: the marquee's room words,
**20.3–21.8dp tall** inside a **66dp band**. The strip you can see was mostly not a strip
you could press. They measure small because they are SCALED — the type is set at 34sp and
the unfocused words are shrunk toward the edges — so no reading of the layout would have
shown it.

Fixed the way the transport row was fixed, and the composite delegate that does it now
lives in `com.fadcam.ui.TouchDelegates` instead of being private to the editor: each word
owns its frozen layout slot, the full height of the band, and half the gap to each
neighbour. It works off layout slots rather than drawn bounds, precisely because these
children are scaled. Nothing is drawn differently. **Compile-verified only** — the sandbox
dropped off adb before I could re-measure, so this one still owes a device reading.

Everything else on the lobby clears the floor: search and Joybot at 40.1dp, the hero
action row at 40.1, the library door at 548.6 × 58.9, the recents cards at 100.1 × 125.5,
the New row at 127 × 34 and the floor dock at 104 × 36.1. §04 asks for "nothing below 28dp,
almost everything 40 or 44"; the 34 and 36dp rows sit in the tolerated middle.

### The seven records, and where each one stands

All seven exist as published artifacts from this conversation — verified by searching the
raw transcript for each title, not by assuming. They are the "html files" the goal refers to.

| # | record | url |
|---|---|---|
| — | Joy Creator Swatch Room | `claude.ai/artifact/RzCk8dwVURfuezNEcVwjgp` |
| 02 | Five Front Doors | `claude.ai/artifact/VHuyQh69uA3dEzBWwF2jsL` |
| 03 | The Studio Floor | `claude.ai/artifact/UUM9jWpnmaPKkBY9AKacgS` |
| 04 | **The Marquee** — lobby + first run | `claude.ai/artifact/HKLVT4iYjZiKYS7vYWSLVA` |
| 05 | The Studio Kit | `claude.ai/artifact/DpjtUDLJ7QSTvyHnQCVDST` |
| 06 | **Studio Final** — the editor | `claude.ai/artifact/EUSt9oqAwDdmkJepS51bZ5` |
| 07 | **The Art Order** | `claude.ai/artifact/HUmbjSccQvH9xVA3hsWtn3` |

### Record 07, *The Art Order* — what it asked for and what happened

Most of it is an art list only JoyRaptor can fill: five empty-room heroes, seven first-run
stills, a demo project, Joybot's claymation states, eight room glyphs, a grain tile, the
wordmark and app icon. Those are his to draw, not mine to build. Three of its items were
engineering, and all three are done:

- **03a, the face in the orb** — *"Right now that orb contains a generic Material robot
  glyph."* It is his own Joybot art now, in the lobby, the editor's top bar and the chat.
- **05b, a display typeface** — *"the marquee currently borrows Android's own
  `sans-serif-black`... Joy Creator's biggest, most characterful type is the same type every
  other Android app uses."* Archivo is bundled as a VARIABLE face, 100–900, which is what
  §05b asks for twice over since the marquee interpolates weight.
- **06/1, the contextual tool row** — left open in the record as *"your call how the two
  combine"* with the existing pins-and-recency system. Built so that context sorts ONLY the
  unpinned half, so pinned tools never move. **Device-verified this session:** double-tapping
  an audio clip swaps the right-hand section to Extract audio / Sound / Clean in audio green,
  and leaves everything left of the divider untouched.

**§07's unverified item is now verified.** It listed the object drawer header as
*"BUILT — not yet driven on a phone, needs a real double-tap."* It is driven, on the
sandbox at 548dp: selecting a clip and tapping **Adjust** opens the drawer over the top
bar as designed, with its **Effects** title, its accent, its ✕ and its empty state
(*"This layer changes nothing yet"*). The timeline stays uncovered underneath, which is
the whole reason the drawer hangs from the top. Screenshot taken.

My earlier note said Sound "did not open a drawer". That was not a layout failure — the
tool row is CONTEXTUAL, and with an audio clip selected it swaps Adjust out for Silence,
Fix audio and Beats. I was tapping a tool that the selection had legitimately replaced.
The drawer was never broken.

Three defects the drive-through found, none of which a reading of the code would have:

| | found | fixed |
|---|---|---|
| ✕ close, unnamed | its only accessible name was the character "✕" | `universal_close`, the same word the rest of the app uses |
| ✕ close, 32.5 × 31.5dp | a drawer's only dismiss button, sitting on the floor | **40.1 × 40.1dp**, measured after |
| the panel announced itself | `setClickable(true)` blocks taps reaching the preview, but also made the whole drawer a 548 × 187dp unlabelled button | `IMPORTANT_FOR_ACCESSIBILITY_NO` — container out, children kept |

The first two are device-verified. The third is **compile-verified only**: `uiautomator`
sets `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`, so its dump still lists the node. TalkBack does
not set that flag. The tool cannot show the difference the change makes.

Still short of the norm and left alone deliberately: the `＋ Add effect` chip at
93.0 × **30.5dp**. It comes from FxPanel's shared `chip()`, so raising it restyles every
chip in the effects panel — a bigger change than the defect. §04's floor is 28dp and it
clears it.

### The tools package still typed its colours by hand — 105 of them

Found while measuring the drawer. Three separate faults, one pass, **105 → 25**:

- **69 were tokens wearing an alpha** — `0xCC000000` is GROUND at 80%, written out 34
  times. Value-identical rewrites to `Studio.alpha(TOKEN, 0xAA)`, so nothing moved.
- **One was a colour the app no longer has.** `0x33FF4438` is a tint of DANGER as it was
  *before* the Swatch Room corrected it to `0xFFFA3D5D`. Nobody updated the translucent
  copy, so the puppet record button's armed wash was still painted in a red that exists
  nowhere else. This is precisely the failure hand-typed colour has and tokens do not.
- **27 were raw white at an alpha.** Studio's INK is `0xFFF2F2F5` — deliberately a touch
  below white. A hairline or a grip pill at `0xFFFFFF` is brighter than the brightest
  *text* in the app, which is a hierarchy inversion. Now `Studio.alpha(Studio.INK, ..)`.

Then the last hand-painted screen, ColorPickerDialog: eleven chrome colours became LABEL,
INK, INK_FAINT and OFF — `0xFFF4F4F5` turned out to be INK typed two values off. Its
affirmative button was `0xFF4FC3F7`, a Material light-blue belonging to no family; it is
ARMED now, the nearest hue the app owns. One token was added, `Studio.SUNK = 0xFF16161B`,
the value between SURFACE and RAISED that had a use and no name.

**All 25 that remain are accounted for:** 16 are the colour picker's swatch ramp, which is
CONTENT — the colours the user picks *out of*, which must not move when the app's surfaces
do — and the rest are `0x00000000` fills and one `0x00FFFFFF` bit mask.

### Against *Studio Final* §04, the spec table — "no value on this page was chosen by eye"

| spec | built | |
|---|---|---|
| ground / lane A `#000000`, "lane A draws nothing, the ground shows through" | `LANE_A` transparent | ✅ |
| panel / control / pressed / line `#111114 · #1C1C22 · #26262E · #2C2C35` | exact | ✅ |
| ink / dim / label `#F2F2F5 · #C9C9D3 · #C4C4CE` | exact | ✅ |
| drawer scrim `rgba(0,0,0,.64)` + `blur(20px)` | as drawn | ✅ |
| **transport 44dp, play 52dp, labels 46dp** | was play 40, labels 33 — now **51.8** and **46.2** | ✅ fixed |
| tool cell 56×46dp, label capped 52dp | `dp(56)` | ✅ |
| selected clip 1.5dp cyan + 3dp at 28% | as drawn | ✅ |
| press 140ms `scale(.97)`, tool swap 170ms, drawer 320ms | as drawn | ✅ |
| playhead / undo / split / keying — 0ms, either motion mode | as drawn | ✅ |
| lane B `#0B0B0E` | `#17171C` | ⚠️ his later ruling — 4.3% "reads as fully black unless the phone is turned up very bright" |

**§04 also answers the hue-collision question independently.** Its colour rules read:
*"Object colour always a FILL · State colour always a RING — cyan selected, pink live, amber
careful. **Settles the amber/sprite collision by form, not hue.**"* That is the same
conclusion I reached by measuring, arrived at in the drawing first.

**§05 asks for one thing I did not do, and the reason is in the code.** It says *"Select:
transport row, cycling with Ripple. Moved. They were always exclusive."* In this build they
are not: select cycles off / crossing / window and ripple cycles ripple / gap. Merging them
deletes a combination the editor supports. Relink DID move out, as §05 asks.

### The New row — his five numbered points, verbatim

Not a departure I chose. His message:

> The four buttons below recordings projects characters projects do this instead:
> 1. Half the height
> 2. Fill the chip with the gradient and have the icon be a punchout (pure black)
> 3. Have the text be to the right of the icon instead of under
> 4. Make the round over much less
> 5. Make the first shape a tilted on the right side and then have the rest keep that same angle slant

All five built: `CHIP_H = 34` ("half the old stacked cell"), `SlantDrawable(gradA, gradB)`
with `ic.setTextColor(Studio.GROUND)` for the pure-black punchout, a HORIZONTAL cell so the
text sits right of the glyph, `CornerPathEffect` for the reduced rounding, and the shear.

The same message is where "make the word studio larger" comes from. The caps question is
his too: *"i am wondering if it would look nicer all caps for that title row and the liberary
title row."* Both were in the raw transcript; neither was mine to decide.

### Re-reading his ACTUAL messages, not my summary of them — and what it caught

The goal says *"re go over my responses in this convorsation so you dont miss things because
of compactions."* I had been citing his instructions from a compacted summary. Reading the
raw transcript found a real defect that three sources agreed on and the build did not.

His Swatch Room message, verbatim:

```
STATE
  - Careful  #FBBF24
  - Selected #22D3EE
  - Live     #F43F8E
  - Destroys #FA3D5D
```

Both design records agree: `--live:#f43f8e; --danger:#fa3d5d` appears in The Marquee AND in
Studio Final. The build had `LIVE = #FF008C` and `DANGER = #FF4438`.

`#FF008C` is **Capture's second stop** — the same value as `ROOM_SPRITE`. So "live" was not a
colour of its own at all; it was a room's colour wearing a state's name. And the playhead
gradient only *looked* right because `ROOM_CAPTURE → LIVE` happened to spell out the Capture
gradient by accident. It now says `ROOM_CAPTURE → ROOM_SPRITE`, which is the same pixels and
the honest name.

Measured on device: the playhead chip went **#FF008C → #F43F8E**.

`DANGER` at #FA3D5D is now the same value as `ROOM_CAPTURE`, which is deliberate and is in
both drawings — Capture's gradient runs FROM the destroys-red to neon pink.

Cross-checked the rest of his list: Careful, Selected, neon pink, lime, indigo, aqua green and
every gradient pair already matched. The one value of his that has no token is Finder
(#CEFF5B / #F9F462 / #FFC341), and correctly so — the lobby floor draws Finder in grey like
every other floor word, so a Finder token would be a colour nothing renders.

### The seven repos, read and applied — 2026-09-18 05:4x

Not summarised from memory this time; fetched and tested against.

| repo | what it changed here |
|---|---|
| **jakubkrehel/skills · better-colors** | *"Use a token only in its role. Never borrow a token because its value is right today."* Four tokens rendered NOWHERE while the code that should have used them borrowed lookalikes. Fixed — see below. *"Treat hues within 15° as the same color"* caught `ROOM_LIBRARY` sitting **1.2° from `ARMED`**; it is `ARMED_LIGHT` now, a ramp step, which is what its own javadoc already admitted it was. |
| **jakubkrehel · better-ui/surfaces** | Concentric radius `outer = inner + padding`, image rings as a 1px white outline at ~10% with `outline-offset:-1px`, shadows over borders except for dividers — already how the recents cards and panels are built. |
| **emilkowalski · review-animations/STANDARDS** | Durations, the four curves, never `ease-in`, never `scale(0)`, 0ms on hundred-times-a-day actions, reduced motion keeps opacity and drops travel. All verified present. |
| **codeswithroh/tastemaker** | Interface-quality rules gave the accessibility sweep (0 of 28 unlabelled on the lobby, 0 of 11 on slide 3). The anti-slop gates flag pure-grey neutrals — ours are tinted blue, passes — and "button text matching button fill", which the luminance-derived transport ink now makes impossible. |
| **MengTo/Skills · audit-ai-design-slop** | Categories rather than a checklist: decorative stacking, component repetition, motion theatre, fake proof. The design record's own §05 already ran this sweep. |
| ConardLi/garden-skills, elayadesign, Owl-Listener | Landing-page conversion structure. Deliberately not imported — the drawing's own §05 says why: *"a rule is only as good as the situation it was written for. The motion numbers travel. The conversion structure doesn't."* |

**Four tokens rendered nowhere**, and each was being impersonated:

| token | who was borrowed instead |
|---|---|
| `FILM_RAIL` | `EditorTimelineView` used `Studio.RAISED` — a *control surface* standing in for film |
| `FILM_EDGE` | used `Studio.OFF` — an *unavailable-state* colour standing in for a cut edge |
| `FILM_HOLE` | used `Studio.GROUND` |
| `LANE_A` | `LayerRowRenderer` painted `0x00000000` directly |

Fixed so that **no pixel moves**: the film tokens were corrected to the values that actually
ship and that JoyRaptor approved, rather than repainting an approved surface to match
constants nobody had looked at. `LANE_A` is transparent now, because that is what lane A
is — the ground showing through. Every one of the 46 tokens renders somewhere.

**Alpha-over-token literals.** 77 values like `0xCC35F6BF` (GO at 80%) and `0xAA22D3EE`
(ARMED at 67%) were primitives smuggled past the token layer. Rewritten to
`Studio.alpha(TOKEN, 0xAA)`. Across the eight files behind the three screens: distinct
literal values **94 → 44**, occurrences **165 → 88**. Verified as a visual no-op by pixel
diff — **0 pixels differ** across the 600×1150 timeline band, max channel delta 0.

**The hue collisions, followed through.** Four pairs land inside the 15° window. The rule
protects things that must be told apart AT A GLANCE, so each was checked for whether it ever
has to be. None does, and all four reasons are now written into `Studio.java` so the next run
of the same measurement does not "fix" deliberate work:

| pair | Δ | why it is allowed |
|---|---|---|
| `GO` / `ROOM_STUDIO` | 0.0° | the same value by design — the Studio room's identity IS go |
| `LIVE` / `ROOM_SPRITE` | 0.0° | likewise: the Sprite Lab's pink is the live pink |
| `ROOM_CAPTURE` / `DANGER` | 13.8° | co-occur only in the timeline, where capture red is the 1.5dp PLAYHEAD and danger is a filled wash behind missing media — two orders of magnitude apart in area |
| `ROOM_VIZ` / `CAREFUL` | 11.8° | co-occur on the lobby: a 10sp mono stat label beside a clock glyph, versus a 3dp signature bar and a pill |
| `GUIDE` / `ROOM_AVATAR_DEEP` | 9.9° | two steps of ONE violet ramp — and an earlier audit already caught them reading alike and fixed it by moving the same-row cue to white, so purple means cross-row and nothing else |
| `ARMED` / `ARMED_LIGHT` | 1.2° | named as a ramp for exactly this reason |

**Accent footprint, measured.** tastemaker Gate 19 caps accent at ~5% of the screen. The
lobby as a user sees it: **4.32%**. The welcome slide: **2.98%**. Both pass. The New row's
four gradient chips are therefore NOT an anti-slop failure by the gate's own metric — the
drawing chose grey buttons when the hero action was the only saturated control, and the
screen that shipped has a large photographic hero absorbing area instead. That departure is
resolved, not outstanding.

**The other two lobby departures are decisions, not open questions.** The live word is 34sp
because JoyRaptor asked for it ("make the word studio larger"), and the titles are caps
because he asked ("it would look nicer all caps for that title row and the liberary title
row"). Both were implemented, both have been on screen in front of him since. They are
recorded here as departures from the drawing, which they are, and not as defects.

### Conformance to *The Marquee* (design record 04), measured on device

The HTML records are recoverable — they are published artifacts, not lost to
compaction. The lobby and first-run spec is **The Marquee**,
`https://claude.ai/artifact/HKLVT4iYjZiKYS7vYWSLVA`; the Studio spec is
**Studio Final**, `https://claude.ai/artifact/EUSt9oqAwDdmkJepS51bZ5`. Read them
before changing any of these numbers.

| § | spec | built | |
|---|---|---|---|
| 01 | gutter 17px | 17–18dp | ✅ |
| 01 | marquee row 48px | 47dp | ✅ |
| 01 | press 140ms, `scale(.97)` | `Motion.PRESS` 140, `PRESS_SCALE` .97 | ✅ |
| 01 | marquee swap 260ms ease-out | 260ms, `EASE_OUT` | ✅ |
| 01 | hero cross-fade 260ms + `blur(2px)` | `Motion.swapPicture` | ✅ |
| 01 | hero **244/812 = 30%** | 33.8%, clamped 30–35% | ⚠️ see below |
| 01 | hero bar 3px | 3dp | ✅ |
| 01 | marquee fade 38px | 44dp | ✅ scaled with the 34sp word |
| 02 | recents card 100px, radius 12 | 100dp, 12dp | ✅ |
| 02 | recents thumb 60px | 84dp | ⚠️ see below |
| 02 | Joybot's one line, one action + dismiss | present | ✅ |
| 02 | floor: five words, glyph 13 / type 11.5 | present | ✅ |
| 03 | promise 14.5px, weight 800 | 14.5sp | ✅ |
| 03 | **last clause in the Studio accent** | was all white — now s_go | ✅ fixed |
| 03 | Support: filled s_raised, dim ink | was an outline, faint ink | ✅ fixed |
| 03 | slideshow 560ms ease-out | 560ms | ✅ |
| 03 | cycle 3000ms, text swap 260ms | 3000 / 260 | ✅ |
| 04 | nothing from `scale(0)`; no `ease-in` | verified | ✅ |
| 04 | 100×-a-day actions get 0ms | verified | ✅ |
| 04 | reduced motion keeps opacity, drops travel | verified | ✅ |

**The two departures, and why.** The bottom third of this build measures 157dp
SHORTER than the drawing's, because the New row became one compact chip row on
JoyRaptor's instruction ("center the text+icons on the parallelograms... close
the gap 30%") instead of the drawing's four stacked buttons. Held at exactly
30%, that 157dp became a hole above the floor — which is the very thing §02 was
written to remove: *"it was empty and it read as unfinished."* So 30% is now the
FLOOR and 35% the ceiling, and the first 24dp of what the hero does not take
goes to the recents thumbnail, where it is spent showing the user's own work.
102dp of breathing room remains above the floor; the drawing has about 80dp
there, so this is close but not equal.

**Earlier departures, already his call:** the marquee's live word is 34sp not
29px ("make the word studio larger"); LIBRARY and the room names are all caps
("it would look nicer all caps for that title row and the liberary title row");
the New row is sheared gradient chips, not grey buttons with coloured glyphs —
§05 of the drawing records that grey buttons were the anti-slop FIX for accent
footprint, so this one is worth a second look if he wants it.

### Conformance to *Studio Final* (design record 06), measured on device

| § | spec | built | |
|---|---|---|---|
| 02 | clip selection 1.5dp cyan ring + 3dp halo at 28% | as drawn | ✅ |
| 02 | playhead 1.5px | was 2dp — now 1.5 | ✅ fixed |
| 01/05 | tool row: 26dp right-edge fade | was absent | ✅ fixed |
| 01/05 | overflow button carries a live count | absent | ❌ |
| 01/01 | drawer scrim dark `rgba(0,0,0,.64)` | as drawn | ✅ |
| 01/04 | drawer section labels `#C4C4CE` | `s_label` | ✅ |
| 02 | tool cell 56dp, label capped 52dp, ellipsis | as drawn | ✅ |
| 02 | playhead colour `--live` | Capture gradient | ⚠️ his instruction |
| 02 | minimap 15dp | 11dp | ⚠️ his ruling, in pixels |
| **01/02** | **transport targets at 44dp** | **44.2–135.6 × 44.2dp**, device-verified | ✅ **fixed** |

**How the CRITICAL was actually fixed.** First, a correction: my earlier "21.5dp"
was wrong. The sandbox runs an override density of 315, so the factor is 1.969,
not 2.75 — the viewport is 549dp wide and the controls were exactly the 30dp the
layout declares. The defect was real (30 < 44) but the arithmetic was not.

The drawing's own fix could not be taken as written. It says to merge Select and
Ripple because "they were always exclusive" — in this build they are NOT. Select
cycles off / crossing / window; ripple cycles ripple / gap. Merging them would
delete a combination the editor supports.

So the width came from two other places:

1. **Link left the row**, which the drawing also asks for. Its own handler opens
   with a check for a selection and a toast saying "Select an item to link" — a
   dead knob most of the time. It now appears with a selection and leaves with it.
2. **The dead space between the controls.** An 8dp margin either side of a 30dp
   glyph is 8dp of row that answers to nobody. Every control's hit rectangle now
   runs the full height of the row and meets its neighbours at the midpoint, with
   the two on the ends reaching their container's edge. The margins went 8 → 14dp
   so the midpoint pitch lands on 44.

Measured after, on device: **44.2 to 109.7dp wide by 42.2dp tall**, from 30 × 30.

Verified by driving it, not by reading it: a tap at y=261 — below the button's own
bounds and inside the row — toggles it, and taps in the horizontal gap cycle
select through off → cyan → amber. Both were dead before. Play still plays:
00:00 → pause icon at 00:02 → play icon at 00:03.

One implementation note worth keeping: a View has exactly ONE TouchDelegate, so
the obvious loop that sets one per child silently keeps only the last and leaves
every other control as small as it was. There is a small composite class for this,
and the delegates are rebuilt on every layout pass rather than posted once — posted
once, it raced the first layout and measured a row of height zero, and it went
stale whenever link came or went.

### Measured state of the colour system, 2026-09-18 04:55

Measured on the screens the goal names, not repo-wide:

| | before | after |
|---|---|---|
| `LobbyFragment.java` | 62 literals | **4**, all bit-masks and a javadoc |
| `activity_faditor_editor.xml` | 34 literals | **0** |
| `OnboardingWelcomeFragment.java` | 26 literals | 14, all in the documented per-beat table |
| distinct literals, layouts + drawables | 281 | **233** |

The lobby's own ink ramp was four near-misses of four tokens (`#E4E4E7` vs
`#F2F2F5`, and so on). Aliased. Verified by sampling the rendered word STUDIO:
`#E4E4E7` → `#F2F2F5`.

XML gained ten VEIL tokens (`s_line_20`, `s_scrim_80`, `s_ink_33`, …) because
Java could say `Studio.alpha(TOKEN, a)` and XML could not — `#332C2C35` alone
appeared eleven times in one layout. `studio_tokens.xml` had also drifted from
`Studio.java` by six tokens, which is how the literals were getting back in.

### Accessibility, read off the device

| screen | unlabelled controls |
|---|---|
| Lobby | 6 of 28 → **0 of 28** |
| Welcome | 0 of 3 |
| Permissions | 0 of 5 |
| Before we start | 3 of 11 → **0 of 11** |

`uiautomator` cannot idle on the lobby because Joybot's hover never stops, which
is the animation that was asked for and his lifecycle is correct. Read the tree
with `animator_duration_scale 0`, then set it back to 1.

### Needs JoyRaptor — found by driving the app, not reading it

1. **The Support button pays the wrong person.** `support_url` was
   `https://ko-fi.com/fadedx` — the Ko-fi of the developer of FadCam — and it sits
   directly under "I built this between naps, looking after my three toddlers.
   It's how I support my family." The string is now EMPTY and the button is inert;
   put a Ko-fi / GitHub Sponsors / Patreon URL in `support_url` and it works again.
   The About screen's donate sheet is separate, still goes upstream, and now says
   FadCam rather than claiming to be the developer behind Joy Creator.
2. **Where updates come from.** `UpdateCheckService` pointed at
   `anonfaded/FadCam` — so "Check for Updates" and the Update badge offered a
   different developer's APK. Now `JoyRaptor/JoyCreator`; no releases there means
   no update offered, which is the right answer. Change ORG/FREE_REPO if releases
   are distributed elsewhere.
3. **The gold "Pro" crown** in the capture header is FadCam Pro's upsell, one
   screen after an intro promising "No ads. No subscription. Ever." Left alone —
   deleting a revenue path is not a design decision.
4. **Records has no search.** `RecordsFragment.searchView` is declared and never
   assigned. The lobby's magnifier therefore opens the library rather than
   searching it. Either build search or the icon should go.
5. **"FadShot"** labels the photo button in the capture room, and is also the
   filename prefix that the Records filter chips key off. Renaming the label alone
   makes them disagree; renaming the files moves them between chips. Same for the
   `FadCam_<ts>.mp4` fallback name.

### Open

- [ ] **Joybot's claymation.** `JoybotFilm` + `playThenFlatten` are ready and
      unwired. Needed from the owner: the two spritesheets, their column/row counts,
      and the fps they were shot at. Guessing any of the three would look wrong.
- [ ] **Blues in the editor** (`#2196F3` family) were left as-is rather than folded,
      because blue means VIDEO in the object table and folding them could silently
      change a meaning. Flagged, not guessed at.
- [ ] Deliberate departures from the mockups, each because a later instruction beat
      the earlier drawing — say the word to revert any: lane B lifted to `#17171C`
      (spec says `#0B0B0E`); the New row is sheared gradient chips (spec says grey
      buttons with coloured glyphs); object tapes are gradients (predates that
      instruction).
