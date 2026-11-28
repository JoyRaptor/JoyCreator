# Avatar Studio: pilot v31 vs the phone room (parity list)

Made 11-27 by reading the pilot (`tools/avatarlab/`) against the phone room (`app/src/main/java/cc/joycreator/avatar/`,
`strings_avatar_room.xml`). Each gap was grepped on both sides. Sizes: S a label or one control, M a section, L a whole feature.
Status column is kept current by the room session; ✅ = built (not tried on a phone unless said), ⬜ = open, ➖ = decided not to.
Skipped on purpose: Landmarks (replaces "Match two angles"), face-capture internals, downloads, scenes (slice 3), Export (top bar).

| # | Status | Area | Pilot label (file) | Phone | Size |
|---|---|---|---|---|---|
| 1 | 🟡 | Set up, eyes | Eyes card: Looking, Blink by itself / About every / Eyes lead the head / Blink now / Hold the blink / Centre the blink (`al-cards.js`) | Looking, Blink by itself / About every / Eyes lead / Blink now built 11-27; Hold the blink, Centre the blink owed | L |
| 2 | ⬜ | Set up, eyelids | Add eyelids, Invisible / Drawn, tilt/cheek reach, Blink by closing the lids, Remove | none | M |
| 3 | 🟡 | Set up, mouth | How it talks: Hinge / Squash / Swap, Open / Shut, Shuts to, Try: talk | hinge/squash/swap only, drawn open or shut, Swing, Shuts to, Try: talk, Same on every angle built and seen on the emulator 11-28; mouth shapes (rows 4) owed | M |
| 4 | ⬜ | Set up, mouth shapes | Its shapes, This is the sound, Try a sound, Other mouths | none | M |
| 5 | ✅ | Set up, brows/jaw/nose | Lift, Drops, The face stretches with it, Depth | none | M |
| 6 | ⬜ | Set up, head angles | Its angles pad, Move the dots, Mirror, Copy/Rename/Remove angle, Tip, Size | only Landmarks | L |
| 7 | ⬜ | Set up, arms/legs | numbered drawings, arrows, Resting pose, Flip elbow/knee | tile tap looks only | M |
| 8 | ⬜ | Set up, limb actions | Copy this arm, Move the whole arm, Remove, In its hand / On its foot | none | M |
| 9 | ⬜ | Arrange, part and body | In front / Behind / Half and half, Comes in front from | stack + Very front/back; Changes over in stances only | M |
| 10 | ⬜ | Motion, limb bending | At the elbow / Like a hose, Stretch, Wobble, Thickness, Bend to reach, Cross-fade, hand rests | none | L |
| 11 | ⬜ | Motion, limb walking | arms can become feet, This leg walks, Step timing | none (Walker supports it) | M |
| 12 | ⬜ | Motion, Showing now | Showing now strip, Flip elbow, Mend its pins | none | M |
| 13 | ✅ | Motion, ground and walking | Drops to all fours, Jumps, Which limbs walk, Stride, Step height, Rise and fall, Heel to toe, Lean, Arm swing, Falls back, Smaller far away, Turns to face, Ground, Floats (`al-ground.js`) | none | L |
| 14 | ⬜ | Set up, colour sets | From a picture, Add by hand, See it, Pick on the picture, Becomes/Reaches/More colour/Lighter, How it arrives (`al-tone.js`) | none (shader and Inputs support them) | L |
| 15 | ⬜ | Perform, walking | Walking / Floating label and the walk settings | Walk with your face drawer only | M |
| 16 | 🟡 | Set up, shapes | Add pins around it, How far, Bouncy, Add a shape, Handles in Perform | Reshape with pins only (built 11-27); no named shapes | L |
| 17 | ✅ | Motion, follows | Follows: signal to property, Bouncy, Back and forth, How much | none | L |
| 18 | ⬜ | Arrange, masks | Show only inside, Hide where | none | M |
| 19 | ⬜ | Set up, carried / looks | Carried by, Its looks, Make this its usual look, Change its picture | none | M |
| 20 | ⬜ | Motion, loop / stream | Loop speed; Stream (How many, Each lasts, Drifts, Fades out, Sways) | none | M |
| 21 | ⬜ | Set up, round shape | per layer: Turns, Nods, lies flat, stands off, on the back | round head sliders only | M |
| 22 | ⬜ | Set up, what it is | What is this?, Rename, Remove, Pin it again | Layers list hides/shows only | M |
| 23 | ⬜ | Arrange, size and turn | Fit it by hand, readout, Size/Turn/Mirror sliders | heading + hint; Put it back always shown | S |
| 24 | ⬜ | Whole stage | labelled body map, path tabs | chips row, one path line (spec A2) | L |
| 25 | ⬜ | Arrange, stance row | picture per stance, A new stance sheet | chips in the card | M |
| 26 | ⬜ | Set up, pins | named pins (Neck, Shoulder, Elbow, Hand, Hips, ...) | unnamed dots | M |
| 27 | ⬜ | Hint line | per-stage coach lines | one line | M |
| 28 | ⬜ | Stage buttons | Hold this pose, Held pose, Back to Set up | not there | S |
| 29 | ⬜ | Stage buttons | Onion skin | not there (spec) | S |
| 30 | ⬜ | Motion, swing | Make it move by itself, Bendy/Stiff, Hangs down, Hangs from | no way to add a swing | M |
| 31 | ✅ | Motion, body | Breathing toggle, How deep, Never quite still | Breaths a minute only | S |
| 32 | ✅ | Set up, facing | Drawn facing Left/Front/Right, See it flipped, Turn around by itself | tick only if not front | S |
| 33 | ⬜ | Set up, body angles | Give the body its own angles, Follows the head | none | M |
| 34 | ⬜ | Perform | Voice file | none (spec A7) | S |
| 35 | 🟡 | Perform | Let go of everything (built 11-27); Show-off loop; voice meter | Let go only | S each |
| 36 | ⬜ | Perform | More to turn pads; Turn right round | one head pad | M |
| 37 | ✅ | Perform words | Mood (eyelids and brows); Weather with readout; hover texts | Mood, Breeze, shorter hovers | S |
| 38 | ⬜ | Perform take strip | lane bars, writing: head, face; recording… | time only | S |
| 39 | ⬜ | Menu | Create new avatar card, open now/unsaved/built in, long-press Rename/Copy/Remove | names only | M |
| 40 | 🟡 | Menu | Add pictures, What can this do?, door sentences, One long card per part | door sentences, one prefix and What can this do? built 11-27; Add pictures to the open character and One long card toggle owed | M |

## Order of work (value to a user, then size)
Small first (37, 23, 31, 32, 35, 28), then 13 (Ground and walking: the walker is built, its settings are not), 40 + 39 (the menu), 1 to 5 (the
face cards), 14 (colour sets), 24 (body map), the rest. Each lands with its own check and a screenshot from `avatar-emu`.
