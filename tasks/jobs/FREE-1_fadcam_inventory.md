# FREE-1: Every FadCam-derived part: rebuild, replace or cut

**Milestone:** M3  ·  **State:** 🟡 inventory done 10-28 → [`audits/fadcam_inventory_20261028.md`](../audits/fadcam_inventory_20261028.md); waiting on S19/S20  ·  **Owner:** lead  ·  **Checked:** 2026-10-28

## Why it matters
The owner wants Joy Creator completely and legally free of FadCam: take the ideas, not the code, and
rebuild every feature he values with full parity so the tie can be cut. FadCam is licensed under the
**GPL version 3** (the repo's `LICENSE`). While any FadCam-derived code remains, Joy Creator stays a GPL
fork and must keep that licence. Cutting the tie means every FadCam-derived file is gone or rebuilt
clean. **This is not legal advice:** before relying on it (for example to change the licence), the owner should ask a lawyer.

## Done when
- [ ] `tasks/audits/fadcam_inventory_<yyyymmdd>.md` lists every package, screen, service and resource that came from
      FadCam, each with a verdict: keep-for-now, rebuild clean, replace with existing Joy Creator code, or cut.
- [ ] Each "rebuild" and "replace" verdict has a job card.
- [ ] The owner has seen the list and the plan.

## What we already know
- FadCam-era packages and screens include recording (camera, dual camera, FadRec screen recording), Remote
  streaming, cloud upload, Smart Detection / forensics, motion detection, the clock widget, MiniApps, the
  watermark, 30 disguise icons, the "verify you are a human" screen, Records (old version), Home, onboarding,
  What's New, FadCam Pro, and `AvatarToggleView` (to be replaced by the Joybot switch).
- Joy Creator's own code is large and separate: Studio (`ui/faditor/**`, despite the old name), studiokit,
  JoyBrush (`cc.joycreator.joybrush`), VisLab, Joybot, the Joy Creator folder, Library v2, Settings hub, Trash v2.
- 11 FadCam classes have no caller at all and 4 drawables are unused: [legacy_screens_audit.md](../audits/legacy_screens_audit.md)
  and [UI-1](UI-1_app_ui_leftovers.md) (removal needs S10).
- Package names are still `com.fadcam.*` and the app ID `com.fadcam.beta`: [FREE-2](FREE-2_names_and_ids.md).
- Joy Creator-only features to keep and promote (STATE §1): the webcam overlay while screen recording, auto-hiding recorder controls.

## What is left
- [ ] The inventory (read-only): walk `app/src/main/java/com/fadcam/**` and resources; mark what is FadCam origin
      (`git log --diff-filter=A --format=%ad -- <file>` on the oldest commits shows what arrived with the fork).
- [ ] Verdicts with the lead; the owner reviews.
- [ ] Rebuild the most user-visible parts first (they also serve [DOOR-2](DOOR-2_lobby_landing.md)): Records → Library v2 (mostly done), Home recorder, onboarding.
