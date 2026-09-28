# R8: What the risky patents actually claim, and how Joy Brush can get the same look without them

**For:** Joy Brush (painting and animation wing of Joy Creator, Android, GPL-3.0)
**Date:** 2026-09-28
**Author:** research agent (Claude)
**Question from the owner:** "These are very simple primitive concepts. How can they be patents? Is there a legal way around them to get the same effects?"

> **This is not legal advice.** I am not a lawyer. This is an engineering reading of public patent text, meant to help plan the design and brief a lawyer. **A US patent attorney should review it before any commercial US launch**, and especially before anyone relies on the "lapsed" or "expires soon" findings below.

**Legend**
- **[CLAIM]**: read directly from the patent's granted claim text (Google Patents full text, fetched 2026-09-28).
- **[STATUS]**: from the USPTO legal-event record as mirrored on Google Patents, fetched 2026-09-28. I tried Justia, the USPTO fee site and the USPTO Open Data API as second sources; all refused automated access (403 or 401). **Every status is therefore single-source.** Re-check each one in USPTO Patent Center.
- **[SRC]**: another primary source (paper, source-code history, official docs). The URL is in §7.
- **[INF]**: my inference or recommendation.
- **[RECALL]**: from my background knowledge. I did not verify it in this session.

---

## 0. The short version

1. **The patents are much narrower than their titles.** Each one claims a *specific recipe*, not an effect. To infringe, a product must contain *every* step or part of at least one independent claim (the "all-elements rule"). Leave out one element and that claim is not infringed. [INF, standard patent law]
2. **One patent is already dead.** **US 8,296,668** (Adobe, "absorption channel" paper texture) **lapsed on 2024-10-23** because Adobe did not pay the 12th-year maintenance fee. The earlier estimate of "about 2030" in R2 is superseded. [STATUS]
3. **Two may lapse soon.** US 8,917,282 and US 8,917,283 (Adobe, polygon watercolour) show no 12th-year fee payment yet. The last day to pay (with a late surcharge) is about **2026-12-23**. Google's data can lag, so check this. [STATUS + INF]
4. **The rest are active** until 2028–2032 (table in §1).
5. **Every visible effect the owner wants can be built without these recipes:** smudge and colour pickup, a brush loaded with several colours, wet-paper absorbency, blooms, soft edges, and oil-style mixing. The design-arounds are ordinary, well-known raster techniques. Most of them shipped in open-source software years before these patents were filed, for example GIMP smudge (1999), MyPaint smudge (2005/2007) and Curtis watercolour (1997).
6. **None of these patents has a non-US family member** (Google Patents family data). They only matter for making, using, selling or importing in the **United States**. [STATUS/INF]

---

## 1. Status table

Expiry dates are Google Patents' "adjusted expiration". That means filing date + 20 years + patent-term adjustment, and it assumes all fees are paid.

| Patent | Owner | Priority date | Granted | Maintenance fees seen | Status (2026-09-28) | Ends |
|---|---|---|---|---|---|---|
| **US 8,296,668 B1** Paper texture for watercolor painting effects | Adobe | 2008-08-15 | 2012-10-23 | 4th, 8th paid; **12th not paid** | **Expired (lapsed for non-payment)**. "Patent expired due to nonpayment of maintenance fees under 37 CFR 1.362", recorded 2024-11-25, effective **2024-10-23** | Already ended |
| **US 8,462,173 B2** Pickup and reservoir model | Adobe | 2009-09-30 | 2013-06-11 | 4th, 8th, 12th paid (12th on 2024-12-11) | Active; no more fees due | **2031-08-12** |
| **US 8,599,213 B2** Configurable wetness, drying, mixing | Adobe | 2009-09-30 | 2013-12-03 | 4th, 8th, 12th paid (12th on 2025-06-03) | Active | **2031-02-28** |
| **US 8,654,143 B2** Non-uniform loading of brushes | Adobe | 2009-09-30 | 2014-02-18 | 4th, 8th, 12th paid (12th on 2025-08-18) | Active | **2031-03-27** |
| **US 9,030,464 B2** Simulating painting | Microsoft | 2010-04-08 | 2015-05-12 | 4th, 8th paid | Active. 12th-year fee window is open; due 2026-11-12, or with surcharge by 2027-05-12 | **2031-11-24** if the fee is paid |
| **US 8,335,675 B1** Natural media paints (height-field LBM) | Adobe | 2009-02-27 | 2012-12-18 | 4th, 8th, 12th paid (12th on 2024-06-18) | Active | **2031-04-24** |
| **US 8,605,095 B2** Vector output from bristle simulation | Adobe | 2010-04-12 | 2013-12-10 | 4th, 8th, 12th paid (12th on 2025-06-10) | Active | **2032-04-02** |
| **US 7,777,745 B2** Edge effect | Autodesk | 2007-04-27 | 2010-08-17 | Lapsed in 2014, then **reinstated** 2014-09-22; 8th and 12th paid | Active (reinstated) | **2028-09-14** |
| **US 8,917,282 B2** Separating water from pigment (polygons) | Adobe | 2011-03-23 | 2014-12-23 | 4th, 8th paid; **12th not seen** | Active, but inside the 6-month late-payment window [INF] | **2032-05-28** if paid; otherwise ends ≈ **2026-12-23** |
| **US 8,917,283 B2** Polygon processing | Adobe | 2011-03-23 | 2014-12-23 | 4th, 8th paid; **12th not seen** | Same as above | **2032-05-14** if paid; otherwise ends ≈ **2026-12-23** |

All [STATUS]. The late-window dates are my arithmetic from US maintenance-fee rules: fees are due 3.5, 7.5 and 11.5 years after grant, with a 6-month surcharge grace period. [INF]

**Other members of the same family:**
- US 2013/0120394 A1, "Natural Media Painting Using Automatic Brush Cleaning and Filling Modes", is a sibling application of the Adobe pickup/reservoir family. It was **abandoned on 2014-07-17** after a failure to respond to an office action. [STATUS]
- **What that means:** this Adobe filing never became a patent, so it does not stop auto-clean or auto-refill brush modes. Another patent might still exist, so the counsel's search in §6 should cover it. [INF]

**About a lapsed patent (US 8,296,668):**
- The owner can petition to reinstate it for "unintentional delay" (37 CFR 1.378). US 7,777,745 in this very list shows that reinstatement happens.
- If a lapsed patent is reinstated, 35 U.S.C. §41(c)(1) gives **intervening rights**. These protect anyone who began using the invention, or made substantial preparations to use it, in the US while it was lapsed.
- Adobe let it go roughly two years ago, and I found no petition in the record. **Lawyer to confirm.** [INF, RECALL of the statute]

---

## 2. Patent by patent: the claims, prior art and design-arounds

How to read each entry:
- **"Elements"** lists what a product must contain to infringe the independent claim(s). I paraphrase closely; the key words are quoted.
- **"We omit"** names the element Joy Brush will leave out.
- Where a patent has several independent claims (method / system / storage medium), they almost always carry the same elements in different legal wrappers. I note where they differ.

### 2.1 US 8,296,668: paper texture with a "dedicated absorption parameter channel" (Adobe). LAPSED

**Independent claims 1, 14, 24 and 34.** They are identical in substance; claim 34 adds "binary digital electronic signals" wording. [CLAIM]
- **E1.** Input representing a deposition of ink on a paper that has "a given paper texture".
- **E2.** The paper texture has "a plurality of parameter channels", one of which "is a **dedicated absorption parameter channel** that controls the absorption behavior of the paper".
- **E3.** "Determining a **blocking effect**" of the texture on the ink, and this depends on the absorption channel.
- **E4.** Simulating the ink's effect, and the simulation depends on the blocking effect.
- **E5.** Displaying the result (claim 34: storing it).

Dependent claims add the rest of the MoXi-like design [CLAIM]:
- lattice Boltzmann (claims 7, 18, 28);
- a surface/flow/fixture three-layer model (claims 5, 17, 27);
- grain and pinning channels (claims 11–12);
- noise on the absorption channel (claim 13);
- wax masking;
- evaporation.

**Prior art before 2008-08-15:**
- **Curtis et al., "Computer-Generated Watercolor", SIGGRAPH 1997.** "Paper texture is modeled as a height field and a fluid capacity field". The capacity is computed from the height field as c = h·(c_max − c_min) + c_min, and water spreads in a capillary layer until cells are "saturated to capacity c". [SRC, text extracted from the paper]
- **Chu & Tai, "MoXi", SIGGRAPH 2005 / ACM TOG 24(3).** Lattice-Boltzmann ink flow in absorbent paper, with paper-texture-dependent blocking and pinning. The Adobe patent itself cites MoXi (see R2). [SRC]
- My reading [INF]: the claimed advance over these was mainly making absorption its *own dedicated channel*.

**Design-around.** The patent has lapsed, so none is strictly needed. For belt-and-braces, and in case Adobe reinstates it:
- Use the **Curtis 1997 model**. Store a paper height map, *derive* capacity and absorbency from height with a formula, and put a *single global* "absorbency" slider on each paper type.
- That has no separate absorption channel, so it leaves out **E2**. [INF]
- **Visible result:**
  - Wet paint soaks in faster on "thirsty" papers.
  - Pigment granulates into the valleys of the paper.
  - Blooms and backruns appear where wet meets damp.
  - All of these come from height + capacity + global rate in Curtis's model.

### 2.2 US 8,462,173: brush with a pickup buffer and a reservoir buffer (Adobe)

**Independent claims 1, 10 and 15.** They are identical in substance. [CLAIM]
- **E1.** Input defining a brush stroke of a virtual brush.
- **E2.** Applying paint from "at least one of a **pickup buffer** … and a **reservoir buffer**" of the brush model. The reservoir holds "paint stored in the belly of a paint brush". The pickup buffer holds "paint that has been picked up by the paint brush".
- **E3.** "**Comparing an amount of virtual paint in the pickup buffer to a brush flow rate**".
- **E4.** "**If** the amount of virtual paint in the pickup buffer is greater than or equal to the brush flow rate", deposit from the pickup buffer "and **not** depositing any virtual paint from the reservoir buffer".
- **E5.** Storing the modified image.

This is narrower than earlier notes said. R2 summarised it as "two buffers with a configurable ratio". The real hook is the **pickup-first rule in E3 + E4**: dirty paint on the brush comes off first, and only then clean belly paint. Mixing both buffers is only a *dependent* claim (claim 3). [CLAIM]

**Prior art before 2009-09-30:**
- **GIMP smudge tool, imported into GIMP on 1999-07-01** ("Import of Smudge and dodge and burn tools"). It keeps a brush-sized "accumulation buffer" copied from the canvas and blends it forward. [SRC: GIMP git history, commit 5b5c24e9d]
- **MyPaint:**
  - "adapt color from image" on 2005-03-01.
  - Reworked into "smudge" and "smudge_length" on 2007-06-06. Its help text reads: "Paint with the smudge color instead of the brush color. The smudge color is slowly changed to the color you are painting on. 0.5 mix the smudge color with the brush color". [SRC: MyPaint git, commits a9e94467 and e17df081a]
  - Note that MyPaint's state is **one colour** (`smudge_r, smudge_g, smudge_b`) that is always mixed at a fixed ratio. It never compares an amount against the flow.
- **Baxter et al., dAb (SIGGRAPH 2001).** "A bidirectional, two-layer paint model", with brushes loaded from a palette. [SRC]
- **Corel Painter "Well" controls** (Resaturation, Bleed, Dryout) and **"Brush Loading"**, which lets brushes "pick up existing colors, hair by hair". [SRC for the features; that they shipped well before 2009 is RECALL]
- **ArtRage** (2004 onwards): oils that smear and carry canvas colour. [SRC for 2004 release; the mechanism is RECALL]

**Design-around (smudge / colour-pickup brush).** We omit **E3 + E4**, and preferably E2 as well. [INF]
- **What the brush carries:** a *single* "brush load" state per brush: one colour plus a paint amount. Optionally it can be a small low-resolution grid of such cells for streaky smears (see §2.5).
- **Each dab does this:**
  1. `load.color = mix(load.color, canvasColourUnderDab, pickupRate × canvasWetness)`
  2. `load.color = mix(load.color, selectedColour, refillRate)`
  3. `load.amount -= dryout`
  4. Deposit **one** dab of `load.color` with opacity from `load.amount`.
- **The rule that keeps this safe:** there is never a check of the form "picked-up amount ≥ flow → use only picked-up paint". There are also no two separately stored paint quantities.
- **Visible result:** the stroke starts clean, drags neighbouring colours along, goes muddy, then fades as it dries out. That is the Photoshop-Mixer-Brush / ArtRage look.

### 2.3 US 8,599,213: reservoir + pickup buffers with drying / wetness / mix-ratio parameters (Adobe)

**Independent claims 1, 9 and 15.** They are the same in substance, except that claim 9 drops the phrase "reflects a rate at which virtual paint dries on the virtual brush". [CLAIM]
- **E1.** Brush-stroke input. The brush is modeled with "a brush model that comprises **a reservoir buffer and a pickup buffer**".
- **E2.** Obtaining values for "one or more of" the following:
  - a **drying rate** (affects stroke length);
  - a **canvas wetness** (affects how much paint is picked up);
  - a **pickup mix ratio** (affects whether picked-up paint is mixed with other paint on the brush when re-deposited).
- **E3.** "Applying the brush stroke to **concurrently deposit virtual paint from both the reservoir buffer and the pickup buffer … independent of each other** … such that at least some of the virtual paint from the reservoir buffer … is deposited to the image **without loading** [it] into the pickup buffer".
- **E4.** The stroke's effect depends on the obtained values.
- **E5.** Storing the image.

**Prior art before 2009-09-30:** as §2.2. MyPaint's `smudge` (mix ratio) and `smudge_length` (how fast the carried colour adapts) are close functional cousins of the "pickup mix ratio". They date from 2007. [SRC]
- [INF] This prior art does not make the claim invalid by itself, because the claim adds the two-buffer, independent-deposit structure. It does show that the *parameters* are old. Only the *structure* is Adobe's.

**Design-around.** We omit **E1** (no two-buffer brush model) and **E3** (nothing is deposited "independently" from a reservoir that bypasses the carried paint). [INF]
- Use the single-state brush from §2.2. Every bit of reservoir paint is first mixed *into* the one carried state before it touches the canvas. That is the opposite of E3's "without loading … into the pickup buffer".
- We **can** still offer the sliders an artist expects: "Wetness", "Load", "Mix", "Dry-out". Those are E2, and E2 alone is not infringement. The claim needs every element.
- A lawyer may want to consider the doctrine of equivalents. The claim's explicit "independent of each other … without loading" wording makes a one-state design hard to call equivalent. [INF; lawyer to confirm]

### 2.4 US 8,654,143: loading a brush with several colours by sampling the canvas (Adobe)

**Independent claims 1, 9 and 13.** They are the same in substance. [CLAIM]
- **E1.** Input "**initiating a non-uniform paint loading mode**". Everything else happens "while in" that mode.
- **E2.** Input selecting "a region of an image **on a digital canvas**" whose paint has a non-uniform distribution of colours.
- **E3.** Loading the brush with a matching colour distribution "**without affecting**" the paint on the canvas (claim 13: "preserving" it).
- **E4.** "Obtaining **two or more samples** each comprising values of each of a plurality of canvas pixels under the virtual brush **as the virtual brush is swept over the selected region**".
- **E5.** Storing a colour in "each of a plurality of cells of a brush model". For each sample, the cells are updated "to reflect an addition of virtual paint".

The two-dimensional "stamp" version is only a dependent claim (claim 7), so it still needs E1–E5. [CLAIM]

**Prior art before 2009-09-30:**
- **dAb (2001):** "a palette interface, enables easy loading of complex blends onto the 3D virtual brushes". [SRC]
- **Corel Painter "Brush Loading" / multi-colour loading.** [SRC for the feature; pre-2009 date is RECALL]

**Design-arounds (colour-loaded brush).** Any one of these is enough. [INF]
- **A. Swatch/gradient loading (Expresii-style).** The artist taps two or three swatches, or draws a gradient in a small **loading widget that is not the painting canvas**. The brush tip receives that gradient across its width. This omits E2 (no region of the image on the canvas) and E4 (no sweep sampling).
  - *Visible result:* a brush that paints light on one side and dark on the other. This is the classic "double-loaded brush" of decorative painting.
- **B. Eyedropper to a uniform colour.** A single tap loads one averaged colour. This omits "non-uniform" and E4.
- **C. Let loading happen only through smudging.** The brush picks up colour as it paints, as in §2.2. That changes the canvas (it smears), so it omits E3. It is not a separate loading mode either, so it also omits E1.
- **Avoid:** a "load from canvas" mode where the artist drags the brush over their painting and the brush tip fills with the colours underneath while the painting stays unchanged. That is the claim.

### 2.5 US 9,030,464: 3D brush model + canvas-resolution 2D pickup map (Microsoft; Baxter, Chu, Govindaraju)

**Independent claim 1 (device).** [CLAIM]
- **E1.** A canvas "paint map" of cells holding colour and amount.
- **E2.** "A brush component that outputs a **three-dimensional** computer-implemented model of an image editing tool in contact with the … canvas". The footprint is where that 3D model touches the canvas.
- **E3.** A paint component that generates "a **two-dimensional paint pickup map** for the footprint". Its cells hold colour and amount of paint on the model.
- **E4.** "**Resolution of the paint pickup map matches resolution of the paint map**".
- **E5.** The pickup map is updated from the canvas cells as the model moves.

**Independent claim 13 (method)** adds [CLAIM]:
- sensor data for tool orientation;
- a "**deformation table** … of previously captured deformations";
- generating the 3D model from that table;
- a footprint;
- a pickup map with "matching resolutions";
- "bi-directionally updating" canvas and pickup values.

**Independent claim 17 (memory)** adds [CLAIM]:
- a 3D model generated from sensor data and "previously observed deformations of the paintbrush";
- a pickup map positioned over the footprint whose resolution "equals" the canvas paint-map resolution;
- updates as the tool moves.

**Prior art before 2010-04-08:**
- **GIMP smudge (1999)** already kept a brush-sized, canvas-resolution buffer copied from the canvas and carried along the stroke. That is E3–E5 without a 3D brush. [SRC: GIMP source at commit 5b5c24e9d, `accumPR` / `accum_data`]
- **dAb (Baxter et al., SIGGRAPH 2001)** had deformable 3D brushes with bidirectional paint transfer. [SRC] So did Baxter's later IMPaSTo work (2004) [RECALL].
- [INF] The patentable step was the specific *combination*: a 3D brush from captured deformations + a pickup map at matching resolution. Notice that the inventors' own earlier papers are the closest prior art.

**Design-around (smudge / pickup).** We omit **E2**: Joy Brush does not model the tool in 3D. [INF]
- The brush footprint is a 2D stamp or analytic 2D shape (an ellipse from tilt, pressure → size).
- It is not a rendered 3D bristle mesh, and it is not driven by a table of captured deformations.
- This alone clears all three independent claims, because each of them requires a 3D model.
- **Belt and braces:** make the pickup state **lower resolution than the canvas**. Examples:
  - a single colour (MyPaint / Krita "dulling" style);
  - an 8×8 or 16×16 grid scaled over the footprint.
  That also omits E4.
- Krita-style "smearing" (copying the canvas dab at full resolution) is still fine *as long as there is no 3D brush model*. It is essentially the 1999 GIMP design.
- **Visible result:** streaky smears that carry detail from the stroke's start.

### 2.6 US 8,335,675: several paint types simulated by height-field lattice Boltzmann (Adobe)

**Independent claims 1, 12, 23 and 34.** They are identical in substance. [CLAIM]
- **E1.** Deposition of a first paint type with a first set of fluid properties.
- **E2.** Deposition of a second paint type with a different set of fluid properties.
- **E3.** Simulating the motion of both, with each motion depending on its own properties and the two motions different.
- **E4.** "Using a **lattice Boltzmann method** comprising a streaming step and a collision step based on two spatial dimensions and a **height field**, … a fluid formulation **using the height field instead of fluid density**".
- **E5.** Determining the effect, and displaying it (claim 34: storing it).

**Prior art before 2009-02-27:**
- **Zhou, *Lattice Boltzmann Methods for Shallow Water Flows*, Springer 2004.** This is lattice Boltzmann in which water depth plays the role of density. [SRC for the book; the depth-for-density detail is RECALL]
- **MoXi (2005):** lattice Boltzmann for ink in paper, in the density form. [SRC]
- **Curtis (1997):** shallow-water watercolour on a grid, not lattice Boltzmann. [SRC]

**Design-around (wet paint flow, several media).** We omit **E4**: do not use lattice Boltzmann for the paint fluid. [INF] Instead use one of:
- a Curtis-style grid shallow-water solver (velocity + pressure relaxation);
- the Darcy / diffusion "lite" solver proposed in R2;
- the thin-film PDE in R1.

Several media with different viscosity are fine with any of these. Only the lattice-Boltzmann height-field formulation is claimed. Lattice Boltzmann with a *single* medium avoids E2, but is a weaker position; do not rely on it. **Visible result:** the same runny watercolour, thick slow gouache and drips.

### 2.7 US 8,605,095: vector output from per-bristle paths (Adobe)

**Independent claim 1.** [CLAIM]
- a brush tool "comprising a plurality of bristles" sweeping the canvas;
- "determining a path **for each bristle**";
- "generating a **bristle vector representation** of each path", built from transformed "unit bristle stamp instances";
- fill colour or pattern stored per bristle;
- "generating a vector representation of effects of the brush stroke based on the bristle vector representations".

**Independent claims 9 and 15** are broader. They drop the stamp and fill details, but still require per-bristle paths → per-bristle vector representations → a vector representation of the stroke. [CLAIM]

**Prior art.**
- Strassmann's "Hairy Brushes" (SIGGRAPH 1986) modeled per-bristle trajectories. [RECALL]
- Vector "skeletal strokes" (Hsu & Lee 1994; Creature House Expression) produced vector brush strokes, though not per bristle. [RECALL]
- I did not find clear prior art for per-bristle *vector export* specifically.

**Design-around.** We omit the per-bristle paths / per-bristle vector representations. [INF]
- Joy Brush output is raster, or implicit-field "vexel" rendering (R2).
- If we ever export vectors, build them from the **stylus path as a whole**: one outline or centreline per stroke, never one path per simulated bristle.
- **Visible result:** crisp scalable strokes are still possible. Only "every bristle becomes its own vector path" is off-limits until 2032.

### 2.8 US 7,777,745: edge effects on vector fills (Autodesk)

**Independent claims 1, 6 and 11.** They are identical in substance. [CLAIM]
- **E1.** An object "comprised of **vector geometry**".
- **E2.** "Creating **interior geometry** for a fill of the object".
- **E3.** "Creating **contour geometry for an outline stroke** that covers an edge of the object".
- **E4.** Calculating proximity to the edge for each pixel of the object *and* of the contour geometry.
- **E5.** Rendering an effect based on those proximities.

The dependent claims add two offset curves, tessellation, and low-frequency noise. [CLAIM]

**Prior art before 2007-04-27:** Curtis 1997 edge darkening, done "by decreasing the water pressure near the edges of the wet-area mask" in raster. [SRC] Many "watercolour filter" effects did similar things. [RECALL]

**Design-around (dark, feathery watercolour edges).** We omit **E1–E3**. [INF]
- **For painted strokes:** compute edge proximity **on the raster**, from a blurred or distance-transformed wet mask (Curtis-style). No vector object, no contour geometry.
- **For Joy Creator's vector shapes:** if animation shapes need a watercolour edge, **rasterize the shape to a mask first**, then run the same raster edge pass. Alternatively, render the shape with a signed-distance shader in one pass, which builds no separate outline-stroke geometry.
- Avoid tessellating an offset "outline stroke" band around the fill for this effect until **2028-09-14**.

### 2.9 and 2.10 US 8,917,282 and US 8,917,283: watercolour as growing polygons (Adobe)

**US 8,917,282, claim 1.** [CLAIM]
- Depositing "**water polygons** and … **pigment polygons**", with the water polygons deposited independently.
- Each pigment polygon's border is "defined by a plurality of **vertices**", each with a "local vector".
- "Rasterizing … the water polygons into a **wetness layer**".
- "Iteratively growing … the border" of each pigment polygon by "independently **moving each vertex** … according to a local vector … and a velocity", where the velocity is affected by a water polygon.

**US 8,917,282, claims 12 and 17** are similar, but the vertex details are replaced by "iteratively grow the border … such that growth … is affected by at least one deposited water polygon". [CLAIM]

**US 8,917,283, claims 1, 9 and 15** have the same polygon, water-polygon and wetness-layer structure, plus [CLAIM]:
- growing by moving vertices;
- rendering;
- "aging" by decrementing wetness until the polygons are dry;
- in claim 1: **resampling** the boundary to equal vertex spacing;
- in claim 15: rasterizing dry polygons into a dried-paint layer.

**Prior art.** This is DiVerdi et al.'s own "Painting with Polygons" (TVCG 2013) line of work. The claims are specific enough that I did not search for anticipating art. [INF]

**Design-around.** We omit the **polygons with moving vertices** and the **water polygons**. [INF] Joy Brush's watercolour is a raster grid simulation (Curtis-style, as planned in R1 and R2). Spreading, feathered and blooming edges come from grid flow and diffusion, not from pushing polygon vertices outward. **Visible result:** the same. Watch for a possible lapse on about 2026-12-23.

---

## 3. Recipes for the three effects the owner asked about

These are the recommended defaults. Each one leaves out claim elements of every patent above, and uses techniques with dated public prior art. [INF]

**A. Smudge / colour-pickup brush (oils, pastels, finger blending)**
- One carried "brush load" per brush: a colour plus an amount. It can optionally be a small low-res grid, but never a 3D brush and never two paint stores.
- Each dab:
  1. Mix the carried colour toward the canvas colour under the dab, at `pickup × wetness`.
  2. Mix it toward the selected colour, at `refill`.
  3. Reduce the amount by `dryout`.
  4. Stamp one dab.
- Lineage: GIMP 1999, MyPaint 2005/2007, Krita dulling and smearing modes (R3).
- Leaves out: '173 (the "pickup first when enough" rule), '213 (two buffers, independent deposit), '464 (3D model).

**B. Wet-paper absorbency (soak-in, blooms, backruns, granulation, soft or dark edges)**
- A raster grid.
- Paper = a height map. Capacity and absorbency are *derived* from height, times one per-paper absorbency slider (Curtis 1997).
- Flow uses shallow-water, diffusion or thin-film on the grid. No lattice Boltzmann.
- Edge darkening comes from the raster wet mask.
- Leaves out: '668 (lapsed anyway), '675 (no lattice Boltzmann), '282/'283 (no polygons), '745 (no vector contour geometry).

**C. Colour-loaded brush (double-loading, rainbow brushes, "tip in dark, heel in light")**
- A **loading tray** UI that is separate from the painting. The artist taps swatches or drags a gradient in the tray, and it maps across the brush width and/or along the stroke.
- A plain eyedropper loads a single colour.
- Brushes can still get "dirty" from the canvas through recipe A.
- Leaves out: '143 (no mode that samples the canvas by sweeping while leaving it untouched).

---

## 4. Plain-English explanation for the owner

### Why can "simple" ideas be patents?

1. **The title is not the patent.** The part that counts is the numbered "claims" at the end, and they are far narrower than the title.
   - "Pickup and reservoir model" *sounds* like "a brush that picks up paint".
   - What Adobe actually owns is a specific recipe: two separate paint stores on the brush, plus a rule that *if the dirty store holds at least one dab's worth of paint, use only that, and none of the clean paint*.
   - Other patents here work the same way. "Non-uniform loading" is really: a special loading mode + sweeping the brush over your picture + filling the brush's cells from repeated samples + leaving the picture untouched.
2. **You only infringe if you use the whole recipe.** Think of a claim as a recipe with 5–6 required ingredients. If your cake leaves out even one of them, it is not their cake. That is the "all-elements rule". A smudge brush in general is not claimed; that specific way of building one is.
3. **Why the office granted them.** Around 2008–2011 the US Patent Office granted many software patents for new *combinations* of known pieces.
   - Examiners search mostly other patents and papers, under time pressure. They often do not find old open-source code (GIMP, MyPaint) or shipping apps (Painter, ArtRage).
   - An idea can also be "new" on paper just because nobody had written down that exact combination.
   - Since 2014 (the *Alice* Supreme Court decision) and with stricter "obviousness" rules (*KSR*, 2007), some of these would probably have a harder time today. They are still valid until a court or the Patent Office says otherwise. [RECALL for the case law]
4. **The effect is not owned. The method is.** Nobody can patent "watercolour that blooms" or "paint that smears". They can only patent a particular machine or recipe for producing it. That is why a different recipe can give the same look legally.

### Why go around them instead of fighting them?

- **Cost.**
  - Challenging a patent at the Patent Office (an *inter partes review*) typically costs a few hundred thousand dollars in legal fees.
  - Defending a lawsuit in court commonly costs from about one million to several million dollars. [RECALL, order-of-magnitude figures from AIPLA cost surveys; not verified this session]
  - A design-around costs some engineering time, and here it costs almost no visual quality.
- **Uncertainty.**
  - In court, a patent is presumed valid. You must prove it invalid by "clear and convincing evidence".
  - At the Patent Office you can only use prior *patents and printed publications*. Shipped software such as GIMP or Painter counts only through its published documents. [RECALL]
  - "We do the same thing old software did" is **not by itself a legal defence** in the US. You still have to prove invalidity, which is expensive. [RECALL]
- **Time.** Most of these end in 2031–2032. One has already ended, and two might end this December. Designing around them now costs little, and we can simplify later.
- **GPL-3.0 note (from R2).** Even if Adobe or Microsoft offered a licence, a patent licence that cannot be passed on to every user is incompatible with GPL-3 distribution. So designing around is the only path that fits the licence anyway.

---

## 5. Corrections to earlier research notes

These notes do not edit R1 or R2. They record what should be read differently.

- **R2 §5 said** US 8,296,668 has an "estimated expiry ≈ 2030-05-20 if maintained". **It was not maintained:** it lapsed on 2024-10-23. [STATUS]
- **R2 §5 described** US 8,462,173 as depositing from both buffers "with a configurable ratio". The independent claim is actually the **pickup-first threshold rule**. Mixing is a dependent claim. [CLAIM]
- **R1 §4 and R2 §5 agree** with the claim text for 8,599,213, 8,335,675, 8,917,282/283, 8,654,143, 8,605,095 and 7,777,745.
- **R1 listed** 8,917,282/283 as "active to 2032". This needs re-checking after 2026-12-23 because the 12th-year fee was not seen.

## 6. What a lawyer should check before a US commercial launch

1. Confirm every status in USPTO Patent Center, especially:
   - the '668 lapse, and that no reinstatement petition has been filed;
   - the '282/'283 and '464 12th-year fees.
2. Review the design-arounds in §3 against the full claim sets, including the doctrine of equivalents.
3. Run a fresh search for **later** Adobe, Microsoft, Corel and Escape Motions patents on:
   - WetBrush (US 9,600,907 is noted in R1);
   - thin-film drips (Herson et al. 2026);
   - Fresco "live brushes";
   - "Mixbox-like" pigment mixing.
   This report covers only the ten listed patents.
4. Search CN/HK filings (R2 gap). This matters only for those markets.

---

## 7. Sources

**Patent claim text and legal events.** All fetched from Google Patents on 2026-09-28; the legal events mirror USPTO data.
- US 8,296,668 B1: https://patents.google.com/patent/US8296668B1/en
- US 8,462,173 B2: https://patents.google.com/patent/US8462173B2/en
- US 8,599,213 B2: https://patents.google.com/patent/US8599213B2/en
- US 8,654,143 B2: https://patents.google.com/patent/US8654143B2/en
- US 9,030,464 B2: https://patents.google.com/patent/US9030464B2/en
- US 8,335,675 B1: https://patents.google.com/patent/US8335675B1/en
- US 8,605,095 B2: https://patents.google.com/patent/US8605095B2/en
- US 7,777,745 B2: https://patents.google.com/patent/US7777745B2/en
- US 8,917,282 B2: https://patents.google.com/patent/US8917282B2/en
- US 8,917,283 B2: https://patents.google.com/patent/US8917283B2/en
- US 2013/0120394 A1 (abandoned sibling): https://patents.google.com/patent/US20130120394A1/en
- Second-source attempts that failed with 403 or 401: patents.justia.com, fees.uspto.gov, api.uspto.gov. The USPTO full-text PDF service (image-ppubs.uspto.gov) answered, but I used the Google text.

**Prior art**
- Curtis, Anderson, Seims, Fleischer, Salesin, "Computer-Generated Watercolor", SIGGRAPH 1997. https://grail.cs.washington.edu/projects/watercolor/paper_small.pdf (text extracted: §4.1 paper model, §4.3.3 edge darkening, capillary flow)
- Chu & Tai, "MoXi: real-time ink dispersion in absorbent paper", ACM TOG 24(3), 2005. https://dl.acm.org/doi/10.1145/1073204.1073221
- Baxter, Scheib, Lin, Manocha, "DAB: interactive haptic painting with 3D virtual brushes", SIGGRAPH 2001. https://dl.acm.org/doi/10.1145/383259.383313 ; https://research.google/pubs/dab-interactive-haptic-painting-with-3d-virtual-brushes-2/
- Zhou, *Lattice Boltzmann Methods for Shallow Water Flows*, Springer 2004. https://www.springer.com/gp/book/9783540407461
- GIMP git history (gitlab.gnome.org/GNOME/gimp): commit 5b5c24e9d, 1999-07-01, "Import of Smudge and dodge and burn tools", `app/smudge.c` with `accumPR` accumulation buffer.
- MyPaint git history (github.com/mypaint/mypaint):
  - a9e94467, 2005-03-01, "+adapt color from image";
  - e17df081a, 2007-06-06, "reworked smudge (previously called 'adapt color from image')", which adds the `smudge` and `smudge_length` settings and the single `smudge_r, smudge_g, smudge_b` state.
- MyPaint first release 2005-03-12: https://en.wikipedia.org/wiki/MyPaint
- ArtRage began in 2004: https://en.wikipedia.org/wiki/ArtRage ; https://www.artrage.com/press-section/ambient-design/
- Corel Painter Well controls and Brush Loading: http://product.corel.com/help/Painter/540215550/Main/EN/Win-Documentation/Corel-Painter-Well-controls.html ; https://product.corel.com/help/Painter/540213829/Main/EN/Win-Documentation/Corel-Painter-Using-Brush-Loading.html
