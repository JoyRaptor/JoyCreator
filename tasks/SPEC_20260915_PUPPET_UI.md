# SPEC — Puppeteering: the UI

Design settled with JoyRaptor over 2026-09-15, against the interactive study published at
`claude.ai/artifact/DTJmSn68ToxnqKF7nvvVTk`. The back end is a separate lane
(`SPEC_20260904_PUPPET_ARCHITECTURE`, stages 1–3 landed: trace → triangulate → solve).

**This spec owns the UI only.** Where it constrains the engine, it says so and says why —
those lines are the contract between the two lanes.

---

## 0. The decisions, in one place

| | Decision | Why |
|---|---|---|
| Tape | A recorded move is a **long diamond** with pointed caps; surviving keys sit inside it in the pin's own colour at ~50% | A bar and a keyframe must read as the same species. The taper is also literally the shape of the blend. |
| End keys | The caps **are** the first and last keys — not drawn again | Drawing both reads as two keys one frame apart |
| The word "take" | **Never shown.** Key made while playing → bar. Key made while stopped → diamond. | One primitive, and it generalises to position/opacity/volume later |
| Overdub | **Replace in range**, anchor in, blend out | The in-point never jumps; only the out-point does |
| Simplification | **On by default**, one Detail control | Option C is only usable because keys were thinned |
| Other pins' keys | **Not** ghosted on the tape. Pin-type colour + "tap another to see its keys" | A ghost strip of a live take is a thousand marks of grey |
| Weights | **Distance measured across the body**, then smoothed | See §4 |
| Bones | **Carry no keyframes.** A bone writes to the pins it joins | One motion, one place it is stored |
| The gate | A **puppet badge on the preview**, not a drawer setting | The accident it prevents happens while the drawer is shut |

---

## 1. The preview

**Pin types and colours.** Four types, and the colour is the type — it carries through to the
keyframes on the tape, which is how the tape says what kind of thing you are editing without
showing every pin at once.

| Type | Colour | Source |
|---|---|---|
| Pin (anchor) | `#F2B138` | new |
| Stiff | `#E5556B` | new |
| Dangle | `#39C5D8` | new |
| Free | `#A78BFA` | **`HandleModel.COLOR_GUIDE`** |
| Bone | `#5AA9FF` | **`HandleModel.COLOR_BEND`** |

Free and Bone deliberately reuse the transform tool's existing colours. A puppet handle and a
transform handle must look like the same family.

**A selected Free pin wears transform helpers**, drawn from the same vocabulary
`HandleModel` already defines:
- a dashed **circle** — the shape that file already assigns to a FREE corner
- an **amber arc** outside the top for rotate — `COLOR_SCALE` `#FBBF24`, the same amber and the
  same gesture as the existing spin arc
- an **amber square** for scale — the existing structural handle

**The puppet badge.** Top-right of the preview. A stick puppet on strings.
- Appears **only when the item has at least one pin.** Never clutter on anything else.
- Green = pins answer to touch. Tap → figure greys, a padlock appears in front of him, every
  pin greys and stops responding, bones dim.
- This **is** the gate. There is no drawer setting for it and no tab state to remember.
- ⚠️ **Collision check owed:** confirm nothing already occupies the preview's top-right at this
  size. The G3 keyframe ribbon rides the preview's LOWER edge, so that one is clear.

---

## 2. The drawer — a new "Puppet" tab on `ObjectDrawer`

Fixed row order. Nothing is mostly-empty; no toggle ever gets a line to itself.

```
[ Video | Mask | Key | PUPPET | Blend ]            ← existing tab strip
 1  ● name ──────────  ‹ ♦ ›  3/10   (●)          ← appears once the item has ≥1 pin
 2  Grab  Pin  Stiff  Dangle  Free  Bone          ← always present
 3  [ Selected | Character | Recording ]           ← scope
 4+ scope content
```

**Row 1 appears only once the item has at least one pin**, and it appears *above* row 2,
pushing the tools down. JoyRaptor's design: before you have made anything, the tools are what
you need and they are at the top; the moment a pin exists, naming and key navigation become the
common work. Nothing is ever removed from the screen, so the tool row is always reachable for
the seventh pin on day nine.

- Rule: row 1 exists **iff the item has ≥1 pin.** Delete the last pin and it goes. Symmetric
  and predictable.
- Row 1 holds, on one line: type swatch · editable name · `‹ ♦ ›` · key position · record button.
- **The `‹ ♦ ›` carries no label.** It is the program-wide `KeyframeDiamondControl` and it is
  high in the stack because after a live take, landing one frame off a key authors a second key
  beside the first — a jitter nobody can find afterwards. See INBOX 2026-09-15 on snapping.

**Row 2** is six equal tools. Approved at ~58px each on a 390px screen.
⚠️ The 8px labels under them are below legible — either the row gets taller or the labels go.

**Sliders** carry a grey eye on the left and stop short of the right edge. Eye on = the reach
ring stays visible; eye off = it shows only while dragging.

**Scope content:**

| Selected (by type) | Character | Recording |
|---|---|---|
| Pin: *nothing* | Softness | Record on touch · Snap to keys |
| Stiff: Area, Strength | Mesh detail | Detail |
| Dangle: Springiness, Settle, Mass, Max stretch | Gravity, Wind | Blend out |
| Free: Scale (turn/scale are gestures) | Show pins · bones · mesh | |
| Bone: Parent, Length, Rest angle, Reach, Stretchy, Joint limits | Edge threshold · expansion | |

⚠️ **Open:** tapping a pin while the scope is Character or Recording must snap the scope back to
Selected, or the tap appears to do nothing in the drawer.

---

## 3. Rigging an IK chain on a phone

The whole point: **there is no "create IK constraint" step.** A chain of bones *is* an IK chain.

1. **Pin** tool, tap the hip → an amber anchor. *An anchor is the chain root. That is what the
   Pin type is for — the setup is encoded in the pin types the user already understands.*
2. **Bone** tool, drag hip → shoulder → elbow → wrist.
   **A bone drag plants a pin wherever there isn't one**, so this one tool rigs the whole arm.
3. **Grab**, drag the wrist. The arm follows — `FabrikSolver` already exists and is tested.

No pole target, no chain-length field, no constraint dialog.

**Which way does the elbow fold?** The rest pose decides it: store the sign at bind time from
how the artwork is already bent. One "Flip elbow" toggle if it guesses wrong. One bit instead of
a third object to position.

**Tool arming resolves the gesture clash:** while Bone is armed, pins do not move — a drag
between them draws a bone. While Grab is armed, no bone is drawn.

**Joint limits default OFF.** Most cartoon rigs never want them.

**Stretchy** is a per-bone bit, not animation. Rigid bones make the shoulder snap when you drag
a hand past its reach — the ugliest thing in any IK rig. Stretchy lets the bone lengthen so the
hand stays under the finger.

---

## 4. Weights — the engine contract

**Ruling: measure distance ACROSS THE BODY, not through the air, then smooth the result.**

Everything else stays: the same `1/distance²` falloff, normalised across handles, which is
already density-adaptive — adding a pin next to another splits their influence with nothing to
set. Only the distance function changes.

Why this and not the fancier options:

| | Wait when a pin is dropped | To write | |
|---|---|---|---|
| Straight-line (today) | ~0 ms | done | **breaks once bones exist** — influence crosses the gap between two hands |
| **Across the body** | ~1–3 ms | ~60 lines | ✅ fixes exactly that bug, inside code that already works |
| Heat (Pinocchio/Blender) | ~5–20 ms | ~300 lines | smoother; built for bones, so a loose pin must be faked as a zero-length one; famous failure mode on awkward geometry, and traced art is awkward geometry |
| Bounded biharmonic | 0.2–2 s | weeks | best, guarantees, mixed rigs — but a QP solver in Java |

**All four cost the same at playback** — they are bind-time precomputes producing one weight per
vertex per handle, and the per-frame blend is identical. Moving up the ladder later is a solver
swap and touches no UI, model or export.

---

## 5. Engine contract — things the UI needs that the back end must guarantee

These are the non-obvious ones. Each is a bug if missed.

1. **Per-handle key storage.** `MeshPoseTrack` is whole-pose only and says so in its own doc —
   *"There is no API here that can address a single handle at a single time."* That rule is right
   for a lattice dot and **wrong for a puppet pin**, which is named, selectable and individually
   performable. Without a per-pin write, live overdub is impossible: recording pin B overwrites
   pin A. Scope the rule to the topology, not the track type.

2. **A chain simplifies on ONE shared set of key times.** If each pin in an arm is thinned
   independently, its keys land at different instants, the chain no longer solves to the same
   shape between them, and the limb wobbles. Non-obvious and will not be caught by looking at one
   pin.

3. **Chain operations are atomic.** A blend-out applied to one pin of a chain while its
   chain-mates do not blend will tear the limb. Blend the chain, not the pin.

4. **One drag of one pin = ONE undo step, even when it solves through three pins.** The seam
   already snapshots on `beginGesture`; the snapshot must cover the whole chain, not the touched
   pin.

5. **Anchor in, blend out.** A punch-in's first sample inherits the existing animated value (the
   finger grabs the pin where the animation already had it), so the in-point never jumps. The
   out-point does, and the last ~200 ms ramps back onto the old curve.

---

## 6. The adversarial findings, and how each is resolved

**A. A chain must simplify as ONE signal.** 🔴 → **RESOLVED.**
Do not thin each pin separately. Treat the chain's handles as a single vector — exactly the shape
`MeshPoseTrack` already thinks in — and run the curve simplification on that vector. Then the
error metric accounts for the whole limb, every pin in the chain keeps the **same key times**,
and the chain necessarily solves to a valid pose at every key.

Consequences, both good:
- Blend-out becomes atomic for free: blending the chain's shared keys blends the chain.
- **It dissolves finding C below.** Select any pin in a chain and you see the chain's timing,
  because they share it.

Cost: a long chain carries more keys than its quietest pin needs. Accepted — correctness over
bytes, and Detail still thins the whole chain together.

**Rejected alternative, recorded so nobody re-proposes it:** store only the dragged pin and
re-solve IK at playback. Tempting — one pin's keys, no wobble possible, far less data. It fails
twice. FABRIK seeds from the *current* joints, so it is history-dependent and therefore not
scrubbable backwards — the identical trap `DangleSim` already documented and solved by baking.
And it makes a mid-joint un-editable: hand-adjusting an elbow would mean a target *and* a joint
override, which is two sources of truth for one motion again.

**B. Record-on-touch is dangerous with chains.** 🟠 → **RESOLVED.**
**Movement gates recording.** A touch that never moves past the threshold (~8dp) selects and
records nothing. The first movement past it starts the take — and from that instant, holding
still IS recorded, because holding a pose against the motion underneath is a real performance.
No arming step, no new control, and it also kills the jitter spike a 3px "tap" would otherwise
author.

**C. Seeing a whole limb's timing.** 🟠 → **Dissolved by A** for chains. Loose pins (tail, head)
remain independent, which is correct — they are independent.
*Residual, not building now:* there is still no view of the whole character's timing. If it is
missed, the option is one opt-in **"Everything"** chip at the head of the chip row, read-only and
greyed. That is the rejected ghost strip in a hat, so it only earns its place if the absence is
actually felt — it must never be the default view.

**D. Scope not following selection.** 🟡 → **NOT A DEFECT. Over-graded on my part.**
Tapping a pin already changes four visible things: row 1's name and swatch, the key count, the
chip row, and the tape. The scope body *should not* change — Character settings are not about the
selected pin. No fix needed. (When the scope IS "Selected", the body updates, which is correct.)

**E. Six 8px labels at 58px targets.** 🟡 → **RESOLVED: make the row taller, label at 10px.**
Costs about 8dp of drawer height on row 2. Dropping the labels was considered and rejected —
five pin types are not self-evident as icons, and this row is the feature's front door. (The
owed app-wide hover-label sweep still applies on top; a tooltip cannot be the only affordance.)

**F. Puppet badge collision, top-right of preview.** ✅ → **CHECKED, clear** — but it found a
different one. Nothing is anchored inside the preview's top-right. `PreviewPipController` does
anchor `Gravity.TOP | Gravity.END`, but to `rootFrame`, not inside the preview.
⚠️ **However:** that controller promotes the whole preview into a small floating shell (`pipW`
wide). A 38dp badge would dominate a preview that size. **The badge must hide while the preview
is popped out.**

**G. Placing the first pin shifts the tool row down**, once per item. Accepted by JoyRaptor —
nothing is removed, and it reads as "you have made something."

---

## 7. Status — 2026-09-16, after the overnight run

**Grade honestly: 🟢 means JoyRaptor used it on a phone. Almost nothing here is 🟢 yet.**

| | Level | Notes |
|---|---|---|
| Trace → triangulate → weights → solve | 🟢 | He confirmed a rigged dinosaur bends, 2026-09-15 |
| Detached limbs as one puppet | 🟢 | Same session |
| Halo / depth ordering | 🟢 | "halo fix appears fixed" |
| Pins, bones, IK drag, dangle bake | 🟡 | Engine proved; the gestures have only been driven by him briefly |
| Puppet badge, drawer tab, persistence | 🟡 | On the phone, lightly used |
| **Keyframes — drop, delete, jump, per-pin** | 🟡 | Built and unit-proved; **nobody has keyed a pin on a phone** |
| **Live takes — record on touch, thin, blend out** | 🟡 | Same. The headline interaction is unexercised. |
| **The tape — bars and diamonds** | 🟡 | Drawn from key density; never seen |
| **The helper strip** | 🟡 | Four controls, drag-out, drag-in-to-delete, Z scrub. Never touched. |
| Shapes per pin type | 🟡 | Pushpin / square / teardrop / circle |
| Loupe, shared with the transform tool | 🟡 | **The migration could have broken the transform tool's own loupe and nobody has checked.** |

### What is deliberately NOT built

- **Sprites, PiP, text and the spine.** Puppets are image overlays only. The model would carry
  it (`MeshBendSeam.Owner` is six methods every type already has) but nothing is wired.
- **A take as a stored object.** Bars are inferred from key DENSITY — see `PuppetTapeMarks`.
  Thin a take hard enough and it correctly stops being a bar.
- **Per-pin easing.** Keys are written with the track's default curve.

### Owed, and known

1. **Nothing here is device-verified beyond the bend itself.** Twelve suites and ~390
   assertions run off device; that is not the same claim.
2. **The timeline grab bar is still dead** and is not this feature's doing — a diagnostic now
   logs on the editor opening (`GRABBAR setup ok` / `SKIPPED`) rather than needing a drag.
3. **One corner widget is still unidentified** ("the audio clipping widget"). Three of the four
   things that were colliding are accounted for; a screenshot settles the rest.
4. **`componentTimes` counts the two ends of a held value as keys**, which is honest about
   storage and a lie on screen — hence `PuppetKeys.displayKeyCount`. Any new surface reading key
   counts must use the display one.
