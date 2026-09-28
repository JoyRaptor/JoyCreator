# R6 — Android drawing, painting and 2D animation apps: rankings, UI anatomy, and a minimal UI for Joy Paint

Research date: 2026-09-28. Author: research agent R6.
Scope: Android apps, with iPad apps used only as design references. Concepts and Infinite Painter
are ranked here but covered in depth by R5 (`R5_concepts_infinite_painter.md`).

## Evidence legend

| Tag | Meaning |
|---|---|
| **[C]** | Confirmed from a cited primary source: official docs, a Play listing, or a vendor page |
| **[C3]** | Confirmed from a third-party source (review site, blog, forum) that is cited |
| **[R]** | From my own analysis of **34,110 Google Play reviews** (method in §4.1) |
| **[I]** | Inferred or from background knowledge, not re-verified this session. Treat as a hypothesis |

Install counts come from Google Play's own page data (the `realInstalls` field, read with
`google-play-scraper`, US store, 2026-09-28). The public bucket (for example "100M+") is shown next to
it. Ratings and rating counts are from the same scrape.

---

## 0. Executive summary

* **Android has no "Procreate".** The market splits three ways. **ibis Paint X** has the users
  (328M installs, 4.48★, 3.0M ratings). **Clip Studio Paint** has the professional reputation
  (18.6M, 4.45★) but is subscription-only on Android. **Infinite Painter** is the closest to Procreate
  in UI (37.4M, 4.49★). Sketchbook is huge (191M) but is maintained slowly, with its last update in May 2026.
  Krita is the only free desktop-class tool, but on Android it is a beta desktop port for tablets and Chromebooks only.
  Adobe Fresco still has **no Android version** [C].
* **Animation on Android is weak at the professional end.** **FlipaClip** dominates (81.8M installs) but
  still has **no frame holds** (a "Planned" forum request that is 12 years old) [C3], limits layers in the free tier, and puts audio
  behind a paywall [R]. The professional options are **RoughAnimator** (68k installs, $7.99) and **Callipeg**
  (Android *tablets only* since May 2025, 3.9k installs, $14.99). ibis Paint and HiPaint have added
  basic flipbook animation. Toonsquid and Procreate Dreams are iPad-only, and Toonsquid has "no plans" for Android [C].
  Animatic was pulled from Play in January 2025 [C3].
* **The gesture vocabulary has settled.** Two-finger tap undoes, three-finger tap redoes, two fingers pan, pinch and rotate,
  a quick pinch fits the canvas, a long-press picks colour, and draw-and-hold snaps to a shape. Procreate, ibis, CSP, Infinite Painter,
  Callipeg, RoughAnimator, HiPaint and Procreate Dreams all use it [C]. The one outlier is Sketchbook, and its
  reviewers ask for two-finger undo [R].
* **Users complain about business model and data loss far more than about UI.** In 1–2★ reviews, ads and paywalls
  dominate for ibis (39% paywall, 27% ads), MediBang (50% ads), CSP (41% subscription) and FlipaClip (33% ads,
  30% paywall). **Crashes and lost work** dominate for Infinite Painter (48%), Sketchbook (35%) and MediBang (34%)
  [R]. UI clutter is a single-digit share everywhere except the desktop-port and pivot apps (Krita, Stick Nodes, Animation Desk).
  For a GPL app with no ads, **reliability and autosave are the real competitive edge**.
* **Proposed Joy Paint UI (§5).** On a phone it uses a Procreate-Pocket-style three-zone layout, with the slider rail moved under
  the **non-drawing thumb**: size, opacity, eyedropper modifier, undo and redo. A 5-item tool bar sits at the bottom and 3 contextual buttons sit at the top. Animation is
  a document toggle that adds **one 56 dp frame strip** above the tool bar, with holds shown as cell width, a play
  button, an onion button whose long-press opens settings, and three-finger flipping. Selection and transform are one continuous flow with a
  floating launcher and explicit commit, and there is **no commit on a stray tap**. The S Pen button held during a stroke erases.
  Hover shows the brush outline and suppresses finger input.

---

## 1. Rankings

### 1.1 Drawing and painting apps on Android

Ranking = popularity (installs × rating) weighed against professional reputation and whether the app is actively developed.

| # | App | Installs (Play data / bucket) | Rating (count) | Model | Last update | Why it ranks here |
|---|---|---|---|---|---|---|
| 1 | **ibis Paint X** | 328,166,091 / 100M+ | 4.48 (3.00M) | Free + ads, Prime subscription, IAP $2.99–79.99 | 2026-09-25 | The mass-market standard: huge brush set, manga tools, vector layers (v12), animation (v11), Smart Shape and a new stabilizer in v14.1 [C]. Monetization is the main complaint [R]. |
| 2 | **Clip Studio Paint** (Android and Galaxy) | 18,565,255 / 10M+ | 4.45 (50.3K) | Subscription only on Android (from $2.49/mo). Phones get 30 h/month free [C3] | 2026-09-03 | The professional and comic-industry reputation. Desktop feature parity. Simple Mode (v2.0 phone, v2.1 tablet) was built to make mobile usable [C]. Paid users hate the licence checks [R]. |
| 3 | **Infinite Painter** *(R5 covers in depth)* | 37,382,349 / 10M+ | 4.49 (245K) | Freemium, one-off unlock | 2026-09-08 | The closest thing to Procreate on Android. Its chrome buttons respond to both tap and drag [C]. Crash and lost-work complaints are 48% of its negative reviews [R]. |
| 4 | **Sketchbook** (Sketchbook Inc., spun out of Autodesk in 2021) | 191,389,200 / 100M+ | 4.06 (706K) | Free, $2.99 premium bundle [C3] | 2026-05-30 | Clean and minimal, with the "puck", marking menu and corner shortcuts [C]. **Status:** alive and independent, but updates are slow (desktop Pro 9.4 in April 2026 [C3]). Undo is hidden behind a corner or the puck [R]. |
| 5 | **Krita** (Android and ChromeOS) | 4,564,092 / 1M+ | 3.32 (6.4K) | Free (GPL); Play IAP is donations | 2026-09-21 | Desktop-class and free. Still a **beta for tablets and Chromebooks only**, with the desktop UI and Touch Docker [C3]. The 2026 roadmap has a mobile UI prototype and QML tool options [C]. |
| 6 | **Concepts** *(R5)* | 7,494,971 / 5M+ | 4.36 (24.3K) | Freemium and subscription | 2026-09-10 | Vector, infinite canvas and a tool wheel. Paywalled shapes and lasso draw complaints [R]. |
| 7 | **MediBang Paint** | 37,829,098 / 10M+ | 3.74 (303K) | Free + ads, premium | 2026-08-04 | Comic-oriented and cloud-centred. **50% of 1–2★ reviews mention ads**, and many describe crashes during backup that lost work [R]. |
| 8 | **HiPaint** | 5,454,219 / 5M+ | 4.29 (62.4K) | Free + ads, IAP | 2026-08-20 | Rising fast (launched Dec 2022). Procreate-like, with animation and onion skin [C3]. Reviews report stability problems and disappearing regions [R]. |
| 9 | **Tayasui Sketches** | 10,539,406 / 10M+ | 3.71 (78.4K) | Freemium | 2026-09-22 | The most minimal UI in the set, praised for "realistic" tools. Paywall complaints (26%), and no autosave or rotation in older reviews [R]. |
| 10 | **ArtFlow** | 6,438,604 / 5M+ | 3.36 (33.9K) | Free + ads, pro unlock | 2025-07-24 | An early Android favourite with edge menus and one-tap size/opacity [C3]. It **caps undo in the free tier** ("psychological tactic", 152 upvotes) [R]. Now stale. |
| 11 | **ArtRage** | 123,990 / 100K+ | 4.0 (2.6K) | $2.99 paid | **2021-10-19** | Realistic oils. **Abandoned on Android**, with no selection tool [R]. |
| — | PENUP (Samsung) | 220,870,152 | 4.53 | Free | 2026-09-18 | A Samsung-preinstalled drawing SNS and colouring app, not a professional tool. Its reach comes from preinstallation [I]. |
| — | Picsart Color, Sketchar (AR tracing), ArtLoop, Simply Draw | 84M / 7.0M / 7.7M / 12.5M | 3.75 / 3.67 / 4.57 / 4.74 | — | 2026 | Adjacent categories (colouring, AR tracing, lessons). Not competitors for a painting tool. |
| ✗ | **Adobe Fresco** | — | — | — | — | **Not on Android.** Only iOS, iPadOS and Windows, and Adobe has announced no plans [C3]. |

Tiering: **S** = ibis (reach), CSP (pro), Infinite Painter (Procreate-like). **A** = Sketchbook, Krita,
Concepts. **B** = MediBang, HiPaint, Tayasui. **Legacy** = ArtFlow, ArtRage.

### 1.2 2D animation apps on Android

| # | App | Installs | Rating (count) | Model | Last update | Notes |
|---|---|---|---|---|---|---|
| 1 | **FlipaClip** | 81,790,436 / 50M+ | 4.34 (797K) | Free + ads, "Plus" subscription | 2026-09-25 | Dominant. Frame-by-frame with onion skin (before/after count, two opacity sliders, red/green tint, loop, skip) [C]. **Max 30 fps and one global fps** [C]. **No frame hold** (forum request from 12 years ago still "Planned") [C3]. Free tier gets 3 layers [C3]. Audio import is paywalled, which reviews resent heavily (1,081 and 381 upvotes) [R]. |
| 2 | **ibis Paint X (animation mode)** | (same 328M base) | — | Audio for animation is paywalled [R] | 2026-09-25 | Added in v11 [C]. Animation bar with +, duplicate and delete, press-hold-drag to reorder, **per-frame Duration slider**, loop, ping-pong and one-shot, and onion skin with count, first/last opacity and colour [C]. |
| 3 | **Callipeg** | 3,948 / 1K+ | 4.26 (209) | $14.99 one-off | 2026-09-12 | The most complete gesture design on Android. **Tablets only** (Android 11+, 4 GB RAM) [C]. Two/three-finger tap undo/redo, **3-finger vertical drag flips frames**, **4-finger tap play/stop**, a floating two-finger long-touch menu, drag on sheet edges to change exposure, and a slice gesture to split a sheet [C]. Crash and palm-rejection complaints [R]. |
| 4 | **RoughAnimator** | 68,275 / 50K+ | 4.5 (3.6K) | $7.99 one-off | 2026-08-25 | For professionals: layers × drawings timeline, drawing-duration arrow, playback range, onion opacity, tint and counts, stylus-only mode, and export to QuickTime, GIF or PNG sequence plus Harmony/AE/Animate import [C]. Its reviewers call it "without a doubt the best animation app for android" (78 upvotes) [R]. |
| 5 | **Stick Nodes** | 16,846,589 / 10M+ | 4.7 (109K) | Free + ads (Pro version separate) | 2026-09-03 | Pivot and stick-figure animation, not drawn frames [I]. Relevant to Joy's Avatar Studio. |
| 6 | **Draw Cartoons 2** | 19,805,664 / 10M+ | 4.6 (380K) | Free + ads | 2026-09-06 | Bone and keyframe puppet animation [I]. Also relevant to Avatar Studio. |
| 7 | **HiPaint (animation)** | (5.45M base) | — | MP4 export paid [R] | 2026-08-20 | Frame-by-frame with customizable onion skin, exporting "at full canvas resolution" [C3]. |
| 8 | **Krita (animation)** | (4.56M base) | — | Free | 2026-09-21 | A full desktop timeline and onion-skin docker, but a desktop UI on a tablet [C3]. |
| 9 | **Animation Desk** | 3,797,628 / 1M+ | 3.18 (16.2K) | Free + ads, subscription | **2024-05-31** | Stale. Reviews cite no canvas rotation, shallow zoom and cut/paste degrading resolution [R]. |
| — | Stop Motion Studio | 22,002,014 | 4.1 | Freemium | 2025-12-30 | Photo stop-motion, not drawing. |
| ✗ | Animatic (Inkboard) | 680K lifetime | — | — | removed **2025-01-28** | Gone from Play [C3]. |
| ✗ | Toonsquid | — | — | — | — | iPad only. The FAQ says it relies on Swift and Metal and has **no Android plans** [C]. |
| ✗ | Pencil2D, Tahoma2D | — | — | — | — | Desktop only [I]. |
| ✗ | Procreate Dreams, Procreate, Fresco | — | — | — | — | iPad design references only (§2.3). |

### 1.3 What the rankings say about the opportunity [I, from the tables]

* Professional-grade animation on **Android phones** is essentially empty. Callipeg is tablet-only, RoughAnimator is a
  small, dated-looking tablet app, and FlipaClip lacks holds and caps fps at 30.
* Every mass-market app monetizes by gating things artists see as basic: undo steps, layers, audio, or
  deleting a drawing (Tayasui, 348 upvotes). A GPL app with **no gates** is a differentiator in its own right.
* None of the surveyed animation apps documents **sprite-sheet export**. Callipeg lists PEG, MP4, HEVC, GIF, PNG, TGA,
  JSON, OCA and XDTS [C]. RoughAnimator lists QuickTime, GIF and PNG sequence [C]. FlipaClip lists MP4, GIF and PNG sequence [C3].
  Joy Creator already has a sprite-sheet editor, so this gap is Joy's to take.

---

## 2. UI anatomy of the important apps

### 2.1 Painting apps

#### ibis Paint X [C unless marked]
* **Layout:** a bottom toolbar, left to right: *Brush/Eraser, Tool Select, Properties, Color, Full Screen, Layer,
  Back*. At top right sit *View menu, Selection Area, Stabilizer, Ruler, Material*. Tool Select is a pop-up
  window listing 15 tools, in 1–3 columns. The brush size and opacity **Quick sliders** sit on the canvas edge [C].
  Undo and redo buttons are in the chrome [I].
* **Gestures:** two-finger drag, pinch or rotate for the view. **Two-finger tap = undo, three-finger tap = redo**, each can be turned off.
  **Long-press with the brush = Quick Eyedropper**, with an adjustable activation time. Tapping another finger while drawing cancels the stroke.
  Rotation can be disabled.
* **Stylus:** pressure curve, touch offset, **palm rejection**, and **Button 1 / Button 2** assignments (undo,
  brush/eraser toggle). The hover **cross mark** and **thickness mark** arrived in v13 [C].
* **Selection/transform:** the Lasso, Magic Wand and Transform tools live inside Tool Select, so they take two taps. A Selection Area
  shortcut sits top right. v14.1 added **undo/redo inside a transform operation** [C].
* **Recent additions:** Smart Shape (hold to auto-correct into a shape), a new stabilizer, a 30-day trash, layer solo mode and brush lock [C].
* **Animation:** an animation bar on the canvas. **+ adds the next frame**, a frame menu offers Duplicate and Delete, press-hold-drag reorders,
  and "Animation Settings" holds fps, **per-frame Duration**, loop, ping-pong or one-shot, and onion skin (mode, count, first/last opacity,
  colour) [C]. Export is "Save Animation Video" (formats not stated) [C].
* **Complaints [R]:** ads and paywalls (39% and 27% of 1–2★). Brushes went from 18 h to 4 h of free use while ads became "two 60-second ads in a row"
  (246 upvotes). Crashes on launch. The Sep 2026 stroke-engine update lagged with Samsung styluses (77 upvotes).
* **Praise [R]:** "easy" (22% of ≥4★), brushes (27%), free.

#### Clip Studio Paint, Android [C]
* **Two modes:** *Studio Mode* is the full desktop palette UI. *Simple Mode* ("maximizes the use of mobile
  device screens … more quickly switch between tools … so users can focus on drawing") came to phones in v2.0 and tablets in v2.1 [C].
* **Simple Mode on a phone:** the brush tool icon sits bottom centre and opens a brush list with categories on the left. **Size and opacity sliders run along the bottom.**
  A **colour circle** at the bottom opens a wheel, sets or sliders. The **Layer palette** is at bottom right. Materials are at top right. **Undo/redo arrows sit top centre.**
  The "…" menu at top right holds export, effects and settings [C].
* **Gestures:** 2-finger tap undo, 3-finger tap redo, 2-finger pan, pinch and rotate. **One-finger swipe scrolls when "Use
  different tools with fingers and pen" is on.** **One-finger long-press = eyedropper**, shown as a circle whose upper half is the new
  colour [C]. Every gesture can be toggled in Preferences › Interface › Touch Operations [C].
* **Selection:** the **Selection Launcher** is a floating bar under the selection with Move/Transform and other
  commands. It can be dragged and hidden [C]. This is the best contextual-selection pattern on Android.
* **Complaints [R]:** subscription only, with no desktop licence carry-over (669 and 527 upvotes). Startup licence checks break it
  offline (527). Saving is confusing without premium (611 and 583). A OneDrive import regression (518).

#### Sketchbook [C]
* **UI:** a toolbar, a brush panel, the **Double Puck** (drag vertically for opacity, horizontally for size), a **marking menu** (radial) from the
  clutch icon at bottom centre, and **customizable corner shortcuts** (double-tap bottom-left to undo) [C].
* **Gestures:** two-finger pan, pinch and rotate. **Three-finger single tap = undo, three-finger double-tap = full screen**, and three-finger
  swipes left/right/up/down = undo, redo, colour editor, brush library (all customizable) [C]. **Tap-hold = colour picker** [C].
* **Complaints [R]:** "undo and redo buttons are inconveniently not in the toolbar" (225 upvotes). "The undo takes three button
  presses" (360). A request for two-finger tap undo (144). **"It won't detect small strokes"** (1,278), which points to a touch-slop or
  stroke-threshold bug. A photos-permission prompt with no OK button (4,014). Lost files (993–1,712). **Left-handed use: "disable UI
  navigation"** touches from the palm (220).
* **Praise [R]:** "extraordinarily clean" UI (797), minimal, no ads, brushes.

#### MediBang Paint [C3]
* A top toolbar that can be hidden, a **bottom command bar** that opens the brush, colour and layer panels, and an HSV and size bar on the left edge [C3].
* **Complaints [R]:** ads (50%), **crashes during cloud backup lose work** (365, 331 and 317 upvotes). "Saved" work that was never
  really saved locally (175). A two-finger-plus-pen bug zooms all the way out (109).

#### Krita for Android [C3]
* The desktop UI on a tablet: dockers, a Touch Docker, a pop-up palette. The team is prototyping a **mobile UI** and moving tool options to
  QML (2026 roadmap) [C].
* **Complaints [R]:** "the PC version style … change the simple mode for smartphones" (186). A complex interface (210). Chromebook
  breakage after an update (266 and 256). Corrupted saves (240, 173).
* **Praise [R]:** "Excellent support for multitouch gestures and the S Pen button" (55). Free and desktop-grade.

#### HiPaint [C3/R]
* Procreate-like, with 2/3-finger tap undo/redo, customizable gestures and quick sliders. The radial menu is hard to find
  ("I can't find the radial menu", 54). **Content disappearing ("random squares of my pieces just disappear")** (55) [R].

#### ArtFlow [C3/R]
* **Edge menus** on the left and right that open by tapping chevrons. Size and opacity are "one click away". A floating toolbar [C3].
* It **caps undo** and caps layers at 3 in the free tier, which reviewers call manipulative (152 and 153) [R].

#### Tayasui Sketches [I/R]
* Very minimal: a strip of realistic tools plus colour and layer buttons [I]. Praised as "flows great … like drawing on paper".
  Criticized for no autosave, **"you can't rotate the canvas"**, and paywalled layers, watercolour and delete (252, 441, 348) [R].

#### Infinite Painter (brief; see R5) [C]
* A minimal studio: Home, tool menus, a layer toggle, options, a main toolbar and undo/redo. **Every chrome button does two things.** On Size, tap opens a panel and drag
  adjusts. On Opacity, tap or drag. On Colour, tap opens the panel, **drag up/down changes brightness, and dragging outward starts the eyedropper**. The toolbar can be dragged with
  two fingers. A HUD shows the size, opacity and flow numbers. Tapping undo reveals a history slider (64 steps) [C].

#### Concepts (brief; see R5)
* Infinite vector canvas, a tool wheel, and a strong S Pen button story (11 S-Pen-button mentions, the most of any app) [R].
  Paywalled shapes and lasso draw complaints (397 and 410) [R].

### 2.2 Animation apps

#### FlipaClip [C/C3/R]
* **Layout:** the canvas in the centre, a **tool bar on the left** (brush, eraser, lasso, fill, text, ruler), layers on the right, the timeline and
  **frames bar at the bottom** with play, and a separate "frames viewer" [C3]. The overflow is a "three dots" menu at top right [C].
* **Onion skin:** ⋮ › Onion Skin toggles it, and **⋮ › Onion Skin › edit** opens settings, three taps away. Settings are frames before/after, first/last opacity,
  red/green "traditional colours", looping onion and frame skipping [C].
* **Timing:** one global fps, max 30, set at creation or via ⋮ › Project settings › fps. **No per-frame hold**.
  "Frame Hold" has been "Planned" since a 12-year-old forum thread [C/C3].
* **Stylus:** Draw Input is "Stylus and touch" or "Stylus" only. Stylus pressure. **Hover preview** [C]. No palm-rejection or
  button-mapping settings are documented [C].
* **Export:** MP4, GIF, PNG sequence [C3]. The free tier adds a watermark [C3].
* **Complaints [R]:** ads (33%) and paywalls (30%), especially **audio import** (1,081 and 381 upvotes) and layers. **No stabilizer**
  (340). **The lasso freezes the canvas** (630). **Lasso does not apply to other frames** (904). **Can't find how to delete a project** (460).
  Laggy pinch-zoom (192).
* **Praise [R]:** easy (26% of ≥4★), audio (13%), layers.

#### RoughAnimator [C]
* A tools panel (brush, eraser, fill, lasso, onion toggle), a hamburger menu for import and export, and a **timeline of layers × drawings**.
  **Press-hold-drag** reorders. **Press-hold then drag up or down** duplicates or adds an empty drawing. A **"Drawing duration" slider, or dragging the arrow on
  the selected drawing, sets exposure**. Sync modes: independent, sync layers, maintain overall duration.
  **Playback range** sliders. Scrub with the playhead or a **scrub wheel**. **2-finger tap undo, 3-finger tap redo**. **Stylus-only mode**.
  Onion opacity, tint, and before/after counts. Export: QuickTime, GIF, PNG sequence with transparency [C].
* **Complaints [R]:** "the scrubbing function doesn't work well AT ALL" (71). Import and audio failures. The developer does not respond.

#### Callipeg (Android tablets) [C]
* **Canvas:** 2-finger tap undo, 3-finger tap redo. **3-finger drag up/down flips frames.** A **2-finger long-touch opens a floating menu** (clear,
  flip, copy, cut, paste, colour). Quick 2-finger pinch resets the view. **Swiping circles on the sidebar** sets size and opacity. **Holding a finger and dragging the pencil** changes
  size and opacity. **One-finger hold while drawing switches to eraser** ("Finger Tool Switch"). **4-finger tap plays or stops.**
  A 4-finger long touch opens the transform tool [C].
* **Flip button:** drag it in any direction, linear or circular, to flip through drawings [C].
* **Timeline:** tap to jump, drag to flip, **double-tap to select a sheet, then drag its side arrows to change exposure**. Double-tap then drag to
  multi-select. Pinch to zoom and two-finger pan the timeline. **Slice down or up with the pen to split a sheet** [C].
* **Onion:** **touch-and-hold the onion icon for options**, shortcut "o". It can render over or under the drawing [C].
* **Export:** PEG, MP4, HEVC, GIF, PNG (still or sequence), TGA, JSON, OCA, XDTS [C].
* **Complaints [R]:** crashes that lose projects. "palm rejection is not available so a dot would be in the canva".
  **"Dragged the flip button off screen, no way to get it back without clearing the whole apps data"**. UI "a tad small, no setting to change".

### 2.3 iPad references (design only)

#### Procreate / Procreate Pocket [C]
* **Three zones.** Top-left holds the advanced features (Gallery, Actions, Adjustments, Selection, Transform). Top-right holds the painting tools (Brush, Smudge, Erase, Layers,
  Colour). A **sidebar** carries the size slider, the **Modify button** (eyedropper by default), the opacity slider and **undo/redo**. It can be moved vertically
  and mirrored for right-handed use [C]. **Pocket (iPhone) keeps the same three zones** on a phone screen and hides
  advanced features behind the top-left menu [C].
* **Gestures:** 2-finger tap undo (hold for rapid undo), 3-finger tap redo, **3-finger scrub clears the layer**, 3-finger swipe down opens
  copy/paste, **4-finger tap toggles full screen**, **quick pinch fits the canvas**. **Draw-and-hold = QuickShape**, with a delay adjustable from 0.10 to 1.50 s, and
  **a second finger while holding snaps to a perfect shape** [C]. Touch-and-hold eyedropper is optional [C]. Layer gestures: pinch to merge, swipe for secondary selection,
  2-finger swipe for alpha lock [C]. **Hover gestures:** pinching while the pencil hovers changes size, and sliding while it hovers changes opacity [C]. On a slider, **dragging away from the
  bar gives precise control** [C].
* **Selection:** a ribbon icon opens a **bottom selection toolbar** (Automatic, Freehand with taps for polygons, Rectangle, Ellipse, Add, Remove, Invert,
  Feather, Copy & Paste, Colour Fill, Save & Load). Tapping the icon again cancels [C].
* **Animation Assist:** turned on via Actions › Canvas, three taps. A frame strip of layers, Add Frame, Play, frame options (duplicate, delete,
  **hold duration**), loop, ping-pong or one-shot, 1–60 fps, onion 0–12 frames, opacity, blend primary, and red/green colours [C].

#### Procreate Dreams [C]
* **Flipbook** is entered by dragging the timeline handle down, which fills the screen with canvas and brings up painting tools. **Swipe the frame strip to move in time.**
  Tap-hold a frame for options. **Tap a frame twice for handles that stretch its duration.** "Frame duration" works for twos and fours. Play/pause.
  Previous and next drawing buttons [C]. Two/three-finger tap undo/redo, **flicking the playhead left plays from the start**, **an extra finger while moving snaps**,
  and a 4-finger tap gives a full-screen preview [C]. A reviewer found the timeline "awkward and cramped at first" [C3].

#### Adobe Fresco [C3]
* A **Touch Shortcut**, a floating circle. Holding the centre gives the primary modifier (for example **erase with the current brush**). Sliding to the outer ring
  gives the secondary modifier. **A double-tap locks it** [C3].

### 2.4 Cross-app gesture convergence

| Action | Procreate | ibis | CSP | Infinite | Sketchbook | Callipeg | Rough | FlipaClip | Dreams |
|---|---|---|---|---|---|---|---|---|---|
| Undo | 2-tap | 2-tap | 2-tap | 2-tap | **3-tap / corner** | 2-tap | 2-tap | button [I] | 2-tap |
| Redo | 3-tap | 3-tap | 3-tap | 3-tap | 3-swipe → | 3-tap | 3-tap | button [I] | 3-tap |
| Pan/zoom/rotate | 2-finger | 2-finger | 2-finger | 2-finger | 2-finger | 2-finger | ✓ | ✓ (laggy [R]) | 2-finger |
| Fit canvas | quick pinch | — | — | — | 3-double-tap | quick pinch | — | — | quick pinch |
| Eyedropper | hold (opt) / Modify btn | **hold** | **hold** | drag out of colour | **hold** | floating menu | — | tool | — |
| Shape snap | **draw-and-hold** | Smart Shape | — | [I] | — | — | — | ruler | — |
| Hide UI | 4-tap | button | — | — | 3-double-tap | — | — | — | 4-tap preview |
| Flip frames | strip | strip | — | — | — | **3-drag ↕ / Flip btn** | scrub wheel | strip | **swipe strip** |
| Play | button | button | — | — | — | **4-tap** | button | button | button / flick |
| Hold/exposure | frame menu | Duration slider | timeline | — | — | **drag sheet edge** | **drag arrow** | **none** | **drag handles** |

**Conclusion:** the core gestures are a de facto standard. Breaking them costs more than any novelty gains, and Sketchbook's reviews
prove it [R].

---

## 3. Menu depth to common actions (competitors)

| Action | Best-in-class depth | Worst observed |
|---|---|---|
| Undo | 0: 2-finger tap plus a visible button (Procreate, ibis, CSP) | Sketchbook: "three button presses" [R] |
| Eyedropper | 0: long-press (ibis, CSP, Sketchbook) | FlipaClip: a tool switch [I] |
| Size/opacity | 0: always-visible sliders (Procreate, ibis, CSP Simple) | MediBang: open the brush settings [C3] |
| Selection | 1 (Procreate ribbon) | ibis: Tool Select › Lasso = 2 [C] |
| Onion settings | 1: long-press the onion icon (Callipeg) | FlipaClip: ⋮ › Onion › edit = 3 [C]. ibis: Animation Settings window [C] |
| FPS | 1 (a settings pop-over) | FlipaClip: ⋮ › Project settings › fps = 3 [C] |
| Hold a frame | 0: drag the cell edge (Callipeg, Dreams, Rough) | FlipaClip: impossible [C3] |
| Enter animation | 1 (Dreams handle) | Procreate: Actions › Canvas › Animation Assist = 3 [C] |

---

## 4. What users complain about and what they praise

### 4.1 Method [R]
I used `google-play-scraper` (US, English) to pull up to 1,500 "newest" and 1,500 "most relevant" reviews per app, deduplicated.
That gave **34,110 reviews across 16 apps**, 9,039 of them 1–2★. The keyword regexes are crude and a review can hit more than one theme, so read the
percentages as **relative signal between apps, not absolute truth**. The quotes cited above are the most-upvoted examples.

### 4.2 Complaint themes (% of 1–2★ reviews mentioning each theme)

| App | n (1–2★) | Ads | Paywall / subscription | Crash / lost work | Lag | UI confusing | Undo | Account / login |
|---|---|---|---|---|---|---|---|---|
| ibis Paint X | 414 | **27** | **39** | 14 | 7 | 3 | 1 | 4 |
| Infinite Painter | 657 | 2 | 21 | **48** | 5 | 2 | **12** | 4 |
| Clip Studio Paint | 1,141 | 6 | **41** | 8 | 5 | 6 | 1 | **12** |
| MediBang | 1,174 | **50** | 22 | **34** | 5 | 2 | 2 | 4 |
| Krita | 548 | 1 | 1 | 7 | 5 | 5 | 1 | 0 |
| Sketchbook | 626 | 2 | 4 | **35** | 5 | 2 | 3 | 2 |
| Concepts | 483 | 3 | 27 | 10 | 5 | 4 | 2 | 5 |
| ArtFlow | 745 | 4 | 14 | 23 | 2 | 2 | **11** | 2 |
| HiPaint | 518 | 26 | 10 | 25 | 9 | 3 | 8 | 1 |
| Tayasui | 883 | 2 | 26 | 19 | 4 | 2 | 4 | 1 |
| ArtRage | 142 | 0 | 8 | 11 | **19** | 2 | 1 | 0 |
| FlipaClip | 438 | **33** | **30** | 11 | 5 | 1 | 3 | 3 |
| RoughAnimator | 210 | 0 | 11 | 20 | 7 | 5 | 4 | 0 |
| Callipeg | 13 | 0 | 8 | **46** | 15 | 8 | 8 | 0 |
| Animation Desk | 876 | 8 | 8 | 9 | 7 | 7 | 3 | 1 |
| Stick Nodes | 171 | 2 | 6 | 8 | 3 | 8 | 0 | 1 |

Krita's negatives are mostly Chromebook breakage and "desktop UI" complaints that my regexes did not catch.
Its top reviews are quoted in §2.1.

### 4.3 Specific UI complaints worth designing against [R]
1. **Hidden or awkward undo.** Sketchbook: "not in the toolbar" (225), "three button presses" (360). ArtFlow and Infinite Painter: undo
   caps or limits (152). → **Always-visible undo/redo plus the 2-finger gesture, and unlimited history.**
2. **Lost work.** MediBang backups crash (365 and 331). Sketchbook files won't load (1,712 and 993). Infinite Painter layers vanish (471).
   CSP won't save without premium (611). Stick Nodes has no autosave (473 and 400). → **Journaled autosave on every stroke, and no "save" concept at all.**
3. **Transform degrades pixels.** "the resolution of the layer gets blurry when I want to transform it" (Sketchbook, 2,242).
   Animation Desk: cut/paste becomes low-resolution (324). → **Resample from the original pixels on every edit, one resample at commit.**
4. **Selection problems.** FlipaClip lasso freezes the canvas (630) and doesn't apply to other frames (904). Infinite "can't see outside
   selected areas" (284). Concepts and Tayasui put lasso or cutter behind a paywall or have bugs in it (410 and 285). Requests for lasso fill (263).
5. **Palm and touch misfires.** Callipeg has no palm rejection. A MediBang palm triggers redo (154). Sketchbook left-handers can't disable touch UI (220).
   Tayasui asks for palm rejection (544).
6. **Small strokes dropped.** Sketchbook (1,278). → **No touch slop on stylus strokes.**
7. **UI elements lost off-screen.** Callipeg's flip button and reference window (C3 review). → **Anything draggable snaps back on
   screen, and there is a "Reset layout" item.**
8. **Stroke lag after engine changes.** ibis Sep 2026 (77). FlipaClip laggy zoom (192).
9. **Missing canvas rotation.** Tayasui and Animation Desk (444).
10. **Findability.** FlipaClip "can't find" how to delete a project (460). HiPaint radial menu (54). CSP and Sketchbook "where is…" (the most
    findability hits: 34 and 32).
11. **Stabilizer missing.** FlipaClip (340). Stabilizer is the #12 feature request overall.

### 4.4 Most praised [R]
* "Easy, simple, intuitive" is the #1 praise theme in almost every app (17–27% of ≥4★). The exceptions are Krita (10%) and ArtRage (11%), the two desktop-flavoured UIs.
* Brushes (27% for ibis, Infinite, MediBang and Sketchbook, 30% for HiPaint).
* "Professional / like PC / like Procreate" is highest for HiPaint (19%), Krita (13%), Infinite (10%) and CSP (10%).
* In animation apps, **audio** (FlipaClip 13%, Callipeg 18%) and easy onion skin.

### 4.5 Feature requests (phrases like "please, wish, add…", 4,981 reviews)
Layers 304, **audio 181**, reference/import 165, **lasso/selection 130**, shapes/lines 104, fill 95, export 89,
undo 76, text 57, zoom 53, customizable UI 45, stabilizer 42, rotate 37. In animation apps: **audio 128**, layers 98,
import 50, lasso 48, export 47, shapes 42, fill 39, stabilizer 25 [R].

---

## 5. SYNTHESIS: the minimal UI for Joy Paint

### Design axioms (each backed by evidence above)
1. **Keep the standard gestures and add nothing that conflicts with them** (§2.4).
2. **The pen draws, fingers navigate.** Once a stylus is seen, fingers never make marks until the user says otherwise.
   Precedents: FlipaClip's "Stylus only", RoughAnimator's "Stylus only mode", CSP's "different tools with fingers and pen" [C]. Phones without a pen keep finger drawing.
3. **Every control that exists is visible in one place, and every chrome button responds to both tap and drag** (Infinite Painter [C]).
   This keeps the button count low without hiding things (findability complaints, §4.3-10).
4. **Nothing destructive on a gesture.** No three-finger scrub to clear (Procreate): a misfire costs a layer.
5. **Work is never lost and never gated.** Autosave, unlimited layers limited only by memory, unlimited undo. Top complaints in §4.2.
6. **Joy house rules carry over** from `tasks/SPEC_20260910_SPRITELAB_UI.md`: 40 dp minimum hit boxes, value bubbles 48 dp
   **above** the finger, one gesture = one undo step, pointer-event parity for pen, touch and mouse.

### (a) Gesture table

Pen mode turns on automatically when a stylus event arrives. Quick settings can turn it off.

| Input | Pen mode (stylus seen) | Finger mode (no stylus) | Evidence |
|---|---|---|---|
| **Pen tip, drag** | Draw with the current tool | — | universal |
| **Pen draw-and-hold** (≥ 350 ms still, adjustable 0.1–1.5 s) | Snap to line, arc, ellipse or rectangle. Handles stay live until the next stroke | same with finger | Procreate QuickShape, ibis Smart Shape [C] |
| … **plus a finger tap during the hold** | Constrain to a perfect circle or square, or 15° angles | — | Procreate [C] |
| **Pen hover** | Brush-outline cursor at true size and hardness. **Suppress finger touches while hovering and for 300 ms after lift-off** (hover-aware palm rejection) | — | ibis thickness mark, FlipaClip hover preview [C]. Palm complaints [R] |
| **S Pen button held + stroke** | Temporary eraser using the *current brush's* shape | — | Fresco "erase with brush" [C3], ibis Button 1/2 [C], MediBang request [R] |
| **S Pen button held + tap** (< 150 ms, < 8 px) | Eyedropper | — | [I] design choice |
| S Pen button click while hovering | **Not bound.** Samsung Air command owns it | — | Samsung [C] |
| Pen eraser end (`TOOL_TYPE_ERASER`) | Eraser | — | Android API [C] |
| **1 finger drag** | Pan | Draw | CSP one-finger scroll [C] |
| **1 finger long-press (400 ms)** | Eyedropper loupe (a ring showing old and new colour). Release to pick | same | ibis, CSP, Sketchbook [C] |
| **1 finger tap** | Nothing on canvas (tap chrome only) | — | avoids misfires |
| **2 finger tap** | **Undo.** Hold for repeated undo | same | universal [C] |
| **2 finger drag, pinch, twist** | Pan, zoom, rotate. Rotation snaps to 0°/90° within ±5° | same | universal [C]. Snapping [I] |
| **2 finger quick pinch** | Fit canvas and reset rotation | same | Procreate, Callipeg [C] |
| **3 finger tap** | **Redo** | same | universal [C] |
| **3 finger horizontal drag** | *(animation)* flip through frames, one frame per ~24 dp. *(still)* nothing | same | Callipeg flips with 3 fingers [C]. Horizontal matches the strip [I] |
| **3 finger swipe down** | Clipboard menu (cut, copy, paste, paste to new layer) | same | Procreate [C] |
| **4 finger tap** | Hide or show all chrome (focus mode) | same | Procreate, Pocket [C] |
| **Finger held + pen stroke** | Not bound by default (option: temporary eraser) | — | Callipeg "Finger Tool Switch" [C] as an option |
| Volume keys | Off by default. Option: undo/redo | — | Sketchbook request (185) [R] |

Implementation notes [C: Android docs]: honour `ACTION_CANCEL` and `FLAG_CANCELED` (Android 13+) for palm rejection. Use
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` so edge strokes are not eaten by system gestures. Use `requestUnbufferedDispatch`,
`GLFrontBufferedRenderer` (androidx.graphics) and `MotionEventPredictor` to cut ink latency. Apply **no touch slop to stylus
`ACTION_DOWN`** (the Sketchbook short-stroke bug).

### (b) Always-visible chrome

Target device: the owner's Note 9 at 1080×2220 override is about 411×846 dp [I]. Right-handed pen, left thumb on the rail. The rail mirrors for left-handers.

**Phone portrait: 14 controls, about 90% canvas**
```
┌────────────────────────────────────┐
│ ☰      ⬚ Select   ✥ Move   ▤ Layers│  44 dp top bar (menu = gallery/export/settings)
│▕                                   │
│▕ size    (thin 28 dp slider,       │
│▕          value bubble 48 dp       │
│◉ modify   above the thumb)         │  ◉ = eyedropper tap / hold-to-modify
│▕ opacity                           │
│▕                                   │
│↶                 CANVAS            │  undo/redo at the rail bottom = under the thumb
│↷                                   │
│   ┌── frame strip (animation only) ┐│  56 dp, see (f)
│ 🖌 Brush  ⌫ Eraser  ☁ Blend  ● Colour  ⋯ │  52 dp tool bar
└────────────────────────────────────┘
```
* The rail is Procreate's sidebar moved to the **thumb side**. On a Note-class phone the non-drawing hand holds the device,
  so its thumb reaches size, opacity, eyedropper, undo and redo without the pen leaving the canvas [I, grounded in Procreate [C]
  and Sketchbook undo complaints [R]]. The rail can slide vertically and collapse to a 6 dp sliver.
* **Top bar:** Menu, Select, Move (transform), Layers (shows a thumbnail of the active layer and the layer count). Undo sits on the rail, not
  the top (unlike CSP Simple), because the top of a 6.4" phone is out of thumb reach [I].
* **Bottom bar:** Brush (the icon shows the current brush and a size ring), Eraser, Blend (smudge), Colour chip, ⋯ (Fill, Shapes/
  Ruler, Text, Symmetry, Reference, Canvas).
* Count: 4 top + 5 bottom + 5 on the rail (size, modify, opacity, undo, redo) = 14. Procreate has 15 [C] and ibis about 17 [C/I].

**Phone landscape**
```
┌─┬────────────────────────────────────────┬──┐
│▕│                                        │☰ │
│▕│                                        │⬚ │ right column 48 dp: Menu, Select,
│◉│               CANVAS                   │✥ │ Move, Layers, then Brush, Eraser,
│▕│                                        │▤ │ Blend, Colour, ⋯
│↶│                                        │🖌│
│↷│  [frame strip 48 dp across the bottom, │⌫ │
│ │   animation only]                      │● │
└─┴────────────────────────────────────────┴──┘
```
Vertical space is the scarce resource here, so there is no top or bottom bar. Only the animation strip runs horizontally.

**Tablet (≥ 600 dp wide)**
```
┌─────────────────────────────────────────────────────────────┐
│ ☰ Gallery  ⚙ Actions  ◑ Adjust  ⬚ Select  ✥ Move     🖌 ☁ ⌫ ▤ ● │  Procreate two-zone top bar
│▕                                                            │
│▕ size                                   ┌─ Layers popover ─┐│  Layers/Colour open as
│◉                   CANVAS               │ (floating, pin-  ││  floating, pinnable
│▕ opacity                                │  nable, movable) ││  popovers (ibis 12.2
│↶                                        └──────────────────┘│  floating layer window [C])
│↷                                                            │
│ [animation: 120 dp timeline, 1–3 layer rows × frames, drag handle to expand] │
└─────────────────────────────────────────────────────────────┘
```
Adjust (filters and adjustments) gets its own top-level button only on tablets. On phones it lives in ☰.

### (c) Menu hierarchy: the 25 most frequent actions

Depth = the number of surfaces opened before the action happens. **0** = a gesture or always-visible control. **1** = open one
pop-over and pick. **2** = pop-over › sub-page.

| # | Action | How | Depth | Competitor reference |
|---|---|---|---|---|
| 1 | Draw | pen | 0 | — |
| 2 | Undo | 2-finger tap / rail ↶ | 0 | Sketchbook 3 presses [R] |
| 3 | Redo | 3-finger tap / rail ↷ | 0 | — |
| 4 | Pan, zoom, rotate | 2 fingers (1 finger pans in pen mode) | 0 | — |
| 5 | Brush size | rail slider, or drag vertically on the Brush button | 0 | MediBang 1 [C3] |
| 6 | Opacity | rail slider | 0 | — |
| 7 | Eyedropper | finger long-press / S Pen button tap / rail ◉ | 0 | FlipaClip tool [I] |
| 8 | Eraser | bottom Eraser / S Pen button + stroke / pen eraser end | 0 | — |
| 9 | Pick a recent colour | tap the Colour chip → a row of 8 recent swatches at the top of the pop-over | 1 | Procreate 1 |
| 10 | Pick any colour | Colour chip → wheel or square (same pop-over) | 1 | — |
| 11 | Switch to a recent brush | tap Brush → the last 6 brushes at the top | 1 | ibis 2 (Tool Select + brush) |
| 12 | Browse the brush library | Brush → category list | 2 | — |
| 13 | New layer | Layers → + | 1 | — |
| 14 | Switch layer | Layers → tap row (or long-press eyedropper → "select layer of this pixel") | 1 | ibis content-layer pick [C] |
| 15 | Layer visibility, opacity, lock | Layers → row eye / 2-finger tap on a row for opacity | 1 | Procreate 2-finger tap [C] |
| 16 | Lasso select | Select → draw | 1 | ibis 2 [C] |
| 17 | Move/transform the selection or layer | Move → drag / handles (auto-shown after a selection) | 1 (0 after selecting) | Procreate 1 |
| 18 | Flip, rotate 90° or scale-fit the selection | launcher bar button | 1 | CSP launcher [C] |
| 19 | Fill an area | drag the Colour chip onto the canvas (flood) / ⋯ › Fill | 0 / 2 | Procreate ColorDrop [I] |
| 20 | Straight line or shape | draw-and-hold | 0 | QuickShape [C] |
| 21 | Hide UI | 4-finger tap | 0 | Procreate [C] |
| 22 | Export or share (PNG/JPG/PSD, MP4/GIF/PNG-seq/**sprite sheet**) | ☰ → Export → format | 2 | — |
| 23 | Add a frame (animation) | strip + (inserts after the current frame and moves to it) | 0 | ibis, Procreate [C] |
| 24 | Flip between frames | swipe the strip / 3-finger drag / tap a cell | 0 | Callipeg, Dreams [C] |
| 25 | Play / stop | strip ▶ | 0 | — |
| 26 | Onion on/off; onion settings | strip onion tap; **long-press** for the pop-over | 0 / 1 | FlipaClip 3 [C]; Callipeg 1 [C] |
| 27 | Hold a frame longer | drag the cell's right edge (snaps to whole frames) | 0 | Callipeg, Dreams, Rough [C]; FlipaClip ✗ |
| 28 | Duplicate or delete a frame | long-press cell → menu | 1 | ibis, Dreams [C] |
| 29 | FPS, loop mode | long-press ▶ → pop-over | 1 | FlipaClip 3 [C] |

**Result:** 16 of the 29 actions are at depth 0 (counting the primary route for rows 19 and 26) and 11 are at depth 1. Only the brush library and export are at depth 2, plus ⋯ › Fill as a fallback. **Nothing is at depth 3.** For comparison, FlipaClip's onion and fps settings and Procreate's Animation Assist toggle are all at depth 3 [C].

### (d) Brush, colour, size and opacity without leaving the canvas
1. **Rail sliders** for size and opacity. The value bubble floats 48 dp above the thumb (Joy rule) and shows **a live brush dab at true size on the
   canvas centre** while dragging (Krita forum request [C3]). **Dragging sideways off the slider slows it to 10× precision** (Procreate precise slider [C]).
   Double-tap the bubble to type a value. Size is a logarithmic scale.
2. **Buttons that respond to drag** (Infinite Painter [C]):
   * Brush button: **drag up/down = size**. Tap opens the pop-over: 6 recent brushes, 1 row of favourites, a library link.
   * Colour chip: tap opens the pop-over (recent swatches, wheel/square, palette tabs). **Drag up/down changes lightness.** **Drag onto the canvas and
     release = flood fill** (Procreate ColorDrop pattern [I]). Holding and dragging past the edge = eyedropper.
3. **Eyedropper everywhere:** finger long-press, S Pen button tap, or the rail ◉. The loupe ring shows old and new colour (CSP Simple [C]).
   Option: "sample this layer / all layers" (ibis [C]).
4. **Hover cursor:** the brush outline at true size. A tiny opacity/size readout appears for 1 s after any change (Infinite HUD [C]).
5. **Per-brush memory with optional lock:** each brush remembers its size, opacity and stabilizer, and a brush lock option shares settings across brushes (ibis 14.0 [C]).
6. **The stabilizer is a slider inside the brush pop-over**, not a separate tool (FlipaClip complaint [R], ibis per-brush stabilizer [C]).

### (e) Selection and transform that are not frustrating
1. **One continuous flow, no mode dead-ends.** Select → draw a lasso (tap points for polygon segments, like Procreate Freehand [C]).
   On release the selection is **immediately live**: dragging inside moves it, corner handles scale, the ring outside the corners rotates. **Two fingers that start
   inside the box pinch and rotate the object. Two fingers that start outside navigate the canvas** [I].
2. **A floating launcher bar** (CSP Selection Launcher [C]) under the selection, draggable and snapping back on screen, with at most 7 items:
   **Transform mode (Free / Uniform / Distort / Warp)** · Flip H · Rotate 90° · Copy to new layer · Fill · Clear · ⋯ (Invert, Feather, Save
   selection, **Apply to frames…**).
3. **Explicit commit.** A ✓ and ✕ pair lives in the launcher. **A stray tap outside does not commit or deselect a pending transform.** It does
   nothing, and the launcher pulses. Choosing another tool commits (Procreate [C]). Adobe users report losing transforms to
   off-by-2-px clicks [C3].
4. **Lossless while editing.** Keep the source pixels and resample from the original on every drag. Resample **once** at commit. Interpolation choice:
   *Bilinear / Bicubic / Nearest (pixel art & sprites)*. This addresses the Sketchbook "gets blurry" complaint (2,242 upvotes) [R].
5. **Undo inside the transform, step by step** before commit (ibis 14.1 [C]). Undo after commit restores the whole operation as one step (Joy one-press-one-step rule).
6. **Snapping by adding a finger** while dragging: centre, edges, 15° steps (Procreate Dreams [C]).
7. **Small objects:** handles sit outside the bounding box at a 40 dp minimum. When the box is under 64 dp, the handles move outward and dragging anywhere in
   the enlarged zone moves it. This fixes Procreate's "don't touch inside small objects" issue [C3].
8. **Visibility:** marching ants plus an optional 20% dim outside the selection. The dim can be toggled so users can "see outside" (Infinite complaint [R]).
9. **Magic wand and "select layer contents"** are in the same Select pop-over as mode chips. **Lasso fill** is a brush mode (Sketchbook requests [R]).
10. **Never block the UI thread** during a transform (FlipaClip freeze [R]). Preview on the GPU and bake on commit off-thread.
11. **Animation:** "Apply to frames…" repeats a transform or clear across a frame range or all frames (FlipaClip request, 904 upvotes [R]).

### (f) Frame-by-frame animation in the same UI
* **Animation is a document property, not a separate app.** Turn it on from ☰ › Animation (on by default for documents made from the "Animation" preset).
  The painting UI stays exactly the same. **One 56 dp strip** appears above the tool bar. This follows Procreate Animation Assist [C] with Dreams-style
  entry [C] and avoids FlipaClip's split between stage and frames viewer [C3].
* **Strip layout (phone):**
  `[ ▶ ] [ ◐ onion ] | ▢▢▢▢▭▭▢▢ … (cells) | [ + ]`
  * Each cell is a thumbnail of the **active layer's drawing**. **Width is proportional to the hold** (1, 2 or 3 frames), which makes holds visible at a glance
    (Callipeg and RoughAnimator sheets [C]). The current cell has an accent underline. The playhead is the cell under a centre marker.
  * **Swipe the strip = scrub.** **Tap a cell = go.** **Drag a cell's right edge = hold** (snaps to whole frames). **Long-press a cell = Duplicate · Delete ·
    Insert blank · Copy/Paste · Hold ±**. **Long-press then drag = reorder** (ibis [C], RoughAnimator [C]).
  * **+** inserts a blank frame after the current one and jumps to it. **Long-press +** = duplicate the current frame (RoughAnimator drag-down duplicate [C]).
  * **▶** plays and stops. **Long-press ▶** opens fps (1–60), Loop / Ping-pong / Once, and playback range (ibis, Procreate, RoughAnimator [C]).
  * **◐** toggles onion. **Long-press ◐** opens before/after count (0–5 each), opacity falloff, past/future tint (red/green default), "onion
    keys only", and over/under the drawing (Callipeg, FlipaClip, Procreate [C]).
* **On-canvas:** a 3-finger horizontal drag flips frames. With the pen, **press ◀ ▶ nudge buttons** at the ends of the strip for single steps (Dreams previous/next
  drawing [C]).
* **Layers × frames:** on phones the strip shows the **active layer only**, and the Layers pop-over lists animation layers with visibility and onion toggles.
  **Pulling the strip's grab handle up** expands it into a multi-row timeline (all layers × frames, 1–4 rows): Dreams' handle [C], RoughAnimator's layer rows [C].
  On tablets the timeline is docked at 120 dp by default.
* **Audio track** (import or record) is shown as a waveform under the strip when present. It is **free**. Audio is the #1 animation feature request and the #1
  paywall grievance in FlipaClip and ibis [R]. Joy already has audio infrastructure.
* **Playback** uses cached flattened frames, so play starts instantly. Scrubbing plays audio snippets (RoughAnimator scrubbing complaint [R]).
* **Export:** MP4 (H.264), animated GIF, animated WebP, **PNG sequence with alpha**, and **sprite sheet with JSON**, handed straight to Joy's SpriteLab
  (the gap noted in §1.3). "Send to Joy video editor" as a clip.
* **Light rules:** no frame limit, no layer limit, no watermark.

### (g) Pitfalls to avoid (with the evidence)
| Pitfall | Evidence |
|---|---|
| Gating undo, layers, audio or delete behind payment | ArtFlow undo cap (152), FlipaClip audio (1,081), Tayasui delete (348) [R] |
| Any path where work can be lost ("backup" crashes, "not really saved", uninstall wipes local projects) | MediBang (365, 331, 175), Sketchbook (1,712), Infinite (357) [R] |
| Licence or account checks that block launch or offline use | CSP (527), Sketchbook "create an account in 7 days" (129) [R] |
| A desktop UI port with dockers | Krita (186, 210) [R] |
| Undo hidden in a corner, puck or menu | Sketchbook (225, 360) [R] |
| Touch slop that drops short stylus strokes | Sketchbook (1,278) [R] |
| Draggable UI with no recovery | Callipeg review [R] |
| No palm rejection, or palm touches that trigger gestures (redo, zoom) | Callipeg; MediBang (154, 109) [R] |
| Binding the S Pen **hover-click** (Samsung Air command takes it). Relying on S Pen Bluetooth Air Actions (**removed on the S25 Ultra**) | Samsung [C], SamMobile [C3] |
| Destructive gestures (3-finger scrub to clear) | Procreate [C]. Design judgement [I] |
| Transform resampling that accumulates blur | Sketchbook (2,242) [R] |
| Tapping outside to commit a transform | Adobe community [C3] |
| No frame holds, one global fps capped at 30 | FlipaClip [C] |
| Onion and fps settings buried three menus deep | FlipaClip ⋮ › Onion › edit [C] |
| Stroke-engine regressions shipped without a latency test on Samsung pens | ibis Sep 2026 (77), HiPaint lag [R] |
| Canvas rotation missing, zoom too shallow | Animation Desk (444), Tayasui [R] |
| Storage and permission prompts with no way through | Sketchbook (4,014) [R] |
| ChromeOS and large-screen regressions | Krita (266, 256) [R] |
| Ads of any kind in a creative flow | MediBang 50% of 1–2★ [R] |

---

## 6. Open questions for the owner
1. Should finger drawing stay enabled in pen mode behind a toggle (a Callipeg-style finger tool switch), or be off entirely?
2. On phone portrait, should undo/redo sit on the rail (thumb side, as proposed) or at top centre (CSP Simple)? The proposal favours the rail.
3. Should the first release target Note-class phones only (S Pen hover and button) with tablet layouts second, or both at once?
4. Should sprite-sheet export open SpriteLab directly, or write the files only?

---

## Sources

**Play Store listings (installs, ratings, update dates via Play page data)**
- ibis Paint X — https://play.google.com/store/apps/details?id=jp.ne.ibis.ibispaintx.app
- Infinite Painter — https://play.google.com/store/apps/details?id=com.brakefield.painter
- Clip Studio Paint — https://play.google.com/store/apps/details?id=jp.co.celsys.clipstudiopaint.googleplay
- MediBang Paint — https://play.google.com/store/apps/details?id=com.medibang.android.paint.tablet
- Krita — https://play.google.com/store/apps/details?id=org.krita
- Sketchbook — https://play.google.com/store/apps/details?id=com.adsk.sketchbook
- Concepts — https://play.google.com/store/apps/details?id=com.tophatch.concepts
- ArtFlow — https://play.google.com/store/apps/details?id=com.bytestorm.artflow
- HiPaint — https://play.google.com/store/apps/details?id=com.aige.hipaint
- Tayasui Sketches — https://play.google.com/store/apps/details?id=com.tayasui.sketches
- ArtRage — https://play.google.com/store/apps/details?id=com.ambientdesign.artrage.playstore
- PENUP — https://play.google.com/store/apps/details?id=com.sec.penup
- FlipaClip — https://play.google.com/store/apps/details?id=com.vblast.flipaclip
- RoughAnimator — https://play.google.com/store/apps/details?id=com.weirdhat.roughanimator
- Callipeg — https://play.google.com/store/apps/details?id=enoben.callipegandroidprod
- Animation Desk — https://play.google.com/store/apps/details?id=com.kdanmobile.android.animationdeskcloud
- Stick Nodes — https://play.google.com/store/apps/details?id=org.fortheloss.sticknodes
- Draw Cartoons 2 — https://play.google.com/store/apps/details?id=com.zalivka.animation2
- Stop Motion Studio — https://play.google.com/store/apps/details?id=com.cateater.stopmotionstudio
- Sketchar — https://play.google.com/store/apps/details?id=ktech.sketchar
- Review corpus tool: google-play-scraper (PyPI), run 2026-09-28

**ibis Paint**
- Toolbar and tool selection — https://ibispaint.com/lecture/index.jsp?no=04&lang=en
- Settings window details — https://ibispaint.com/lecture/index.jsp?no=81&lang=en
- Gestures and keyboard shortcuts — https://ibispaint.com/lecture/index.jsp?no=151&lang=en
- Create an animation — https://ibispaint.com/lecture/index.jsp?no=182
- New features (v10–14.1) — https://ibispaint.com/newFeature.jsp?lang=en

**Clip Studio Paint**
- Basic touch gestures — https://help.clip-studio.com/en-us/manual_en/750_gestures/Basic_touch_gestures.htm
- Smartphone Simple Mode basics — https://tips.clip-studio.com/en-us/articles/7906
- Smartphone canvas and gestures — https://tips.clip-studio.com/en-us/articles/2426
- Simple Mode for tablets (v2.1) — https://www.celsys.com/en/topic/20230727
- Studio vs Simple Mode — https://support.clip-studio.com/en-us/faq/articles/20230045
- Selection Launcher — https://help.clip-studio.com/en-us/manual_en/330_selection/Selection_Launcher.htm
- Android licence (monthly only) — https://support.clip-studio.com/en-us/faq/articles/20200097

**Sketchbook**
- Gestures — https://help.sketchbook.com/docs/gestures
- Customizing your UI — https://help.sketchbook.com/docs/customizing-your-ui
- Brush puck — https://help.sketchbook.com/docs/using-the-brush-puck
- Sketchbook Pro 9.4 (Apr 2026) — https://www.cgchannel.com/2026/04/sketchbook-releases-sketchbook-pro-9-4/
- Sketchbook (software), spin-off history — https://en.wikipedia.org/wiki/Sketchbook_(software)

**Infinite Painter**
- General controls — https://docs.infinitestudio.art/painter/studio/

**MediBang, ArtFlow, HiPaint, Tayasui**
- MediBang Android screen explanation — https://medibangpaint.com/en/manual/android/screen-description-and/
- ArtFlow review (edge menus) — https://www.talkandroid.com/reviews/featured-android-app-review-artflow-sketch-paint-draw-media-video/
- ArtFlow Studio — http://artflowstudio.com/
- HiPaint App Store listing — https://apps.apple.com/us/app/hipaint-sketch-paint/id1664391616
- Tayasui Sketches on Android — https://www.tayasui.com/sketches/android/

**Krita**
- 2026 roadmap — https://krita.org/en/posts/2026/roadmap-2026/
- Android UI feedback thread — https://krita-artists.org/t/krita-android-ui-feedback-and-suggestions/74677
- Touch Docker — https://docs.krita.org/en/reference_manual/dockers/touch_docker.html
- Usable interface for smartphones — https://krita-artists.org/t/usable-interface-on-krita-for-smartphones/141784

**Adobe Fresco**
- Android status (community) — https://community.adobe.com/t5/fresco-discussions/feature-request-fresco-for-android/m-p/10635293
- Wikipedia — https://en.wikipedia.org/wiki/Adobe_Fresco
- UI, gestures, Touch Shortcut — https://helpx.adobe.com/fresco/using/getting-started-with-user-interface.html

**FlipaClip**
- Onion skinning — https://support.flipaclip.com/article/17-onion-skinning
- FPS — https://support.flipaclip.com/article/56-frames-per-second-fps
- Using a stylus — https://support.flipaclip.com/article/69-using-a-stylus
- The Basics index — https://support.flipaclip.com/category/7-the-basics
- Frame Hold request (Planned) — https://flipaclip.userecho.com/communities/1/topics/171-frame-hold
- Wikipedia (2024 data breach, Plus) — https://en.wikipedia.org/wiki/FlipaClip
- 15 best free animation apps for Android (layout summary) — https://buzzflick.com/best-free-animation-apps-for-android-smartphones-tablets/

**RoughAnimator**
- Home — https://www.roughanimator.com/
- User guide (tablet) — https://www.roughanimator.com/userguide-tablet/

**Callipeg**
- FAQ (Android tablets only, exports, pricing) — https://callipeg.com/faq/
- Gestures on the canvas — https://callipeg.com/learn-gestures-canvas/
- Gestures on the timeline — https://callipeg.com/learn-gestures-timeline/
- Flip — https://callipeg.com/learn-flip/
- Release note 2.0 — https://callipeg.com/release-note-2-0-0/
- Android tablet launch video — https://www.youtube.com/watch?v=fBw0TxF_UL0

**Animatic, Toonsquid, Animation Desk**
- Animatic (removed Jan 2025) — https://www.appbrain.com/app/animatic-by-inkboard/com.inkboard.animatic
- Toonsquid FAQ (no Android) — https://toonsquid.com/handbook/guides/faq/
- Toonsquid Android discussion — https://github.com/keiwando/toonsquid/discussions/959
- Animation Desk — https://www.kdan.com/animation-desk

**Procreate / Pocket / Dreams**
- Gestures — https://help.procreate.com/procreate/handbook/interface-gestures/gestures
- Interface — https://help.procreate.com/procreate/handbook/interface-gestures/interface
- Selections interface — https://help.procreate.com/procreate/handbook/selections/selections-interface
- QuickShape — https://help.procreate.com/procreate/handbook/guides/quickshape
- Animation Assist interface — https://help.procreate.com/procreate/handbook/animation/animation-interface
- Animation Assist settings — https://help.procreate.com/procreate/handbook/animation/animation-settings
- Pocket interface — https://help.procreate.com/pocket/handbook/interface-gestures/interface
- Pocket gestures — https://help.procreate.com/pocket/handbook/interface-gestures/gestures
- Dreams Flipbook — https://help.procreate.com/dreams/handbook/draw-and-paint/flipbook
- Dreams gestures — https://help.procreate.com/dreams/handbook/interface-and-gestures/gestures
- Dreams review — https://www.awcomix.com/2023/11/procreate-dreams-review.html
- Moving a selection in Procreate (tips) — https://adventureswithart.com/problems-moving-a-selection-in-procreate/

**Android and Samsung stylus platform**
- Advanced stylus features (hover, cancel, front buffer, prediction) — https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features
- AOSP stylus — https://source.android.com/devices/accessories/stylus
- Air command (hover + button) — https://www.samsung.com/us/support/answer/ANS10002892/
- S25 Ultra S Pen usage — https://www.samsung.com/us/support/answer/ANS10004602/
- S25 Ultra S Pen loses Bluetooth — https://www.sammobile.com/news/it-is-true-galaxy-s25-ultra-s-pen-does-not-have-bluetooth-features/
- Android Central on Air Actions removal — https://www.androidcentral.com/phones/samsung-removed-air-actions-from-galaxy-s25-ultra-s-pen-but-who-cares

**Round-ups (secondary, used for consensus only)**
- Procreate alternatives for Android 2026 — https://www.softwaretestinghelp.com/procreate-alternatives-for-android/
- Proko: best Android drawing app — https://www.proko.com/community/topics/what-s-the-best-android-drawing-app
- TechCult best animation apps for Android — https://techcult.com/best-animation-apps-for-android/

**Joy Creator internal**
- `tasks/SPEC_20260910_SPRITELAB_UI.md` (40 dp hit boxes, value bubble above the finger, one-press-one-step undo)
- `tasks/TAPMAP_NOTE9.md` (owner device: Note 9 at a 1080×2220 override)
- `tasks/joypaint/research/R5_concepts_infinite_painter.md` (Concepts and Infinite Painter deep dive)
