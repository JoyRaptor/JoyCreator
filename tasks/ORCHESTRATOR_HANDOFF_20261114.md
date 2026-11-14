# Orchestrator handoff, 2026-11-14 (Joy Creator, all wings)

The orchestrator session coordinates every lane for the owner (JoyRaptor). This file is what a fresh
orchestrator needs. Session ids live in the orchestrator's private memory note, not here (public repo).
Live claims and messages: [BOARD.md](BOARD.md). Source of truth: [ROADMAP.md](ROADMAP.md).

## 1. Where the work list stands (audit of 11-13)

[`audits/remaining_work_20261113.md`](audits/remaining_work_20261113.md) listed **287 open items**
(87 small, 96 medium, 62 large, 42 extra large; 175 needed neither the owner nor the paint engine).

As of main `c7f731016` (160 commits after the audit): **63 audit items are named in commits**
(BOT 13, SHELL 14, L 13, STU 8, JBX 8, AN 3, TX 3, VL 1). More were done under card names without the
audit id (the puppet proofs, the brush import JBX-18, UI-1 rows), so the true figure is roughly **70 to 80
touched, about 60 to 70 closed**, leaving **roughly 210 to 220**, weighted toward the large and extra-large
ones. A fresh orchestrator should recount by reading each card, not trust this estimate.

## 2. What the owner added after the audit (not in the 287)

| # | Ask (date) | State 11-14 |
|---|---|---|
| A1 | Joybot sprite sheets aligned and curated (11-13) | v1 and v2 built in `art/Joybot-sprite-assets/aligned_v2_20261113/` (untracked art); wired into the chat on the sprites lane's branch, built, idles on the tablet; arrival / action / return recording owed; not landed until he watches it play. Greeting and 3 actions need regenerating by him |
| A2 | Lobby heroes with motion (VisLab preview, JoyBrush slow pan, etc.) (11-13) | VisLab moving preview sheet landed (c32a62808); lobby hero drift landed; per-wing heroes partly; not all seen on a device |
| A3 | Curated final brush set; oil/watercolour presets; rewetting water brush; "Versa brush" naming (11-13) | 20 presets landed; Proto Pencil 2 held for retune; naming options owed; 9 blank contact-sheet cells |
| A4 | Pencil only draws black (11-13) | fixed and landed |
| A5 | Pencil vs Infinite Painter's Proko pencil (11-13) | reference shots saved; side-by-side owed (waits on Proto Pencil 2) |
| A6 | Brush import: Photoshop, Krita, Procreate, MyPaint (11-13) | landed. His Photoshop set: 17 of 19 import; Val's Krita pens import; Val V2 fades on fast strokes (fix owed); Memileo Impasto and Metallics need a browser download by him into `D:/JoyCreator-brush-samples/`; no Procreate file yet |
| A7 | Every screen size, Studio drawers as side panels (11-13) | card [TAB-1](jobs/TAB-1_every_screen_size.md) and roadmap row; tablet notes written by lanes; **no session started** |
| A8 | Brush editor: one editor for every brush, live slider redraw, true hover outline with tilt, oil taper, slider A/B pictures, Unibrush sheet fixes, "pattern has to be set" refusal, top bar (11-14) | card [BRUSH-UI](jobs/BRUSH-UI_one_brush_editor.md). Landed: readable chips, Softness, tip beside pen pad, top bar. Design page sent for his pick. Open: hover outline (needs the paint engine), refusal (brush not identified), oil taper, A/B pictures, live redraw |
| A9 | The test stroke drawn exactly as his reference (11-14) | his drawings and words in `joybrush/research/test_stroke_20261114/`; the first version (a3b1c0175) rejected; brush lane redoing it |
| A10 | Second test device: Note 20 (11-13) | backed up to `D:/JoyCreator-Note20-backup-20261113` (20 projects, verified), then unplugged; out of use |
| A11 | Test tablet clean install (11-13) | done; Tab S8 Ultra, `PHONE_SLOT=tablet`, Wi-Fi or USB. **It has a PIN lock**: sessions stall when it sleeps. Owner asked to set Swipe/None |
| A12 | Disk tidy (11-13) | 8 GB freed from idle build folders; worktree pruning left to the owner's call |
| A13 | Use Sonnet / Opus / Fable by the job, save tokens (11-14) | Sonnet: clerical, device checks, matching an existing look. Opus: new design, tricky code. Fable: hard innovative engine work only (the paint engine) |

## 3. Planned but never started (do not lose these)

1. **TAB-1 lane** (Opus): shared size helper, max content width, then a mix-and-match design page
   (Studio side panels, JoyBrush on a tablet, wide lobby). Could not start over Remote Control.
2. **Select on pixels, design first** (Opus): spec in `joybrush/specs/`, reviewed by the paint-engine session
   before any engine edit (Codex handback section 3).
3. **Editable watercolour lines acceptance** (Sonnet or Opus): verify the existing sampled-line path through
   selected edits, save/reopen, recovery, appearance on the Note 9 (Codex handback section 2).
4. **History scrub of the public repo**: ON HOLD by the owner (building first). Plan and failed rehearsals in
   `D:/JoyCreator-scrub-20261114/RUNBOOK.md` (outside the repo). Needs: rehearsal on a GitHub mirror, his yes
   to moving `.githooks/_patterns` private, a tested re-sync of local branches and worktrees. Guard hooks
   were off from 10-27 to 11-13 (fixed). Never force-push without him.
5. **Thread hygiene**: archive Library-files and UI-1 lanes (finished); have the Studio lane write a handoff
   and clear it (history since 11-10); then this orchestrator.
6. **Owner decisions still open**: S1 app ID, S18 front door pick, the brush-editor design pick, the puppet
   "does the last pose hold?" question, the Joybot recording, Play account and testers, privacy email,
   OpenRouter key.

## 4. Rules learned this run (also in memory)
- One adb server only; devices found by model through `tools/phone.ps1`; serials only at run time.
- No real name, personal email or serial in tracked files or commit messages; the guard is on; never `--no-verify`.
- A device is claimed only while in use; builds one at a time, batched.
- Read `AGENT_START.md` before committing; verify sessions' claims against the repo before reporting.
- Compaction vs clear: clear with a written handoff is better whenever the work's state lives in files
  (cards, BOARD, this file). Compact only mid-task when the in-head detail (a half-finished diagnosis,
  numbers not yet written down) cannot be written out cheaply first.
