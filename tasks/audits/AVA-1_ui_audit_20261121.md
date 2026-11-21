# Avatar Studio pilot: interface and organisation audit (2026-11-21)

Asked for by the owner before the phone port. Run by an independent agent on the built pilot (page version 8/9),
read-only, at 360 x 740 (phone portrait), 740 x 360 (phone landscape), 800 x 1280 and 1280 x 800 (tablet).
Job card: [AVA-1](../jobs/AVA-1_avatar_studio_redesign.md). Nothing in this file is built yet unless the card says so.

"Seen" = checked on screen or by measurement. "Code" = read from source. The lead re-checked findings 3, 7 and 32
in the source (fixed 44 px inset in `app.fitTo`; no layout media queries in `index.html`; nine raw error toasts).

## Verdict

1. The bones are right: stage plus one card per part, plain words nearly everywhere, a hover label on every control
   swept, solid-cyan ON states, visible way-out chips for stance and colour-set modes.
2. On the 360 x 740 phone the bottom panel is the problem. Its header takes 112-138 px of a 326 px panel, leaving
   187-213 px of card, so cards run 3.5 to 11 screens of scrolling.
3. The stage is tall enough (368 px), but the hint bubble and the bottom chip bar sit on the picture and the auto-fit
   ignores them. Feet, the Ground pin and the box's turn handle end up behind chips, worst in a stance.
4. There is no tablet layout. The only switch is "wider than tall puts the panel on the right". Tablet landscape is
   acceptable; tablet portrait is a stretched phone.
5. Organisation needs one pass: no collapsible sections, "in front / behind" lives in four places, limb cards carry
   arm wording onto legs, and "On two legs / On four legs" collides with the stance "All fours".

## Findings, most harmful first

| # | Sizes | Where | What is wrong | Fix | Effort |
|---|---|---|---|---|---|
| 1 | Phone | Build, panel header (body map, title, description, Parts / Layers / On two legs) | Seen. 112-138 px and never scrolls away; 41% of the landscape panel is header. | One 44 px row: small body-map button, part name, Parts/Layers. Description becomes the card's first lines. | Medium |
| 2 | Phone portrait | Head card, first screen | Seen (the owner's screenshot). "Copy this angle / Rename / Remove" is cut by the bottom edge; the green "Move, turn, resize on the picture" is fully hidden. | Fixed by 1; also put the green button beside the pad. | Small |
| 3 | All | Stage auto-fit against the hint bubble and the "Try it / Pick a piece" bar | Seen and code. `fitTo` reserves a fixed 44 px at the top and nothing at the bottom. In "All fours" at 360 wide the legs and feet are behind the chips; Toe and Heel pins and the Ground pin too. | Fit into the rectangle left between the real bubble and the real bar. | Small |
| 4 | Phone | Transform box turn handle | Seen. 10 px above the bar when standing, under "Pick a piece" in a stance. | Same inset fix; fit to the box plus its handle. | Small |
| 5 | Phone, tablet portrait | Body card | Seen. 1,337 px tall (6.3 phone screens). Stances and Colour sets start about 1,000 px down, below 14 walking controls. With "Coral" open: 2,365 px. | Collapsible sections; the colour-set editor becomes a sub-card with a back chip. | Medium |
| 6 | All | Changing card | Seen three times. The new card opens at the old card's scroll position. | Scroll to top whenever the selected part changes. | Small |
| 7 | Tablet | Whole layout | Seen and code. No tablet rules. Portrait: 600 px sliders, 74 x 92 body map, empty areas. Landscape: 420 px panel beside an 860 px stage. | See "Proposed layout". | Large |
| 8 | Phone most | Body map zones | Seen and code. Leg tap areas about 8 x 21 px, arms 12 x 19, Extras 13 px square. This is the main navigation. | Tapping the small map opens a large labelled one (it exists on the Start screen), or a chip row of parts with thumbnails. | Medium |
| 9 | All | Save dot after "Meet Topps" or opening a character | Seen. Says "Saved in this browser" but nothing is stored until the first change; a reload brought back the previous character. | Store as soon as a character is opened. | Small |
| 10 | All | Menu, New character | Seen. "This one is put away. Save it first if you want to keep it." Cancel / Start new only; not undoable. | "Save, then start new", or a recents list. | Small-medium |
| 11 | All | "In front / behind" | Seen and code. Four controls for one idea (limb drawing; part ▲▼; face part at this angle; in a stance), with a "Depth" slider between them that means something else. | One section on every card, same place, with "for: always / this angle / this stance". Rename Depth to "Slides when the head turns". | Medium |
| 12 | All | In a stance, a limb card opened from the body map | Seen. No stance controls at all; they appear only on the card reached by "Pick a piece". | Show the stance fit and order rows at the top of any card while a stance chip is on. | Medium |
| 13 | All | "Pick a piece" on an arm | Seen. Title is the raw layer name ("Left Arm_WarpTrigger"); green "What is this?" although the role is known. | Title = role, file name small beneath; quieten "What is this?". | Small |
| 14 | Phone portrait | Box on the arm in "All fours" | Seen. Box rotated and far larger than the visible arm; three of four corners off the stage. | Fit the view to the box when it turns on; cap the box to the visible art. | Medium |
| 15 | Phone portrait | Body, Walking, "On a ground line / Walking anywhere" | Seen. Ends at x 369 in a 360 px panel. | "Ground line / Anywhere", or wrap. | Small |
| 16 | All | Slider label (104 px) and value (44 px) columns | Seen. "fully in", "not at all", "heel first" wrap or clip; "Tip the whole an…", "Wrist above the …" cut even on tablet. | Size columns to content. | Small |
| 17 | Phone | "On two legs" chip in the header | Seen. Cut off in phone landscape; a setting dressed as a tab; collides with "All fours". | Move into the big body-map picker. | Small |
| 18 | All | Leg card wording | Seen. "shoulder", "Elbow softness", "when the arm is left alone" on a leg. | Swap the words by limb kind. | Small |
| 19 | All | Arm and Leg section headings | Seen. "How it moves" holds one button; bend controls sit under "In front of the body". | Regroup (below). | Small |
| 20 | All | "Reshape with pins" | Seen. Entering it records an empty undo step; no stage chip; hint does not change. | No undo step for entering a mode; add a "Reshaping: X ×" chip. | Small |
| 21 | Phone | Head pad, bridge diamonds | Code and seen. 11 px tap radius, 13 px from the dots on a 132 px pad. | Show only the selected dot's neighbours, further out; or a row under the pad. | Medium |
| 22 | Phone | Layers, folder arrows and eyes | Seen. Arrow 16 x 14 px, eye 26 x 26. | Bigger tap areas, same row height. | Small |
| 23 | Phone portrait | Top bar name | Seen. Zero width at 360. | Drop it at this width, show it in the menu header. | Small |
| 24 | All | Extras card, "Buttons (in Perform)" | Seen. Perform buttons are managed under Extras; one is called "Layer 7". | A Buttons section in Perform; ask for a name when a layer becomes a button. | Medium |
| 25 | Phone portrait | Perform, first screen | Seen. Pad and Hold to talk fill the fold; triggers start at the bottom edge; timeline hint cut. | Buttons row directly under the transport. | Small |
| 26 | All | Hint bubble | Seen. Cannot be dismissed, up to 78 px. | Tap to fold to one line; fold after the first drag. | Small |
| 27 | All | Stage tool column | Seen. Icon-only, labels on hover only; the "take back the last pass" arrow is the Undo drawing. | Long-press label on the phone; its own icon. | Small |
| 28 | All | Four "move" controls | Seen. Stage "Move art", the box, "Move the whole arm", "Move the dots". | Fold the first and third into the box. | Medium |
| 29 | All | Stance chip row | Seen. Actions and switches look the same; a second "Pick a piece". | Switches as toggles on their own lines; drop the duplicate. | Small |
| 30 | All | Colour set, "Becomes" slider | Seen. A hue slider on a grey track. | Paint the track as the colour strip. | Small |
| 31 | All | Menu | Seen. Thirteen rows; three "Meet …" rows share one icon; ".psd", ".character.json". | Character thumbnails; plain words. | Small |
| 32 | All | Error toasts | Code. Nine places show the raw error text. | A fixed friendly sentence; details to the log. | Small |
| 33 | All | "Try it" against Perform | Seen. A second, smaller Perform while the top bar still says Build. | Title it "Trying it" and reuse Perform's layout. | Small |
| 34 | All | Perform switch colour | Seen. ON Perform is solid pink, as is Recording. | Owner's call; if kept, pink for recording only. | Small |

Checked and fine: a hover label on every control in Head, Body, Arm, Leg, Tail, Extras, stance, colour set and Perform;
ON chips, toggles and thumbnails solid cyan; throwing a take away asks first; previews on Layers, the drawings fan,
face-part chips and mouth shapes; the box's corner handles have a 20 px tap radius; stance and colour-set modes show a
cyan "×" chip on the stage.

## Proposed layout

- **Phone portrait (360 x 740):** top bar without the name; stage keeps 368 px; the panel header is one 44 px row
  (body-map button that opens the big labelled map as a sheet, part name with thumbnail, Parts / Layers); the card gets
  about 280 px; collapsible sections, one open at a time; all mode chips in one row above the panel edge, clear of the
  fit; hint bubble folds to one line.
- **Phone landscape (740 x 360):** panel stays on the right (340 px) with the same 44 px header; "Try it / Pick a
  piece" moves to a vertical strip on the stage's left edge; the hint is a one-line strip.
- **Tablet portrait (800 x 1280):** stage on top (about 60%); the panel in two columns: a rail (about 200 px) with the
  body map at double size, labelled, and Layers under it; the card on the right; sliders capped at about 320 px so two
  fit side by side. Perform: pad and talk on the left; buttons, mood and walking on the right; no scrolling.
- **Tablet landscape (1280 x 800):** left rail (about 220 px) with the labelled body map, Layers and the stance and
  colour-set lists, always visible; stage in the middle; card on the right (420-560 px, sections in two columns).

## Proposed re-organisation

**Body card** (collapsible, a one-line summary when closed): 1 Stances (a stance opens as a sub-card with a back chip)
· 2 Colour sets (same) · 3 Breathing · 4 Which way it faces (with "Its own turn drawings") · 5 Walking (main toggle,
Ground line / Anywhere, Steps / Hops; eight tuning sliders under "Fine tuning", the same block Perform shows) ·
6 Limbs.

**Arm and Leg cards:** 1 Drawings · 2 Pins (with Move the whole limb) · 3 In front or behind (the one shared control)
· 4 How it bends · 5 Walking · 6 In its hand / On its foot · 7 Copy, Remove.

**Any other part:** Shown / Rename / Remove → Fit it by hand → In front or behind → Swinging → Follows → Shapes →
one closed "More" (Carried by, Stream, Round shape, Masks, Keeps its own colours).

## Controls that need a picture

| Control | Picture |
|---|---|
| In front / Behind / Half and half; ▲ ▼; the five stance order chips | One family of overlapping tiles: front, back, split, one step, top of stack, bottom of stack |
| On two legs / On four legs | Upright figure / four-legged side view |
| Stance chips ("All fours"), in Build and Perform | The character in that stance |
| Colour-set chips ("Coral") | Two or three swatch dots from the set |
| Perform buttons ("Layer 7", "smile") | The drawing they show |
| Mood chips | A pair of eyes with the lids in that mood |
| Drawn facing: Left / Front / Right | A head in profile left, front, right |
| Smooth / Instant / A card | Curved arrow / a step / a card on edge |
| On a ground line / Walking anywhere | Figure on a line / figure with four-way arrows |
| Steps / Hops; Jumps / Hangs | Footprints / an arc; leaving the ground / dangling |
| At the elbow / Like a hose | Angled two-bone line / smooth curve |
| Hinge / Squash / Swap only | Jaw on a hinge / squashed oval / two cards |
| Bendy / Stiff | Wavy line / straight rod on a pivot |
| Flip elbow, Flip knee | Bent limb with a mirrored ghost |
| Settles with a bounce / Goes down by itself | A spring / an arrow down onto a line |
| Menu "Meet …" rows | The character's own thumbnail |
| "Take back the last pass" | Its own icon, not the Undo arrow |
| Extras square on the body map | A label or a "+ more" glyph |

## Not checked

- No real phone or tablet and no real fingers: touch sizes are measured, not felt; no long-press or many-finger checks.
- Motion was not judged.
- Not opened: Add a stance, the body's own angles, From a picture, the bridge tool, Send to Studio, Sprite sheet,
  the Rename sheet with a keyboard, the hold-Undo history, every file-opening door, mic, voice file, face camera.
- Brows, Nose, "Hair, ears, frill" and the four-legged map's cards were not walked.
- Tablet portrait and phone landscape were checked for layout and card length, not control by control.
