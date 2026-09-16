# PUPPET — the finish ledger

JoyRaptor, 2026-09-16: *"systematically go through all the functions that were previously
designed, that we talked about, that we mocked up in the web thing ... bring everything to a
finished state. And don't come back here until it's done."*

This file is the list. Every row is either **DONE** with the commit that did it, or **OPEN** with
the reason. Nothing is allowed to sit in a third state.

A row earns DONE when the code exists **and drives something real**. A control whose value no
renderer or solver reads is not done — it is the dead-knob defect (the Free-pin rotate arc,
removed 2026-09-16), and it is worse than an absence because it teaches the user the feature is
broken rather than missing.

---

## A. Dead knobs found by this audit

Controls that were on screen and connected to nothing. Each is either wired or removed.

| | Control | Finding | Resolution |
|---|---|---|---|
| A1 | "Scale at this point" (Free pin) | `PuppetPin.scale` is persisted and read by NOTHING in the puppet path. Same root cause as the rotate arc: `handleComponents()` is 2. | |
| A2 | `Show · Mesh` toggle (Character) | `rig.showMesh` is written, saved, and never read. The overlay draws pins and bones only. | |
| A3 | The reach EYE on a slider | Documented as "the reach ring stays visible"; actually toggles `showPins`, duplicating the Character toggle. No ring is drawn anywhere. | |
| A4 | Per-bone `bendSign` | Every bone stores it, `FabrikSolver` consumes it, and `puppetSolveChain` never copies it into the `Chain` — so it is always +1. Flip elbow would have been born dead. | |
| A5 | `PuppetPin.muted` | Honoured by the mesh builder AND drawn hollow by the overlay — with no way to set it. A feature complete except for its switch. | |

## B. Designed, specced, never built

| | Item | Where it was promised |
|---|---|---|
| B1 | Bone selection and the bone scope (parent, length, rest angle, stretch, joint limits) | SPEC §2 scope table; §3 |
| B2 | "Flip elbow" | SPEC §3 |
| B3 | Wind, and which way it blows | SPEC §2 Character column |
| B4 | Poof animation on delete | JoyRaptor, 2026-09-15 |
| B5 | Long-press a pin for its options | JoyRaptor, 2026-09-15 |
| B6 | Tape: drag the body to slide a performance | SPEC §01 / §7 |
| B7 | Tape: drag a cap to stretch it | same |
| B8 | Tape: drag a key inside it to retime one moment | same |

## C. Known and still open

| | Item | Why it is still open |
|---|---|---|
| C1 | The timeline grab bar is dead | Not this feature's doing. A diagnostic logs on editor open. |
| C2 | One unidentified corner widget | Needs a screenshot from the phone. |
| C3 | The "Everything" chip | SPEC §6C: build it only if the absence is actually felt. Building it on spec would be the rejected ghost-strip in a hat. |
