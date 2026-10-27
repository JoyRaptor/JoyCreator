# HYG-5: Make STATE.md true

**Milestone:** M0 (and the last step of every other job)  ·  **State:** ⬜ high priority  ·  **Owner:** open  ·  **Checked:** 2026-10-27

## Why it matters
STATE.md is the catalogue Joybot's in-app help and every advertised feature are built from. If a
feature is missing there, users cannot ask Joybot about it and the owner will not advertise it. If a row
is stale, Joybot gives wrong help and the store listing lies.

## Done when
- [ ] Every user-visible feature built since 2026-10-01 has a STATE row: what the user gets, where they find
      it, proof level (🟢 only for phone-proven), and its help mapping.
- [ ] Every 🔴 and 🟡 row in STATE has been re-checked against the current code since 10-27, and fixed.
- [ ] STATE's header says the date of the last full pass.

## Where it stands (10-27 spot checks, not a full audit)
STATE was last fully passed on 2026-09-09; sections have had scoped refreshes since (JoyBrush 10-26,
VisLab 10-27, Joybot 10-24). Spot checks found:
- **Missing:** timeline bookmarks and regions (not committed yet: STU-3). No row for Library v2, Settings hub,
  Recently Deleted v2 or the Home recorder restyle (built and phone-verified 10-16 to 10-20 by the UI sessions).
- **Probably stale:** §9 #8 "remove_silence is report-only" (Joybot's plan says it really cuts since 10-14);
  §9 #10 "No automated tests at all" (there are 66 JVM harness scripts under `tools/jvm-harness/`);
  §9 #11–12 "sprite animations and cell names are write-only" (Joybot names cells since 10-15; check);
  §8 "New intro copy 🔵" (never saved: S5); §7.5 "Object drawer header 🟡" (check).
- §1 recording rows marked "~" are inherited claims from August, never re-verified.

## What is left
- [ ] List every commit on `origin/joy-creator` since 10-01 whose subject names a user-facing feature;
      check each has a STATE row. Add missing rows at the honest proof level.
- [ ] Re-check every 🔴 and 🟡 row against the code; fix or upgrade with evidence.
- [ ] Re-check the "~" rows in §1–§4 (inherited claims).
- [ ] Ask the Joybot and VisLab leads to confirm their sections (BOARD message), and the JoyBrush lead §5.6.
- [ ] Add the help mapping wherever it is missing.

## Files and traps
- STATE is shared by every lane: edit rows, never paste a whole old copy over it.
- Proof levels: 🟢 means the owner, or an agent on the phone, actually used it. Compiling is 🟡.
