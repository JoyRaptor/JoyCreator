# SPEC — Linking: parents, helpers, and moving many things at once (2026-09-24)

Owner's ask (JoyRaptor, 2026-09-24, close to verbatim): *"Linking. We have a primitive ability to
select multiple objects in the timeline. After Effects uses a pick whip — I don't know if that's the
best option. Objects must be easily linked, moving in time or in space or both, nestable, and we'll
probably need a helper/null object — just a container with a pivot other things link to. Linking has
to be super easy. Right now if I select several things it's not easy to move them, it's not clear
who they're linked to, how to unlink them, or whether to unlink one or many."*

Design only. No code in this doc. Builds on `PLAN_G9_LINK_ENGINE.md` (G9a–e built), composes with
`STUDIO_PLAN.md` P3 (Path object, text-on-path, rigging) and `SPEC_20260904_PUPPET_ARCHITECTURE.md`.

---

## 0. The recommendation in one paragraph (for the owner)

Linking becomes **one idea: "this follows that."** You pick one or more objects, tap **Link to…**,
then tap the thing they should follow — in the picture or on the timeline. That's it. Two switches
on each link say *what* follows: **Moves in the picture** (position, size, turn) and **Moves on the
timeline** (slide it later, they slide too). Nothing jumps when you link or unlink. A new object
called a **Helper** is an invisible handle with a pivot and its own lane — link a crowd of things to
it and animate just the Helper. Every linked object wears a small coloured tab on its lane saying
who it follows; its drawer has a **Link** row that says *"Follows ⟨Hat Helper⟩ · Unlink"*. Unlinking
from an object unlinks only that object; unlinking from the parent's drawer offers "all children".
Separately, and first: in Select mode, **dragging any selected object drags all of them**.

---

## 1. What exists today (precise)

**Two-and-a-half link systems already ship, none of them parent/child in space.**

| System | What it does | Where |
|---|---|---|
| **G9 peer link groups** | Symmetric "move together". Axes TIME (move-only, never trim) and OPACITY (text + sprites only). Built 2026-07-19. | Model `layers/LinkGroup.java:26`, `LinkMember.java:16`, `LinkedProperty.java:9` (POSITION/SCALE/ROTATION declared, never propagated). Engine `model/Timeline.java:2820-3265`. |
| **G5 visualizer attach** | A visualizer rides the master clip under it (host-relative time). Shown as a transient "preset" link group. | `Timeline.attachVisualizerToHostUnderStart` `Timeline.java:2784`, `synthesizeG5PresetLinkGroups` `:3246` |
| **Text/image anchoring to footage** | Every text/image overlay silently anchors to the master clip under its start (`hostClipId`), so it follows when footage is re-cut. Invisible to the user. | `Timeline.attachOverlayToHostUnderStart` `Timeline.java:2451`, `TextOverlayItem.java:431-473` |
| **Dual-stream clip link** | Screen + webcam pair (`Clip.linkedClipId`), edits mirror. | `Timeline.linkClips` `:2714`, batch menu `FaditorEditorActivity.java:14735-14743` |
| **Mask "Move with the object"** | A mask captures the object's pose at switch-on and then follows its X/Y/scale/rotation. **This is exactly the space-parenting math we need.** | Capture `tools/PipDrawerTabs.java:963-990`, `tools/MaskKeyPanel.java:218-240`; math `model/MaskAnimator.java:143-192` (`applyLink`: delta from a captured base, rotation done in pixels to avoid shear) |
| **Avatar rig part hierarchy** | `parentId` per rig part, cycle validation, parent picker list. Inside one object, not timeline objects. | `avatar/AvatarRig.java:55`, cycle walk `avatar/AvatarRigValidator.java:63-75`, picker `avatar/AvatarStudioActivity.java:562-598` |

**How G9 behaves.**
- *Making a link*: Select mode (toggle `tool_select` / `btn_select_mode`, `FaditorEditorActivity.java:3379-3383`, cycles OFF→crossing→window `:14668`) → box or tap objects → **hold** on a selected object (`EditorTimelineView.java:7946-7949`, `ITEM_PICKUP_MS`) → batch menu "Link objects…" (`FaditorEditorActivity.java:14722-14731`) → checkbox dialog Timing / Opacity (`:14868-14885`) → peer group created, one undo step (`:14894-14964`). Only text, sprite, audio, visualizer, PiP clips are linkable; master clips are stripped (`:14912-14917`).
- *Propagation*: `Timeline.resyncLinkGroups()` `:2987`, called from `syncTimelineOverlays()` after every edit (`FaditorEditorActivity.java:14282`), at load (`ProjectStorage.java:3596`) and before every export (`ExportManager.java:731, 1030, 1262, 1680`). Peer TIME = "exactly one mover pushes its delta" (`resyncPeerTimeGroup` `Timeline.java:3029`); OPACITY twin `:3090`. Host/rider TIME pull exists in the engine (`:2996-3007`) **but no UI ever creates a host/rider group.**
- *Showing it*: a purple two-ring chain badge in the block's top-right corner, only if the block is wider than 26dp (`layers/LayerRowRenderer.java:2159-2168`). **It does not say who the partners are.** Tapping it does nothing.
- *Breaking it*: the object's hold-menu gets "Unlink timing…" → "This object only" / "Unlink whole group" (`maybeAddLinkActions` `FaditorEditorActivity.java:15054-15073`; wired for text `:27768`, sprite `:28114`, visualizer `:31118`; one-undo snapshot `unlinkTimeMember` `:15129-15157`).
- *Storage*: tolerant `timeline.linkGroups[]` (`ProjectStorage.java:2600-2624` save, `:3554-3597` load; unknown axes dropped).

**Why multi-select moving feels broken.** In Select mode, a drag that starts on an object calls the
single-item gesture controller (`EditorTimelineView.java:7959-7975` → `LayerGestureController.beginPickup`),
which tracks exactly one `activeItem` (`LayerGestureController.java:270`). **Only the touched object
moves; the rest of the selection stays put.** There is no multi-select in the preview at all.

**Dead knob.** `handleLinkTap`/`showLinkOptions` (`FaditorEditorActivity.java:19318-19335`) offer
"Link selected"/"Unlink selected" that only show a toast and do nothing; the button is hidden
(`:3282`) but still wired (`:3278, 3290`). Delete it when the Link row ships.

---

## 2. What the leaders do (research summary)

| App | Make | Show | Break | Notes for us |
|---|---|---|---|---|
| **After Effects** | Pick whip: drag the spiral from the child's Parent column onto the parent layer (or pick from the Parent dropdown). Shift+whip snaps child onto parent. | Parent column names the parent. Nothing on the canvas. | Set Parent to "None". | Keep-world on link is the default. **Opacity is NOT inherited.** Nulls: Layer › New › Null — an invisible square with an anchor point. The whip is a mouse gesture; the column is a desktop spreadsheet. |
| **Alight Motion** (touch, closest peer) | Select child → **Layer Parent** button at top of timeline → pick parent from a dropdown list. | Parent name in the list. | Pick "none". | Has null objects. Inherits position/rotation/scale. **Their docs warn the playhead position at link time matters** when either layer is animated — the same keep-world subtlety we must solve honestly. |
| **Moho** | Reparent Bone tool: select child, **click the parent**; click empty = unparent. | **Arrows drawn from child to parent** on the canvas while the tool is active. | Click empty space. | Tap-child-then-tap-parent is the most touch-friendly model in the pro world. |
| **Blender** | Ctrl-P › Object / **Object (Keep Transform)**; Child-Of constraint with "Set Inverse". | Dashed relationship line child→parent in the viewport; outliner nesting. | Alt-P › **Clear and Keep Transformation**. | Names the exact problem: parenting stores the *inverse* of the parent's pose at link time so the child doesn't jump. Known bug class: keep-transform with *animated* children. Empties = our Helper. |
| **Rive** | Drag an item onto another in the Hierarchy panel. Constraints (translation, follow-path…) are a separate "copy from target" system. | Tree indentation. | Drag out. | Parenting ≠ constraints — good split for our Path "follow along" later. |
| **Spine** | Bone tree; per-bone inherit toggles (rotation / scale / reflection). | Tree + bones on canvas. | Re-parent in tree. | Per-link inheritance switches are proven; we expose only two (picture / timeline) plus opacity. |
| **Figma** | Group (children keep canvas coords, group box follows them) vs Frame (children in frame-local coords, constraints). | Layer tree. | Ungroup. | Our Helper is a Frame-like thing with a pivot, not a Group box. |
| **Procreate Dreams** | Select tracks with Pencil in Timeline Edit → Group. Group moves/animates as one. | Collapsible group track. | Ungroup. | Proof that "select several, then make a container" is natural on a tablet. |
| **Final Cut Pro** (Mac/iPad) | Clips above the storyline are *connected* to the clip below. | **Thin connection line** from the connected clip to its host; dots when not selected. | Move the connection / lift. | The model for our timeline tether: a thin line only while selected. |
| **LumaFusion / CapCut / Premiere / Resolve** | Time-only linking: secondary clips follow the primary track (LumaFusion link toggle, CapCut desktop "linkage"), A/V link + Linked Selection toggle (Premiere/Resolve), compound/nested clips. | Chain icon / toggle state. | Unlink command. | Time-linking is table stakes; none does space-parenting. *(LumaFusion/CapCut/Kinemaster details from prior knowledge — web research was cut short; not re-verified.)* |

**Takeaways.** (1) On touch, the winners are *pick from a list* (Alight Motion) and *tap child then
tap parent* (Moho) — not a drag-whip. (2) Keep-world on link and on unlink is universal and must be
the default. (3) Showing the relationship as a **line child→parent** (Moho, Blender, FCP) is the
most readable cue, shown only while selected so it doesn't clutter. (4) Nobody inherits opacity by
default except group/container models (Procreate Dreams, Figma groups).

---

## 3. The model

### 3.1 Parent link (new) — "this follows that"
One record per child. **A child has at most one parent** (that single rule gives nesting, cycle
checks, and a clear "Follows X" label). A parent can have many children.

```
ParentLink {
  childKind, childId          // TimedItem.payloadKind() tags + the payload's own UUID
  parentKind, parentId, parentSub   // parentSub reserved: a bone id later (§8.3), null now
  boolean space;              // "Moves in the picture": X, Y, scale, rotation
  boolean time;               // "Moves on the timeline": child start = parent start + offset
  boolean opacity;            // child opacity × parent opacity (default: ON for Helpers, OFF otherwise — Q1)
  float baseX, baseY, baseScale, baseRot;   // parent WORLD pose captured at link time
  long timeOffsetMs;          // child start − parent start, captured at link time
}
```

Why a new record and not another `LinkGroup`: G9's `axisOwner` rule (`Timeline.java:3215`) makes an
(item, axis) belong to one group — but in a chain, a middle object is *host* of one relation and
*rider* of another on the same axis. The one-parent-per-child key is the correct uniqueness rule for
hierarchies. The TIME math is **reused, not rewritten**: `resolveLinkStartMs` (`Timeline.java:2873`)
and `applyLinkStartMs` (`:2913`) are already payload-generic.

### 3.2 Space: keep-world, the mask way
Exactly `MaskAnimator.applyLink` (`MaskAnimator.java:159-192`) generalised from "mask follows object"
to "object follows object": at link time capture the parent's world pose (`base*`). At time *t* the
parent's delta (`scale ratio`, `rotation delta`, `translation`) is applied to the child's **own
authored pose**, about the parent's pivot, **in pixels** (the shear lesson in that file's javadoc).
- Link = nothing moves (base equals the parent's current pose at the playhead).
- The child's own keys stay the child's keys; they just ride the parent. Same as AE/Blender "parent
  inverse" — but stored as four floats, not a matrix, so it's readable and harness-testable.
- Honest caveat (Alight Motion's warning): if the *parent* is animated, "nothing jumps" is true at
  the playhead where you linked. The Link-to banner says "Linking at 0:04.2 — objects stay where they
  are now."
- Opacity (if on): multiply by the parent's world opacity. Helpers start at 100% so linking to one
  never dims anything.

### 3.3 Time
`time=true` → child start = parent start + `timeOffsetMs`, re-derived on every resync (the G5/host
pattern). **Move-only** (owner's 2026-07-19 ruling #1): trimming the parent does not trim children.
Children can still be moved on their own — that rewrites `timeOffsetMs` (it's a "slide within").

### 3.4 Nesting and cycles
- Resolve in parent-before-child order (depth-first from roots), so a grandchild sees its parent's
  already-resolved world pose. World pose = parent world ∘ own.
- **Cycle prevention at link time**: walk up from the proposed parent; if you meet the child, refuse.
  Same walk as `AvatarRigValidator.java:70-75`. In the pick UI, would-cycle targets are simply dimmed
  and untappable — you can't make the mistake.
- Load-time safety: a cycle or dangling parent in a saved file drops that link and logs (the
  `pruneLinkGroups` philosophy, `Timeline.java:2964`). Depth cap 32.
- A child's own children come with it when it's linked, unlinked or moved — subtrees are units.

### 3.5 The Helper (null object)
New payload kind `"helper"` (`HelperObjectItem`, sibling of `SpriteOverlayItem` in shape):
`id, name, colour, startMs, endMs (default: project length), layerId, centerX/Y, sizeFraction,
rotationDeg, opacity (100%), pivot (nine-dot, reuses PivotPickerPopover), KeyframeSet (item-local,
like text/sprites), hidden, locked`.
- **Never rendered in export.** In preview: a small crosshair square + its name, drawn only when it
  or one of its children is selected, or when "Show helpers" is on (Q5).
- Its own lane with a distinct tape: dashed outline in its link colour, label "◎ Hat Helper · 3".
- Animatable exactly like any object (same drawer Transform tab, same ◇ keys).
- Created from the Add sheet ("Helper"), or in one step by **Link to… › New helper** (§4).
- Why it matters: a Helper is also the cleanest *group* — "select 5 things → Link to new helper" is
  Procreate Dreams' Group, but animatable and nestable.

### 3.6 Existing peer groups
Kept working, saved and loaded as today. Rule to avoid two time-owners: **an object that follows a
parent in time cannot also be in a peer TIME group.** Linking a peer-grouped object to a parent
links its whole peer group (they're "together" by definition) and dissolves the peer group, in the
same undo step. Later (phase L5) the batch menu's "Link objects…" becomes "Link to new helper".

---

## 4. Interaction — making a link on a phone

### Three alternatives considered

**A. Tether drag (a touch pick whip).** Drawer Link row has a ⛓ knob; drag it out onto the parent
in the picture or timeline; targets light up and a name bubble follows the finger.
✚ direct, familiar to AE users, great with a stylus.
✖ one-handed thumb across the whole screen is hard; the drawer covers half the picture; small or
overlapping objects and invisible Helpers are unhittable; collides with existing hold-drag moves.

**B. "Link to…" pick mode (Moho + Alight Motion).** Select one or more objects → tap **Link to…**
(drawer Link row, batch menu, or hold-menu) → a banner: *"Tap what these should follow"* + two
buttons **List** and **New helper** + **Cancel**. Valid targets glow in the picture *and* on the
timeline; would-cycle targets dim. Tap one → linked, banner closes, tethers appear.
✚ taps only (one-handed, baby-in-arm), works for many children at once, stylus-precise, the List
fallback reaches hidden/overlapping/off-screen things and Helpers; "New helper" makes a group in one step.
✖ a mode — mitigated by the banner, Cancel, and auto-exit after one pick.

**C. Timeline drag-onto (Rive hierarchy).** Hold a lane block and drop it onto another block to parent.
✚ spatial, no menus.
✖ the timeline hold-drag already means "move in time / change lane" — ambiguous and dangerous;
does nothing for picture-space targets; no way to pick a Helper you can't see.

### Pick: **B — "Link to…" pick mode.**
It is the only option that is fully one-handed, works identically in the picture and on the
timeline, handles many children, and can never be mis-triggered by a move gesture. A (tether drag)
can be added later as a stylus accelerator on the same code path if the owner wants it.

**Flow details**
- Entry points (all the same action): drawer **Link** row › *Link to…*; Select-mode batch menu ›
  *Link to…* / *Link to new helper*; object hold-menu › *Link to…*.
- The two switches default: **picture ON, timeline ON**, opacity per §3.2. Change them later in the
  Link row; no dialog up front (the current checkbox dialog `FaditorEditorActivity.java:14868` goes away).
- *New helper* places a Helper at the centre of the selection's bounding box, spanning the selection's
  time range, named "Helper N", then links everything to it. One undo step.
- Master (film) clips can't be children in v1 (their time is the tape). They can be **parents in
  time only** later — that's today's invisible anchoring made visible (L5).

---

## 5. Showing links

**Timeline**
- **Tether tab** on the child's block: a 3dp strip on the left edge in the link colour, plus a chip
  "↳ Hat Helper" when the block is wide enough (replaces the anonymous two-ring badge,
  `LayerRowRenderer.java:2159-2168`, for parent links; the ring stays for peer groups).
- Each parent gets a colour from a small link palette; all its children share it (colour guides).
- Parent block: a count chip "⛓3".
- **While a child or parent is selected**: a thin vertical tether line from the child's block to
  the parent's block (Final Cut's connection line); faint otherwise-invisible.

**Preview**
- While selected: a faint line from the child's pivot to the parent's pivot, the parent pivot drawn
  as a small crosshair (Moho/Blender arrow). Selecting a parent faintly outlines its children.
- In Link-to pick mode: every valid target glows; would-cycle targets dim.

**Drawer — a Link row** (top of the Transform tab, row anatomy per STUDIO_PLAN #9:
`[⛓] [title] [switches] [action]`):
- Unlinked: `⛓ Not linked · [Link to…]`
- Linked: `⛓ Follows ‹Hat Helper› · Picture ☑ Timeline ☑ · [Unlink]` — tapping the name selects the parent.
- On a parent: `⛓ 3 follow this ▸` → a small list (each row: name · Unlink), plus **Unlink all**.
- Every control gets a hover label (stylus/mouse, standing rule).

---

## 6. Unlinking — one vs many (plain rules)

| Where you tap | What happens |
|---|---|
| **Unlink** in an object's Link row | Only *that* object stops following. It stays exactly where it is now. Its own children come with it. |
| Parent's **Unlink all** | Every direct child stops following; each stays where it is. |
| Select several → batch menu **Unlink** | Each selected object stops following its parent. |
| **Delete a parent** | Children stay where they are and become unlinked (Q4); toast "3 objects unlinked · Undo". |
| Turn off **Picture** or **Timeline** switch | That half of the link stops; the object stays where it is. Both off = unlinked. |

"Stays where it is" = the parent's current delta at the playhead is baked into the child's pose
(static value, and shifted into every key of that property) — the AE/Blender "keep transform" rule.
Honest limit: if the parent was *animating* the child, that motion is gone after unlinking (Q2).

---

## 7. Moving many things at once (the first fix)

- **Timeline, Select mode:** a drag that starts on a *selected* object moves **every selected
  object** by the same time delta (lanes unchanged; lane changes stay single-object). Locked objects
  are skipped with a note. One undo step. Today's path is single-item
  (`EditorTimelineView.java:7959-7975`); the group move becomes a sibling of `beginPickup` that
  snapshots every selected item's start and applies Δ, reusing `applyLinkStartMs`-style writes and
  the batch-delete undo shape (`FaditorEditorActivity.java:14820-14856`).
- **Preview:** the timeline selection is the preview selection. Drag any selected object → all move
  in the picture. **Two fingers anywhere** on the preview with 2+ selected → move / pinch-scale /
  twist the whole selection about its common centre. Each object writes its own value at the playhead
  exactly as a single drag would (keyed objects get a key, unkeyed get a static change). One undo step.
- **Linked moves are free**: moving a parent moves its children through the resolver — nothing to
  propagate by hand.
- Nudge: when the owner finds themselves group-moving the same set twice, the batch menu's
  *Link to new helper* is right there.

---

## 8. Composition with the other primitives

1. **Masks — "Move with the object".** A mask on a child must follow the child's *world* pose. Make
   `TextOverlayItem.timelinePose()` (`:581`) and its sprite/PiP equivalents return world pose once
   parented, and masks inherit parenting for free. Later the mask's `LinkSource`
   (`PipDrawerTabs.java:728`) widens from "this object" to "any object" using the same Link-to picker
   ("Move with: this object ▾ / Hat Helper").
2. **Path object (P3 #1).** A Path is an ordinary linkable object: link it to a Helper and the whole
   path moves/scales/turns. Its points stay in its own local space.
3. **Text on a path (P3 #2).** Text points at a Path; it reads the path's *world* shape, so a path
   riding a Helper carries its text with no extra code. Text-on-path is a *reference*, not a parent
   link; the Link-to picker offers "Follow along path" as a separate relation when the target is a Path
   (Rive's split: parenting vs constraints).
4. **Motion paths (P3 #4).** Also a constraint, not a parent. Same picker, same tether visuals.
5. **Rigging (P3 #5 / puppet spec).** Bones live *inside* one object (`AvatarRig.parentId`) and keep
   their own drag-pin-onto-pin authoring. `parentSub` in §3.1 reserves "follow the head bone" (a hat
   on a puppet's head). Tether visuals match (child→parent line) so bones and objects read alike.

---

## 9. Engineering notes

**One write-point, again.** `Timeline.resyncParentLinks()` beside `resyncLinkGroups()` at every call
site (`FaditorEditorActivity.java:14282`, `ProjectStorage.java:3596`, `ExportManager.java:731, 1030,
1262, 1680`). It (a) prunes dangling/cyclic links, (b) pulls TIME in parent-first order,
(c) binds each child a transient parent-pose source.

**World pose in one place.** New pure `model/ParentPoseResolver` (JVM-harness it like
`ReplayMappingTest`): `worldPose(item, t)` = parent world ∘ `applyLink`-style delta ∘ own pose,
using the project canvas W/H for pixel-space rotation. Preferred wiring: the item's own
`animatedCenterX/Y/SizeFraction/Rotation/Opacity` (`TextOverlayItem.java:2284-2289`,
`SpriteOverlayItem.java:327-345`) compose the bound parent source, so the ~35 existing readers
inherit it unchanged. PiP clips read `getOverlayTransform()` directly — add one world accessor and
switch those readers. Audit raw reads such as the live-drag branch at `TextOverlayLayer.java:1407`.

**Editing a linked child.** Handle drags produce world deltas; convert through the parent's current
inverse (un-rotate, un-scale) before writing the child's own values — in the transform hosts
(`transform/AffineTransformHost.java`, `CornerPinTransformHost.java`, `SpineTransformHost.java`) and
in the drawer X/Y sliders, which show the child's own numbers with a caption "relative to Hat Helper".
Corner-pin/bend on a child: the parent delta wraps the child's finished quad.

**Files that must resolve the same hierarchy (preview = export).**
- Export: `export/CompositeExportOverlay.java`, `ImageOverlayDraw.java`, `ImageOverlayFrameOverlay.java`,
  `ImageBlendGlEffect.java`, `SpriteBlendGlEffect.java`, `TextFxGlEffect.java`, `GlPipFrameOverlay.java`,
  `PipFrameOverlay.java`, `ExportManager.java` (resync calls); masks via `model/MaskAnimator.java`,
  `MaskPathBuilder.java`, `MaskSdf.java`.
- Preview: `compositor/FxLivePreviewController.java`, `LayerImageOverlayView.java`,
  `OverlayVideoPreviewView.java`, `overlay/TextOverlayLayer.java`, `sprite/SpriteOverlayView.java`,
  `waveform/WaveformOverlayView.java`.
- A/B frame-diff proof owed for every renderer touched (absolute-geometry diffs, standing rule).

**Undo — one press, one step.** Link (N children), Link to new helper (create + link), Unlink one /
all / selected, switch flips, group move, delete-parent-with-bake: each one `LambdaAction` with
before/after snapshots, same ids on redo (pattern: `unlinkTimeMember`, `FaditorEditorActivity.java:15129`).

**Save format (tolerant, absent = none, zero migration).** Two new sibling arrays in `timeline`:
```json
"helpers":     [{ "id":"…","name":"Hat Helper","color":"#…","startMs":0,"endMs":12000,"layerId":"…",
                  "cx":0.5,"cy":0.4,"size":0.1,"rot":0,"opacity":1,"pivot":4,"keyframes":{…} }],
"parentLinks": [{ "child":{"kind":"textOverlay","id":"…"}, "parent":{"kind":"helper","id":"…"},
                  "space":true,"time":true,"opacity":true,
                  "base":{"x":0.5,"y":0.4,"s":0.1,"r":0}, "timeOffsetMs":1200 }]
```
Written by `ProjectStorage` next to `linkGroups` (`:2600`), read next to it (`:3554`); links to
unknown/missing ids dropped with a log. **Downgrade**: an older build ignores both arrays, so helpers
vanish and children fall back to their un-parented pose — run the `DRILL_SCHEMA_DOWNGRADE.md` drill
and bump the schema version so the old build can warn. (`ProjectStorage.java` has an unrelated
staged hunk right now — coordinate before editing.)

---

## 10. Build order (always green, each slice device-checked)

| Slice | What the owner gets | Scope |
|---|---|---|
| **L0 — Group move** | Drag any selected object in Select mode → all selected move in time. One undo. | `EditorTimelineView` select-mode drag, activity undo. No model change. |
| **L1 — Follow (smallest useful link)** | "Link to…" pick mode (timeline + List), Picture/Timeline switches, drawer Link row, tether tab + selected-line on the timeline, Unlink one/all, keep-world both ways. Text, images and sprites as parents *and* children. | `ParentLink`, `ParentPoseResolver` + harness, `resyncParentLinks`, storage, export parity for text/image/sprite renderers, A/B proof. Delete the `handleLinkTap` dead knob. |
| **L2 — Helpers** | Add › Helper; *Link to new helper*; preview pivot lines and crosshair; parent's "3 follow this" list. | `HelperObjectItem`, lane tape, drawer, preview draw (never exported). |
| **L3 — More kinds + preview multi-select** | PiP video and visualizers link; two-finger selection move/scale/twist in the picture. | PiP world accessor + readers; preview selection set. |
| **L4 — Composes** | Masks follow any object; Path objects/text-on-path/motion paths ride Helpers; "follow the head bone". | With STUDIO_PLAN P3 #1-#5. |
| **L5 — Tidy** | Peer groups offered "convert to helper"; film clips as time-only parents (today's hidden anchoring made visible). | Migration UI, `hostClipId` surfacing. |

L0 is independent and should ship first — it removes the "hard to move several things" pain today.
L1 is the smallest slice that answers "who am I linked to, and how do I unlink just this one".

---

## 11. Questions for the owner (plain language)

1. **Fading:** when a parent fades out, should the things following it fade too? *(Suggest: yes for
   Helpers, off by default for ordinary objects — After Effects never fades children.)*
2. **Unlinking something that was being carried by an animated parent:** it keeps the spot it's in
   *right now* and stops moving with the parent from then on. OK?
3. **Trimming:** earlier you chose "linked things slide together but don't get shortened". Keep that
   for parent links too?
4. **Deleting a parent:** the followers stay where they are and become free (suggested), or get
   deleted with it?
5. **Name and visibility:** "Helper", "Null", or "Handle"? And should Helpers show as a small
   crosshair in the picture all the time, or only when one is selected?

---

## 12. Owner's answers and the link tool (JoyRaptor, 2026-09-24) — SUPERSEDES §4 where they differ

### Answers to §11
1. **Fade with the parent?** Only when **opacity** is one of the linked properties. Opacity is a
   per-link switch like position/scale/rotation, not a special case.
2. **Unlink keeps the object where it is at that moment.** Yes — preferred.
3. **Linked things slide together and are never shortened.** Confirmed. If a slide would make a
   follower overlap something on its lane, it **drops into a new lane** instead. No overlap on a
   lane, no shortening because it got pushed against something.
4. **Deleting a parent asks.** A dialog: "This object has N dependents" + a checklist of them
   (checked = delete with the parent) + **[Never mind]** and **[Confirm — 5 of 8 objects will be
   removed]** (the button sums what will go). Unchecked followers are set free, keeping their pose.
5. **Name.** Brainstorm; the owner leans to **Dummy** or **Proxy** (also on the table: Helper,
   Null, Handle). Decide at build time; the code name stays `Helper` until then.

### The link tool — a transport button beside the magnet (same grammar as the magnet)
The owner's design replaces §4's "Link to…" drawer row as the primary door (the drawer row stays
as the place links are LISTED and unlinked).
- **Tap** = ARM. Whatever is selected becomes the link source; the icon lights. Every object that
  can accept the link gets a clear "can link here" treatment in the preview AND the timeline, so
  targets are hard to miss. The **next single tap on an available object** picks the target and
  opens a small **link-details popup**: which properties (position · scale · rotation · opacity ·
  time), relationship style, remembered from the last link made. As the choices change, the
  **tether's colour and animation** preview the relationship: parent→child = arrows travelling one
  way; peers = two-way movement. OK commits (one undo). **Tapping the link icon again disarms** —
  back to grey, nothing happened.
- **Double-tap** = link settings for the selected object(s): its current links and defaults. With
  several selected, fields that differ show merged in grey and are overwritten/unified by any
  change you commit.
- **Long-press** = the link TOOL's own settings: defaults, modes, "add a Dummy/Proxy to what's
  selected", and any other sub-tools.
- **Armed must not hijack navigation.** While armed, pinch-zoom, scroll, scrub and the minimap all
  behave normally; ONLY an explicit single tap on an available object picks a target.
- Once linked, tapping either object shows the thin tether in the timeline and/or preview.

### Beyond transforms (the After Effects lesson: things drive things)
- **Warp (bend) pins and puppet pins are linkable** — a hand pin can carry a prop.
- **Parameter drivers:** link one property to another of a different kind, e.g. motion → a hue
  slider, rotation → brightness, position → font size. A driver link has a source property, a
  target property and a mapping (range in → range out, optional curve).
- **Keyframing a change of parent** ("a person holds a ball, passes it to another, and the ball
  follows the second person's hand"): don't animate the ball — align it to a Dummy/Proxy and key
  the **proxy's parent** over time. These parent-switch keys and all the "creative connection"
  properties live in the **Dummy/Proxy's drawer**, which keeps every other drawer uncluttered.

### Build order change
L0 (group move) ✅ a9e1be4c. Next: **L1 = the link button (arm → tap target → details popup →
tether) for position/scale/rotation/opacity/time between text, images and sprites**, then
Dummy/Proxy objects with keyed parent switches, then pin links and parameter drivers.
