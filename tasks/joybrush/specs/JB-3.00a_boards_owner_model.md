# JB-3.00a — Boards: the owner's model (region boards on the infinite canvas)

| | |
|---|---|
| **Tier** | T1 (architecture: it changes what an animated layer IS) |
| **Status** | 📝 Spec — Lead, 2026-10-01, from the owner's own description (quoted in full in §A). **Overrides** the parts of JB-3.01, JB-3.00, JB-3.06b and JB-4.01 that it contradicts; see §D. |
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

## E. Questions for the owner (each has the Lead's default, used until answered)

1. **Which layers animate in a board?** Default: every paint layer, with a per-layer "Same on every frame" switch for
   backgrounds. The alternative is choosing layers one by one when you make the board.
2. **"Move board with all frames"** (C3): offer it later, behind a confirm? Default: yes, later.
3. **Tile board while armed:** do strokes far from the board still wrap into it (your words: "anywhere on the
   screen"), or only strokes that start near it? Default: anywhere, exactly as you said. Painting elsewhere on the
   canvas means disarming first.
4. **Sprite cell moves:** swap two cells when you drop one on another, or push the rest along like a list? Default:
   swap.
