# Avatar Studio: every control and its hover label (harvested from the pilot)

Made by `node tasks/avatar/harvest-hover.mjs` from `tools/avatarlab/al-*.js`. The phone room uses these words as they are
(rule: every button gets a hover label). Part A of [the phone spec](AVA_PHONE_SPEC.md) says where each control sits.
A slice moves its rows into `strings_avatar.xml` (control words) and `strings_hover_avatar.xml` (hover labels).

## `al-bridge.js`

| Control | Kind | Hover label |
|---|---|---|
| See-through | slider | How much of  |
| It is in the same place | chip | This landmark sits at the same spot on both drawings |
| They meet | slider | Where the two drawings meet at the moment of the swap. Slide toward the one that should bend less. |

## `al-cards.js`

| Control | Kind | Hover label |
|---|---|---|
| Parts | chip | Work part by part, from the body map |
| Layers | chip | Every layer of the file, as a list |
| Done trying | chip | Back to the still pose, where pins can be moved |
| Try it | chip | Bring the character to life here: drag the head, the hands, the body |
| Move it | chip | A box on the picture for this part: drag inside it to move, a corner to resize, the round handle to turn |
| Put it back | chip | Forget the move, the turn and the size: back to how it was drawn |
| Move it | chip | On: drag this limb on the picture to slide it, pins and all, to where it joins the body |
| Colours:  | chip | Showing this colour set. Tap to go back to its own colours. |
| Depth | slider | How far the nose, eyes and frill slide between two angles. More depth feels rounder. |
| Cross-fade | slider | How softly one angle gives way to the next |
| Tilt | slider | How far the head can tip from the neck pin |
| Turns | slider | How far round the head goes when it looks fully left or right |
| Nods | slider | How far it tips when it looks fully up or down |
| Guess the parts again | chip | Fit the ball to the face drawing again and decide again which parts are painted on and which stick out |
| Copy this angle | chip | Make a copy of this drawing as a new dot: for the same head tipped up or down. Drag the new dot where it should show, then turn the copy. |
| Rename | chip | Give this angle another name |
| Remove | chip | Take this angle away. Undo brings it back. |
| Move, turn, resize on the picture | chip | A box round this whole angle: drag inside it to move every layer together, a corner to resize, the round handle below to turn |
| Put it back | chip | Forget the move, the turn and the size of this angle |
| Tip the whole angle | slider | Turns every layer of this angle together, from the neck. Between two dots the tip blends. |
| Size of the whole angle | slider | Makes every layer of this angle bigger or smaller together |
| Remove the  | chip | Take away the angles that were made by mirroring |
| Mirror the other side | chip | Make the missing side by flipping the drawings you have |
| \u25b2 | chip | One layer forward |
| \u25bc | chip | One layer back |
| Head | chip | Back to the whole head |
| Pupils move | slider | How far a pupil slides when the eyes look to the side |
| Hold the blink | chip | Keep the eyes shut, to see where the blink drawing sits |
| Centre the blink on the eye | chip | At this angle the blink drawing sits  |
| Try: relaxed | slider | The upper lid comes down |
| Try: angry / sad | slider | The upper lid tilts |
| Try: cheery | slider | The lower lid pushes up, as a cheek does in a smile |
| Tilt reach | slider | How far the lid can tilt |
| Cheek reach | slider | How far the lower lid can rise |
| Show the lids | chip | See the invisible lids as shapes, to check where they sit |
| Depth | slider | Front slides with the turn, behind slides against it |
| Same on every angle | chip | Copy these settings to the same-named layer at every head angle |
| Try: talk | slider | Slide to see the mouth open and shut |
| Swing | slider | How far it turns to shut. Minus swings the other way. |
| Guess again | chip | Work the hinge out from the art again |
| Same on every angle | chip | Copy this way of talking to the mouth at every head angle (each keeps its own hinge pin) |
| Flip  | chip | Bend the drawing that is showing right now the other way |
| Mend its pins | chip | Go to Set up with this very drawing open |
| ‹ | chip | Previous drawing |
| › | chip | Next drawing |
| Resting pose | chip | Use this drawing when the arm is left alone |
| Flip  | chip | Bend this drawing the other way |
| Guess pins again | chip | Work out every pin of this arm from the art again |
| Try this drawing | chip | Bring it to life with ONLY this drawing, so you can drag the  |
| Changes over at | slider | Where along it the drawing goes from behind the body to in front |
| Stretch | slider | How far past its drawn length the arm may stretch to reach |
| When the hand is close | slider | A hose keeps its length and bows out. A beam of light just gets shorter. |
| Always arcs | slider | A curve it keeps even at full reach |
| Wobble | slider | A wave that runs along it: a wiggly noodle, a crackling arc |
| Thickness | slider | Make the whole limb fatter or thinner without redrawing it |
| Elbow softness | slider | A soft elbow curves like a noodle; a hard one creases |
| Step timing | slider | Its place in the round of steps. Feet with the same timing step together; the others take turns in order. Two legs: 0 and 50. Four: 0, 25, 50, 75. A long row: spread them out and they move as a wave. |
| Wrist above the ground | slider | How high the wrist pin stays when the hand is planted, so the palm or knuckles rest on the ground and do not sink into it |
| Move the whole  | chip | On: drag this limb on the picture to slide it, pins and all, to where it joins the body |
| Remove | chip | Take this limb away. Undo brings it back. |
| Give the body its own angles | chip | The drawing you have becomes the first angle. Then add more, or copy this one and reshape it. |
| Add a drawing… | chip | Choose a picture of the body from another angle. It gets its own dot on the pad. |
| Follows the head | slider | How far the body turns when the head turns all the way. Less than 100%: the head leads and the body follows a little. |
| See it flipped | chip | Show the mirrored side while building |
| It takes | slider | How long the turn lasts, in frames of film (24 a second) |
| It tucks in | slider | How far the body, tail and arms pull in toward the middle before they swap sides. 0 swaps at full width. |
| Ground and walking | chip | Everything about walking is on one card: the bar under the feet on the body map |
| Bendy | chip | Hair, a braid, a feather, a tail, a ribbon: it bends along its length |
| Stiff | chip | A chain link, an earring, a charm: it never bends, it swings and rattles |
| Springiness | slider | Low is floppy, high snaps back |
| Settle | slider | How quickly it comes to rest. Low keeps swinging (a rattling chain). |
| Follow-through | slider | 0 rides rigidly, 1 is left behind and catches up, more is flung |
| Joints | slider | More joints make a longer, lazier wave down its length |
| Rattle room | slider | How far it may swing before it knocks and bounces back |
| Breeze | slider | How much the wind moves it |
| Flutter | slider | A fast shiver in the wind: feathers, leaves, ribbons |
| Idle wag | slider | A small movement of its own when nothing else is happening |
| Show only inside… | chip | Pick the shape this must stay inside |
| Hide where… | chip | Pick the shape that hides this |
| Back to the middle | chip | Float back to where it started |
| Lean | slider | How far it tips into the move |
| Keeps up | slider | How quickly it catches up with your finger |
| Ground | chip | ON: there is a ground line where it stands now; pull it up to jump. OFF: it is always on the ground, so up and down the stage is walking too (stairs, a hill). |
| Walk settings | chip | Stride, body bob, heel and toe |
| Back to the middle | chip | Walk back to where it started |
| Stride | slider | How far apart the footfalls are |
| Step height | slider | How high a foot lifts |
| Rise and fall | slider | The wave the body and head ride with every step: low as the weight lands on a foot, high as the other leg passes. From flat to cartoony. |
| Step | slider | Where the weight lands: reaching out with the heel, flat-footed, or up on the toes |
| Heel to toe | slider | How much the foot rolls. None suits a ball or a peg for a foot. |
| Lean | slider | How far it leans into the walk |
| Arm swing | slider | A hand nobody is holding swings against the legs |
| Smaller far away | slider | How much it shrinks as it walks up the stage |
| Add pins around it | chip | Eight pins round its edge and one in the middle. Drag them to where the shape should bend from. |
| How far | slider | How far this shape goes when what drives it is fully on. Below zero it goes the other way. |
| Bouncy | chip | It overshoots and settles, like something soft |
| Start again | chip | Put every pin back where it rests, for this shape only |
| Add a shape | chip | A new pose of this part, made by dragging its pins |
| Add a pin | chip | One more pin, in the middle: drag it where you need it |
| Handles in Perform | chip | On: each pin can be dragged while performing. Off: the pins only shape the part. |
| Match to  | chip | Mark landmarks on this piece only, between “ |
| Turns | slider | How far round it goes when the head looks fully left or right. A body turns less than a head. |
| Bouncy | chip | It overshoots and settles, like something soft, instead of following exactly |
| Back and forth | chip | It rocks to and fro in time instead of holding still: a hover, a bob, a wag |
| Add | chip | Add this link |
| Size | slider | Make this part bigger or smaller without redrawing it |
| Mirror | chip | Flip this part left to right (use one glove for both hands) |
| Turns with it | slider | A hand turns with its arm. A lantern on a tail stays upright. |
| Loose wrist | slider | It lags behind the arm and overshoots when the arm stops, the way a real wrist does |
| Always in front | chip | Off: it goes behind the body when its arm does |
| Mirror | chip | Flip this one drawing left to right (a thumb on the wrong side) |
| A button for each look | chip | Every drawing except the usual one gets a button in Perform. Drawings with the same name on another part share the button. |
| Rename | chip | Change the name of this look, and of its button |
| Change its picture | chip | Put a different picture in this look. Its place, its button and its settings are kept. |
| Mirror | chip | Flip this look left to right |
| Remove | chip | Take this look out (Undo brings it back) |
| Add looks | chip | Add more pictures as looks of this part |
| Blend into another look… | chip | Mark the same spots on this look and another, and the change between them becomes one shape melting into the next instead of a swap |
| Stands off the surface | slider | A nose stands off the face, so it moves further and shows in profile |
| Keeps its shape | slider | A sticker squashes and leans exactly as the surface does. A cartoon eye stays upright and only narrows a little. |
| What is this? | chip | Say which part of the body this is |
| Remove | chip | Take this out of the character (Undo brings it back) |
| Open “ | chip | Rig this layer: say what it is, make it follow something |

## `al-export.js`

| Control | Kind | Hover label |
|---|---|---|
| The set | toggle | Bake the take to a sprite sheet for Sprite Lab, VisLab and Studio |

## `al-face-walk.js`

| Control | Kind | Hover label |
|---|---|---|
| Walk with your face | chip | Walk with your face: lean to a side to walk that way, come closer to walk toward the camera. And Up close. |

## `al-facepins.js`

| Control | Kind | Hover label |
|---|---|---|
| Done | chip | Close Face pins and go back to where you were |
| All pins | slider | Every face pin at once: lower to calm them all, higher to make them all pull more. Each pin keeps its own strength on top |
| Plain map | chip | Drop the picture: the map of your neutral face |
| All points | chip | Show every point the camera follows (478), not only the named ones |
| Fit | chip | Show your whole face again |
| Turn on the face camera | chip | Start the face camera (the picture is never kept) |
| Make one piece | chip | Move these pins onto the whole head, where they are now (one Undo puts them back) |
| Remove | chip | Take this face pin off (one Undo brings it back) |
| Strength | slider | How far this spot moves for how far your face moves |

## `al-fit.js`

| Control | Kind | Hover label |
|---|---|---|
| As when standing | chip | The same order as when it stands |
| Comes in front from | slider | Where along it the drawing goes from behind to in front. Put it where the limb is clear of  |
| Changes over at | slider | How far into the stance the order changes. Pick the moment the two parts are not touching and it cannot be seen. |
| Move, turn, resize | chip | A box on the picture: drag inside it to move, a corner to resize, the round handle below to turn |
| Reshape with pins | chip | Tap the part to drop pins, then drag one: the part stretches from there and the other pins hold the rest still |
| Start again | chip | Every pin back to where it was dropped |

## `al-ground.js`

| Control | Kind | Hover label |
|---|---|---|
| Drops to all fours | slider | How far down the body has to be pushed before it goes onto all fours instead of walking upright |
| Jumps | slider | How far up the body has to be pulled before the feet leave the ground |
| Stands this high | slider | How far above the ground line the wrist pin stays when the hand is planted. 0 puts it on the line; raise it if the hand sinks into the ground. |
| Step timing | slider | Its place in the round of steps. Feet with the same timing step together; the others take turns in order. Two legs: 0 and 50. Four: 0, 25, 50, 75. |
| Work on the all-fours stance | chip | Puts it down on all fours and opens the stance: how far it tips, where each part sits |
| Make an all-fours stance | chip | Tips the body forward about the hips until the hands reach the ground |

## `al-intake.js`

| Control | Kind | Hover label |
|---|---|---|
| All | chip | Choose every cell, in reading order |
| Back | chip | Take back the last step |
| One long card per part | toggle | Open a character file |

## `al-perform.js`

| Control | Kind | Hover label |
|---|---|---|
| Blink | chip | Tap to blink. Hold to keep the eyes shut. (key B) |
| Breeze | slider | Wind. Hair, feathers, tails and chains with Breeze turned on lean and wave in it. |
| Mic | chip | Lip-sync to your voice |
| Voice file | chip | Play a recording and lip-sync to it |
| Re-centre | chip | Take your face now as neutral: look at the screen, relaxed |
| Swap sides | chip | If the character turns the wrong way, swap left and right |
| Check directions | chip | Five short moves that settle which way is which on this device |
| Let go of everything | chip | Head to the middle, hands to rest |
| Turn right round | slider | Spin the whole character round: to its side, to its back |
| Show-off loop | chip | The character performs by itself: looks around, waves, talks. Anything you grab is yours. |
| Turns around | chip | Flip the whole character when the head turns the other way |
| Export | chip | Export: the Joy Creator file (this character and its take, for Studio and the rest of Joy Creator), one frame as a picture, or the take as a video with its voice |
| Sprite sheet | chip | Bake the take to a sprite sheet for Sprite Lab, VisLab and Studio |

## `al-pose.js`

| Control | Kind | Hover label |
|---|---|---|
| Shoulder sensitivity | slider | Higher: a small shrug lifts the character all the way. Lower: it takes a big one. Kept on this device |

## `al-scene-ui.js`

| Control | Kind | Hover label |
|---|---|---|
| Open  | chip | Put  |
| Another one | chip | One more of this, beside it |
| Remove | chip | Take it out of the scene (Undo brings it back) |
| Character | chip | Someone from your shelf, or one that comes with the room |
| Object | chip | A picture that stands on the floor: a tree, a chair, a sign. Pick several pictures and they play as a loop. |
| Backdrop | chip | A picture behind everything. It fills the stage. |
| Foreground | chip | A picture in front of everything: leaves at the edge, a window frame |
| Name it | chip | Give this scene a name |
| Delete the scene | chip | Throw this scene away. The characters in it stay on your shelf. You are back in the empty scene. |
| Sways in the wind | toggle | How fast its  |
| Show the frame | chip | Turn the frame on, then try the shots |

## `al-stack.js`

| Control | Kind | Hover label |
|---|---|---|
| Back one | chip | One step further back: behind the part on its left |
| Forward one | chip | One step further forward: in front of the part on its right |
| Very back | chip | Behind everything |
| Very front | chip | In front of everything |
| Comes in front from | slider | Where along it the drawing goes from behind to in front. Put it where the limb is clear of the other part. |
| Changes over at | slider | How far into the stance the order changes. Pick the moment the two parts are not touching and it cannot be seen. |

## `al-stages.js`

| Control | Kind | Hover label |
|---|---|---|
| Back to  | chip | Back to where you were, exactly as you left it |
| Hold this pose | chip | Freeze it exactly as it is now and go to Set up, to mend a pin or a joint while it is bent this way |
| Held pose | chip | The character is frozen in the pose you held. Tap to let it go back to standing. |

## `al-stance.js`

| Control | Kind | Hover label |
|---|---|---|
| Make an all-fours stance | chip | Tips the body forward about the hips until the hands reach the ground, keeps the legs, tail and head where they belong, and lets the hands walk as front feet |
| Add a stance | chip | A new whole-body pose with its own button |
| See it | slider | Slide to watch the character go into this stance and out again |
| How far it tips | slider | How far the body leans forward about the hips |
| Hips come down | slider | How far the hips sink while it is on all fours. The feet stay planted, so the back legs fold. Watch the back legs as you slide. |
| It takes | slider | How long it takes to get into and out of this stance |
| Pick a piece | chip | Then tap any part on the picture to move, turn or resize it for this stance |
| Settles with a bounce | chip | It goes a little too far and comes back, like something heavy arriving |
| Goes down by itself | chip | On: pushed low it drops onto all fours instead of squatting deeper, and pulled up it stands again. Off: only its button does it. |
| Remove | chip | Take this stance away. Undo brings it back. |

## `al-tone.js`

| Control | Kind | Hover label |
|---|---|---|
| From a picture… | chip | Choose a picture of the character in other colours. Its main colours are found and matched to this art, biggest to biggest. |
| Add by hand | chip | Start an empty colour set, then tap a colour on the character to change it |
| See it | slider | Slide to watch the colours sweep across |
| Pick on the picture | chip | Then tap the colour on the character that this row should change |
| Becomes | slider | The colour it turns into |
| Reaches | slider | How far from the picked colour this change spreads |
| Another colour | chip | Tap a colour on the character; it joins this set and you choose what it becomes |
| Edge of the wave | slider | A sharp line crossing the character, or a soft one, or the whole thing melting together |
| Blotchy | slider | How ragged the front of the wave is: skin changing in patches |
| It sweeps | slider | Which way the change crosses the character |
| Remove | chip | Take this colour set away. Undo brings it back. |

## Built at run time

Hover labels whose control words are made while the room runs (a part, a stance, a button, a shot by its own
name). Read them in these files: al-app.js (2), al-bridge.js (4), al-cards.js (16), al-export-video.js (3), al-export.js (1), al-facepins.js (2), al-fit.js (2), al-ground.js (1), al-intake.js (6), al-perform.js (8), al-scene-strip.js (4), al-scene-ui.js (14), al-stages.js (5), al-stance.js (1), al-stick-depth.js (1), al-stick-moves.js (10), al-stick.js (1), al-tone.js (1).

207 controls listed.
