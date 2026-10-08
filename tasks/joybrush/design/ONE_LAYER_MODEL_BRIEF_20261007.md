# Joy Brush: One Layer Type, Editable Strokes, Tile-Level Media

### Brief for the Project Lead

**Date:** 2026-10-07
**Written by:** Claude (chat assistant), from a brainstorming conversation with the founder. **Claude had no access to the code base.** Everything said about the code is inferred from status reports pasted into the conversation.
**Purpose:** give the Lead everything needed to evaluate three linked changes:

1. Remove the MEDIA layer kind, so every layer is the same kind of layer.
2. Move realistic-media state (water, pigment, graphite, oil relief) from the layer down to the tile.
3. Let one layer hold both editable vector strokes (Concepts-style) and raster pixels (Infinite Painter-style).

**Tags used throughout**

|Tag|Meaning|
|-|-|
|**DECIDED**|The founder has ruled on it.|
|**PROPOSED**|Claude's design. The Lead may change it.|
|**OPEN**|The Lead decides.|
|**VERIFY**|Needs a check against the code, or a measurement on the Note 9.|

\---

## 0\. Review request

The founder asked for two passes, and asks the Lead to do the same:

> "I want you to have an agreeable side where you're saying, yes, this can work, and here's how. And then also an adversarial check to make sure that we take into account all the ways this could be a bad idea, and if there isn't a better solution."

> "what I'm going to do is after I have this conversation with you and come to a conclusion, I'm going to then take it to the lead agent and have it overview it with the code base for any other potential problems."

1. **Agreeable pass:** for each proposal, say how it would be built in this code base.
2. **Adversarial pass:** find where it breaks, correct anything in this document that the code contradicts, and propose a better solution where one exists.
3. **Answer Section 10** (questions only the code can answer) and **Section 11** (Note 9 measurements) before any further wiring of the MEDIA kind.

The founder also licensed wide brainstorming: *"feel free to be a little bit wild and wacky with your ideas."* Section 9 is that.

\---

## 1\. Summary

1. **No layer kinds. \[PROPOSED]** A layer is a layer. The brush decides what gets recorded.
2. **Two kinds of content inside a layer, invisible to the user. \[PROPOSED]**

   * **Path items:** editable vector strokes and filled shapes (position, pressure, tilt, velocity retained).
   * **Patches:** sparse pixel tiles, as today.
3. **Realistic media becomes tile-level. \[PROPOSED]** A tile gains a wet payload only where a media brush (or its drip) touched it. The payload expires, so tiles dry. Saves hold no wet state.
4. **One sentence of user education. \[PROPOSED]** "Brushes that mix with what's underneath become pixels. Everything else stays editable." Plus: "Wet paint dries after a few seconds, when you change frames, or when you close the app."
5. **Two buttons carry the whole raster/vector boundary. \[DECIDED]** *Rasterize Down* and *Flatten Lower.*
6. **Two smudge brushes. \[DECIDED]** One ignores vectors; one rasterizes them on touch.
7. **Flood fill is a vector. \[DECIDED]**
8. **Hold step 4c** (canvas branch) and 4d (drawer) until the Lead has answered Section 10. The MEDIA kind is cheap to remove now because nobody but the founder has any drawings.

**Net UI change:** *added* two buttons, one extra smudge brush, a small "bakes to pixels" badge on pixel brushes, and one one-time toast. *Removed* layer kinds, the "counts as 7" layer cost, "this brush only works on a paint layer" refusals, and media-layer creation prompts.

\---

## 2\. Where the code stands (INFERENCE from status reports)

The Lead has ground truth; this table is Claude's reading of the transcripts.

|Step|Status|What it contains|
|-|-|-|
|1|Landed|Document version 8, MEDIA layer kind, float-tile cel keys, validation, smudge/push blocked on media layers.|
|2|Landed|Brush presets with engine `media`, 19 realistic brushes, shelves (Pencils, Watercolour, Oils), a slider for every tool number.|
|3a|Landed|Float stores per tile in the paint engine, undo for running water, delete/duplicate/clear carry float stores.|
|3b|Landed|Half-float (`.f16`) archive, per-medium lazy stores, \~256 MB media ceiling on the Note 9.|
|3c|Landed|Brush window (\~1024 px, \~5 cm): transient, lazy, freed after \~10 s idle, memory check before each stroke, \~4 cm spread cap.|
|4a|Landed|The "only paint layers have pixels" fixes (7 sites), brush gating (only media brushes paint on media layers; eraser passes through; fill/stamp refused), media layer counts as 7 in the layer count.|
|4b|In progress|GL plumbing: media paper accessor, `MediaCanvas` stroke driver, media eraser shaders.|
|4c|Not started|Canvas branch: strokes, eraser, auto-creating a media layer, tilt-to-run water.|
|4d|Not started|Drawer: brushes appear, opacity gets meaning, strip Eraser on media layers.|

Rules already agreed that this proposal keeps:

* A drip after pen-up belongs to the stroke that was last active; one Undo takes back what you just saw. Undo while wet stops the water where it is.
* An undo step whose water all dried is removed (no empty steps).
* Paint data is stored per medium, lazily (watercolour: `p0,p1,w0,w1`; oil: `p0,p1`, plus water when thinned; dry media: the paper store only).

### Keep / rework / remove

|Keep as is|Rework|Remove or retire|
|-|-|-|
|Float stores per tile; lazy per-medium stores; dropped dried water tiles|Float stores attach to a tile payload instead of a MEDIA layer|MEDIA layer kind and its validation|
|Brush presets, 19 brushes, shelves, sliders|Brush gating becomes "brush declares what it produces"|"Counts as 7" layer budget|
|Brush window, spread cap, memory precheck|One memory governor over window + wet tiles + undo + frame cache|Fill/stamp/smudge refusal on media layers|
|Undo rules above|Undo log gains stroke-edit entries|Layer-level media unavailable message|
|Media eraser shaders, `MediaCanvas`|Archive: look tiles plus oil relief only|`.f16` archive path becomes dormant behind a flag|
|Paper catalogue|Document version: reset or bump (no users, no migration needed)|256 MB ceiling as a layer ceiling (becomes a wet-tile working budget)|

\---

## 3\. The founder's words, and what each changed

The founder dictates; transcription errors are left as spoken. Entries are in the order they were said.

**Q1: the mental model**

> "my last working mental model is just having one type of layer. And so when I was going through its updates, hearing that there are multiple layer types, I was like, whoa, whoa, what is happening? The testing, I only had on one layer when I was testing the brushes."

→ A single layer type is the product model. The MEDIA kind is an implementation detail leaking into the product.

**Q2: the Apple standard**

> "if you had to build it so that the user could use any brush type on one layer, how would you build it?"
> "How could we make everything function still the same, but just, quote, just work, end quote, in the way Apple does it, where the user doesn't have to think. They can be brainless and just use one layer, and they don't have to know what's going, the magic going on in the back end. Because I know they'll be like, what? I have to keep track of which brush goes on which layer? And that's a bit of a UI penalty."

→ Any brush on any layer. Brush behaviour is chosen by the brush, never by the layer.

**Q3: boards (context)**

> "this app already has different boards, which is where you have a semi-infinite canvas, which you can select a region and have that region duplicate the sparse tiles so that you can animate frames in that region. And each frame will just count as more tiles that it cycles through."

→ Sparse tiles are already the unit of storage and animation. Per-tile payloads fit this design; layer-level kinds do not.

**Q4: per-tile mode**

> "why does the layer need a mode at all? We can just have whenever a tile gets touched with a fancy brush, it just goes into fancy mode for that tile. And that is a per tile thing. If a drip drizzles and touches and uh, a tile below it, that tile becomes a fancy tile."

→ Per-tile payload, with promotion on touch, including drips (Section 4.3).

**Q5: dry on reopen**

> "I'm wondering how many people are actually going to want to come back to a wet canvas after closing their app. They're probably just going to expect that it's dry."

→ Saves hold no wet state. Retires the half-float save format as a live path, the 256 MB layer ceiling, and the wet-reopen tests.

**Q6: oil**

> "I don't know if oil needs something else, if there's some other reason why we should keep the pixels in a different state for oil. Maybe thickness or impasto or something like that is important. But even still, that doesn't have to be the whole layer."

→ Oil keeps a persistent per-tile relief channel and does not dry on a timer (Section 4.4).

**Q7: stakes and risk**

> "there is nobody using this product but me right now. It's just in development, and I haven't shared it with anybody, so nobody's pictures are going to be broken by some sort of architectural change. But I have to make sure to get this right, because this is actually a pretty sophisticated and complicated app. And I am really scared about UI bloat. If there's just too many options, it may turn off people. Even more so. I already have a built-in video editor."

→ Two hard constraints: (a) changing the architecture now costs nothing in migration; (b) the UI budget is fixed. Every proposal below is judged against both.

**Q8: vector goals**
Claude asked: crisp at any zoom, or editable after drawing? Answer:

> "Both."

→ Both rendering resolution-independence and post-hoc editing are in scope.

**Q9: storage and headroom**

> "I find it very attractive that I can work on very large things and pan around and not have to worry about huge file saves. Because on a phone, I can be working raster, but a lot of phones no longer have removable storage."
> "it seems to me, lighter on storage because it moves most of the effort into, I'm guessing, memory and CPU or the graphics chip, which most phones seem to be able to easily handle, including my old Note 9, which is the floor."
> "we have to keep headroom for phones with less storage, and we also have to keep in mind headroom for phones with limited speed. But so far, my Note 9 has been able to do everything that I've been able to throw at it."

→ Storage and speed headroom are design inputs. The Note 9 is the performance floor. The "vector is smaller" trend is an assumption to measure, not assume (Section 11).

**Q10: animation boards**

> "with raster, I imagine, get the file pretty huge pretty quick if somebody's been drawing like 80 or 90 frames."
> "if they just read the wiki and it says, hey, for animation, if you want lower storage, try using our vector lines."
> "they only really need to worry about storage when they export, which is easy because you can just upload it to YouTube and then be done with it."

→ Stroke-based frames are a first-class animation workflow. Wiki guidance replaces UI for the storage trade-off.

**Q11: fold it in, don't build twice**

> "I would either have to have a, a, a separate section for vector work or just fold it in. That way I'm not having to rebuild everything twice and the an- animation tools uh, naturally flow in it."

→ Vector lives in the same layer system, undo, selection, and board system. No separate section.

**Q12: the design target**

> "concepts is basically my poster child for the vector side of everything. And Infinite Painter is my poster child for everything on the raster side. I'm basically trying to combine those two into one app that also does animation well."

→ Target: Concepts-grade stroke editing and Infinite Painter-grade raster painting, in one layer.

**Q13: what Concepts does (the founder's understanding from use, not from documentation, so \[VERIFY] against the Concepts UI synthesis paper)**

> "it's my understanding that concept stores your stroke with your tilt and your pressure. And so I can select a line, I can nudge any part of it with my little finger to nudge it, and it doesn't smudge the pixels, it actually moves the underlying vector line while keeping all the pressure, velocity, and tilt that was in the initial stroke, so that at any time I can change it, let's say from a pen stroke to a pencil stroke to a watercolor stroke to a filled shape stroke that isn't stroked at all, but just a filled shape."
> "have the ability to nudge things after the fact and round things over and grab them and move the tip of a line, uh, erase uh, hangover after intersections of brush strokes, and all sorts of other cool features that you just can't do with raster."
> "I want to be able to retain that."

→ Paths store samples (not fitted curves). The brush is a swappable field separate from the path. Filled-shape is a mode of the same item. Required edits: select, soft-falloff nudge, move a tip, recolour, swap brush, erase overhang at intersections.

**Q14: watercolour comes in two kinds**

> "Granted, concepts only makes a facsimile of watercolor. It doesn't have a sim, but one of its watercolor brushes is actually quite good and looks reasonable enough. But it would be just a standard vector brush."

→ A **stroke watercolour** (editable, non-simulated) and a **sim watercolour** (bakes to pixels) both exist. The user-facing naming problem is in Section 4.2.

**Q15: mixed workflow on boards**

> "I'm sure a lot of people will have a workflow where they paint the background and then use vector for their characters. As it is, you can select several layers to ignore the animation cycle so that they just remain as background. And other layers are the ones that are animating in your uh, animation board."

→ Raster background (static layers) plus vector characters (animated layers) is a primary workflow. Static layers are shared across frames, so the stroke-based frames are only the animated part.

**Q16: re-wetting**

> "What are the abilities that I'm losing or that I would miss by having things dry?"
> "Am I not able to re-wet pixels? It seems like if it's looking at whatever paper is selected for the project at that time, that re-wetting pixels would be just fine because it's just taking the pixels and lifting them off by all the same density metrics that the paper was before. But I could be wrong."

→ Claude's answer: correct for watercolour (approximately), wrong for oil. Detail in Sections 4.4 and 6.

**Q17: two smudges \[DECIDED]**

> "would have to be two smudge brushes, one that ignores vectors, and one that rasterizes them on touch."

**Q18: fill, selection, and the boundary buttons \[DECIDED]**

> "flood fill is best as a vector. vectors will be selectable in the same way as the concepts app so its as simple as a rasterize down button and a flatten lower button to collapes it and below to raster. I already have the concepts ui synthesis research paper."

→ The Lead should cross-check semantics of these buttons and of selection against that paper.

**Q19: non-simulated watercolour overlap \[OPEN, deferred to the Lead]**

> "'Non-sim watercolour can still overlap.' sounds technical id probably defer to lead or have a special"

→ See Section 4.2 and Red Team item 13 for a plain-language statement of the question.

\---

## 4\. The proposal

### 4.1 Data model \[PROPOSED]

```
Document
 └─ Layer  (one type: name, opacity, blend, mask, visibility, static-on-board flag)
     └─ Items, in time order, bottom to top
         ├─ Path item   : samples \[x, y, pressure, tilt, azimuth, t]  (the smoothed samples the user saw)
         │                brush reference, colour, size, params
         │                mode: stroked | filled shape
         └─ Patch run   : sparse pixel tiles, each with an optional payload (Section 4.3)

Tile cache (never saved): rendered pixels per tile, rebuilt from items
```

Rules:

* **Time order decides what covers what.** A raster stroke painted over a vector stroke covers it. A vector stroke painted afterwards sits above. There is no "above or below" setting for the user.
* **Consecutive pixel operations coalesce into one patch run.** A layer painted only in raster is a single patch run, the same as today. A layer drawn only in vector has no patch runs. Runs alternate only when the user interleaves, so the common cases cost nothing extra.
* **Brush is a separate field from the path.** Swapping pen to pencil to stroke-watercolour changes one reference and re-renders. Samples are stored after smoothing, so edits are stable.
* **A filled shape is a path item** with `mode: filled`, the Concepts behaviour the founder described. Flood fill produces one (Section 4.6).

### 4.2 Which brushes produce what \[PROPOSED]

The rule: **a brush produces a path item if its output depends only on its own stroke, the paper, and a deterministic blend mode. It produces pixels if it reads what is underneath in an arbitrary way.**

|Produces a path item (editable)|Produces pixels (baked)|
|-|-|
|Pen, ink, marker, technical lines|Sim watercolour (water, pigment, drips)|
|Stroke watercolour (Concepts-style, non-sim)|Oil with colour mixing|
|Filled shape and flood fill|Smudge (all) and smudge (pixels only)|
|Pencil **\[OPEN]**: a pure function of (samples, paper) if overlap uses a deterministic blend; Lead to check with the media session|Blur, liquify, clone/stamp, paste|

**OPEN: two watercolours in the drawer.** If a stroke watercolour and a sim watercolour both exist, the user must tell them apart, which is the "which brush goes where" problem moved into the drawer. Options:

|Option|Pros|Cons|
|-|-|-|
|a. Two named brushes, with a small "bakes to pixels" badge on pixel brushes|Zero modes; the badge tells exactly what the one-sentence rule needs|Two entries per medium|
|b. One brush with a "wet simulation" toggle in its settings|One drawer entry|A mode, and a hidden one|
|c. Sim only|Simplest|Loses the editable Concepts-style watercolour the founder named|

Claude's pick: **a**.

**OPEN: non-sim overlap (the founder deferred this).** Plain version: if two non-sim watercolour strokes overlap, the overlap should look darker, like real glazing. That can be done deterministically by compositing in time order with a multiply-type blend, so the strokes stay editable. The Lead decides whether the stroke-watercolour brush carries that blend, and whether blends inside a layer need an isolation rule.

### 4.3 Tile payloads \[PROPOSED]

* **Every tile always has pixels** (the "look"). That restores the invariant "a layer's content is pixels," so export, the layer column, file open, and fill need no media knowledge. That invariant failing was the cause of the seven "assumes only paint layers have pixels" sites.
* **A payload is optional.** It is created the first time a media brush touches the tile, **including by a drip or spread into a neighbour**. The founder's rule: a tile touched by a drip becomes a fancy tile.
* **Version check.** Each tile carries a version counter. Any pixel write (fill, smudge, transform, paste, erase) bumps it. A payload records the version it was built against. A mismatch makes the payload discard itself. A missed code path then cannot resurrect stale paint; it just loses wetness.
* **Drying.** A watercolour payload expires on any of: a timer after the water stops moving, app pause or background, frame change on a board, layer delete. The look tile already holds the colour, so drying means dropping the water and pigment stores.
* **Saving.** The file contains look tiles plus oil relief and nothing else. Saving does **not** dry the live session; the file is dry, and the user's wash keeps flowing.
* **Undo.** Pre-touch snapshots, merge of drips into their stroke's step, and removal of empty steps all stay as already built.
* **Memory.** Cost follows how much wet paint exists right now, not how many layers exist.
* **Archive code.** Keep the half-float archive behind a flag in case users later ask for wet reopening. **VERIFY** that it is cheap to leave dormant.

### 4.4 Drying and re-wetting by medium

|Medium|After drying|Persisted|Notes|
|-|-|-|-|
|Watercolour|Colour in the look tile|Nothing extra|Re-wetting (lifting) reconstructs pigment density from colour using the paper's grain, which is a function of canvas position. Approximate in two cases: glaze over glaze merges into one mixed pigment; staining and sedimenting pigments lift differently. If the project paper changes after baking, the old grain stays, so grain doubles.|
|Oil|Colour plus **height**|One byte per pixel of relief on touched tiles|Height cannot be recovered from colour. Oil should not dry on a timer; real oil stays workable for days. No water simulation unless thinned. **VERIFY** what `p0`/`p1` hold.|
|Pencil|Graphite depth baked into alpha|Nothing|Paper grain is positional, so it reconstructs. Heavy layering may saturate slightly differently. **VERIFY** with the media session.|

Wet-on-dry glazing needs no special code: baked pixels sit under the new wash.

### 4.5 Selection and the two buttons \[DECIDED; semantics VERIFY]

* **Selecting strokes follows Concepts.** Tap or lasso to select path items; move; nudge any part with soft falloff (samples are warped, nothing is smudged); drag a tip; recolour; swap brush (stroke-capable brushes only); delete; erase overhang at intersections.
* **Rasterize Down.** Claude's reading: bake the selected path items (or all path items of the layer if none are selected) into pixels.
* **Flatten Lower.** Claude's reading, from the founder's words "collapse it and below to raster": merge this layer and everything beneath it into a single raster layer.
* **VERIFY** both readings against the Concepts UI synthesis paper.
* These two buttons are the only place a user meets the stroke/pixel distinction.
* **One selection tool, two behaviours:** where strokes exist under the finger, select strokes; where only pixels exist, behave as the existing raster selection. No mode switch. **\[OPEN]**

### 4.6 Fill and smudge \[DECIDED; details OPEN]

**Flood fill as a vector.** At tap, flood the visible composite (with a gap-closing tolerance), trace the region's contour, and store it as a filled path item on the active layer.

* Gains: colour stays editable; no pixel write; no stroke is consumed; export stays crisp; the "fill refused on media layers" rule disappears.
* **OPEN:** the stored contour is fixed geometry. If the user later moves the line art that bounded it, the fill does not follow. A region bound to its surrounding strokes would follow but is much more work. Claude suggests fixed geometry first.
* **OPEN:** seams. Traced edges of antialiased line art can show a hairline gap. A 0.5–1 px expansion that tucks under the lines hides it.
* **VERIFY:** contour-trace time on the Note 9 at board resolution.

**Two smudges.**

* *Smudge (pixels):* reads and writes pixel content only; path items are untouched.
* *Smudge (all):* any path item whose footprint meets the smudge is baked into pixels **whole** and removed from the item list, then smudged. One-time toast: "That stroke became pixels."
* Consume the whole stroke, never part of it. A partial bake leaves a ghost copy behind when the stroke is later moved.
* **OPEN:** where do pixel-only tools write when patch runs are interleaved with paths? Simplest reading: the tool operates on the patch run that contains the touched pixels and leaves paths untouched.
* **OPEN:** other pixel-reading tools. Suggested default: each tool declares one flag, *ignores paths* or *consumes paths*. Colour adjustments can apply to stroke colours directly (exact and lossless). Spatial filters (blur, sharpen) bake first.

### 4.7 Rendering and cache \[PROPOSED]

* The tile cache is the only pixel store for path content. It is rebuilt from items, never saved, and invalidated by dirty tiles.
* Paths replay through the existing brush stroke stream. The Lead reports that the live path already consumes a stroke stream, so journalling at pen-up should be cheap. **VERIFY**
* On boards: a bounded cache, rendered ahead of the playhead, sized by device class. The Note 9 is the floor. Arithmetic: 80 frames of 1080p RGBA is about 663 MB, which the Note 9 cannot hold.
* Optional: a hardware-encoded video proxy for scrubbing and playback, since the app already has a video editor.

### 4.8 Storage format and efficiency \[PROPOSED]

* **Vector is not always smaller.** Dense hatching or scumbling can exceed compressed raster tiles. Line-art frames are probably small already under sparse tiles; painted frames are what grow. Measure before promising the wiki line (Section 11).
* Path samples: quantise positions, delta-encode, 8-bit pressure/tilt/azimuth, time deltas. Rough estimate **\[VERIFY]**: 6 to 8 bytes per sample, so a 100-sample stroke is under 1 KB before compression.
* Decimate at pen-up with a tolerance that keeps edits stable at the highest supported zoom. **VERIFY**
* **Frames:** copy-on-write duplication (a duplicated or held frame references the source's tiles and paths until edited), and content-hash dedupe of identical tiles across frames. **VERIFY** whether the board already does either.
* **One memory governor.** A single budget covers wet tiles, the brush window, the frame cache, and undo, with priority eviction. It replaces separate limits (256 MB media ceiling, window reservation, undo limit). **OPEN**
* **Layer count.** With the 7x rule gone, the "n of 32" counter is a plain count. Show a memory indicator only when the governor is near its limit.

### 4.9 Animation boards \[PROPOSED]

* A frame is its own path items and tiles. Static layers (the existing "ignore the animation cycle" setting) are shared by every frame, so the founder's raster-background-plus-vector-character workflow costs one background in the file.
* Leaving a frame is a natural drying trigger, so "frames dry for free."
* Because strokes are sample lists with timestamps, several animation features become cheap (Section 9).

\---

## 5\. How this simplifies, by area

|#|Change|UI effect|Efficiency effect|Consolidation effect|
|-|-|-|-|-|
|1|One layer type|Plain layer list; no kind badges; no "which brush on which layer"|Honest layer count|Removes MEDIA branches at the 7 sites; removes brush, eraser, fill, smudge refusal gates|
|2|Per-tile payload|None|Memory follows wet area, not layer count|Layer-level float store management disappears; tile-level code is kept|
|3|Dry on save|"Reopens dry" is the expectation, not a feature|Smaller saves for tiles that held float stores (estimate \~8x **\[VERIFY]**)|Drops half-float archive from the live path and the wet-reopen tests|
|4|Version counter per tile|None|Prevents stale paint|One invariant replaces N invalidation points|
|5|Path items in the same layer|No vector mode, no vector section; drawer unchanged|Line work scales with strokes, not pixels|One layer system, one undo, one selection tool, one board system|
|6|Flood fill as a vector|No refusal messages; fills stay recolourable|Fills are small and crisp|Removes a fill special case for media|
|7|Two smudges|One extra brush rather than a mode|None|Each pixel tool declares *ignores paths* or *consumes paths*|
|8|Colour adjustments act on stroke colours|No bake step|Exact, lossless|Reuses the same colour pipeline|
|9|Copy-on-write frames, tile dedupe|None|Boards of 80+ frames store shared content once|Uses the sparse-tile design already in place|
|10|Single memory governor|One consistent "close other apps" message|One budget instead of three limits|Replaces three separate ceilings|
|11|Visible wetness (wet paint looks glossy or darker)|Drying explains itself without teaching|None|Uses data already in the payload|
|12|Wiki line instead of UI|"For low-storage animation, use line brushes"|None|No settings screen|

\---

## 6\. What is lost, ranked, with mitigations

|#|Loss|Severity|Mitigation|
|-|-|-|-|
|1|Cross-session wet-into-wet|Low. The founder expects dry on reopen.|Accepted. Archive code stays dormant behind a flag.|
|2|Re-wetting fidelity: approximate for glaze-on-glaze and for staining vs sedimenting pigments; double grain if the project paper changes|Low to medium|Lift by inverting colour to pigment density using paper grain. Document it.|
|3|Sim strokes cannot be moved or recoloured afterwards|Medium|Offer a stroke watercolour for editable work; badge pixel brushes; one-sentence rule.|
|4|Strokes touched by *Smudge (all)* or *Rasterize Down* stop being selectable|Medium|Whole-stroke consume only; one-time toast.|
|5|Oil needs a persistent relief channel and no timer drying|Low|One byte per pixel on touched tiles only.|
|6|Pencil saturation differs slightly after baking|Very low|**VERIFY** with the media session.|
|7|Rework of steps 1–4a (MEDIA kind, gates, 7x counter, brush gating)|Medium|Most tile-level work carries over (Section 2). **VERIFY** the effort delta.|
|8|New work: path items, hit-testing, stroke editing tools, dirty-tile re-render, list-based undo|High, but this is the feature|Phased (Section 12). Journalling is cheap; the editing tools are the real cost.|
|9|Cross-device GPU float differences make sim strokes non-replayable|Not a loss|Patches are authoritative for sim; only non-sim strokes replay, and that is deterministic.|

**What remains as real trade-offs after mitigations:** (a) new work for stroke editing; (b) sim strokes are not editable; (c) re-wetting is approximate; (d) cross-session wetness is gone; (e) first-play render cost on large boards, mitigated by the cache and proxy.

\---

## 7\. Options considered

|Option|Pros|Cons|Verdict|
|-|-|-|-|
|**A. Current plan: MEDIA layer kind**|Mostly built; explicit|User-visible kinds; "counts as 7"; brush refusals; every tool and export path must know kinds; a vector kind would be a third kind; contradicts the founder's one-layer model|Retire|
|**B. Per-tile payload only; vector elsewhere**|Fixes the media UI cheaply|Vector needs a home: either a vector layer kind (kinds return) or a separate section (layers, undo, boards built twice)|Insufficient|
|**C. Path items plus patch runs in one layer, with per-tile payloads**|One layer type; one selection tool; vector and raster share undo and boards; media cost follows wet area|Largest new work: item list, journalling, cache, stroke editing|**Recommended**|
|**D. Separate vector section**|Isolated from the raster engine|Rebuilds layers, undo, boards, export twice. The founder rejected this ("rebuild everything twice").|Rejected|
|**E. Hidden auto-layers (the app silently makes a media layer)**|No kind visible at first|Ordering problem (is media above or below?); clutters the layer column; surprises the layer count; kinds return under another name. Part of the current 4c plan.|Rejected|
|**F. Wetness only in the brush window** (whatever leaves the \~5 cm window dries)|No persistent payload at all|A wash dries behind the pen as it moves; the founder's drip-promotion behaviour is lost|Not as the design. **Run as a Note 9 experiment, and keep as a fallback** if tile promotion costs too much.|
|**G. All vector; emulate sim**|One system|Sim cannot be re-simulated deterministically across GPUs; abandons the Infinite Painter side|Rejected|
|**H. All raster plus tile compression**|Simplest|No post-hoc editing; storage grows with frames|Rejected|

\---

## 8\. Red team

|#|Failure|Why|Fix|Severity|
|-|-|-|-|-|
|1|**Stale payload.** A pixel write leaves old paint to reappear on redraw.|Same bug class as the seven "only paint layers have pixels" sites, moved down to tiles.|Version counter (Section 4.3). A missed path then self-corrects.|High|
|2|**Ghost strokes.** A smudge bakes part of a stroke; moving the stroke leaves its old copy.|Partial consumption.|Consume whole strokes (Section 4.6).|High|
|3|**Drying trigger missing.** Android kills the app without a close event; an idle wash holds memory.|A close-time rule is not enough.|Timer + pause + frame change + layer delete.|High|
|4|**Save while wet**|Drying on save would stop a wash mid-flow.|The file omits payloads; the live session is untouched.|Medium|
|5|**Interleaved patch and path order**|Pixel-only tools need a defined write target.|Coalesce into runs; define the target (Section 4.6). **OPEN**|Medium|
|6|**Fill leaks and seams**|Gaps in line art; antialiased edges.|Gap tolerance; 0.5–1 px tuck under lines. **VERIFY** time.|Medium|
|7|**Heavy piles.** Editing one stroke under thousands is slow.|Dirty tiles must re-render everything above.|Cache the composite beneath the edited item per tile; cap strokes per tile before offering to bake.|Medium|
|8|**First-play cost**|The first pass over 80 frames rasterizes each from strokes.|Bounded cache, render-ahead, optional video proxy.|Medium|
|9|**Playback memory**|\~663 MB for 80 frames at 1080p RGBA.|Bounded cache sized by device class.|High on the Note 9|
|10|**Resolution mismatch on export**|Strokes export sharp at any size; patches are fixed density.|State the rule in the wiki. Mixed layers are sharp where strokes are, soft where sim paint is.|Low|
|11|**Cross-device look**|GPU float differences.|Only non-sim strokes replay. Patches are authoritative.|Low|
|12|**Vector bigger than raster**|Dense hatching.|Measure; decimate at pen-up; cap sample density.|Medium|
|13|**Non-sim overlap** (founder deferred)|Overlap must be deterministic to stay editable.|Time-order composite with a multiply-type blend. **OPEN**|Medium|
|14|**Selection conflict**|Tap to select vs tap to paint.|Follow Concepts; **VERIFY** with the synthesis paper.|Medium|
|15|**Undo for stroke edits**|A nudge is many sample changes.|One gesture is one undo entry holding the delta.|Medium|
|16|**Two watercolours confuse users**|See Section 4.2.|Naming plus badge. **OPEN**|Medium|
|17|**Oil unknowns**|`p0`/`p1` contents not known to Claude.|Lead and media session confirm. **VERIFY**|Medium|

\---

## 9\. Wild ideas (brainstorm; each stands alone)

1. **Write-on animation.** Paths carry timestamps, so a line can draw itself on screen at no extra cost.
2. **In-betweening.** Resample two strokes to equal point counts and interpolate. Raster tools cannot offer this.
3. **Re-ink the whole film.** Swap the brush on every stroke of an animation in one action (pen to pencil).
4. **Global recolour.** Change a character's colour across 90 frames by editing stroke colours.
5. **Resolution-independent export.** Export vector frames at 4K with no upsampling.
6. **Video proxy playback.** Render the board to a hardware-encoded proxy and scrub that; memory stops mattering and strokes re-render only on change. The app already has a video editor.
7. **Time-lapse export.** Stroke timestamps make "watch me draw this" free.
8. **Journal as a recipe for sim strokes.** Keep sim stroke input as a recipe, with pixels remaining authoritative. It would allow re-rendering at a higher resolution or on a different paper while the tile is untouched. Costs storage; treat as a maybe.
9. **Level of detail for strokes.** Render decimated strokes when zoomed far out, to keep the Note 9 fast.
10. **Auto-bake suggestion.** When a tile exceeds a stroke-count threshold, suggest baking it rather than doing it silently.
11. **Visible wetness.** Wet paint renders glossier and darker, then settles lighter, so drying is self-explanatory.
12. **Node-style editing later.** Illustrator-style point editing can come as a separate tool; the sample model needs no fitted curves.

\*2 expanded: tweens. yes!

1. duplicate an animation frame with the new frame button.
2. move the vector lines in the second frame, nudge the vectors, change there color
3. up the timing of the first frame so that it is greater then 1 and tap the "tween" toggle - its frame changes color in the bottom scrubber bar - every vertext and every thing that continues to exits in the second frame gets an interpoliation, transforms like rotation rotate around there pivot, line paths recurve from a to be, color hsb slides from one to another etc.

\---

## 10\. Questions for the Lead (answers need the code) \[VERIFY]

1. List every pixel-writing path (fill, smudge, transform, paste, erase, flood, stamp, filters). Can a per-tile version counter bump on all of them?
2. Can the existing stroke stream be journalled at pen-up cheaply, and at which stage is smoothing applied?
3. Can the tile cache be rebuilt from items without touching the undo log?
4. What do oil's `p0` and `p1` hold? Is height among them?
5. Is pencil a pure function of (samples, paper), and does overlap use a deterministic blend?
6. Which parts of steps 1–4b does this invalidate, and what is the effort delta? Can the MEDIA kind be removed before 4c without losing the tile float stores?
7. Does 4b depend on the MEDIA kind, or is it layer-neutral (paper textures, window, `MediaCanvas`, eraser shaders)? Is it safe to land?
8. Do boards already share static layers and use copy-on-write or hash dedupe for frames?
9. Fill: where does gap closing live, and what is the cost of contour tracing at board resolution?
10. Where do pixel-only tools write when patch runs and paths interleave?
11. Do the Concepts UI synthesis paper's selection, nudge, rasterize, and flatten semantics match Sections 4.5 and 4.6?
12. Can window, wet tiles, undo, and frame cache sit under one memory governor?
13. Document version: reset or bump to 9? (No users, so no migration.)
14. Is keeping the half-float archive dormant behind a flag cheap?

\---

## 11\. Measurements on the Note 9 (the floor)

1. Bytes per frame on a real board, raster tiles vs the same frame as strokes. Test with line art and with painted frames.
2. Time to rasterize a journalled frame from strokes (50, 500, 2000 strokes; pen, pencil).
3. Frame cache memory for 80 frames at board resolution, plus render-ahead throughput during playback.
4. Wet payload memory during a large wash, including drip-promoted tiles, and time to dry.
5. Tile re-render time after editing one stroke under 1,000 others.
6. Contour-trace time for flood fill on a 2048 px frame.
7. Playback smoothness with and without a video proxy.
8. Experiment F (wetness only in the brush window): how does it feel to paint with, compared with tile promotion?

\---

## 12\. Suggested sequence \[PROPOSED; Lead decides]

|Phase|Work|Gate|
|-|-|-|
|0|Hold 4c and 4d. Let 4b land only if the Lead confirms it is layer-neutral.|Section 10 answered|
|1|Measurements in Section 11|Numbers on the Note 9|
|2|Remove the MEDIA kind. Float stores become tile payloads with the version counter and drying. Re-scope 4c to "any brush on any layer."|A pure raster layer is unchanged; media brushes work on a plain layer|
|3|Item list (paths plus patch runs), journalling, tile cache|Pure raster output is bit-identical to today|
|4|Stroke selection and editing; Rasterize Down; Flatten Lower; two smudges; vector fill|Section 4.5 semantics confirmed against the Concepts paper|
|5|Boards: copy-on-write frames, dedupe, playback cache, optional proxy; animation features from Section 9|80-frame board plays on the Note 9 within the memory budget|

Regression gate for every phase: one stroke is one undo, and a layer that contains only pixels behaves exactly as it does today.

\---

## 13\. Glossary

|Term|Meaning|
|-|-|
|Tile|The sparse unit of pixel storage.|
|Look|The visible pixels of a tile.|
|Payload|Optional extra per-tile state while a media brush is active (water, pigment, graphite) or persistent (oil relief).|
|Promote|Attach a payload to a tile because a media brush or its drip touched it.|
|Dry / bake|Drop the wet payload, leaving the look.|
|Path item|An editable vector stroke or filled shape stored as samples plus a brush reference.|
|Patch run|A set of consecutive pixel operations stored as sparse tiles.|
|Consume|Bake a path item whole into pixels and remove it from the item list.|
|Sim|A brush whose output depends on arbitrary reads of the pixels beneath it.|
|Non-sim|A brush whose output depends only on its stroke, the paper, and a deterministic blend.|
|Window|The \~5 cm working area that follows the pen while a sim brush runs.|



