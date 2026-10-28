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

## Progress

**2026-10-27 first pass (session "Joybrush roadmap task", account C, fork of the R8 session):**
- [x] STATE section 7.6 added: Library v2, Settings hub v2, Recently Deleted v2, Home recorder / Remote / Smart Detection, About, JoyBot switch, sub-screens, lobby truth pass, slanted chips. All 🟡 (agent-checked on the Note 9, owner has not accepted).
- [x] STATE section 9 rows 8, 10, 11, 12 corrected against the code (remove_silence cuts video but refuses audio; tests exist; sprite animation chips exist since 09-13; cell names are read and written; `Cell.tags` still unwritten in `ai/`).
- [x] Checked and found already covered, no change: Joybot (section 6, 8 rows), Joy Creator folder / Space / Copy-Move-Leave (7), Studio effects and Echo Trails (5.5), timeline marks (STU-3 owns that row).
- [ ] **Still missing a row** (the lane owner should add it, I did not touch these sections): JoyBrush layer operations (Lock row, Merge Down, Flatten, Freeze), export of the whole drawing or one board, "Tilt with phone keeps the screen from turning" (JoyBrush lead, section 5.6); Joybot Director mode, recipes and hands-free (Joybot lane, section 6); sprite pin links (Studio, section 5).
- [ ] Re-check every 🟡 and 🔴 row in sections 1 to 6; re-check the "~" rows in sections 1 to 4; ask the VisLab lead to confirm section 5.5 (its R8 to R14 rows are ahead of the roadmap row).
