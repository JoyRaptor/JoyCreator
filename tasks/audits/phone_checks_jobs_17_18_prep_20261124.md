# Phone checks for queue jobs 17 and 18, prepared from the code (11-24, hover-1 lane)

Written while the Note 9 was off Wi-Fi debugging. Nothing here is run yet. Method that works in Studio: `uiautomator dump`
lists every control with its content description (a Studio control's hover label) and id; tap its bounds, never fixed
coordinates. The lobby is custom-drawn (no text nodes): screenshot first, then tap "Carry on" or a card. Hold a picture or
clip (swipe from a point to the same point, 900-1200 ms) to open its drawer. Always on a throwaway project ("Restore video
test" has a star picture, text, sprites, a visualizer and two voice/music lanes).

## Re-checks owed after landings
| # | Check | How | Expect |
|---|---|---|---|
| R1 | Envelope **Clear** is now one undo step (`ce27b5e47`) and the row label refreshes | Restore video test, hold the audio clip, audio drawer (Level, Pan, Envelope, In, Out). Read `undo_count` (id of the counter), tap **Clear** (desc "Clear the volume envelope and ..."), read the counter again, tap `btn_undo` | counter +1 after Clear; row reads "Flat gain" at once (not "10 pts"); Undo brings the points back and the label "Envelope - N pts" again |
| R2 | Odd frame at the cut after PreviewClock fix | Untitled 7, tap overview bar near 17 s, tap `btn_play_pause`, screenshot at once and ~0.5 s later, across editor time 18 s | picture counter at editor 18.0x reads about 0.0x s (not 1.0 s); the button shows Pause while playing |

## Job 17: STU-4 leftovers (card rows)
| Row | Route | What to look at | Expected if fixed |
|---|---|---|---|
| Audio envelope Clear / In oddities | see R1; then drag the **In** slider (`FineSeekBar`, row "In", value pill taps to type seconds) | readout while dragging a short distance; undo count | readout rises smoothly from 0 ms; ONE undo step per drag |
| Sprite **Lanes** tab pills over the preview edge | Add, Sprite (or an existing sprite lane), hold the sprite in the preview to open its drawer, tab titled `drawer_tab_lanes` ("Lanes", icon `ic_lanes_24`; `buildImageMoveTab` builds it for images, the sprite drawer has its own) | take a screenshot: do the pills overlap the preview's edge? | pills sit fully inside the drawer, preview edge clear |
| Caption motion "Type - word" stuck | a clip with Transcript run (needs speech: use the Restore project's voice lane clip or a clip with talking), Captions tool, motion chips (`caption_anim_pop/zoom/bounce` and the Type option) | choose Type, then pick another motion | the chip changes; the preview animates the new one |
| Caption hide-eye and outline swatch | caption drawer: `lane_b_caption_hide` ("Hide this caption track"), the outline colour swatch | tap eye, tap swatch | eye hides/shows captions in the preview; swatch changes the outline colour at once |
| Puppet RECORDING tab button column clipped | job 18 route below, scope **Recording** | screenshot of the Recording scope | buttons ("Record on touch", "Snap to keys") fully visible, none cut off at the drawer edge |
| `OverlayNavUtil.dismiss` pops one level | Library, open Trash, close | back to Library, not to lobby | one level only |
| Sprite warp net looks detached | select a sprite, Distort / corner pin | net lines follow the sprite | net sits on the sprite |

## Job 18: puppets (ANI-1 card; route in `FaditorEditorActivity` ~line 36785, `PuppetDrawerTabs.java`)
1. Open "Restore video test" (or add an image via Add, **Image as new layer**).
2. Hold the picture (star) in the preview: its image drawer opens; the **Puppet** tab (string `drawer_tab_puppet`, icon person)
   is the **last** tab. A picture that already has pins opens on it.
3. Tools row (enum `Tool`): **Grab, Pin, Stiff, Dangle, Free, Bone**. Scope switch: **Selected / Character / Recording**.
4. **Pins:** choose Pin, tap the picture twice (e.g. waist, hand). Expect: two pin markers; one undo step each (`btn_undo` count +1).
5. **A keyed pin:** move the playhead, choose Grab, drag a pin. Expect: a keyframe for that pin; scrubbing replays the move.
6. **A live take:** scope Recording, arm "Record on touch" (`lane_b_puppet_rec_off` -> `rec_on` text: "Recording when you touch a pin. Tap to stop."), press `btn_play_pause`, drag a pin while it plays, stop. Expect: a recorded take on the timeline lane.
7. **A dragged performance:** play it back; expect the pin to repeat the drag; "Snap to keys" (`lane_b_puppet_record_snap`) snaps it.
8. Record frame times or any stall; log battery temperature.

Evidence into `tasks/screens/<date>/`, one line per check in the card (pass / fail / not reachable).
