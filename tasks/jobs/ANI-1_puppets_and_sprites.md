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
      C5 holes in traced artwork and C6 sliver triangles (engine); C7 hover labels (app-wide) [C7 puppet + sprite + avatar screens: done 2026-11-04, see strings_hover_puppet.xml; not compiled].
- [ ] Sprite read-back, the "three missing doors": saved animations as chips, cell names on chips and the tape,
      a sheet library across projects ([SPEC_20260910_SEMANTIC_CELLS](../SPEC_20260910_SEMANTIC_CELLS.md) §5). The old roadmap put this after launch; ask the owner.
- [ ] Sprite warp net looks detached from the sprite (owner report 10-15; see [STU-4](STU-4_studio_sweep_leftovers.md)).
- [x] (list written 11-13: [SPRITELAB_PARITY_20261113.md](../SPRITELAB_PARITY_20261113.md); the gaps are still to build) SpriteLab web tool (`tools/spritelab/SpriteLab.html`) vs the phone editor: list what the web tool does that the phone
      cannot (phone-first rule) and the per-cell transforms the sprite format must hold.
- [ ] Avatar Studio: an owner decision for launch.

## Specs, handoffs and evidence
- [SPEC_20260915_PUPPET_UI.md](../SPEC_20260915_PUPPET_UI.md), [SPEC_20260904_PUPPET_ARCHITECTURE.md](../SPEC_20260904_PUPPET_ARCHITECTURE.md), [PUPPET_FINISH_LEDGER.md](../PUPPET_FINISH_LEDGER.md)
- JoyBrush's puppet and character boards are separate: JoyBrush roadmap, specs JB-7.01 and JB-7.04.

## Files and traps
- `app/.../ui/faditor/puppet/**`, `ui/faditor/sprite/**`. Use a throwaway project, never his real ones.

## Phone results, 2026-11-13 (anim-labs lane, Note 9, build of 374fe099f, throwaway project "Animation test")
- ✅ **AN-09 bend (partly):** selected a sprite, Bend on, dragged the centre point and the top-left corner point with a finger: the sprite bent in the preview each time, one undo step per drag, two undo taps put it back flat. Screens: `tasks/screens/anim-labs-20261113/`. **Not checked:** preview equals an exported frame; the yellow corner-pin squares; a drag started within ~20 px of the screen edge opens the phone's camera (Samsung edge gesture, not the app).
- ✅ **VisLab lobby hero sheet (c32a62808):** leaving the room after ~9 s wrote `hero-<t>.jpg` and `hero-<t>.5x4.sprite.webp` (135 KB, 1280x576, 20 distinct moving frames), old pair gone. Lobby playback not seen: the lobby side (`LobbyHeroMotion`) had not landed.
- ❌ Not yet run (no rigged picture project found; needs one made): AN-06 pins, key, live take, tape drags, bone tap; AN-07 tail swing; AN-08 grab bar.

### Note 9, 11-13 evening (same throwaway project, own test picture of a figure with a tail)
- ✅ **Where the puppet lives:** hold a picture in the preview, Image drawer, person-icon "Puppet" tab (Grab, Pin, Stiff, Dangle, Free, Bone; Selected / Character / Recording).
- ✅ **Place pins:** two Pin-type pins placed by tapping the picture, auto-named "Waist" and "R.Hand", one undo step each (undo counter 3 after pins).
- ✅ **Key a pin:** the diamond made a key on R.Hand at 0.844 s (diamond turned green, undo counter 4, a key mark on the tape).
- ❌ Not proven: moving a pin and playing it back, live take, tape drags, bone tap, dangle tail swing (AN-07), grab bar (AN-08), rotated-picture pins, hole/depth sliders. The drawer re-lays-out after the first pin (tool row moves down ~180 px), so scripted taps by fixed coordinates missed; a second pass should screenshot between steps.
- Found, not fixed: none a bug yet. A drag from the screen's right edge opens the camera (Samsung edge gesture).

### Tablet (Tab S8 Ultra, landscape), 11-13 night, own build of this worktree, same figure picture in the old "Untitled" project
- ✅ Image add via the picker, Puppet tab, one Pin-tool pin ("Waist") placed: works on the tablet as on the Note 9. The tablet's picker opened the Files app straight away, no chooser.
- ❓ **Possible bug, not confirmed:** after the first pin, choosing **Free** and then **Dangle** and tapping the figure (a hand, then the tail tip) placed no pin (undo count stayed 7). The Free tap landed on the depth slider (the drawer's controls sit over the picture on the tablet), the Dangle taps on empty picture area did nothing visible. Needs a re-run with the drawer pulled down, or a check whether Dangle/Free need a pin selected to hang from. Screenshot: `tasks/screens/anim-labs-20261113/tablet_puppet_dangle_tap_no_pin.png`.
- ❌ Still not run: moving a pin and playing it back, live take, tape drags, bone tap, dangle swing (AN-07), grab bar (AN-08), VL-01/03/04 and the merged VisLab fixes. The folder-choice step was already done on the tablet (it opened straight to the lobby).

### Tablet re-check, 11-14 (build 1d56a6cf4 as the owner installed it)
- ✅ **Dangle placement is NOT a bug.** After the first pin the Puppet drawer grows (tool row, Selected panel, depth slider, Active / Reach ring) and sits over the top of the picture; taps there reach the drawer, not the picture. A Dangle tap on the leg (below the panel) placed a "Base" dangle pin at once, and its panel showed Springiness / Settle / Mass and "Simulated — no keys to edit". Screenshot: `tasks/screens/anim-labs-20261113/tablet_dangle_placed_low.png`.
- 🟡 **Usability finding (for the owner, not fixed):** on the tablet in landscape the drawer covers most of the picture, so pins on the upper body or hands can only be placed after collapsing the drawer. The first pin is easy because the drawer is small until a pin exists. A pin-placing pass should pull the drawer down (handle under the preview) or the placing taps should pass through the drawer's empty area.
- ✅ Playback with a puppet and a dangle pin in the clip plays (the figure stayed still: nothing was keyed, so there was nothing to swing).
- ❌ Still not seen: a dangle swinging after a keyed pose change (AN-07), moving a keyed pin and replaying it, live take, tape drags, bone tap, grab bar (AN-08), VisLab checks.
- Slip to note: one stray tap on JoyBrush's canvas (a playground drawing on the tablet) happened when the lobby opened JoyBrush instead of Studio; no data of the owner's was involved.

### Tablet pass, 11-14 (Tab S8 Ultra, landscape, build 1d56a6cf4; throwaway project "Untitled")
- ✅ **AN-08 grab bar:** one press-and-drag of the bar under the preview gave the log line `GRABBAR gesture ended — moves=56 baseline=140.0dp now=305.17dp`, and the timeline grew while the preview shrank (screen: `tablet_grabbar_dragged.png`). On the tablet only; the original report was on a phone, so the phone is still to be checked.
- ✅ **Pose a puppet:** with the IMG clip selected, tapping the puppet badge (top right, shows the pin count) puts pins on the preview with a small rail (Z, key, hand). Dragging the Waist pin swung the whole figure: body rotated, arms, tail and legs followed, the dangle ("Base") pin made the legs trail (screen: `tablet_live_take_dangle_swing.png`). This is the **AN-07 dangle seen swinging** on a device (tablet).
- ✅ **Live take:** that drag recorded a take ("Recording — let go to stop" showed; undo counter went 9 to 10; a key mark appeared on the clip row). Scrubbed to 0 s the figure shows the dragged pose (`tablet_take_posed_at_zero.png`); played to 1.1 s and 3.3 s it is back upright (`tablet_take_rest_after.png`). **Question for the owner/lead:** is "return to rest after a take ends" the intended hold? I expected the last pose to hold. Not called a bug.
- 🟡 **Found:** after the adb drag the magnifier circle and "Recording" text stayed on screen and the preview stopped updating until a tap on another part of the screen; the preview clock stayed at 0.567 s. Likely the synthetic touch (no pointer-up sent to the pin overlay), not proven a real-finger bug; recheck with a finger.
- ❌ Not run: tape drags (slide/stretch/retime), bone tap, rotated-picture pins, hole/depth sliders, per-pin key easing; VL-01, VL-03, VL-04 and the merged VisLab fixes (no VisLab room opened this pass).
