# ANI-1: Puppets, sprites, SpriteLab and Avatar

**Milestone:** M1  ·  **State:** 🟡 built 9-13 to 9-16; little phone proof since  ·  **Owner:** open (the "Puppeteering UI design research" session is stale: last real work 9-17; start fresh from this card)  ·  **Checked:** 2026-10-27

## Why it matters
Animating characters is one of the studio's headline wings, and the largest built-but-unproven block in
the app. Puppet code has barely changed since 9-17, so this card is still accurate.

## Done when
- [ ] Puppeteering is proven on a phone: place pins, key a pin, record a live take, drag a performance on the
      tape, tap a bone, watch a tail swing, rotate a rigged picture.
- [ ] The finish ledger's open rows are closed or parked by the owner.
- [ ] Sprites have their three missing doors (or the owner moves them after launch).
- [ ] An owner decision on Avatar Studio for launch (STATE recommends: present but not reachable from the UI).
- [ ] STATE §5 rows updated.

## Where it stands
- Puppet engine complete through stage 12; the 9-16 finish sweep traced every control and fixed nine dead knobs
  ([finish ledger](../PUPPET_FINISH_LEDGER.md)). Keyframes and live takes are 🟢 (the owner confirmed the live-take
  loop on 9-16); the tape drags, fitted easing, helper strip, marionette badge, Start over and rotated-picture pins are 🟡.
- Since then: pin links (a prop follows one pin, 9-29; sprite pin links 10-15) and restyles only.
- Sprites: corner pin and the transform surface are 🟡 (see [STU-1](STU-1_studio_phone_backlog.md)).
- STATE §9: saved sprite animations and cell names are write-only (#11, #12), and sheets live inside one project (#13).
  Joybot can now name cells (10-15), so #12 may be partly fixed: check.

## What is left
- [ ] Phone-prove the basics above on the Note 9 with a throwaway rigged picture.
- [ ] Finish ledger §C: C1 timeline grab bar (the diagnostic prints `moves=`); C2 the overlapping corner widget
      (needs the owner's screenshot, S11); C3 the "Everything" chip (owner, S11); C4 face/voice drivers (its own spec);
      C5 holes in traced artwork and C6 sliver triangles (engine); C7 hover labels (app-wide).
- [ ] Sprite read-back, the "three missing doors": saved animations as chips, cell names on chips and the tape,
      a sheet library across projects ([SPEC_20260910_SEMANTIC_CELLS](../SPEC_20260910_SEMANTIC_CELLS.md) §5). The old roadmap put this after launch; ask the owner.
- [ ] Sprite warp net looks detached from the sprite (owner report 10-15; see [STU-4](STU-4_studio_sweep_leftovers.md)).
- [ ] SpriteLab web tool (`tools/spritelab/SpriteLab.html`) vs the phone editor: list what the web tool does that the phone
      cannot (phone-first rule) and the per-cell transforms the sprite format must hold.
- [ ] Avatar Studio: an owner decision for launch.

## Specs, handoffs and evidence
- [SPEC_20260915_PUPPET_UI.md](../SPEC_20260915_PUPPET_UI.md), [SPEC_20260904_PUPPET_ARCHITECTURE.md](../SPEC_20260904_PUPPET_ARCHITECTURE.md), [PUPPET_FINISH_LEDGER.md](../PUPPET_FINISH_LEDGER.md)
- JoyBrush's puppet and character boards are separate: JoyBrush roadmap, specs JB-7.01 and JB-7.04.

## Files and traps
- `app/.../ui/faditor/puppet/**`, `ui/faditor/sprite/**`. Use a throwaway project, never his real ones.
