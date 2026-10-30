# FREE-2: Names: app ID, packages, phone folders, watermark, translations

**Milestone:** M3  ·  **State:** ⛔ waiting on S1 (the app ID)  ·  **Owner:** project lead  ·  **Checked:** 2026-10-27

## Why it matters
The app ID is permanent once published: changing it later loses every user. The Joy Creator folder is the
bridge: a new app ID restores everything from the folder, which is why the folder had to ship first (it has).

## Done when
- [ ] The release app ID is the owner's pick (recommended `cc.joycreator.app`, matching JoyBrush's `cc.joycreator.joybrush`).
- [ ] Moving from the current debug ID (`com.fadcam.beta`) is proven on the Note 9: install the new ID alongside, restore from the folder, nothing lost.
- [ ] No user-visible "FadCam"/"FadRec": watermark, translations, folder names on the phone (`Downloads/FadCam`, `Pictures/FadCam/assets`, the "Faditor_" file prefix).
- [ ] Java package renames (`com.fadcam.*`): decide with [FREE-1](FREE-1_fadcam_inventory.md) whether to rename or let rebuilt code take new packages.

## What is left
- [ ] S1 from the owner.
- [ ] Plan the folder-name change on the phone so old files are still found.
- [ ] Watermark text and translations (also in [UI-1](UI-1_app_ui_leftovers.md)).
