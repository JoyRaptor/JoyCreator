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
| **01/02** | **six transport targets at 44dp** | **eight, all 21.5dp** | ❌ **CRITICAL** |

**The one that is still open, with the arithmetic.** Measured on device, every
control in the transport row is **21.5 × 21.5dp**; the play button is 28.7. The
minimum is 44. Studio Final rated this CRITICAL and did the sum: eight controls
plus two time labels at 44dp needs 444dp and the row has 375, *"which is why a
mis-tap on the play button once got logged as a critical playback regression."*

A TouchDelegate cannot rescue it — the gaps between controls are 6dp, so growing
each one only reaches ~27dp before neighbours collide. The drawing's fix is
structural and is the only one that fits: **collapse Select and Ripple into one
cycling mode button** (they were always exclusive) and **move Relink out of the
row**, appearing instead as an amber ring on the broken clip. That leaves six
targets plus two labels = 356dp, inside the 375 available.

Not attempted here because it changes editor BEHAVIOUR — merging two mode
controls and hiding a third — and doing that unverified at the end of a long
session is how undo or play quietly stops working. It is the largest remaining
gap against any of the three records.

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
