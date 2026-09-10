# PLAN: Timeline Manipulation V1 — the umbrella

> Written 2026-08-03 (Opus 5) on JoyRaptor's instruction: *"get everything specced out so we know it all
> will work together nicely and efficiently."* This is the UMBRELLA. The detailed interaction specs
> live in `PLAN_LAYERS_UX_ADDENDUM.md` §3A (M12 spine drag) and §4A (M11 anchoring); this document
> exists to say how they, and the access-point work, fit together — and to name the shared primitives
> so they get built ONCE instead of three times.
>
> **Read order for anyone picking this up:** `LEDGER.md` §3i → this file → `PLAN_LAYERS_UX_ADDENDUM.md`
> §3A/§4A → the milestone you are building.

---

## 0. The two problems this plan closes

**P1 — the spine is an island.** Every other row in the timeline participates in one drag language
(pick up, hover, drop, snap, one undo step). The master track does not: `TrackKind.isLane()`
deliberately excludes `MASTER`, and `LayerGestureController` documents that master items are
"structurally unreachable" from the row-gesture path. So the single most-wanted edit — move a clip
between the spine and a layer — has no expression, and layers do not follow the spine when it moves.

**P2 — engines without doors.** A repeating failure mode in this codebase: a capability is built,
proven, wired into export, and then has no way for a user to reach it. Confirmed instances: the whole
masking / chroma-key / track-matte family (`CompositingSpec` — only writer is the deserializer), and
blend modes (`BlendModeGlEffect` renders MULTIPLY/SCREEN/OVERLAY/ADD; `setOverlayBlendMode` is only
called by the deserializer). A full audit is in flight; §4 below turns the fix into a RULE so new
engines stop shipping doorless.

These are one plan because they share primitives (§2) and because P1's interaction language is the
vehicle that gives several P2 features their door.

---

## 1. Architecture — what is true today

Established by reading the code 2026-08-03. **Do not re-derive; do correct if wrong.**

- **A master clip and a PiP overlay are THE SAME CLASS.** `Clip`, in two lists on `Timeline`
  (`clips` and `overlayClips`), discriminated solely by `isOverlayClip()` → `layerId != null`.
  Consequence: **promote/demote needs no schema change and no type conversion.**
- **Floating rows are neutral substrates.** Per `SPEC_NEUTRAL_SUBSTRATE.md` (S0–S5 landed), any
  visual payload may sit on any floating lane; membership is by `layerId`, not by kind. Audio is the
  only genuinely separate band.
- **`Track` is a VIEW, rebuilt on every `getLayers()` call.** Mutable per-track state lives in the
  `TrackFlags` side-table keyed by track id. Anything M11/M12 needs to persist goes on the PAYLOAD or
  in a side-table — never on the ephemeral view.
- **Ripple/gap already ships** (`toggleRippleMode()`); gap mode substitutes a black spacer clip so
  downstream clips do not move. `PLAN_LAYERS_V2.md`'s unchecked M11 box is stale on this point.
- **Transition indices are repaired and undo-safe** — `removeTransitionsForDeletedClip` /
  `shiftTransitionsAfterInsert`, snapshot-restore audited in `AUDIT_TRANSITION_INDEX_UNDO.md`.
- **Drag navigation exists for layer items** — edge auto-pan with velocity ramp (A1) and minimap
  drag-nav (A2), both in `EditorTimelineView`.
- **Anchoring does NOT exist.** `anchorClipId` appears nowhere in the source.

---

## 2. THE SHARED PRIMITIVES — build each ONCE

This section is the whole point of the umbrella. Each primitive below is needed by two or more
milestones; building them per-milestone is how this gets expensive and inconsistent.

### 2.0 `RiderAttachment` — ONE host/rider model, THREE policies ⚠ THE KEYSTONE
**Needed by:** M11 anchoring, caption attach (specced, unbuilt), visualizer attach (BUILT), M12.

Established 2026-08-03 by the adversarial audit + JoyRaptor's push-back on captions. **Three
relationships in this codebase are the same relationship**, and two of them were about to be built
as separate systems:

| Rider | Host | Status before this plan |
|---|---|---|
| Visualizer | clip under its start | **BUILT** — `Timeline.attachVisualizerToHostUnderStart` :1563, `detachVisualizer`, `resyncAttachedVisualizers` :1535 |
| Caption | its transcribed source clip | **SPECCED, NO CODE** — `PLAN_GESTURE_CONTRACT_FINAL_20260706.md` §4.1; `PLAN_G9_LINK_ENGINE.md` §4.4 states plainly it "has no code yet" and is "out of scope for G9" |
| Layer item | its anchor clip | **SPECCED, NO CODE** — §4A of the addendum |

The audit found the built one CONTRADICTS §4A: `resyncAttachedVisualizers` **truncates** the rider
into the host's span, where §4A says duration is the user's and must not change. **That is not a
conflict to resolve — it is a POLICY that differs by rider type**, and naming it is what stops a
third and fourth mechanism appearing:

- **Visualizer → SHIFT + TRUNCATE.** It is bound to the host's audio; outliving the host is
  meaningless. Today's behaviour, preserved deliberately rather than by accident.
- **Caption → SHIFT + RE-SOURCE.** It reads the host's transcript; §4.1's detach/tie-break dialog
  is the policy's escape hatch.
- **Layer item → SHIFT ONLY, NEVER TRUNCATE.** Duration belongs to the user.

**Model:** `hostClipId` + `offsetMs` + `policy`, with resolution/repair living in `Timeline` (NOT at
UI call sites — the audit found ~35 `removeClip` sites across 5 files incl. the AI appliers and
undo; a repair that lives in the delete-prompt path orphans anchors on every other route).

**Consequence for M12:** "carry the captions over" becomes *the caption rider travels with its
host*, inherited rather than special-cased. JoyRaptor, 2026-08-03: *"we should be able to carry
everything over, including captions."*

### 2.1 `TimelineShift` — the single time-shift authority
**Needed by:** M11 anchoring, M12 promote/demote, ripple/gap, future lane consolidation (§4.5d).

One authority that, around any structural master edit, computes **each master clip's start-time
delta** (before → after) and applies the consequences: shift anchored items by their anchor's delta,
run the collision resolve, snapshot for undo.

Every structural edit funnels through it. **The call-site map (audit in flight) determines whether
this is one chokepoint or several**; if several, this class is still the single implementation and the
sites merely call it. Rationale: a missed path means layer objects silently desync from their clips,
which is invisible until export — the worst failure shape this project has.

**BUILD IT PURE — android-free, operating on ids and times, not on `Clip`/`Timeline` objects.**
This is the `TransitionIndex` pattern (extracted from `Timeline` precisely so `TransitionIndexTest`
could compile against nothing but the annotation stubs) and the `CompositingSpec.featherRadiusPx`
pattern (math on the model class so both renderers ask the same authority). It is the difference
between phase 1 being provable by the LIGHTWEIGHT harness — `javac` a couple of model files plus
`stubs/androidx/annotation`, seconds to run — and needing `run-matte.sh`'s ~70KB real classpath,
which is long enough to exceed the Windows command line and fail SILENTLY (hence that script's
explicit "no class file" control). Purity here buys a fast, trustworthy proof loop for the exact
logic most likely to be subtly wrong.

### 2.2 `DropResolver` — one typed answer to "where does this land?"
**Needed by:** M12 spine drop, existing layer-to-layer drop, new-layer drop, future lane moves.

Given (screen x/y, payload kind, current viewport), return ONE typed result:
`SEAM_INSERT | SPLIT_INSERT | BEFORE_CLIP | AFTER_CLIP | NEW_LAYER | ROW_DROP | REFUSED(reason)`.

The renderer draws **purely from that enum**. This is what makes §3A.4's colour language correct by
construction rather than by discipline — an indicator cannot disagree with the drop, because both
read the same value. It also gives refusals a reason string for free, satisfying "never silently
ignore an illegal drop".

### 2.3 Payload-agnostic drag navigation
**Needed by:** M12 (master clips), already used by layer items.

Edge auto-pan, minimap drag-nav, and the new timecode chip must key off "a drag is active" rather
than "a layer item drag is active". Extend the existing implementations; **do not fork them.** Adds
content-aware pan velocity (§3A.3) in one place, so every drag inherits it.

### 2.4 Structural-edit undo envelope
**Needed by:** M11, M12, and retroactively every existing structural edit.

One helper that snapshots {clip list order, transition placements, anchor offsets, displaced item
positions} and restores them as ONE step. The transition half already exists and is harness-pinned;
generalise it rather than writing a second snapshot mechanism beside it.

### 2.5 The access-point triple (see §4)
**Needed by:** every P2 feature.

---

## 3. Build order and dependencies

Anchoring lands FIRST because M12's demote must know what the layers above do when the spine moves;
building M12 first would bake in an answer that anchoring then has to unpick.

| # | Phase | Depends on | Device needed? | Est. |
|---|---|---|---|---|
| 0 | Audits + fold findings into §3A/§4A | — | no | in flight |
| 1 | `TimelineShift` + anchor model + JVM harness | 0 | **no** | 1–2 sessions |
| 2 | Orphan-anchor prompt + settings row | 1 | light | ~0.5 |
| 3 | `DropResolver` + M12 model ops (promote/demote) + harness | 1 | **no** | 1–2 |
| 4 | M12 gesture core (liftable master, spine target, seam line, nav) | 3 | **YES** | 2 |
| 5 | Dwell-armed split-insert | 4 | **YES** | 1 |
| 6 | Polish: timecode chip, magnet threshold, refusal feedback | 4 | **YES** | 0.5–1 |

**Total 6–9 sessions**, of which phases 1–3 (roughly half) need no phone at all.

### ⚠ The device gate
House rule: the Note 9 `<note9-serial>` is the sandbox and the ONLY phone that may be driven; if
the Note 20 `<note20-serial>` is attached, device work STOPS (it holds the user's real 45-minute project).
**As of 2026-08-03 only the Note 20 is attached.** Phases 1–3 are therefore the correct place to
spend effort now, and they are sequenced first for that reason as well as the dependency reason. A
drag cannot be replaced by reasoning — phases 4–6 are NOT done until fingers have been on the Note 9.

---

## 4. P2 — the access-point rule (stop shipping doorless engines)

The audit will enumerate the backlog. This is the standing rule that stops it regrowing:

> **A capability is not DONE until it has an access point.** Three surfaces, and a feature declares
> which of them it uses:
> 1. **Tool-row chip** — discoverability. Enabled/disabled against the current selection.
> 2. **Object long-press menu** — the contextual route, for when the user is already on the object.
> 3. **AI tool** (`AIToolExecutor` + `getToolDescriptions()`) — conversational route.
>
> A feature reachable ONLY by hand-editing `project.json` is UNSHIPPED, regardless of how well its
> engine is proven. Say so in the ledger rather than counting it as complete.

This is already the shape §3a proposed for Mask & Key (chip + object menu + a proposed AI path); it is
promoted here from a one-off design to the project's default.

**Ordering note:** masking/blend-mode UI (§3a) is NOT folded into this plan's phases. It is the next
body of work after, and it will inherit `DropResolver`-style typed state and the access-point rule.
Keeping it separate is deliberate — merging two large UI efforts into one branch is how both slip.

---

## 5. Invariants that hold across every phase

Violating any of these has already cost this project a bug, and each is traceable to one:

1. **ONE undo step per user gesture.** A drag that panned eight minutes, split a clip and moved 30
   anchored items is still one step (M10 established the merge pattern).
2. **Preview and export agree, or the divergence is explicitly sanctioned and recorded.** Exactly one
   sanctioned divergence exists today (GHOST blur).
3. **Visibility decisions live only in `LayerPreviewController.visible*`.** Never add a second filter
   in `ExportManager` — conflating "visible" with "renderable" produced two bugs at once.
4. **No silent repair.** If the system moves something the user did not move (collision resolve,
   orphan re-anchor), it says so, with undo to hand.
5. **A refused interaction states its reason.** Silence reads as a bug.
6. **`overlayClips[].layerId` is NEVER null** — null means master-clip semantics and breaks
   `isOverlayClip()`. Both promote and demote must maintain this on every path.
7. **Prove, do not assert; and check the control can discriminate.** A green build proves nothing —
   scan the artifact. A harness pass proves nothing if javac failed.

---

## 6. Explicitly OUT of scope for V1

- Cross-type z-interleaving inside a mixed lane (compositor unification — `SPEC_CROSSTYPE_Z.md`).
- Masks on text/sprites (`TextOverlayItem` has no `compositing` field; deferred by the user).
- A global magnet toggle (per-item unlink covers V1).
- Back-propagating the new interaction language to trims/reorders/lane moves. **Logged as follow-on
  in §3A.7 — do not do it opportunistically inside M12.**
