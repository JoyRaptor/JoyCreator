# FOLD-1: Fold FadCam's features in under Joy Creator names

**Milestone:** M3 (alongside M1/M2)  ·  **State:** 🔨 Remote Studio restyled and locked (11-15); Smart Detection restyle next  ·  **Owner:** open (lead assigns per piece)  ·  **Checked:** 2026-11-15

## Why it matters
The owner, 10-28: "we were actually going to rebrand a lot of those features and fold it in, because multi-camera and
the ability to discover objects in a scene can be really useful." FadCam's best mechanics become Joy Creator features
with their own names and looks, instead of FadCam leftovers or cuts. Ruled 10-17 (HANDOFF_STUDIO_UI_20261017 "OWNER
DECISIONS"): Remote / Server Room and Smart Detection are launch features. Planned 07-02 in
[`DESIGN_JOY_CREATOR.md`](../DESIGN_JOY_CREATOR.md) §7 (CAPTURE → LIBRARY → STUDIO, Remote as a capture superpower).

## Done when
- [ ] Each piece below has its Joy Creator name, look and place in the app (no FadCam wording, no "forensics", no "evidence"),
      is phone-proven, and has a STATE row with help mapping.
- [ ] Nothing a FadCam user had is lost without the owner's yes.

## The pieces (state 10-28; sizes from [`audits/fadcam_inventory_20261028.md`](../audits/fadcam_inventory_20261028.md))
| Piece | From FadCam | Joy Creator form | State |
|---|---|---|---|
| **Remote Studio** | LAN streaming + web remote (`streaming/`, `assets/web/`) | Watch and control the phone camera from a browser on your Wi-Fi; later, a remote phone as a second camera | Cloud parts being cut (S19, `claude/s19-cut-20261028`); restyle owed |
| **Multi-camera** | dual camera (`dualcam/`) | Front + back at once; a remote phone as another angle; becomes Studio layers | Dual-cam exists (STATE §1 🟡); multicam editing not built |
| **Smart Detection / find things in a scene** | motion, noise and object detection (TFLite, OpenCV) | Motion- and sound-triggered capture; find people, pets, objects in recordings | Built in FadCam form; restyle owed (10-17 ruling) |
| **Auto-markers** | event logs from the detectors | Interesting moments become timeline marks and chapters on long recordings | Timeline marks landed 10-28 (STU-3) to receive them; not built |
| **Story Board** | the forensics evidence board (ForensicsGallery/Events, Room DB) | Pin, tag and sequence clips, pictures and notes before editing; hand a rough cut to Studio | Not built (no code under that name) |
| **Annotation** | `fadrec` annotation (~18,000 lines) | Draw on screen recordings now; later a JoyBrush layer over video | Built in FadCam form; JoyBrush layer not planned there yet |
| **Dual-stream recording finish** | screen + webcam recording | The webcam file rolls over on long recordings like the screen file | Spec'd, half-built ([FUTURE_PLANS](../FUTURE_PLANS.md) 1.1 C1) |
| Verified original badge, cross-device sync | integrity hashing, sync queue | parked (DESIGN §7) | parked |

## Progress
- 11-13 (app-shell lane): names only. The Remote screen and its Settings row say **Remote Studio**; the old Lab tab and the lobby word say **Detect** (Smart Detection); no "forensics" or "evidence" wording is left in the English strings. Seen on the Note 9. Restyles, auto-markers, Story Board and multi-camera untouched.

- 11-15 (capture-rooms lane): **Remote Studio** restyled and locked. The web page is Joy Creator's (Joybot instead of FadCam's robot, house tokens, local fonts and icons, same-Wi-Fi wording); the level rings use the owner's pick **rings B** (green, amber, red in house colours, flat); Remote is **locked by default** with a pairing code on the phone (bug hunt B3), proven on the test Fold. Before/after shots `tasks/screens/capture-20261115/`.

- 11-15 (capture-rooms lane): **Smart Detection** pass 1: house colours on every screen, activity levels and heat map in the house language, the photo viewer's leftover "EVIDENCE" strip gone (seen on the Fold). Left: the evidence-board decor (binder clip, tape, torn paper) needs a design page; chip colours wait on the owner (S9).

## What is left
- [ ] Order with the owner: suggest Remote Studio restyle first (it ships), then Auto-markers (cheap now that marks exist),
      then Smart Detection restyle, then Story Board, then Multi-camera.
- [ ] Each piece gets its own small spec or design page before building (the mix-and-match page habit).

## Files and traps
- Never delete FadCam data stores (the Room DB, event logs): hide and repurpose (DESIGN §7 rule).
- Keep FadCam's credit and licence in About (GPL-3).
