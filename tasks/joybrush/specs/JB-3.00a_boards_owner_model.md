# JB-3.00a — Boards: the owner's model (region boards on the infinite canvas)

| | |
|---|---|
| **Tier** | T1 (architecture: it changes what an animated layer IS) |
| **Status** | 🔒 **Design locked 2026-10-01 (§K).** Spec, Part 1 (model) + Part 2 (on-screen design brief) — Lead, 2026-10-01, from the owner's own description (quoted in full in §A). **Overrides** the parts of JB-3.01, JB-3.00, JB-3.06b and JB-4.01 that it contradicts; see §D. |
| **Ruling** | LEAD_RULINGS **R50** |
| **Author** | Joy Brush Lead (Claude), written at the owner's request: *"write up a spec sheet defining this and quote all the direction that I gave directly in a specific section"* |

---

## A. The owner's direction, verbatim (2026-10-01)

Quoted exactly as given, typos included, so nobody has to guess what was meant. **Where the
specification in §B and this section disagree, this section wins and §B is a bug.**

> Please explain to me how you are understanding boards. So my understanding is that we have a pseudo infinite canvas
> that you can draw all over the place on. And we also have a particular feature called a board. And different boards
> hold different functions. Step one, you select the type of board you want. Step two, you draw it on a region that
> you've drawn on. You set the aspect ratio and the exact pixels you want by adjusting the numbers on the side. And when
> you click the little lock icon in the corner, now that board is affixed to that region on your infinite canvas. And
> that board has special features. So for instance, the animation board has a little film scrubber down below. At the
> beginning, it only has one frame, but as soon as you tap the plus, then it swaps out that region's art for those
> layers with new tiles in that exact same place that you can draw on. And so you can continue drawing and making new
> frames, and it only affects the tiles in that region.
>
> . Because of this architectural change, once you've affixed a board, it doesn't really move. At least not the
> animation one, because if it were to move, it would break those tiles that are stacked in that region. So that one's
> pretty much fixed. However, other boards, you can lock those in place so that they won't be affected by your
> touching, but are still functional. For instance, the sprite board, which is effectively a grid that you drag over a
> certain region of your canvas, and then you can draw in it, and at any point, you can arm the grid to be able to move
> or re-scramble around the tiles into different orders, and then disarming will set it at whatever you have arranged.
> Or you can select certain tiles and play them in sequence, and a little preview window will show that animation
> cycling so that you get a preview for how your animation would look.
>
> Another board is an image board, which is simply a selected region, and you can select its aspect ratio, and it'll
> just export that region. Other than that, it's just a nice passive frame. You can also batch export all image frames,
> duplicate it, and it will duplicate the name with a number. And you can rename that artboard, and then it'll export
> with that name.
>
> . Another board is the tile board. When you place this on your canvas, you select what size, its name, just like an
> image board. You can adjust its aspect ratio. And once you have it placed how you would like, you would lock it in.
> And then once you arm it, which is a toggle, toggle, it tiles that region's art so that you can see where any seams
> are. And then you can draw anywhere on or off that tile, and it will paint relative to where it is on that tile. And
> so you can see, no matter if you're painting on the tile or anywhere on the screen, it will land somewhere on that
> tile. And so you can just paint all over the place with brushes, and you can know with 100% certainty that it will
> always perfectly tile. This is a new board feature I haven't talked about yet until now, but it is the same as the
> function in Krita and the function in Infinite Painter. Its little armed button that is at the top corner on the
> outside of the region, you can toggle off and it no longer tiles, but it can still export just like a simple image
> board. And an image board can always be made into a tile board. With a single click. So perhaps it's not so much its
> own board, but simply a modified image board, or putting an image board down with a particular setting on or ready
> to go. So in summary, there is the animation board, which has its look of alignment pegs below the square, as well
> as a film strip sequencer. There is the sprite sheet board, which has the ability to set grid cells. and rearrange
> your art within the cell and a mode so that you can select cells in a sequence and then play them in that sequence.
> And the ability to export, or it will export to Sprite Lab. So that you can do more advanced things. And that is
> just it's exporting of a picture along with a JSON file. And then you have your image board and your pattern tiling
> image board, which is just the image board, but ready to tile. So it's one less step for the user. And so it's just
> easy to place that.
>
> . How is this different than the understandings that we have had previously? I'm asking because I had Sol 6.1,
> which is a chat GPT model, working on animation. And it seems like it's wanting to animate the whole canvas. Which is
> kind of a little bit awkward. for exporting among other things. Maybe it's not fully done. I just wanted to make sure
> that we're on the same trajectory. And if not, could you write up a spec sheet defining this and quote all the
> direction that I gave directly in a specific section So that it has your specification and then also what I said
> that gave that specification. So there's no ambiguity. I would also like you to tell me if this is a bad idea or if
> there are parts that are not good or would be better designed. Because this is an architectural thing. And it is
> very unique. I haven't seen any app do anything like this. At least exactly. Especially the animation chunk, because
> we're taking advantage of the sparse tiles to basically add a new dimension to just a region.

---

## B. The specification

Each rule cites the sentence in §A it comes from (**A:** "…"). A rule marked **Lead** is the Lead's
engineering answer to a question §A leaves open, and §E lists those for the owner to confirm.

### B1. What a board is

1. The document is one unbounded, sparse tile canvas. **A board is a named rectangle on it with a
   kind, a size in pixels, and its own features.** Nothing outside a board is owned by it.
   (A: "a pseudo infinite canvas … And we also have a particular feature called a board. And different boards hold
   different functions.")
2. **Kinds the person can place:** Image, Tile (an Image board with tiling armed), Animation, Sprite. Puppet and
   Character stay in the plan for later and are not in this spec. **Tile is not a kind of its own:** it is an Image
   board whose *tiling* switch starts ON. (A: "perhaps it's not so much its own board, but simply a modified image
   board, or putting an image board down with a particular setting on".) In the file, Image is the existing `CANVAS`
   kind; the screen calls it **Image board**.

### B2. Placing, locking, arming

3. **Place** = choose the kind, then drag a rectangle over the canvas. While placing, the board shows its width and
   height in px and its aspect ratio as numbers on the side, editable; dragging the corners updates them.
   (A: "Step one, you select the type of board you want. Step two, you draw it on a region … You set the aspect ratio
   and the exact pixels you want by adjusting the numbers on the side.")
4. **Lock** = the padlock at the board's corner. A locked board ignores touches on its own frame, so painting near it
   can never drag or resize it, but every feature it has still works. Unlocking lets it move and resize again.
   (A: "when you click the little lock icon in the corner, now that board is affixed to that region"; "you can lock
   those in place so that they won't be affected by your touching, but are still functional.")
5. **Arm** = a separate toggle at the board's top corner, outside the rectangle, for boards whose feature is a mode:
   Tile (tiling on or off) and Sprite (rearranging cells). Arm and Lock are different controls and never share an
   icon. (A: "Its little armed button that is at the top corner on the outside of the region"; "you can arm the grid
   … and then disarming will set it at whatever you have arranged.")
6. **An Animation board is fixed once it has a second frame.** It cannot be moved or resized, because its frames are
   pixels stacked in exactly that place. With one frame it behaves like any other board. (A: "once you've affixed a
   board, it doesn't really move. At least not the animation one … So that one's pretty much fixed.")
   **Lead:** see §C3 for a safe "Move board with all its frames" to offer later.
7. **Lead:** an Animation board may not overlap another Animation board: one pixel can belong to only one sequence of
   frames. Image, Tile and Sprite boards may overlap anything, because they do not own pixels.

### B3. The Animation board — frames belong to the REGION, not to the whole layer

8. **A frame is the board's region, for every layer animated there, and nothing else.** Adding a frame gives the
   board's rectangle fresh tiles in exactly the same place to draw on. Outside the rectangle the canvas is the same
   on every frame and is not touched by the frames.
   (A: "as soon as you tap the plus, then it swaps out that region's art for those layers with new tiles in that exact
   same place that you can draw on … and it only affects the tiles in that region.")
9. **Pixel-exact at the edge.** A tile can straddle the rectangle's edge. Inside the edge, a pixel comes from the
   current frame; outside, from the shared canvas. The rule is a clip rectangle, never "whole tiles", so the edge never
   jumps by up to 256 px.
10. **A stroke that crosses the edge is split by the same rule.** On frame 3, the part inside the board lands on
    frame 3 and the part outside lands on the shared canvas. One stroke is still ONE undo step.
11. **Lead:** a layer can be set to **Same on every frame** (a held background). Its pixels inside the board are then
    shared like the outside. New layers animate by default. (§A says "those layers"; see §E Q1.)
12. Frame operations are the existing, tested JB-3.01 maths: add blank or duplicate, link, hold, move, delete, timing.
    Only *where their pixels live* changes.
13. The film strip ("a little film scrubber down below"), the peg bar under the board ("its look of alignment pegs
    below the square"), onion skin and playback are drawn for THAT board and show only its region.
14. **Export of an Animation board is its rectangle × its frames.** No pixel outside the board is ever in it. (This is
    the "awkward for exporting" the owner saw, fixed at the root.)

### B4. The Sprite board

15. A grid of cells dragged over a region; you draw in the cells. (A: "effectively a grid that you drag over a certain
    region of your canvas, and then you can draw in it".)
16. **Armed:** cells can be dragged to new places to reorder or re-scramble the art. A moved cell carries its pixels,
    on every layer, with it. **Disarming commits** the arrangement as ONE undo step. (A: "arm the grid to be able to
    move or re-scramble around the tiles into different orders, and then disarming will set it at whatever you have
    arranged".)
17. **Sequence and play:** select cells in an order, play them, and a small preview window loops the animation. This
    is JB-4.02's roll and play. (A: "select certain tiles and play them in sequence, and a little preview window will
    show that animation cycling".)
18. **Export:** a PNG sheet plus a JSON file, and "Export to Sprite Lab" opens it there. This is JB-4.03. (A: "it will
    export to Sprite Lab … a picture along with a JSON file".)

### B5. The Image board

19. A named, passive frame that exports its rectangle. It can be renamed (the export takes the name), duplicated (the
    copy is named with a number: "Hero" → "Hero 2"), and every Image board can be exported in one batch. (A: "it'll
    just export that region … batch export all image frames, duplicate it, and it will duplicate the name with a
    number … rename that artboard, and then it'll export with that name.")
20. **One tap makes an Image board a Tile board and back:** the tiling switch is a setting of the Image board.
    (A: "an image board can always be made into a tile board. With a single click.")

### B6. The Tile board (an Image board, tiling armed)

21. **Armed, the board's art is shown repeated around it**, so seams are visible. The repeats are a preview drawn on
    screen only: never pixels, never exported. (A: "it tiles that region's art so that you can see where any seams
    are".)
22. **Armed, every stroke anywhere on the canvas lands in the board**, at the same position modulo the board's width
    and height. A dab that crosses the board's edge is drawn on both sides (wrapped), so the result always tiles
    exactly. (A: "you can draw anywhere on or off that tile, and it will paint relative to where it is on that tile …
    no matter if you're painting on the tile or anywhere on the screen, it will land somewhere on that tile … you can
    know with 100% certainty that it will always perfectly tile.") This is Krita's wrap-around mode and Infinite
    Painter's pattern mode.
23. **Disarmed, it is a plain Image board again** and exports the same way. (A: "you can toggle off and it no longer
    tiles, but it can still export just like a simple image board.")
24. **Lead:** while a Tile board is armed, only one can be armed at a time: a stroke cannot wrap into two boards.

---

## C. The Lead's assessment: is this a good design?

**Yes. The region-animation idea is the best part of it, and it is new.** Procreate, Krita, Infinite Painter and
FlipaClip all animate the *whole canvas*: every frame is a full-size picture, so a small walk cycle in the corner of a
large painting costs a whole painting per frame and exports with everything around it. Joy Brush stores only the
tiles that have paint (sparse tiles), so a frame that is only a board's rectangle costs only the tiles inside that
rectangle. It means you can have a still background, a looping fire in one corner, and a walk cycle in another, each
its own board, on one canvas, each exporting cleanly. I know of no app that does this. It plays to the engine's
strength rather than against it.

**What I would design differently or add, in order of importance:**

1. **Pixel-exact edges, not tile edges (B9–B10).** This is the one thing that could make the idea feel broken. Tiles
   are 256 px squares and a board can sit anywhere, so "swap the region's tiles" would swap art up to 256 px outside
   the board's edge. The fix is cheap (a clip rectangle in the shader and in the exporter) and it must be in from the
   first build.
2. **Say what happens to the outside while you are on frame 5.** It stays the shared canvas, the same on every frame,
   and that is right. But on screen the board should show its frame number and a slightly stronger border while you
   are past frame 1, so painting next to it, with its outside shared, never surprises you.
3. **"Fixed" should mean "not by accident", not "never".** Moving the board AND all its frames together is safe: it is
   a copy of the same tiles by the same offset, one undo step. Offer it later as a deliberate "Move board with all
   frames", behind a confirm. Day-to-day it stays fixed, as you described.
4. **Held layers (B11).** Animators keep a background, a paper texture or a reference sketch the same on every frame.
   Without "Same on every frame" you would have to copy the background into every frame.
5. **Memory is now a budget per board:** frames × animated layers × the tiles inside the rectangle. Show it, the way
   the layer column shows "3/32", so the phone is never pushed into a crash by a long sequence.
6. **The Tile board's hard part is brushes that READ the canvas.** Painting wraps easily: a dab near the edge is
   simply drawn twice. Smudge, the fill bucket and the eyedropper have to *read* across the seam too, or a smudge
   dragged over the edge will leave a visible line. Plan them as a second step, and say so on the board until it
   lands.
7. **Lock and Arm must look different, and always sit in the same place** on every board (padlock at one corner, the
   arm switch at the other). Two toggles on one small frame is the easiest place for this design to get confusing on
   a phone.
8. **Sprite rearranging should move every layer of a cell together**, as if the cell were cut out and moved. Moving
   only the active layer would split a character from its colours.

**Nothing in your description is a bad idea.** The one real risk was the tile-edge problem, and the region model
solves it cleanly as long as it is clip-exact.

---

## D. How this differs from what was specified and built before

| Topic | Before (JB-0.02, JB-3.01, JB-3.06b; what the animation lane is following) | Now (this spec) |
|---|---|---|
| What a frame is | A whole **layer** is animated in a board. Each frame is a full layer's worth of pixels, on the **whole unbounded canvas**. Painting anywhere on an animated layer while on frame 3 changes frame 3, even far from the board. | A frame is the **board's rectangle** only. Outside it the canvas is shared and never per-frame. |
| Export | The board's rectangle, cut out with `clipToBoard`, from frames that hold the whole canvas. Works, but the frames are carrying pixels nobody exports. | The frames hold nothing outside the board, so export is exactly what is stored. |
| The edge | Not a question (frames are the whole layer). | Pixel-exact clip (B9–B10). |
| Lock / Arm | Not specified anywhere. | B4–B6. |
| Tile board | Not in the plan at all. | B6 (new). |
| Image board | "Canvas board"; one per new drawing. | Many, named, duplicate-with-number, batch export, a one-tap tiling switch. |
| Sprite rearranging | Not specified (JB-4.01 is the grid and resizing, JB-4.02 the order and play). | B16 (new). |

**What does NOT change and stays valid:** the frame maths (AnimOps: add, duplicate, link, hold, move, delete,
timing), the film strip maths (FilmStrip, FrameStepper, PlaybackClock), the peg-bar geometry (PaperGeometry), the
sprite grid maths (SpriteGridMath, SpriteBoard), the sprite roll and play (JB-4.02), and the GIF encoder and export
plan. Those are about frames and timing, not about where the pixels live, so they keep working.

**What has to change** (a row each, for the board; the Lead will write them):

1. **JB-3.01b — region frames.** Where an animated layer's frame pixels live and how they are clipped. Proposed data
   (DOC_VERSION bump, R3): an animated layer keeps its ordinary cel as the **shared canvas**, and each frame's cel
   holds only the tiles that touch the board's rectangle. Pixels of those tiles outside the rectangle are ignored on
   read and never written on paint. Plus `Layer.heldIn` (or a per-board set) for "Same on every frame".
2. **JB-3.01c — the engine and the export read frames through one clip rule** (core first, GPU and RegionRenderer
   both calling it: the same pattern as masks, R48).
3. **JB-3.00 (create board)** gains the Lock and Arm states (B3–B6) and the "fixed after frame 2" rule.
4. **JB-6.xx — the Tile board:** wrap-around painting, then the seam-aware smudge, fill and eyedropper.
5. **JB-4.04 — sprite rearrange** (arm, drag cells, disarm commits, one undo).
6. **JB-3.06b (animation export)** keeps its plan; it now reads region frames.

---

## E. Questions for the owner — ANSWERED 2026-10-01, see §F1

1. **Which layers animate in a board?** Default: every paint layer, with a per-layer "Same on every frame" switch for
   backgrounds. The alternative is choosing layers one by one when you make the board.
2. **"Move board with all frames"** (C3): offer it later, behind a confirm? Default: yes, later.
3. **Tile board while armed:** do strokes far from the board still wrap into it (your words: "anywhere on the
   screen"), or only strokes that start near it? Default: anywhere, exactly as you said. Painting elsewhere on the
   canvas means disarming first.
4. **Sprite cell moves:** swap two cells when you drop one on another, or push the rest along like a list? Default:
   swap.

---
---

# PART 2 — the board's on-screen design (owner, 2026-10-01, second message)

Part 1 (§A–§E above) is the model: what a board is and where its pixels live. Part 2 is how a board LOOKS and is
TOUCHED. Part 2 is written so that a designer can produce the visual options (§J) and a builder can build the chosen one
exactly, without either having to come back and ask.

## F. The owner's direction, verbatim (2026-10-01, second message)

Quoted exactly as given. **Where §G–§I and this section disagree, this section wins.**

> All your suggestions are excellent. Pixel exact edges. We should make sure that when drawing the bounding box, that
> the line for the bounding box is on the outside. So every single pixel that is visible is visible within. If I have
> drawn three frames, and the last frame I left it on was frame three, then frame three is what is shown on, on the
> canvas. And it is left there until I move it back to frame one. So if I draw a line from the outside and it crosses
> the bounding box of my animation board, then that line at that intersection lives on frame three. And if I were to
> turn it to frame one, then that section would be missing. Basically, we are hot, we are hot swapping section tiles on
> the infinite canvas.
>
> . In the upper left-hand corner, there should be small icons like for the animation have a little animation film
> with sprockets to let you know that this is an animation board. Right underneath it should be a handsome display of
> your frame number that it's currently set at. Which can just be a number in the same color as the icon above it.
> Below that should be any sort of arming features and I don't think that there's any arming features however there
> may be a feature to lock or move the board which might be a simple toggle Along the top in small discrete numbers
> should be the pixels for the length and along the side likewise the height if it's too crowded on that side we can
> have that on the far side tapping on those while it is unlocked would allow you to adjust their lengths. And I'll
> leave it up to you whether that grows from the center or if we have it where the pivot is from the top left so it
> extends out rightward and downward. I'll leave it up to you to figure that out. Which is better. For images, a simple
> icon of that's a solid that has a picture frame with a mountain and a sun. Very small, but easy to read. For the
> pattern, we would need some sort of familiar pattern maybe houndstooth, or perhaps you have a better. idea for what
> the icon could be. I'm open to suggestions on that. I don't have any strong preference. Maybe actually under the
> arming, under the image, that could be the toggle for patterning. So maybe it doesn't go in the upper corner, but
> it's just a little tab or a little icon to the left. Up above the image, on the top left, is handsome text that has
> the title and that is the title that it would save out as we need somewhere probably on the left on the outside a
> little save floppy disk or hard disk or export icon and that would bring up a save animation or save a range or save
> just this frame which we can reuse from the studio editor That has all that wiring there. For the sprite sheet,
> artboard, we just reuse a lot of the same mechanics that were developed by ArtLab. So point the agents there to look
> and see what needs to be modeled. Maybe even have some of the mechanisms referenced so that if we improve SpriteLab,
> those improvements go directly over. to the uh, sprite board. But the settings that are specific just to the sprite
> board you'll have to enumerate what those are but they should probably go along the bottom and those should be just
> setting the grid And I've covered that in more detail in other places, so I don't need to go over that here with you.
> I'm sure you can distill what needs to be done. But as far as the animation board, it should have below the frame
> there should be a little extension from that which has the three pegs which helps you visually feel like it's there
> and it should have the frame but different than it is in SpriteLab where the frame has its little chips that it shows
> and you can drag on the chips to rearrange it would have all that same functionality of SpriteLab so we can totally
> port that over to the animation board. But what has to change at least I think it's a good idea to change, is how
> it's represented. So instead of being a solid black film, it is instead the same thing but just an outline format. So
> that these things look clean. And we should have it that when a board is drawn but it's not selected on, like maybe
> the top left-hand tab, which is just a small little icon, maybe you tap on that to select it and all the other
> features come alive. But it's just discreetly there where you, all you see is a bounding box and a little tab on the
> bounding box with an icon. Otherwise, and if you're drawing and you happen to draw near that icon or underneath, it
> fades out or either that or moves. so that you can be drawing there unencumbered. Maybe it goes over to the right
> side until you draw on the right side. Or something along those lines. Just so because that is still your art board.
> And I don't want to steal that away with a whole bunch of UI. But when the animation board, the extended part, that
> extended part is not visible. until you've actually clicked on the artboard. And so maybe the icon changes from white
> to with a, a drop shadow to a color or something like that. When an animation board is selected, there should be a
> little dot or icon or something that's really compact that flags which layers are affected by it. So you can go and
> turn on active or inactive or maybe even omitted. I don't know if we need that. But maybe it could toggle between a
> running man versus a background mountain or something like that. Or maybe the running man could be in the app's
> color. And it could be grayed out and dull if it's not actually animated. That way it's easy for the user to see, oh,
> this is, this is just a layer that is not going to be a part of the uh, motion. It, its tiles never swap out. But that
> would be in the layer tool. itself. Locking and arming will look different because they're probably going to be a
> different icon or a different place. Locking has to do with the properties of the canvas. Arming has to do with the
> properties of the feature itself. And you're correct that with the sprite board, you are cutting and rearranging all
> the layers in that cell and swapping them with all the layers in another cell. Swapping them back would be pixel
> perfect, of course. So you could either undo or just move them back. And there would be no seam. But yeah, I think
> that answers all your questions. And pretty much all of your intuitions are correct. I didn't see anything wrong in
> there. You have good judgment on the matter. So go ahead and write this up. And then I when you do, I'm going to take
> that file. That probably has a lot of the answers that I gave in here, along with your any writing that you do. And
> I'm going to drop that into a fresh conversation. And I'm going to want an Opus agent to then do the design work to
> basically get the design nailed down in an HTML format. Probably doing let's say four different options and then once
> I agree on a set option then that is going to be locked in and that is going to be what soul or any other agent
> builds exactly., when you do, I'm going to take that file that probably has a lot of the answers that I gave in here,
> along with your any writing that you do. And I'm going to drop that into a fresh conversation. And I'm going to want
> an Opus agent to then do the design work to basically get the design nailed down in an HTML format. Probably doing,
> let's say, four different options. And then once I agree on a set option, then that is going to be locked in. And
> that is going to be what Sol or any other agent builds EXACTLY.

### F1. Part 1's questions, answered by this message (§E is closed)

| §E | Owner's answer | Becomes |
|---|---|---|
| Q1 Which layers animate? | "all your suggestions are excellent"; the running man / background mountain marker per layer | Every layer animates by default; each layer has a per-board **animated / same on every frame** toggle in the layer column (§G7) |
| Q2 Move board with all frames | "there may be a feature to lock or move the board which might be a simple toggle" | The lock toggle (§G3); a deliberate move moves all frames, as ONE undo step |
| Q3 Tile board wraps from anywhere | (Part 1 §A: "anywhere on the screen") | Anywhere, while armed |
| Q4 Sprite cells swap | "you are cutting and rearranging all the layers in that cell and swapping them with all the layers in another cell" | Swap, all layers, pixel-perfect, one undo step |

## G. The specification (Part 2)

### G1. The board's outline is drawn OUTSIDE the board
1. The bounding line sits entirely outside the board's pixels, so every pixel that will export is visible inside the
   line and none is covered by it. (F: "the line for the bounding box is on the outside. So every single pixel that is
   visible is visible within.")

### G2. The current frame is a property of the board, and it stays
2. Each Animation board remembers the frame it is set to, and the canvas shows THAT frame in the board's rectangle until
   the person moves it. It is saved with the drawing. (F: "frame three is what is shown on … the canvas. And it is left
   there until I move it back to frame one.") **Model:** `Board.currentFrameId` (DOC_VERSION bump, R3).
3. Painting that enters the board lands on the board's CURRENT frame, wherever the stroke started; Part 1 §B10 is
   unchanged. (F: "if I draw a line from the outside and it crosses the bounding box … that line at that intersection
   lives on frame three. And if I were to turn it to frame one, then that section would be missing.") The owner's own
   name for the mechanism: **"hot swapping section tiles on the infinite canvas."**

### G3. The left-hand column (the board's controls)
A slim column outside the board's left edge, top to bottom:

| Position | What | Notes |
|---|---|---|
| Above the board, at its top-left | **The board's title**, in handsome text | It is the export file name. Tap it (when selected) to rename. (F: "Up above the image, on the top left, is handsome text that has the title and that is the title that it would save out as") |
| 1 | **The kind icon** (the tab) | Animation: film with sprockets. Image: a solid picture frame with a mountain and a sun. Sprite: a grid of cells. Tile: §H2. Always visible; it IS the board's tab. |
| 2 | **Animation only: the frame number** | "a handsome display of your frame number", "in the same color as the icon above it". |
| 3 | **Arming (feature) toggles** | Image board: the tiling ("pattern") toggle lives here (F: "under the image, that could be the toggle for patterning"). Sprite: rearrange (arm). Animation: none. |
| 4 | **Lock / move** | A canvas property, so its own icon and place, never mixed with arming. (F: "Locking has to do with the properties of the canvas. Arming has to do with the properties of the feature itself.") |
| 5 | **Save / export** | Floppy or export icon. Animation: "save animation / save a range / save just this frame", reusing the Studio's export wiring (§I). Image/Tile: export this board, and export all image boards (Part 1 §B19). Sprite: export sheet + JSON / to SpriteLab. |

### G4. Size numbers
4. Width in small, discreet numbers along the top edge; height along the side, moving to the far side if the left
   column makes it crowded. Tapping a number while the board is UNLOCKED edits it. (F: "Along the top in small discrete
   numbers should be the pixels for the length and along the side likewise the height … tapping on those while it is
   unlocked would allow you to adjust their lengths.")
5. **Resizing pivots on the TOP-LEFT corner** (Lead, asked to decide; §H1).

### G5. Selected and unselected
6. **Unselected:** only the outline and the small tab (the kind icon). The icon is white with a drop shadow (the same
   "readable on any picture" treatment as the top bar's icons, JB-2.01). (F: "all you see is a bounding box and a
   little tab on the bounding box with an icon".)
7. **Selected:** tap the tab. The icon takes the board's kind colour, and the column, the title, the numbers and (for
   Animation) the peg extension and the film strip appear. (F: "you tap on that to select it and all the other features
   come alive"; "maybe the icon changes from white to with a, a drop shadow to a color".) Board kind colours are the
   existing D.01 tokens: Animation = the Studio's aqua→lime, Sprite = SpriteLab's pink→violet, Image = Joy Brush's own.
8. **The art comes first.** While the pen is drawing near the tab or the column (within about 48 dp) they fade nearly
   out, and come back when the pen lifts. (F: "if you're drawing and you happen to draw near that icon or underneath, it
   fades out or either that or moves … because that is still your art board. And I don't want to steal that away with
   a whole bunch of UI.") Fade-versus-hop-to-the-other-side is one of the things the design options compare (§J).

### G6. The Animation board's extension
9. **Below the board, only when selected:** a short extension with **three pegs** (the animation paper's peg bar), and
   under it the **film strip**. (F: "below the frame there should be a little extension from that which has the three
   pegs which helps you visually feel like it's there".)
10. The film strip has **SpriteLab's mechanics**: chips per frame, drag a chip sideways to reorder, drag it up to
    remove, the playhead and scrub. The difference is that it is drawn **in OUTLINE, not as solid black film**, "so that
    these things look clean". (F: "it would have all that same functionality of SpriteLab so we can totally port that
    over … instead of being a solid black film, it is instead the same thing but just an outline format".) Plus the
    Part 1 frame operations (add blank, duplicate, hold) and play.

### G7. Layers and the Animation board
11. **While an Animation board is selected**, each row in the layer column shows a compact marker: a **running man in
    the app's colour** = this layer animates in the board (its tiles swap per frame); the same mark **greyed and dull**
    (or a **background mountain**) = same on every frame (its tiles never swap). Tapping the marker toggles it. (F: "a
    little dot or icon … that flags which layers are affected by it … toggle between a running man versus a background
    mountain … the running man could be in the app's color. And it could be grayed out and dull if it's not actually
    animated … that would be in the layer tool itself.") **"Omitted" is not a third state** for now: the owner was
    unsure it is needed, and two states read faster.

### G7a. Layer previews follow the selected board — owner clarification, 2026-10-02

Owner's direction, verbatim:

> almost forgot a detail: when a bord is selected/active the layer selector previews just show the bounds inside that board. when its passive the layers go back to normal functioning

When any board is selected/active, each layer-selector thumbnail previews only that board's
rectangle, using the same document bounds for every layer. For an Animation board, previews
show its current frame; layers held on every frame show their shared content in that rectangle.
When the board becomes passive/unselected, restore the normal layer previews. Switching the
selected board updates the previews to the new board's bounds. This is preview framing; it
does not crop or mutate stored layer pixels. The ordinary layer controls remain available.

Acceptance: select a board over artwork that extends outside it; thumbnails show only the
inside portion. Change animation frame; thumbnails follow that frame. Make the board passive;
normal thumbnails return. Selecting another board uses its rectangle without changing artwork.

### G8. The Sprite board
12. **Reuse SpriteLab's mechanics, by REFERENCE where possible**, so improving SpriteLab improves the Sprite board.
    (F: "reuse a lot of the same mechanics that were developed by [Sprite Lab] … Maybe even have some of the mechanisms
    referenced so that if we improve SpriteLab, those improvements go directly over".) §I says which pieces.
13. **Its own settings sit along the bottom, and are the grid** (F: "they should probably go along the bottom and those
    should be just setting the grid"):
    - **by count** (columns × rows) or **by size** (cell width × height, px);
    - the sub-grid guide (2–8 divisions per cell, drawing aid only);
    - the board resizes to whole cells (`SpriteGridMath.fitRect`).
14. **Armed:** rearrange. A cell dropped on another SWAPS with it, every layer of both cells, pixel-perfect. Swapping
    back or undoing restores it exactly, with no seam. One drag = one undo step. **Disarmed:** sequence mode, where you
    select cells in order and play them in a small preview window (JB-4.02).

### G9. Lock and arm look different
15. Lock (the padlock, a CANVAS property) and every arm toggle (a FEATURE property) have different icons, different
    positions in the column (§G3 rows 3 and 4), and never share a colour state.

## H. The Lead's decisions on what the owner left open

### H1. Resize pivots on the top-left corner, not the centre
- **Pixel-exact.** Growing from the centre splits each change between two sides, so an odd change (say +5 px) would put
  half a pixel on each side. Either the board stops sitting on whole pixels, or one side silently gets the extra pixel.
  Top-left growth is always whole pixels.
- **The art stays put.** With the top-left fixed, every pixel already inside keeps its position relative to the board,
  so frames, sprite cells and tiling all stay registered. The exported picture's (0, 0) never moves.
- **The numbers mean what they say.** Width 1920 → 2048 adds 128 px on the right, which is what a person typing a number
  expects. Dragging any edge or corner handle by hand still works when unlocked; only *typing* a number pivots on the
  top-left.

### H2. The pattern (Tile) icon: a 2×2 tile with a motif cut across the seams
Recommended: a small square split into four, with one shape (a circle or diamond) sitting on the centre cross so each
quarter holds a quarter of it. That is literally what tiling means: the art crosses the seam and meets itself. At the
16–20 dp of a board tab it stays readable. Houndstooth turns to grey noise at that size and says "fabric", not
"repeats". **The designer shows both** (§J); the owner picks.

### H3. Save/export: a floppy-free export glyph
A tray with an arrow out of it (the export glyph the rest of the app uses) instead of a floppy disk: a floppy says "save
the file", and this button makes a picture or an animation. **The designer may show the floppy as an alternative.**

### H4. Fade, not hop, while drawing near the tab (the designer compares both)
Fading is predictable: the tab never moves out from under a finger that was reaching for it. Hopping to the other side
can follow the pen around. The Lead's default is fade to about 15% while the pen is down within about 48 dp; option D in
§J tries the hop.

## I. What to REUSE (builders read this; designers do not need it)

| Need | Reuse from | Where | How |
|---|---|---|---|
| Film strip chips, drag sideways to reorder, drag up to remove, the lifted-chip offset, auto-scroll | **SpriteLab** | `app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java`: inner `FilmStrip` (`:3617`), the roll drag (`:3214–3272` `rollDragFrom` / `rollDropAt` / `rollWillRemove` / `autoScrollFilm`), `ScrubBar` (`:3571`), the sprocket note (`:343`) | **Extract to `studiokit`** (as D.02 did for the colour picker), then both SpriteLab and the Animation board use the ONE copy (R23). Add an `outline` drawing style for the board (G10); SpriteLab keeps solid film. This extraction is its own row, and the board row depends on it. |
| Sprite grid, cell hit test, sub-grid, resize to whole cells | Joy Brush core (built) | `core/sprite/SpriteGridMath.kt`, `core/sprite/SpriteBoard.kt` | Use as is. |
| Sprite sequence select + play | Joy Brush core (built) | JB-4.02 `CellRoll`, `PlaybackClock`, `FrameStepper` | Use as is. |
| SpriteLab hand-off (PNG + JSON) | SpriteLab's own format | `SpriteSheet.java` (the `.sprite.json` model) and JB-4.03 | The export writes what SpriteLab reads, no second format. |
| Export: animation / range / this frame | **The Studio** | `FaditorEditorActivity.showExportConfirmation` (`:12644`), `export/ExportService.java`, `ExportManager.java` | Reuse the sheet's look and the service. If "range" and "this frame" are not already options there, they are added to the SHARED sheet, not to a copy. |
| Frame operations, strip geometry, the clock, the pegs | Joy Brush core (built) | `AnimOps`, `anim/FilmStrip.kt`, `FrameStepper`, `PlaybackClock`, `PaperGeometry` | Use as is (Part 1 §D). |
| Icons black/white over the picture | Joy Brush (built) | `core/chrome/IconContrast`, `chrome/TopButton` | The unselected tab uses the same rule (G6). |

## J. Design brief — for the Opus design agent in a fresh conversation

**Your job:** produce ONE self-contained HTML page showing **four distinct design options (A, B, C, D)** for the boards
described in this file, for the owner to choose from. Once he chooses, that option is **locked** and built EXACTLY. So
draw what you mean precisely (sizes in dp, colours as tokens, every state), not a mood.

**Read first, in this file:**
- §A and §F: the owner's own words. They win over everything else.
- §B: the model.
- §G and §H: the specification and the Lead's decisions. You may vary their *look*; do not break their *rules*.

**The owner's house style (non-negotiable):**
- **Ground:** dark: #000 ground, #0d0d10 surface, #111114 panel, #1c1c22 raised, #2c2c35 line.
- **State colours:** cyan #22d3ee is a RING meaning "selected", never a fill; guide violet #A78BFA is for hints only.
- **Board kind colours:** Animation aqua #35F6BF → lime #97FE8B; Sprite pink #FF008C → violet #CC27FF; Joy Brush
  indigo #5C43FD → blue #4397FD for Image and Tile.
- **Fonts:** Archivo (display), IBM Plex Sans (body), IBM Plex Mono (numbers).
- **Icons:** solid shapes. Over the picture they are white with a soft drop shadow (or black on light paper, which is
  the app's existing rule).
- **Panels:** drawers and panels are see-through (about 50%), never opaque.
- **Every control:** a touch target of at least 40 dp, and a hover label.
- **Density:** draw for the Note 9 at its dense setting, 548 dp wide (about 9:18.5). The owner likes that density; do
  not inflate dp for a bigger screen.
- **Compactness:** think Infinite Painter's compact chrome. The art comes first.

**Every option must show, on a phone frame with a real-looking painting behind:**
1. An Animation board **unselected** (outline outside the pixels + the tab), and **selected**: the title, the left
   column (film icon, frame number in the icon's colour, lock/move, export), the size numbers, the peg extension, and
   the OUTLINE film strip with chips (one chip lifted mid-drag).
2. The same board **while the pen draws near the tab** (the fade, or in option D the hop).
3. An **Image** board selected, with the pattern toggle in its column, and the same board as a **Tile** board armed:
   the repeats drawn around it, plainly a preview and not paint.
4. A **Sprite** board selected: the grid, the bottom grid settings (count / size / sub-grid), armed (a cell mid-swap)
   and disarmed (cells chosen in order with the small play preview).
5. The **layer column** with an Animation board selected: the running-man / greyed marker on each row.
6. The **export sheet** an Animation board opens: Animation · A range of frames · This frame.
7. The **two Tile icons** (the seam-cut 2×2 of §H2 and a houndstooth) and **the two export glyphs** (tray-arrow and
   floppy), side by side at real size.

**How the four options should differ** (suggested; change the axes if you find better ones, and say why):
- **A — Tab column:** the left column as described in §G3, stacked tight against the board's edge.
- **B — Corner chip:** everything folds into one chip at the top-left that expands on tap; the film strip floats below.
- **C — Ribbon:** the title and controls run along the TOP edge as a slim ribbon; the column is only the kind icon and
  the frame number.
- **D — Hop-away:** like A, but the tab hops to the opposite side while you draw near it, instead of fading.

Label every option with its trade-offs in one or two plain sentences: what it does well, what it costs on a phone held in
one hand. End the page with a **comparison table**, then **your recommendation and why**.

**Do not:**
- change the model (Part 1);
- add features the owner did not ask for;
- use a colour as a state that the house style reserves for something else;
- make a drawer or panel opaque.

**When the owner picks one,** write it into this file as **§K — Locked design** (option letter, dp sizes, tokens, every
state) and link the HTML. From then on §K is what Sol or any other agent builds, exactly.

## K. Locked design (owner, 2026-10-01)

**The picture of record:** `tasks/design/UI-Boards-options.html` (published: https://claude.ai/artifact/7xpTZazykHjrrTvJ6jRL4N,
version 2 "Locked as §K"). Open it, press **§K** in the top bar, and every scene shows exactly this design. Where this
section and the page disagree, **this section wins** and the page is a bug. Sizes are dp, taken literally (the Note 9 at
548 dp is the target; do not scale up for wider screens).

### K0. The owner's direction, verbatim

> JB-3.00a §K picks (mix): layout=sat outline=crop tab=bare title=halo frame=wheel arm=ring lock=pad exportGlyph=tray
> size=corner peg=rail strip=outline marker=runmtn repeat=ghost tileIcon=seam grid=shelf quiet=duck exportUI=sheet
>
> * a couple things when the pattern icon is armed. It completely covers and replaces your entire canvas with the tile
>   of the art itself. And all you see is the tile icon in the on position. So that you can find it, so you can turn it
>   back off. And the reason for this is so that you can judge how something looks zoomed out, tiled a whole bunch of
>   times, and also so that you can freely draw anywhere and it all feels like the same canvas. Perhaps having the
>   original tile with the highlighted selector around it, just so you can keep your bearings.
> * for the export icon, I forgot that we actually already have an export frame, which happens to be the icon uh, that
>   we are using in the studio in the top right corner, right next to Joybot icon. That is a single frame of animation
>   with an arrow saying to go. So let's just use that app wide because pretty much everything is a frame or a series
>   of frames.
> * The animation frame when not selected should be very thin, soft, and pretty hard to see. Or it should just be the
>   brackets. And I'd also like a feature that when you hover over the film icon without it activated, it just
>   automatically starts looping its animation. That way, if you're screen recording the app and you're talking, to
>   make something play, you just hover over it and the thing will start animating as kind of a fun little way to reuse
>   the drawing board as a sort of interactive PowerPoint style thing. That way, the animation can look seamless. As a
>   matter of fact, we may not even need the frame. When it's not selected, we could just have the icon hovering there.
>   And you can select it if you want to continue painting while seeing the bounding box. That might be even better,
>   but I'm willing to get your feedback or pushback if you think this is a bad idea.
> * well done.

`exportGlyph=tray` and `repeat=ghost` in the picks line are **superseded by the owner's own bullets** below them (the
Studio export mark; the tile fills the canvas). Everything else in the line stands.

### K1. Shared tokens (every board)

| Token | Value | Use |
|---|---|---|
| Chrome (controls) | `rgba(12,12,15,.90)`, 1 dp ring `#F2F2F5` @ 13% | The pill, peg tab, grid shelf, layer column, tile switch. 90% because 50% reads as switched-off grey on white paper (JB-2.01 phone finding). |
| Glass (panels) | `rgba(6,6,8,.55)` + blur when Frost is on, same ring | Export sheet, board menu, size panel. **Never opaque.** |
| Drawer ink | `#F2F2F5` / dim `#C9C9D3` | Every glyph and word on chrome or glass |
| Ink on paper | `IconContrast` (L* 50, hysteresis 4, mixed → light) | Tab at rest, title, size readout, crop marks, strip lines: black + light halo, or white + shadow |
| Kind gradients | Animation `#35F6BF→#97FE8B` · Image/Tile `#5C43FD→#4397FD` · Sprite `#FF008C→#CC27FF` | Identity only, as a fill or a glyph fill |
| Selected / armed | `#22D3EE` ring 1.5 dp + 3 dp halo @ 28% | Selected board, armed switch, onion peg on, insertion caret |
| Live | `#F43F8E` | Current frame cell, playing peg, now-playing sprite cell (2.5 dp) |
| Careful | `#FBBF24` | Lock open (1.5 dp ring), lifted strip cell, hold count |
| Guide | `#A78BFA` | Hints only |
| On gradient | `#050507` | Glyph or text on any gradient fill |
| Type | Archivo (names, frame number) · IBM Plex Mono (every other number) · IBM Plex Sans (words) | |
| Motion | select 0 ms · fade out 120 ms · back 300 ms after the pen lifts · reduced motion drops the wiggle | |

Every control: visual 26–34 dp, **touch target ≥ 40 dp**, a hover label written as what you get (the page's `title`s are
the text to use).

### K2. At rest (not selected)

| Board | Shows | Exact |
|---|---|---|
| **Animation** | **Only the film icon.** No line. A small frame number under the icon when the board is not on frame 1. | Icon 18 dp solid, ink on paper; centre 27 dp left of the board's left edge, 19 dp below its top. Frame number Archivo 800 11 sp, ink on paper with halo, 12 dp under the icon centre. |
| Animation, pen near | **Soft brackets** fade in at the four corners while a pen hovers or paints within 48 dp of the board's rectangle; they fade out 300 ms after the pen leaves. | 14 dp arms, 1 dp, ink on paper at 45%, 2 dp outside the pixels. |
| Animation, hovered | Hovering the film icon (S Pen hover or mouse) **plays the board in place**, looping at its own timing; leaving returns it to the frame it was on. It selects nothing and saves nothing. A finger has no hover, so on a bare touchscreen this simply never fires. | — |
| Image, tile, sprite | Crop marks + the kind icon. | 11 dp arms, 1.5 dp, ink on paper, 2 dp outside. Icon as above. |

**The Lead's answer to the owner's "feedback or pushback":** icon-only is the right call, and it is what makes the
hover-to-play trick look seamless. It has one real risk, and K2 already carries the two guards for it. Because frames
hot-swap the region, a stroke painted across an invisible edge while the board sits on frame 3 is split: the inside
lands on frame 3, the outside on the shared canvas. Without a visible edge that split would feel like a bug.
(1) The **brackets on approach** show the edge exactly when a pen could cross it, and never otherwise. (2) The **small
frame number** shows only when it matters, when the board is not on frame 1, which is the only time a crossing stroke
can disappear from view later. Both are cheap and neither shows up in a screen recording unless the pen goes near.

### K3. Selected: the satellite pill (all boards)

- Tap the icon to select. The **kind icon takes its gradient**, the selection ring appears (keyline 1 dp ink @ 55%
  + 1.5 dp cyan + 3 dp cyan halo, all outside the pixels), and the pill, title, size and extension appear.
- **Pill:** chrome, 34 dp wide, radius 17, 4 dp vertical padding, **10 dp left of the board's left edge**, top aligned
  with the board's top. Items top to bottom, 30 dp each:
  1. kind icon, 18 dp, kind gradient;
  2. **Animation:** the **frame wheel**: current frame Archivo 900 18 sp in the Studio gradient, the previous and next
     numbers Plex Mono 8.5 sp at 45% above and below; slot 50 dp. **Drag up or down to step frames, 9 dp of travel per
     frame.** **Image / tile / sprite:** the feature switch: 16 dp glyph in a 26 dp cell, radius 8; armed = 1.5 dp cyan
     ring + 3 dp halo. Image/tile glyph = the seam-cut tile icon; sprite glyph = swap arrows;
  3. **lock:** padlock 16 dp in a 26 dp cell. Locked = closed. Unlocked = open + 1.5 dp amber ring, resize handles out.
     Animation with ≥ 2 frames = closed with a 5 dp nail dot; holding it offers "Move board with all frames" behind a
     confirm, one undo step;
  4. **export:** the Studio's export mark (K6), 16 dp.
- **The pill does not hop.** It stays on the left.
- **Title:** Archivo 700 12 sp, ink on paper with a 2 dp halo, left-aligned with the board, baseline 7 dp above the
  line. It is the export's file name; tap to rename.
- **Size:** one corner readout, `1920 × 1080`, Plex Mono 500 9 sp, ink on paper with halo, right-aligned to the board's
  right edge, 8 dp above the line. Locked: 60%. Unlocked: full ink with a dotted underline, tap to type; typing shows a
  1.5 dp cyan ring and caret. **Typed sizes grow right and down from the top-left corner** (§H1).
- **Resize handles** (unlocked only): 8 dp squares, radius 2, drawer ink, 1.5 dp cyan ring, entirely outside the pixels.

### K4. While drawing: pen-down fade

Pen down anywhere on a selected board: the pill, title and size fade to **12%** in 120 ms, and the animation extension
folds to its **tick ruler** at 35%. All return 300 ms after the pen lifts. Nothing moves.

### K5. The animation extension (selected only)

- **Peg tab fused to the strip:** chrome, 136 × 24 dp, radius 11/11/3/3, centred under the board, 8 dp below the line,
  overlapping the strip's top by 3 dp. Three pegs, 44 dp apart: **left flat peg = onion skin** (32 × 12, radius 6),
  **centre round peg = play / pause** (17 dp), **right flat peg = loop mode** (loop → ping-pong → once). Pegs are 1.5 dp
  drawer-ink outlines with an 9 dp glyph; onion on = cyan ring; playing = pink ring + pause glyph.
- **Film strip, outline:** no fill. 40 dp tall: 7 dp sprocket rails top and bottom (4 × 4 dp outlined holes every 9 dp),
  26 dp cells. A cell is **44 dp per held tick** minus a 4 dp gap; number Plex Mono 600 9 sp; outline 1 dp ink @ 55% with
  a contrast halo. Current frame = 1.5 dp pink ring + pink number. Hold > 1 = amber `×N` at the top-right. Lifted cell
  = ×1.1, −2°, 1.5 dp amber ring, shadow; insertion caret = 2 dp cyan. Last cell = dashed `+` (tap duplicates, hold =
  blank / link / hold / delete, R33). Width = the board's width, scrolling, fading out over the last 18%.
- **Folded (K4):** 22 dp tick ruler, 14 dp per tick, current tick 2 × 12 dp pink with its number.

### K6. Export (app-wide)

- **One export icon everywhere in Joy Creator:** the Studio's film-strip export mark,
  `app/src/main/res/drawable/ic_export_studio.xml` (the owner's `Export-film-icon.svg`). Studio gradient when it sits on
  the paper or the app ground; drawer ink on chrome. No tray, no floppy.
- **Animation board:** the Studio's export sheet (`FaditorEditorActivity.showExportConfirmation`), glass, 236 dp tall,
  20 dp top corners: *Animation (all frames)* · *A range of frames* · *This frame*, format chips (GIF, PNG frames, sprite
  sheet, Send to Studio), the memory-budget line, one gradient Export button. "Range" and "this frame" are added to the
  **shared** sheet, not a copy (§I).
- **Image board:** this board, or all image boards. **Sprite board:** sheet + JSON, or open in SpriteLab (JB-4.03d).

### K7. Tile board, armed (the owner's ruling replaces §B21's "repeats around it")

- Arming tiling **replaces the whole visible canvas with the tile**, repeated edge to edge at full strength: no fade, no
  seam lines, no hatch, so you judge the pattern zoomed out exactly as it will look. It is still a preview: nothing
  outside the tile is ever paint or export (§B21–B22 unchanged).
- **Everything else disappears** (pill, title, size, other boards' tabs). What remains: the **selection ring around the
  original tile** (K3's ring) so you keep your bearings, and the **tiling switch, on**, in a 34 dp chrome pill at the
  pill's place.
- **The switch can never be lost:** when the original tile is panned off-screen, the switch docks to the nearest screen
  edge (8 dp in) and rides there until the tile comes back. *(Lead's addition, so the one way out is always on screen.)*
- Every stroke anywhere wraps into the tile (§B22). The first time tiling is armed, one toast says smudge, fill and
  the eyedropper do not wrap yet (§C6), until they do.

### K8. Sprite board

Grid shelf under the board: chrome, 30 dp, 8 dp below the line, left-aligned: `# | px` switch (on = sprite pink @ 70%),
columns `− 4 +`, `×`, rows `− 2 +` (22 dp steppers), sub-grid chip `▦ 2` (2–8, a drawing guide, never exported). Armed:
cells wiggle (±2.2°, 300 ms, alternate; off under reduced motion); a lifted cell = ×1.08, −3°, amber ring, shadow; the
target cell = cyan ring; drop swaps all layers of both cells, one undo step. Disarmed: tapped cells get cyan 15 dp order
badges top-centre; the playing cell = 2.5 dp pink ring + pink badge; the preview window (chrome, 84 dp wide, 56 dp art
well on paper, `12 fps`, play/pause, clear) sits above the board's top-right.

### K9. Layer column (animation board selected)

Each row gets a 16 dp marker column left of its thumbnail: **running man in the Studio gradient** = animates in this
board; **grey mountain** (`#A1A1AA`) = the same on every frame. Tap toggles. 13 dp glyphs.

### K10. Owner's answers (2026-10-01, verbatim)

> Your guard is very clever, so let's go with it. And yes to both of your questions. Hold on the play to set Fps. And
> also yes to your second question. It should just be the icon. And if you ever tap the pattern and then draw while its
> armed it sets the image icon to a pattern icon.

- **Guards (K2) confirmed:** brackets on pen approach, and the frame number when not on frame 1.
- **FPS:** **hold the play peg** opens a small fps stepper (Plex Mono, `−  12 fps  +`, chrome, above the peg). Tap play
  still plays/pauses.
- **Every board is icon-only at rest.** Image, tile and sprite boards lose their crop marks at rest and behave like K2's
  animation row: the icon only, and the soft brackets fade in when a pen comes within 48 dp. (The frame number applies to
  animation boards only.)
- **A board you have painted on while tiling was armed becomes a tile board:** its tab icon changes from the image
  icon to the tile (seam-cut) icon, and stays so after disarming. An image board that was armed but never painted on
  keeps the image icon. The board's data does not change (Tile is still an Image board, §B1-2); this is a display flag,
  `Board.tiled` set by the first stroke that wraps into it (saved with the drawing).
