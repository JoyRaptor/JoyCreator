# R1 — Rebelle (Escape Motions): paint-simulation engine, lineage, and how to replicate it on Android

Author: research agent (Claude), 2026-09-28. Scope: Rebelle's wet/dry media engine, the academic lineage, permissive color-mixing options, patent/licensing landscape, and a concrete GLES 3.x design for a "Joy Paint" wet-media layer that runs on Galaxy Note 9 / Note 20 / Tab S devices.

**Legend.** **[C]** = confirmed by the cited source (quoted or closely paraphrased). **[I]** = my inference or engineering estimate; not stated by any source. **[M]** = from my own memory of a paper that I could not re-fetch during this session (paywall or timeout). Treat [M] as likely-correct but verify before relying on it.

---

## 0. TL;DR

1. **Rebelle's watercolor is a CPU simulation, not a GPU one.** It is C++ with SIMD (SSE2/SSE4.1/AVX2) and multi-threading. OpenGL is used only when NanoPixel display is on. The team made it responsive by **pausing the fluid simulation during brush strokes**. Only the **active layer** is simulated, and switching layers "fast dries" the layer you leave. The manual separates **paint water** from **canvas wetness**, which is the same split as Curtis's surface-water and capillary layers. Drips come from a **separate "DropEngine"** that is coupled to the grid simulation. [C]
2. The **user-facing model** Rebelle exposes is a good specification for us. Per paper preset it offers Absorbency, Re-wet, Texture Influence, Edge Darkening, Diffusion Speed, Create Drips / Drip Size / Drip Length, and Granulation (four textures, with Strength and Density, "revealed when the painting is drying"). Global controls are Tilt (angle and direction, optionally from the accelerometer), Blow, Wet/Dry/Fast-Dry layer, Pause Diffusion and Show Wet. [C]
3. **Rebelle's color mixing ("Pigments", Rebelle 5 Pro and later) is Mixbox** (Sochorová & Jamriška, SIGGRAPH Asia 2021). Mixbox is **CC BY-NC 4.0 and needs a paid commercial license**. That is incompatible with a GPL-3.0 app and with commercial use, so **do not use Mixbox**. Permissive alternatives: single-constant Kubelka–Munk on RGB (public since 1931; Curtis's patent has expired), **libmypaint's 10-band spectral WGM mixing (ISC license)** and **spectral.js (MIT)**. [C]
4. **Replicable algorithm.** The core should be Curtis et al. 1997: wet-area mask, surface water, suspended pigment, deposited pigment and a capillary layer, plus Curtis's **edge-darkening "FlowOutward"**, **granulation-aware pigment transfer** and **capillary backruns**. For phones, replace the staggered-grid Navier–Stokes with a **conservative, potential-driven (Darcy-like) water flux** that carries pigment. Add a **particle drip system** and **3-channel K–M rendering**. On Tab S8 and newer the water solver can optionally be upgraded to Stuyck et al.'s mobile shallow-water solver or to Herson et al.'s 2026 thin-film dripping model. [I, built on C sources]
5. **Performance reality.** A full-canvas simulation at 2048² is about 20–30 ms per step on a Note 9 class GPU. That estimate comes from scaling Stuyck's measured iPad Air 2 numbers and from a separate memory-bandwidth estimate. So: **simulate at half resolution, only on active tiles**, run 1–4 steps per frame, and take crisp detail from a full-resolution paper/granulation texture at deposit and display time. With these rules, the simulation costs about 2–7 ms per frame on a Note 9. [I]
6. **Layer coexistence.** The wet state is a **transient, sparse overlay** attached to one "paint layer". When it dries, its deposited pigment is baked into that layer's ordinary tiles. The recommended tile format for wet-media layers stores **pigment (K/S or absorbance) instead of display RGB**. It is composited with K–M or multiply at display time, which makes re-wetting and pigment-correct layering lossless. "Rasterize to RGBA" is available for export and interop. [I]
7. **Patent watch (not legal advice).** Active Adobe patents cover polygon-based watercolor (US8917282/US8917283, expire 2032), height-field lattice-Boltzmann for multiple paint types (US8335675, 2031), brush reservoir+pickup buffers with drying/wetness/pickup-mix parameters (US8599213, 2031), and WetBrush bristle+liquid simulation (US9600907). The Curtis watercolor patent (US6198489, filed 1998) has expired. I found **no Escape Motions patents**. [C, with caveats]

---

## 1. Rebelle: what can be established

### 1.1 Company, people, stack, history

- **Company.** Escape Motions is based in Slovakia. Peter Blaškovič founded it in 2011 and drives its painting software; earlier products were Flame Painter and Amberlight. [C: Rookies interview; CG Channel]
- **Origins.** "Before the first version of Rebelle was released, Peter Blaškovič spent over two years experimenting with watercolor simulation", described as "the most turbulent period for Rebelle's fluid engine". Rebelle 1 "reused many building blocks from Flame Painter". [C: 10-years interview]
  - An early **browser prototype written in Processing** still exists. It supports paint, smudge, blend, water, dry, blow and tilt, and it keeps separate wet and dry states. The page warns that it is "quite compute-intensive" and recommends an i5 or i7 CPU. [C: experiments page]
- **SIGGRAPH 2016 Appy Hour** (Blaškovič & Fapšo): "Its watercolor simulation is based on real-world color mixing, blending, wet-diffusion, and drying." Wikipedia, citing that abstract, adds that "the fluid simulation is based on Navier-Stokes equations" and that "watercolor simulation consists internally of multiple layers, used to calculate water and paint simulation, wetting, drying, and re-wetting". [C: SIGGRAPH history page; Wikipedia]
- **Team roles.** Michal Fapšo (software architect; "helped create the mathematics and physics behind Rebelle's watercolor simulation"), Miroslav Sedlák (lead developer), Martin Surovček (NanoPixel lead), Pavol Obuch (developer). [C: 10-years interview]
- **Stack and scale.** "C++ for our core functionality and Qt for the user interface". The codebase grew from about 120k lines (Rebelle 1) to over 600k lines (Rebelle 7), across 107 versions. [C: 10-years interview]
- **Versions.** Rebelle 1 (May 2015), R3 (2018: new watercolor code and the DropEngine), R4 (2020: SIMD, about 4x faster), R5 (Dec 2021: Pigments/Mixbox and NanoPixel), R7 (metallics, paper height maps), R8 (8.1 in Aug 2025 through 8.3 in Aug 2026: bristle brushes, RealShader, SoftShadows, NanoPixel 2). [C: blogs, CG Channel, Wikipedia]
- **Platforms.** Windows and macOS only. **There is no Android or iPad version.** [C: CG Channel 8.3; manual]

### 1.2 Engine execution model (CPU vs GPU)

- **The simulation is CPU-based.** The Rebelle 4 blog says it uses "vector instructions to perform a single mathematical operation on multiple numbers", detects "SSE2, SSE41, and AVX2" at startup and uses multiple cores, for "more than 4x faster painting". The interview confirms "vectorization and multi-threading" and says it runs acceptably on a "10-year-old development computer". [C]
- **GPU only for NanoPixel display.** From the manual: "When the nanopixel technology is in use, Rebelle uses OpenGL for rendering. Turn NanoPixel off to use the CPU instead." Rebelle Pro requires OpenGL 3.3. [C: R7 manual pp. 132, 211]
- **Latency trick.** The team "paused the fluid simulation during brush strokes and use[d] the full CPU for painting", which made painting faster and more responsive. [C: 10-years interview] So flow and diffusion mostly happen between strokes and after pen-up. This is a key UX and performance pattern to copy.
- **Only the active layer is simulated.** "When you switch layers, the actual layer will 'fast dry' (the water is dried, but the canvas remains wet) and after that, the new layer is selected." [C: manual p. 111]
  - [I] This strongly suggests a single live simulation buffer set per document, attached to the current layer.
- **Canvas size and cost.** A staff member ("Vevo", 2019): "18k x 12k px canvas size is quite much for Rebelle to handle… performance depends mainly on… RAM and processor." The manual warns that large resolutions slow things down "especially if you use a lot of water". Community advice is to paint at about 5k px or less and upscale with NanoPixel. [C: forum; manual p. 4442 region]
- **Other limits.**
  - Brush textures are stored up to 2048² but painted at most 1024². [C: manual]
  - Maximum brush size went from 700 px to 3000 px in Rebelle 8. [C: CG Channel; Digital Production]
  - Recommended hardware: "gtx760 for FullHD, gtx1060 for 4K screen". Minimum is 4 GB RAM; 16 GB is recommended. [C: manual]

### 1.3 Watercolor, ink and gouache: observable model and exposed parameters

From the Rebelle 7.1.1 manual [C]:

- **Brush-level controls.** Size, Opacity, **Water** ("lower numbers define a dry brush while higher numbers simulate color applications that readily spread and drip"), Pressure, and **Length** (the brush "runs out of paint"; 100 means infinite).
- **Paint modes.** Paint, Paint & Mix, Paint & Blend, Blend and Erase.
  - Mix and Blend are driven by pressure curves. "Paint Pressure Threshold" is the relative drop from the stroke's maximum pressure that switches from painting to mixing (default 10%).
- **Watercolor mixing color modes.** Transparent ("light passes through… partially similar to multiply"), Semi-transparent, and Opaque (gouache-like). These are disabled when Pigments are on.
- **Rendering modes.** "Normal" builds up pigment until full opacity is reached. "Glaze" builds transparent layers, limited by opacity.
- **Water sources.**
  - Wet the Layer; Wet All Visible (wets only the painted strokes).
  - Water tool and Dry tool (the Dry tool has an Absorbency setting and a "Keep Layer Wet" option).
  - Fill tool with a "Wet the Layer" option.
- **Drying controls.**
  - **Dry the Layer** removes paint water and canvas wetness.
  - **Fast Dry** removes the water from the painting "but the canvas remains wet".
  - **Pause Diffusion** stops the simulation.
  - This implies at least two water quantities: **surface water in the paint** and **canvas or paper wetness**. [C for behavior; mapping to Curtis's two layers is I]
- **Show Wet.** A light-blue overlay whose darkness is proportional to the amount of water.
- **Interaction rules.**
  - "The wetter the canvas and color, the more color runs."
  - "Dry tools (pastel, pencil, marker and airbrush) don't diffuse on less wet paint; they diffuse according to how wet your painting is." So dry media laid on wet areas enters the simulation.
- **Paper settings (Visual Settings, per paper preset).** Presets are Default, Hot Pressed, Cold Pressed, Rough and Japanese.
  - **Absorbency (0–10)**: "how fast the paper absorbs the washes. When set to 0, the washes diffuse for a longer time."
  - **Re-wet (1–10)**: low values make new paint blend softly with the paint below; high values make new paint "rewet the paint below faster and create strong watercolor edges".
  - **Texture Influence (0–10)**: how much the paper texture shapes washes.
  - **Edge Darkening (0–10)**: "how dark the edges… should get when dried… When set to 0, the edges don't darken during the diffusion."
  - **Diffusion Speed (1–10)**.
  - **Create Drips, Drip Size, Drip Length.**
  - **Granulation**: "Granulation will reveal when the painting is drying. You can choose from four different granulation textures", with Strength and Density. [I] So granulation uses its own textures, separate from the paper height map.
- **Paper height maps** (Rebelle 7): "Use Paper Heightmaps… use the actual peaks and valleys of the paper". "Paper Texture Scratch" chooses whether dry strokes catch light or dark parts of the texture. Display has separate "Paper Texture" and "Paint Texture" visibility controls. [C]
- **Tilt panel.** "Tilt affects the movement of wet paint". It has a direction and an angle, can be locked to the canvas, and can **use the tablet's accelerometer**. "The speed of the color runoff depends on the tilt of the canvas, amount of water and color on the painting." [C]
- **Blow tool.** "Blows a color that is wet in a direction of your stroke… Moving the blow tool over the wet painting blows the wet areas. In dry areas, it creates dripping effects." Rebelle 3 describes it as working "just like in real life by using a straw", and it splatters and mixes adjacent colors. [C]
- **DropEngine** (Rebelle 3). "In Rebelle 3 we implemented a new 'DropEngine' especially to simulate drips and connected it with our watercolor simulation." Drips are **DPI-aware** (the same physical size at any resolution) and react to "paper structure, water, stencils and selections". [C: R3 blog]
  - The marketing names it "DropEngine" or "watercolor DropEngine". I found **no source for the name "DropFlow"**; it is probably a misremembering of DropEngine. [C/I]
- **Stencils and selections mask water too.** "If you wet a layer where the stencil is placed the whole layer will get wet except the area covered by the stencil." [C]
- **"Natural Colors"** (Rebelle 4): "a fast and accurate way of implementing RYB color mixing", replaced by Pigments in Rebelle 5 Pro. [C]

**Takeaway [I].** Rebelle behaves like a Curtis-style layered simulation:

- a surface layer with water and suspended pigment;
- a deposited layer;
- a paper-saturation layer;
- a static paper height map;
- a separate drip particle system;
- a small, artist-facing parameter set per paper type.

Nothing public says whether it solves full shallow-water equations or a diffusion-dominated model. The Wikipedia/SIGGRAPH wording ("Navier–Stokes") suggests an SWE-type solver.

### 1.4 Oils, acrylics, impasto, bristles, lighting

- **Impasto.** Oil and acrylic have per-brush "Max. Impasto Height" and "Max. Impasto Smudge" (0–200). Global "Impasto Depth" and "Gloss" (0–10) apply at any time, so **height is stored, and lighting is applied at display time**. You can "Export impasto heightmap for advanced compositing". [C: manual; about page]
- **Metallic materials** are per layer (Rebelle 7 Pro), with reflectivity, scale and strength. [C]
- **Rebelle 8.**
  - "**RealShader**… using real photos as environment maps" (image-based lighting on thick paint).
  - "**SoftShadows**… raised brushstrokes cast ray traced shadows" (oil only).
  - A **particle bristle brush** engine: "simulate the movement and behavior of each individual strand… each hair can be done differently, or they can even influence each other". Bristle brushes "perform faster than textured" brushes.
  - [C: CG Channel; Digital Production; R8 bristle blog; 10-years interview]
- **Express Oils** are a faster oil variant. [C: manual]

### 1.5 NanoPixel

- "Technology for resizing paintings and canvases in real time based on machine-learning algorithms". It lets you zoom in "to see the detailed structure of every fiber in every Rebelle paper" (papers made from "special microscopic scans"). It exports 16x the pixel count (4x per side), e.g. 3000×2000 to 12000×8000.
- It needs OpenGL and a better GPU. NanoPixel 2 (Rebelle 8) goes up to 20x and caps exports at 32,000² except for TIFF.
- It "does not extend to custom user-created textures". [C: NanoPixel blog; manual; CG Channel]
- [I] What this means for us: it is ML super-resolution of the paint layer plus very high-resolution paper scans composited at display resolution. A cheap equivalent for Joy Paint is to **render paper grain and paper normals at screen resolution** from a high-resolution tiling texture, and upsample paint with an edge-aware filter. That gives much of the "zoom in and see fibers" effect without ML.

### 1.6 Pigments = Mixbox

- "Special credit goes to Šárka Sochorová and Ondřej Jamriška for their work on the pigment color mixing model." The underlying technology is **Mixbox**, published at SIGGRAPH Asia 2021 (ACM TOG 40(6), Article 234). [C: R5 pigments blog; ACM]
- Pigments are **per layer**. When on, they disable blend modes on that layer, and colors "blend with the layers below". [C: manual]
  - [I] So the compositor mixes pigment-space layers with the backdrop in pigment space rather than with RGB "over".

### 1.7 Patents by Escape Motions

- I searched for Escape Motions and Blaškovič as assignee or inventor and **found no patents** (searched Sept 2026). This is not proof that none exist. The engine appears to be protected by trade secret, not patents. [C for the search result; absence is I]

---

## 2. Academic lineage and what is replicable

| Work | Core idea | What to take for Joy Paint | Status / notes |
|---|---|---|---|
| **Curtis, Anderson, Seims, Fleischer, Salesin — "Computer-Generated Watercolor", SIGGRAPH 1997** | Ordered glazes. Each glaze has a **shallow-water layer** (u, v, pressure p, suspended pigment g_k), a **pigment-deposition layer** (d_k), a **capillary layer** (saturation s, capacity c), and a **wet-area mask M**. Main loop: MoveWater (UpdateVelocities, RelaxDivergence, FlowOutward) → MovePigment → TransferPigment → CapillaryFlow. **Kubelka–Munk** optical compositing. | The **data model and the effect recipes**:<br>- edge darkening: `p ← p − η(1 − M′)M` with M′ = K×K Gaussian blur of M (K = 10, 0.01 ≤ η ≤ 0.05);<br>- granulation-aware adsorption and desorption with density ρ, staining ω, granulation γ;<br>- backruns via thresholded capillary diffusion with per-cell capacity noise;<br>- drybrush via a paper-height threshold on the wet mask. | [C: paper fetched]. "Runs too slowly for interactive painting" in 1997. US6198489 (Univ. of Washington) was filed 1998-02-18 and is **expired**. |
| **Van Laerhoven & Van Reeth — "Real-time simulation of watery paint", CAVW 2005** (plus a 2004 layered/distributed version and "Brush up your painting skills", Vis. Comp. 2007) | Real-time Curtis-like three layers (shallow fluid, surface, capillary). The fluid is solved with a Stable-Fluids-style solver. Handles watercolor, gouache and Oriental ink. | The proof that a Curtis-class model runs interactively when restricted to active areas. | [C for title/venue/scope; details are M]. |
| **Stam — "Stable Fluids", SIGGRAPH 1999** | Semi-Lagrangian advection plus a pressure projection. Unconditionally stable. | Semi-Lagrangian velocity advection in an optional SWE tier. | [M] |
| **Baxter et al. — dAb (2001), IMPaSTo (NPAR 2004), Stokes Paint (2004)** | dAb: 3D deformable brush with bidirectional paint transfer (brush↔canvas). IMPaSTo: per-pixel paint volume/height and pigment, conservative advection, real-time GPU K–M rendering with a small number of spectral samples. | The **height + pigment per pixel** representation for oils and acrylics, and bidirectional brush pickup (prior art for reservoir/pickup models). | [C: IMPaSTo metadata; rest M] |
| **Chu & Tai — MoXi, SIGGRAPH 2005** | Lattice-Boltzmann ink dispersion in absorbent paper, with fiber-based pinning. | Another agent covers Expresii/MoXi. See the §4 patent note on LBM. | [M] |
| **Chu, Baxter, Wei, Govindaraju — "Detail-preserving paint modeling for 3D brushes", NPAR 2010** (Microsoft Research → Fresh Paint / Project Gustav) | Oil/pastel smear and mix by repeated paint exchange between a 3D brush and a 2D canvas. | Smudge/pickup design reference. | [C: MSR page] |
| **DiVerdi, Krishnaswamy, Měch — "Painting with Polygons: a procedural watercolor engine" (TVCG 2013)** | Watercolor as growing polygons whose vertices move with wetness. | **Avoid**: patented (US8917282/US8917283, active to 2032). | [C: patents] |
| **Bousseau, Kaplan, Thollot, Sillion — "Interactive watercolor rendering with temporal coherence and abstraction", NPAR 2006** | Image-space watercolor effects via a pigment-density modulation `C′ = C·(1 − (1 − C)(d − 1))`. Each effect layer is a gray texture T with `d = 1 + β(T − 0.5)`; there are separate layers for turbulence flow, pigment dispersion and paper, applied in sequence. "No physical simulation is performed." | The **cheapest way to get the look**: modulate color density with edge, granulation and turbulence maps. | [C: paper PDF fetched, eq. 1 and §4.1.2] |
| **Montesdeoca et al. — MNPR / "Art-directed watercolor stylization of 3D animations in real-time", C&G 2017** | Real-time hand tremor, pigment turbulence, color bleeding, edge darkening, paper distortion and granulation. Open source (Maya). | Shader recipes for stylized effects without simulation (Tier 0). | [C] |
| **Chen, Kim, Ito, Wang — "WetBrush: GPU-based 3D painting simulation at the bristle level", SIGGRAPH Asia 2015** | Bristle dynamics. "The liquid close to the brush is modeled by particles, and the liquid away from the brush is modeled by a density field". CUDA. | Conceptually the ancestor of Fresco's Live Brushes and of Rebelle 8's particle bristles. Far too heavy for phones at full fidelity. | [C]. Adobe patent US9600907 "Paintbrush and liquid simulation" (Kim, Carr, Chen). |
| **Stuyck, Da, Hadap, Dutré — "Real-Time Oil Painting on Mobile Hardware", CGF 2017** | **Shallow-water equations on GLES 3.0 fragment shaders** with ping-pong buffers, 16-bit half floats, and implicit height with 4 Jacobi iterations and semi-Lagrangian advection. `b` = paper height + dried paint height. Gravity comes from the **accelerometer**. Viscosity is a function of pigment density. Multiple pigment layers keep covered paint from mixing. | **The closest published blueprint for our target.** Measured on iPad Air 2:<br>- simulation 2048×1536: **33.4 ms**;<br>- 1024×768: **8.8 ms**;<br>- 512×384: **2.9 ms**;<br>- 45 fps with a 1024×768 simulation rendered at 2048×1536.<br>Memory: about 19 MB for 2 pigment layers, +6.3 MB per extra layer. Cost split: height 30%, brush stamping 27%, rendering 18%, advection 15%, velocity 10%. | [C: paper fetched] |
| **Herson, Paris, Michel (Adobe) — "Dripping Thin Films for Real-time Digital Painting", Eurographics 2026 (CGF 45(2))** | Reparameterized thin-film (lubrication) equation with a new "hydrophoby" cohesive term. Principled controls for **Thickness, Fluidity and Hydrophoby** (drip finger length, thickness, frequency). Pigment advection, diffusion and mixing. | The state of the art for **physically principled drips and runs under tilt**. Cost scales linearly: **88 µs at 256², 3.1 ms at 4096²** per step on an RTX 3080 Ti Laptop. | [C: paper fetched]. Open access (CC BY). Authors are at Adobe, so **check for related patent filings** before copying it closely. |
| **Sochorová & Jamriška — "Practical Pigment Mixing for Digital Painting" (Mixbox), TOG 2021** | RGB → latent (pigment weights of a fixed primary set + additive residual). Linear mixing in the latent is equivalent to K–M mixing. Fast LUT evaluation. | Excellent quality, but the **license rules it out** (§3). | [C] |
| Others | TAMU thesis "GPU programming for real-time watercolor simulation"; "Real-Time Watercolor Simulation with Fluid Vorticity Within Brush Stroke" (2017); GPU watercolorization filters (2021–2023). | Secondary references. | [C: search listings only] |

**Industry proof points.** Adobe Fresco's Live Brushes run watercolor and oil simulation on iPad-class GPUs. Stuyck et al. ran SWE oil on an iPad Air 2 at 45 fps. Mobile wet simulation is feasible; the constraints are **resolution, active area, and bandwidth**. [C for Stuyck; Fresco is common knowledge]

---

## 3. Color mixing: options and licenses

| Option | Quality | Cost | License | Verdict |
|---|---|---|---|---|
| **Mixbox** (Rebelle's Pigments) | Best-in-class RGB-gamut K–M | LUT texture plus a few ALU ops | **CC BY-NC 4.0**, commercial license on request | **Do not use.** Non-commercial plus extra restrictions conflict with GPL-3.0 redistribution and with any paid distribution. [C license; I compatibility] |
| **Single-constant K–M on RGB (3-band)** | Good for glazes, darkening and opacity. Blue+yellow only moderately green. | Trivial | Public math (Kubelka–Munk 1931). Curtis's use of it; patent expired. | **Use as the v1 base.** |
| **libmypaint spectral WGM** (`rgb_to_spectral` / `spectral_to_rgb`, 10 bands, weighted geometric mean) | Convincing subtractive mixing (blue+yellow → green) | About 10 pow ops per mix; suitable for brush-reservoir mixing, heavier per pixel | **ISC (permissive)**; the libmypaint COPYING says ISC-style | **Use for brush pickup/reservoir mixing and color-picker previews.** Port the functions to GLSL. [C: local libmypaint source `brushmodes.c`, `helpers.h`] |
| **spectral.js / spectral.glsl** (Ronald van Wijnen) | K–M with spectral upsampling (7 base curves, LHTSS-style), tinting strength | Moderate; a GLSL version exists | **MIT** | A good alternative or reference for the desktop web prototype. [C] |
| Re-implementing the Mixbox *idea* (own primaries, own latent fit) from the published paper | Close to Mixbox | Same as Mixbox | The paper is public. **I found no patent** covering it, but have counsel check. | Possible v2+, only after a legal check. [I] |

**K–M equations to implement** (Curtis §5; standard) [C/M]:

- Per channel, from absorption K and scattering S for a layer of thickness x:
  - `a = 1 + K/S`
  - `b = sqrt(a² − 1)`
  - `c = a·sinh(bSx) + b·cosh(bSx)`
  - `R = sinh(bSx)/c`
  - `T = b/c`
- Glaze compositing (layer 1 over layer 2):
  - `R = R1 + T1²·R2/(1 − R1·R2)`
  - `T = T1·T2/(1 − R1·R2)`
- From a user sRGB color C in linear space, with S chosen by medium (e.g. 0.05–0.2 for watercolor, 1–4 for gouache):
  - `K/S = (1 − R)²/(2R)`, so `K = S·(1 − R)²/(2R)`.
- Mixing: `K_mix = Σ c_i·K_i`, `S_mix = Σ c_i·S_i`. This is **linear in concentration**, so it stays exact under conservative advection. That is why the simulation should carry concentration-weighted K and S, not RGB. [I]

---

## 4. Patent and licensing landscape (not legal advice)

| Patent | Owner | What claim 1 covers (short) | Expiry | Risk to Joy Paint |
|---|---|---|---|---|
| US6198489 "Computer generated watercolor" | Univ. of Washington (Curtis/Salesin) | Shallow-water glazes with K–M compositing | Filed 1998-02-18, **expired** | None. Free to use. [C filing date] |
| US8917282 "Separating water from pigment in procedural painting algorithms" | Adobe (DiVerdi, Krishnaswamy, Měch) | Water polygons and pigment polygons; polygon borders grow by moving vertices per local vector and velocity, affected by water polygons | Active, adj. exp. **2032-05-28** | Avoid **polygon/vertex-growth** watercolor. A raster grid simulation does not match claim 1 as written. [C] |
| US8917283 "Polygon processing techniques in procedural painting algorithms" | Adobe | Same family: polygons plus a rasterized wetness layer, iterative polygon growth | Active, **2032-05-14** | Same as above. [C] |
| US8335675 "Realistic real-time simulation of natural media paints" | Adobe (DiVerdi, Hadap) | Two paint types with different fluid properties, simulated with a **lattice Boltzmann method on a 2D height field** (height instead of density) | Active, **2031-04-24** | **Do not use a height-field LBM** as the multi-media solver before 2031. Grid SWE, thin-film or diffusion solvers are outside claim 1 as written. [C] |
| US8599213 (pub. US20130120435) "Simulating paint brush strokes using configurable wetness, drying, and mixing parameters" | Adobe (DiVerdi, Krishnaswamy, Harris) | Virtual brush with a **reservoir buffer and a pickup buffer**, parameters for drying rate, canvas wetness and pickup mix ratio, depositing from both buffers independently | Granted 2013-12-03, adj. exp. **2031-02-28** | **Medium.** Our smudge/pickup brush design should be reviewed. Prior art exists (Baxter dAb 2001, MyPaint smudge). Consider a single mixed reservoir instead of two independently deposited buffers. [C claims; I risk] |
| US9600907 "Paintbrush and liquid simulation" | Adobe (Kim, Carr, Chen; WetBrush) | Bristle and liquid simulation around the brush | Published 2016 (US2016/0148395); probably expires about 2034–35 [I] | Avoid WetBrush-style particle-near-brush plus grid-far hybrids. Not needed for our scope. |
| US7777745 "Edge effect" | Autodesk | For **vector geometry**: interior fill plus contour offset-curve geometry, per-pixel proximity-to-edge drives an effect | Active (reinstated), **2028-09-14** | Relevant only if Joy Paint renders **vector fills with watercolor edges** by tessellating offset contours. A raster blur-based edge term (Curtis) is different. [C] |
| Mixbox code and LUT | Secret Weapons | License, not patent | — | CC BY-NC. Excluded. [C] |
| Herson et al. 2026 thin film | Adobe authors; paper is CC BY | Unknown filings | — | Paper text is open. **Check for pending Adobe applications** before shipping a close reimplementation. [I] |

Search coverage note: Google Patents rate-limited some fetches. Statuses above come from Google Patents pages I parsed on 2026-09-28. Have counsel re-check before release.

---

## 5. Joy Paint wet-media engine: concrete design

### 5.1 Design principles [I]

1. **One medium engine, several presets.** Watercolor, ink, gouache, "wash" and wet-pastel interactions should be **parameter sets of one wet simulation**. Oil and acrylic use a height+pigment "thick paint" path (§5.9). This matches the owner's "few excellent brushes" goal.
2. **Tiered fidelity, the same data model.**
   - **Tier 0**: stamp-time effects only, no simulation.
   - **Tier 1 "Wet-Lite"**: potential-driven water flux plus Curtis-style pigment transfer, backruns and edge flow, plus drip particles. **The default on all target devices.**
   - **Tier 2 "Wet-Full"**: Stuyck-style shallow water or a Herson thin film. Optional, for Tab S8+ class GPUs.
3. **Simulation is cheap because it is local and low-resolution.**
   - Simulate at ½ (or ¼) canvas resolution, only on active tiles.
   - Crisp detail comes from the full-resolution paper, granulation and brush masks at deposit and display time. Wet paint is soft by nature; the detail lives in the paper.
4. **Copy Rebelle's UX contracts.**
   - Pause or throttle the simulation while the pen is down.
   - Simulate only the active layer (and the active animation frame).
   - Fast-dry when switching.
   - Provide Show Wet, Wet/Dry/Fast-Dry layer and Pause Diffusion.
   - Expose the same small paper parameter set.
5. **Portable math.**
   - Write every pass as a **GLSL ES 3.00 fragment shader**. The same code runs in the WebGL2 desktop prototype and in GLES 3.x on Android.
   - Avoid compute shaders in v1: WebGL2 has none, and fragment passes let the web prototype stay bit-for-bit comparable.

### 5.2 Data layers (per wet-simulation cell)

Simulation grid at scale `s_sim` (default ½ canvas):

| Texture | Format | Channels | Notes |
|---|---|---|---|
| `W` (water) | RGBA16F, ping-pong | `w` surface water depth, `s` paper saturation (capillary), `m` "wettable" flag (0..1, soft), `age` time since last wetting | `m` = 1 where `w > ε` or `s > σ_backrun`. This replaces Curtis's binary mask. |
| `G` (suspended pigment) | RGBA16F, ping-pong | Concentration-weighted `K_r, K_g, K_b, S` | Linear in concentration, so conservative flux keeps it exact. |
| `D` (deposited pigment, wet-session delta) | RGBA16F, ping-pong | Same 4 channels | Pigment settled during this wet session. Baked into the layer at dry time. |
| `V` (Tier 2 only) | RG16F, ping-pong | Velocity `u, v` | Not needed in Tier 1. |
| `P` (paper, static) | RG8 (full res, tiling) | `hp` height 0..1, `cap` capillary capacity (noise-perturbed) | From paper presets. Sampled with bilinear filtering at simulation resolution too. |
| `N` (granulation, static) | R8 (full res, tiling) | Granulation noise, one of 4 textures (as in Rebelle) | Kept separate from the paper, as Rebelle does. |
| `Mblur` | R8/R16F at ¼ simulation resolution | Blurred wet mask for edge darkening | Recomputed every 2–4 steps. |
| `Act` | R8 at tile resolution (e.g. 1 texel per 32² simulation cells) | Activity per tile | Read back asynchronously (PBO + fence). |

Memory [I]:

- Tier 1 ping-pong state (W, G, D ×2) is 48 B per simulation cell.
- A **2048² canvas simulated at ½ res (1024²) needs about 50 MB**, which is acceptable on Note 9 (6 GB) and later devices.
- For a 4096² canvas, use ¼ res (again about 50 MB) or a movable 1024² simulation window over the active bounding box.
- **Budget rule: ≤ 64 MB of simulation textures.**

### 5.3 Brush → simulation coupling (stamp pass)

Each dab is drawn as a quad into the simulation textures (and optionally straight into full-resolution `D` for dry-brush detail):

- **Water:** `w += water · mask(x) · pressureCurve`. The dab mask is the brush tip; the full-resolution version is used when the dab is written to the layer.
- **Pigment:** `G += load · (K,S)_brush · mask`. The reservoir color comes from pickup mixing (libmypaint WGM spectral or 3-band K–M). Brush "Length" is reservoir depletion.
- **Drybrush** (Curtis §4.7): if `water` is below a threshold, write only where `hp > 1 − dryness`, and write **directly to the layer at full resolution**, bypassing the simulation. Dry media (pencil, pastel) do the same, **unless** the target cell is wet (`m > 0`). In that case add their pigment to `G` so it diffuses (Rebelle's rule).
- **Blend/smudge mode:** pick up from `G + D·(1 − staining)` under the dab into the brush reservoir, then redeposit. Keep pickup on the GPU: a tiny 1×1 render-target reduction per dab batch, read by the next dab's shader. Avoid `glReadPixels` stalls.
- **Re-wet:** water landing on cells with deposited pigment lifts `lift = rewet · (1 − ω_staining) · D` back into `G`. For already-baked pixels, see §6.3.

### 5.4 Tier 1 "Wet-Lite" step (per simulation step, fragment passes)

Notation: cell i and its 4 neighbours j; `dt` is a fixed step.

- **Pass 1 — water flux and update (one pass, conservative by symmetry).**
  - For each face (i, j), compute the potential `φ = w + κ_tex·hp + g_tilt·(x·t̂)`. The `hp` term pools water in paper valleys; the last term makes water flow downhill when tilted.
  - Face flux:
    - `F_ij = k_flow · mob · (φ_i − φ_j)`;
    - `mob = w_up^n` with the upwind depth `w_up` (n ≈ 1–2), which gives shear-thinning-like runs.
  - **Pinning (surface tension):** if `m_j < 0.5` and `w_i < w_pin`, set `F_ij = 0`. Water does not cross onto dry paper unless the puddle is deep. That gives hard wet-on-dry edges (Curtis condition 1).
  - **Clamp:** `F_ij ∈ [−w_j/4, +w_i/4]`. Both cells evaluate the identical expression, so mass is conserved without a separate flux texture.
  - `w_i' = w_i − Σ_j F_ij`.
- **Pass 2 — pigment transport.**
  - `Gflux_ij = F_ij · (G_up / max(w_up, ε))`, i.e. pigment rides with the water at the upwind concentration.
  - Add a small in-water diffusion `D_g·(G_j − G_i)`, only where both cells have `m > 0`. This is the wet-in-wet softness, scaled by "Diffusion Speed".
- **Pass 3 — edge flow, absorption, evaporation, transfer (MRT: writes W and G, D).**
  - **Edge darkening (Curtis FlowOutward):**
    - `w ← w − η·(1 − Mblur)·m`.
    - Make pinned boundary cells slightly **more evaporative**, so interior water migrates outward in pass 1 of the next step and carries pigment (the coffee-ring mechanism).
    - `η` is the "Edge Darkening" slider.
  - **Absorption:** `a = min(α·dt, w, cap − s)`, then `w −= a`, `s += a`. `α` is the "Absorbency" slider.
  - **Evaporation:** `w −= e·dt`, `s −= e_s·dt`.
  - **Pigment transfer (Curtis TransferPigment, with granulation from paper height hp or from granulation texture N):**
    - `δdown = G · (1 − hp·γ) · ρ · f(w)`;
    - `δup = D · (1 + (hp − 1)·γ) · ρ/ω · g(w)`;
    - `f` rises as `w → 0` (drying deposits everything); `g → 0` when dry.
    - `γ` is Granulation Strength; `ρ` is density (heavier pigments settle faster); `ω` is staining.
  - **Final dry-out:** if `w < ε_dry` then `D += G; G = 0` for that cell. Leftover pigment at the retreating front makes hard edges and blooms.
- **Pass 4 — capillary backruns (every step, or every 2nd).**
  - Curtis `SimulateCapillaryFlow`: `s` flows to neighbours where `s_i > σ_min`, `s_i > s_j` and `s_j > σ_recv`, by `Δ = max(0, min(s_i − s_j, cap_j − s_j)/4)`.
  - Cells with `s > σ_backrun` become wettable (`m ← 1`).
  - **Blooms fall out of this:** fresh water on a damp region flows into cells that capillarity made wettable, following the noisy `cap` field. It pushes suspended pigment (pass 2) to an irregular front that dries and deposits (pass 3). The result is cauliflower edges.
- **Pass 5 (every 2–4 steps) — `Mblur` and activity.**
  - Downsample `m` to ¼ resolution and apply a separable Gaussian (K ≈ 10 px at canvas scale).
  - Write per-tile max(w), max(s), max(|ΔG|) into `Act`.

Parameter mapping to Rebelle's sliders:

| Rebelle slider | Our parameter |
|---|---|
| Absorbency | α |
| Re-wet | `lift` and `w_pin` of new water over dried pigment |
| Texture Influence | κ_tex and γ |
| Edge Darkening | η |
| Diffusion Speed | steps per second and `D_g` |
| Drip size / length | particle parameters (§5.6) |
| Granulation | γ plus choice of N |

### 5.5 Tier 2 "Wet-Full" (optional, Tab S8+ class)

Replace pass 1 with **Stuyck et al.'s mobile SWE**:

1. Semi-Lagrangian advection of `(u, v, h)`.
2. Implicit height update with 4 Jacobi iterations; `b` = paper height + dried paint.
3. Velocity back-substitution.
4. Gravity from the accelerometer (for Tilt).

Keep passes 2–5. Alternatively, for **physically principled drips and fingers**, use Herson et al.'s thin-film equation with (Thickness, Fluidity, Hydrophoby) controls: `∂h/∂t = −∇·( h³/(3μ) · (…gravity, surface-tension ∇∇²h, cohesive f_z…) )`, explicit with a small dt. It costs about 3 ms per step at 4096² on a laptop RTX 3080 Ti, so it is plausible at 512–1024² on high-end tablets. [C for cost; I for mobile feasibility]

### 5.6 Drips, tilt and blow

- **Drip particles (a "DropEngine" analogue) [I, consistent with C that Rebelle's drips are a separate engine coupled to the simulation].**
  - **Spawn:** when tilt is on and a boundary cell on the downhill side has `w > w_drip(DripSize)`, spawn a particle with mass `m0 ∝ w`, pigment load `G/w·m0`, and position at the cell.
  - **Update (CPU, ≤ a few hundred particles):**
    - `v += (g_tilt − c_drag·v + ∇hp·k_paper + noise)·dt`;
    - move along v;
    - **stamp** a small water+pigment dab into `W/G` along the path; this keeps the trail in the simulation, so it dries with edge darkening;
    - lose mass `∝ |v|·dt / DripLength`;
    - on stop, deposit a bead (a larger dab).
  - Sizes are specified in **physical units (mm)** and converted by canvas DPI. Rebelle's drips are DPI-aware.
  - Respect stencils and selections by killing or reflecting particles.
- **Grid tilt.** Add the `g_tilt·(x·t̂)` potential in pass 1. Tilt comes from the UI (direction and angle) or the **accelerometer** (Android `TYPE_GRAVITY`), low-pass filtered, with "Lock tilt to canvas" and "Center".
- **Blow tool.** Along the blow stroke, add a local directional potential `φ −= B·(x·dir)·mask` and lower `w_pin` under the nozzle, so wet paint is pushed and splatters over pinning. Where the nozzle passes over **dry** cells near wet ones, spawn drip particles with initial velocity along `dir` (Rebelle: "in dry areas it creates dripping effects"). S Pen pressure can map to blow strength.

### 5.7 Active region, tiles and scheduling

- Split the simulation grid into **64×64-cell tiles**. Keep an **active tile set** on the CPU.
- **Draw each pass as instanced quads over active tiles only**, into the full simulation texture. Work is proportional to the active area; there are no halos to manage, because tiles live in one texture.
- **Dilation.**
  - Each step moves water at most one cell (clamped flux), so dilating the active set by one tile ring every ~64 steps is conservative.
  - In practice, dilate when `Act` shows border water above ε.
  - `Act` readback is asynchronous and 1–2 frames late; the dilation covers the latency.
- **Deactivation.** A tile retires when `max(w) < ε`, `max(s) < ε_s`, `G ≈ 0` and there has been no change for N steps. On retirement:
  1. bake (§6.2);
  2. clear the tile's W, G and D;
  3. remove it from the set.
- **Scheduling.**
  - Target ~6 ms of GPU time per frame for the simulation.
  - While the pen is down, run **0–1 steps per frame** (Rebelle's pause-during-stroke rule).
  - After pen-up, run up to the budget: typically 2–4 steps per frame at 60 Hz. On 120 Hz Tab S displays, run the simulation at 60 Hz.
  - Diffusion Speed scales steps per simulated second, not per frame, so behavior does not depend on frame rate.
- **Hard caps.**
  - If active tiles exceed the budget (e.g. "Wet the whole layer" on 4096²), drop to ¼ resolution for that session or time-slice tiles round-robin. Round-robin produces slower but correct diffusion.
  - Show a subtle "drying…" indicator.

### 5.8 Cost estimates (Note 9 class: Adreno 630 / Mali-G72 MP18, LPDDR4X ~30 GB/s peak) [I]

Simulation is **bandwidth-bound**. Assume ~50% of peak is achievable, i.e. 15 GB/s effective.

| Scenario | Cells | Bytes/cell/step (Tier 1 ≈ 95 B incl. MRT, neighbour taps mostly cache hits) | Time/step | Notes |
|---|---|---|---|---|
| Typical wash, active 512² at ½ res (≈ 1024² canvas px) | 262k | 95 | **~1.7 ms** | 2–4 steps/frame, so 3.5–7 ms. OK at 60 Hz. |
| Large wash, active 1024² (a whole 2048² canvas at ½ res) | 1.05M | 95 | **~6.6 ms** | 1 step/frame; diffusion runs at about half speed. Acceptable. |
| Full-res 2048² simulation, whole canvas | 4.19M | 95 | **~26 ms** | Not interactive. This is why simulation must be ½ res and tiled. |
| Tier 2 SWE (≈ 250 B/cell) at 512² | 262k | 250 | **~4.4 ms** | Only on Note 20 / Tab S8+ (LPDDR5/5X, ~44–67 GB/s peak). |

**Cross-check against measured data.** Stuyck's SWE at 1024×768 took **8.8 ms** on an iPad Air 2 (≈ 25.6 GB/s, ~250 GFLOPS FP32). Adreno 630 has roughly 2–3× the ALU and similar bandwidth, so expect ≈ 4–6 ms. Scaling to 2048² (×5.3) gives ≈ 20–30 ms, which matches the bandwidth estimate. [C for Stuyck numbers; I for scaling]

**Thermal.** Sustained simulation plus 120 Hz display will throttle phones such as the Note 9 within minutes. The scheduler must adapt steps per frame to measured GPU time (timer queries, `EXT_disjoint_timer_query`, where available).

### 5.9 Other media on the same framework

- **Ink.** High `D_g`, low `w_pin` (spreads onto dry paper a little: feathering), high staining ω (no lift), low granulation. Wet-on-wet ink blooms use the backrun path. (MoXi-style fiber pinning is covered by another agent.)
- **Gouache.** High S (opaque), low flow (`k_flow`), weak edge darkening, "Opaque" mixing mode.
- **Washes / fills.** A fill-tool option "Wet the fill" writes water plus pigment into the region (like Rebelle's Fill → Wet the Layer). Selections and stencils mask W writes.
- **Oil / acrylic (thick paint, no free water).**
  - Per-layer **height (R16F) + pigment (K, S)** tiles, IMPaSTo-style.
  - Brush dabs push, pick up and deposit volume.
  - An optional low-cost "slump" uses Stuyck's viscosity-dependent flow only when the paint is thinned.
  - **Lighting at display resolution:** normal = ∇height, Blinn-Phong plus a small environment map (Rebelle's "RealShader").
  - **Soft shadows** via a short 8–16-tap height-field ray-march toward the light. Optional, Tab-class only.
  - Export the height map as a separate file (Rebelle does this).
- **Pastel / pencil.** Dry deposit on paper peaks (`hp > threshold`, Rebelle's "scratch light or dark parts" option). They enter `G` only when the cell is wet.

### 5.10 Tier 0: "80% of the look without a fluid simulation" [I, built on C recipes]

For low-end devices, animation onion-skin previews, or a "fast watercolor" brush:

1. **During the stroke:** accumulate into a per-stroke wet buffer with density `d` and water coverage `M`. Deposit is modulated by paper height (drybrush threshold and granulation `1 − hp·γ`).
2. **On stroke end (or after a short "drying" animation of ~300–800 ms):**
   - **Edge darkening:** `d′ = d · (1 + E·clamp((M − blur_K(M))·k, 0, 1))`. This is the Curtis blurred-mask trick applied once instead of as a flow.
   - **Granulation:** `d′ *= 1 + γ·(N − 0.5)` (granulation texture), revealed progressively as the "drying" animation advances (Rebelle: "revealed when drying").
   - **Pigment turbulence:** `d′ *= 1 + τ·(lowFreqNoise − 0.5)`. Bousseau's `C′ = C·(1 − (1 − C)(d − 1))` is an alternative color-space form.
   - **Soft wet-in-wet:** 3–6 iterations of masked diffusion of `d` within `M ∪ previousWet` (where the previous stroke is still "wet" by timestamp).
   - **Fake bloom:** where the new stroke overlaps a still-damp previous stroke, run a short capillary-style flood of `M` into the old region with a noisy threshold, and push old `d` outward to the front with `d_front += Δ`.
3. **Commit to the layer.**

The cost is a few full-resolution passes over the stroke's bounding box, once per stroke: sub-millisecond to a few ms. This alone reaches roughly 70–80% of the perceived look (edges, granulation, blooms). It lacks runs, tilt and live interaction between strokes.

---

## 6. Coexisting with a normal tile-based raster layer stack

### 6.1 Layer model [I]

- **Paint layer (wet-capable).** Ordinary sparse tile storage (e.g. 256² tiles), with pixel format **Pigment16F** = (K_r, K_g, K_b, S) plus alpha coverage in a second plane (or S doubles as coverage).
  - The compositor renders it with K–M against the backdrop: `R_out = R1 + T1²·R_bg/(1 − R1·R_bg)` per channel.
  - Cheaper form: `R_out ≈ R1 + T1²·R_bg`, i.e. "multiply plus add", which Rebelle itself describes its Transparent mode as partially similar to.
  - This mirrors Rebelle Pigments: per-layer, blend modes disabled, mixes with the layers below.
- **Normal RGBA layers** are unchanged (RGBA8 premultiplied) and follow the usual blend modes.
- **Wet session.** At most **one** paint layer (on one animation frame) owns the transient simulation (W, G, D, active tiles). The layer's display = baked pigment tiles + `D_wet` + `G` (suspended), rendered live only on active tiles. Other tiles use cached composites.

### 6.2 Drying and baking into ordinary pixels

- **Per-tile bake on retirement (§5.7).**
  1. Upsample `D` (and any leftover `G`) from simulation resolution to canvas resolution.
  2. Apply full-resolution modulation: paper height and granulation texture, i.e. `d_full = up(D)·(1 + γ(N − ½))` masked by the upsampled wet mask with a small edge-preserving sharpen.
  3. **Add** it to the layer's Pigment16F tile. K–M concentrations add linearly, so this is exact.
  4. Mark composite caches dirty.
- **Explicit commands.**
  - **Dry the Layer**: bake all active tiles and clear W.
  - **Fast Dry**: set `w = 0`, keep `s`. Tiles stay "damp" and can still backrun, then retire when `s` decays.
  - **Layer or frame switch**: Fast Dry (Rebelle's rule).
- **Rasterize.** "Convert paint layer → normal layer" evaluates K–M against white (or the actual backdrop, flattening) to RGBA8. Used for PSD export, filters, and interop with the existing video and SpriteLab pipelines.

**Alternative (simpler MVP).** Bake directly to RGBA8 with a "multiply" blend (color = T², alpha = coverage). Trade-offs: lossy re-wet and no gouache scattering over dark backdrops, but zero compositor changes.

### 6.3 Re-wetting dried paint

- **With Pigment16F layers:** when water hits a tile, initialise the simulation `D` for that tile from the layer's pigment. Scale it by `(1 − ω_staining)` for liftable fraction, or keep a per-pixel "staining" channel. The simulation `D` is then the liftable part, and the rest stays in the layer. Re-wet slider = lift rate. This is lossless.
- **With RGBA8 layers:** convert the pixel to absorbance `A = −ln(max(ε, 1 − α + α·C))` per channel as an approximate pigment and lift a fraction. This is approximate.

### 6.4 Undo, determinism, animation and export

- **Undo.**
  - The simulation keeps evolving after the pen lifts. Undo returns to the pre-stroke state **including water**.
  - Implement tile-level copy-on-write: at stroke begin, snapshot (a) layer tiles the stroke will touch and (b) all active simulation tiles.
  - Snapshots are GPU-to-GPU copies into an undo atlas, later compressed to CPU.
  - Cap: when the undo budget is exceeded, a new stroke first force-dries the wet session. Rebelle warns about "limited number of undo steps due to low memory", so this is a known pressure point.
- **Determinism.**
  - Use a fixed dt and a fixed step count per input-event batch, and log {dab list, tilt samples, step counts}.
  - Replay reproduces results on the same GPU. This enables time-lapse and re-rendering animation frames.
  - Results across GPUs will differ slightly because of fp16 rounding. Acceptable.
- **Animation.**
  - Each cel owns its own paint layers. Only the edited cel simulates.
  - Onion skins display baked pixels.
  - A cheap "watercolor boil" for animation: jitter the paper and granulation texture offset per frame at render time (Tier-0 style), not re-simulation.
- **Export.**
  - Composite (K–M) → sRGB.
  - Optional impasto height map, and an optional "wetness" debug map (Show Wet).

---

## 7. Desktop prototype ↔ Android module portability [I]

- **Shaders.**
  - Author every pass in **GLSL ES 3.00**. It runs unchanged in WebGL2 (desktop Chrome/Firefox) and GLES 3.0–3.2 on Android.
  - Only the `#version` line and precision qualifiers differ; use `highp` for positions and water, `mediump` elsewhere.
  - Render-to-float needs `EXT_color_buffer_float` in WebGL2 and on GLES 3.0/3.1. [C: Khronos/MDN] To my knowledge it is core in GLES 3.2 [I; still check at runtime].
  - Some mobile devices expose only `EXT_color_buffer_half_float` (see WebGL issue #3093), so design everything to work with **16F render targets**. [C]
- **Medium module format.** A JSON manifest containing:
  - an ordered pass list: shader IDs, inputs, outputs, how often each pass runs;
  - parameters with UI ranges and Rebelle-like names;
  - the paper and granulation texture set;
  - K/S derivation constants.

  Brush modules reference a medium and provide dab-level math (pressure/tilt/rotation curves, water, load, pickup). Prefer a **constrained expression language compiled to GLSL** over arbitrary user GLSL. Shader compile failures and driver bugs on Mali and Adreno are a real risk.
- **Golden tests.** Run identical dab logs through the WebGL2 prototype and the Android build. Compare `D` and `W` after N steps within fp16 tolerance, on CI (desktop) and on a device farm (Note 9 Adreno/Mali, Note 20, Tab S7/S9).

---

## 8. Risks

1. **Licensing.**
   - Mixbox (CC BY-NC) must not ship. Use 3-band K–M plus libmypaint WGM (ISC) or spectral.js (MIT).
   - GPL-3.0 code from Krita or MyPaint is compatible with Joy Creator's GPL-3.0; permissive code is too.
2. **Patents.** Before implementation of the specific features, have counsel review:
   - Adobe US8599213 (reservoir+pickup buffers) against our smudge design;
   - US8335675 (height-field LBM) if anyone proposes LBM;
   - US8917282/3 (polygon watercolor) if anyone proposes vector-growth watercolor;
   - Autodesk US7777745 for vector-fill edge effects;
   - any Adobe filings around the 2026 thin-film paper.
3. **Mobile performance.**
   - The simulation is bandwidth-bound, and full-canvas simulation is impossible on a Note 9.
   - Mitigations: half resolution, active tiles, pen-down throttling, adaptive steps and thermal-aware scheduling.
   - Tile-based GPUs (Mali/Adreno) pay for every full-target load and store. Scissor or instanced-quad only active tiles, and call `glInvalidateFramebuffer` on ping-pong targets.
4. **Precision.**
   - fp16 water/pigment amounts leak or accumulate noise. Use the conservative symmetric-flux formulation, clamp negatives, and renormalise mass per tile occasionally.
   - Consider R32F for `w` if half-float drift is visible (32F render targets are not filterable; use `texelFetch`).
5. **Latency.** Any GPU→CPU readback on the stroke path (pickup color, activity) causes stalls. Keep pickup on the GPU and read activity asynchronously.
6. **Scope.**
   - Rebelle took more than 2 years of watercolor experiments before v1 and has more than 600k lines of C++.
   - Ship Tier 0 first, then Tier 1. Make Tier 2 and thick-paint lighting stretch goals.
   - The owner's "few excellent brushes" constraint is the right one.
7. **Interop.** Pigment layers are not standard RGBA. Every export path (PSD, video pipeline, SpriteLab) needs the rasterize step. Plan it in the layer API early.

---

## 9. Suggested build order [I]

1. WebGL2 prototype, Tier 0 watercolor brush (edge darkening, granulation, turbulence, paper), with 3-band K–M compositing. Validate the look with the owner.
2. WebGL2 prototype, Tier 1 simulation on one layer: stamp, flux, pigment, transfer, capillary, Show Wet, Dry/Fast-Dry, tilt, and drip particles.
3. Port to GLES 3.x on Android behind a `WetLayerSession` class with tile activity, scheduling and bake. Profile on the Note 9 (both Adreno and Mali variants if possible).
4. Pigment16F layer format in the layer stack, plus re-wet, undo snapshots and rasterize.
5. Gouache/ink presets, blow tool, accelerometer tilt.
6. Thick paint (height + lighting). Optional Tier 2 on Tab S8+.

---

## Sources

**Rebelle / Escape Motions (primary)**
- Rebelle 7.1.1 User Manual (PDF): https://www.escapemotions.com/products/rebelle/documents/Rebelle_Manual_v.7.1.1.pdf. Sections used: system requirements; Watercolor, Ink, Dry and Blow tools; Layers panel (Show Wet, Pause Diffusion, Wet, Dry, Fast Dry, fast-dry on layer switch); Tilt panel; Visual Settings (paper params, granulation, NanoPixel/OpenGL vs CPU); Working with Water; Pigments; NanoPixel Export; brush texture limits.
- Rebelle 4: New Watercolors & Crucial Optimizations (SIMD, 4x speed, mixing modes, Re-wet): https://www.escapemotions.com/blog/rebelle-4-new-watercolors-crucial-optimizations
- Rebelle about page: https://www.escapemotions.com/products/rebelle/about
- Rebelle 3: When Traditional Meets Digital (DropEngine, Blow tool, rewritten watercolor): https://www.escapemotions.com/blog/rebelle-3-when-traditional-meets-digital
- Rebelle 5: Meet the New Color Pigments (Mixbox credit): https://www.escapemotions.com/blog/rebelle-5-meet-color-pigments
- Rebelle 5 NanoPixel blog: https://www.escapemotions.com/blog/rebelle-5-nanopixel-export-high-res-canvases-thanks-to-machine-learning
- 10 Years of Rebelle: Interview with Developers (C++/Qt, pause simulation during strokes, vectorization, team): https://www.escapemotions.com/blog/10-years-of-rebelle-interview-with-developers
- Rebelle 8: Bristle Brushes: https://www.escapemotions.com/blog/rebelle-8-bristle-brushes
- Rebelle experimental online prototype (Processing): https://www.escapemotions.com/experiments/rebelle/index.php
- Forum, canvas size (staff statement 2019): https://www.escapemotions.com/community/forum/t/452/question-about-canvas-size
- Forum, working on big files: https://www.escapemotions.com/community/forum/t/41454/question-working-on-big-files
- SIGGRAPH 2016 Appy Hour entry: https://history.siggraph.org/experience/rebelle-real-watercolor-and-acrylic-painting-software-by-blaskovic-and-fapso/
- Wikipedia, Rebelle (software): https://en.wikipedia.org/wiki/Rebelle_(software)
- CG Channel, Rebelle 8.3 (2026): https://www.cgchannel.com/2026/08/escape-motions-releases-rebelle-8/
- Digital Production, Rebelle 8: https://digitalproduction.com/2025/10/08/rebelle-8-digital-paintings-physical-upgrade/
- Rookies interview with Peter Blaškovič: https://discover.therookies.co/2016/06/25/peter-blaskovic-the-driving-force-behind-escape-motion/

**Academic**
- Curtis et al. 1997, Computer-Generated Watercolor: https://grail.cs.washington.edu/projects/watercolor/paper_small.pdf
- Stuyck, Da, Hadap, Dutré 2017, Real-Time Oil Painting on Mobile Hardware: https://tuurstuyck.github.io/assets/oilpaint_low_res.pdf
- Herson, Paris, Michel 2026, Dripping Thin Films for Real-time Digital Painting (Eurographics / CGF 45(2)): https://eliemichel.github.io/dripping-thin-films/documents/herson26dripping_thin_films.pdf and https://onlinelibrary.wiley.com/doi/10.1111/cgf.70416
- Van Laerhoven & Van Reeth 2005, Real-time simulation of watery paint: https://onlinelibrary.wiley.com/doi/abs/10.1002/cav.95
- Chen, Kim, Ito, Wang 2015, WetBrush: https://wanghmin.github.io/publication/chen-2015-wgb/ and https://dl.acm.org/doi/10.1145/2816795.2818066
- Baxter, Wendt, Lin 2004, IMPaSTo: https://doi.org/10.1145/987657.987665
- Chu, Baxter, Wei, Govindaraju 2010, Detail-preserving paint modeling for 3D brushes: https://www.microsoft.com/en-us/research/publication/detail-preserving-paint-modeling-for-3d-brushes/
- Bousseau et al. 2006, Interactive watercolor rendering with temporal coherence and abstraction: https://artis.inrialpes.fr/Publications/2006/BKTS06/
- Montesdeoca et al. 2017, Art-directed watercolor stylization of 3D animations in real-time: https://www.sciencedirect.com/science/article/abs/pii/S0097849317300316
- Sochorová & Jamriška 2021, Practical Pigment Mixing for Digital Painting: https://dl.acm.org/doi/10.1145/3478513.3480549 and https://dcgi.fel.cvut.cz/en/publications/2021/sochorova-tog-pigments/
- Real-Time Watercolor Simulation with Fluid Vorticity Within Brush Stroke: https://www.researchgate.net/publication/321112340_Real-Time_Watercolor_Simulation_with_Fluid_Vorticity_Within_Brush_Stroke
- TAMU thesis, GPU programming for real-time watercolor simulation: https://oaktrust.library.tamu.edu/server/api/core/bitstreams/5575e4a6-40fd-4946-ad32-f83712ddc02f/content

**Color mixing code and licenses**
- Mixbox (CC BY-NC 4.0): https://github.com/scrtwpns/mixbox
- spectral.js (MIT): https://github.com/rvanwijnen/spectral.js
- libmypaint (ISC): spectral WGM in `brushmodes.c` / `helpers.c`, https://github.com/mypaint/libmypaint

**Patents** (Google Patents pages parsed 2026-09-28)
- US6198489 Computer generated watercolor (Univ. of Washington; filed 1998-02-18): https://patents.google.com/patent/US6198489B1/en and https://www.freepatentsonline.com/6198489.html
- US8917282 Separating water from pigment in procedural painting algorithms (Adobe, exp. 2032-05-28): https://patents.google.com/patent/US8917282B2/en
- US8917283 Polygon processing techniques in procedural painting algorithms (Adobe, exp. 2032-05-14): https://patents.google.com/patent/US8917283B2/en
- US8335675 Realistic real-time simulation of natural media paints (Adobe, LBM height field, exp. 2031-04-24): https://patents.google.com/patent/US8335675B1/en
- US8599213 / US20130120435 Simulating paint brush strokes using configurable wetness, drying, and mixing parameters (Adobe, exp. 2031-02-28): https://patents.google.com/patent/US20130120435A1/en
- US9600907 Paintbrush and liquid simulation (Adobe, WetBrush): https://patents.google.com/patent/US9600907 and https://www.freepatentsonline.com/y2016/0148395.html
- US7777745 Edge effect (Autodesk, vector edge proximity, exp. 2028-09-14): https://patents.google.com/patent/US7777745B2/en

**GPU / API**
- EXT_color_buffer_float: https://registry.khronos.org/OpenGL/extensions/EXT/EXT_color_buffer_float.txt
- EXT_color_buffer_half_float: https://registry.khronos.org/OpenGL/extensions/EXT/EXT_color_buffer_half_float.txt
- WebGL issue #3093 (devices with only half-float render targets): https://github.com/KhronosGroup/WebGL/issues/3093
