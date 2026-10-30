# HYG-2: One main line: JoyBrush's integrated work onto `joy-creator`

**Milestone:** M0  ·  **State:** ✅  ·  **Owner:** Engine Lead (took over 10-30; the previous owner was not running)  ·  **Checked:** 2026-10-30

## Why it matters
The app built from `joy-creator`, which is what the watcher builds and what goes on the phones, does not
contain the JoyBrush work integrated since 10-24 (Select, per-brush editable lines, menus, frame copy, the
brush-settings live preview, and more). Two "mains" means features that seem done are missing in the
real app, and every other wing builds against an old JoyBrush.

## Done when
- [x] `origin/joy-creator` contains the JoyBrush integrated line, and the JoyBrush roadmap names `joy-creator` as its base.
- [x] Every other branch that started from the integration branch (for example `claude/symmetry-recover-20261027`,
      already merged onto `bdd3f8ff5`, so it needs one more merge after this lands) is rebased or landed.
      `claude/send-to-existing-20261027` is superseded by `claude/send-to-studio-existing-20261027`, cut from `joy-creator`.

## Where it stands (10-27)
`origin/lead/select-reconcile-20261024` is **159 commits ahead of `joy-creator` and 13 behind.**
The JoyBrush roadmap calls it the canonical integration source, gated by its own GPU, save and recovery checks.

## What is left
- [x] The JoyBrush lead decides which gates must pass before landing, and writes the date or condition here.
- [x] Land it, then announce on the BOARD so every worktree rebases.

## Files and traps
- The JoyBrush hot files (`GlPaintEngine`, `JbCanvasView`, `JoyBrushActivity`, `BenchRunner`) belong to the JoyBrush lead.
- Studio-side files touched by JoyBrush work (`FaditorEditorActivity` board import) can conflict with STU-3.

## Landed 10-30
Gates used: all three test suites plus the Note 9 probes in [the receipt](../joybrush/evidence/one-line-20261030/README.md). Symmetry merged with it. Left out on purpose: the paper test pack (not approved for shipping) and Unibrush U1-U7 and atlas room P2 (roadmap rows name them).
