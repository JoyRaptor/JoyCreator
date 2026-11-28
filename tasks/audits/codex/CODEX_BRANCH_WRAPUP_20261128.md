# Codex branch wrap-up, 11-28

Owner, 11-28: Codex is no longer with us, so anything depending on or handed to Codex is wrapped up and landed by Claude.
Method: for each `origin/codex/*` branch (STU-7 and `motion-blur-plan` belong to the Motion blur session and are not touched),
a patch-id check, then a line-by-line check of every code line the branch added against today's `joy-creator`.
"Present" means the branch's added code lines are already in the same files on `joy-creator`. No branch was deleted.

| Branch | Verdict | Evidence | Action |
|---|---|---|---|
| `r4-save-content`, `r4-save-rotate`, `r4-aud-race`, `r4-gl-span`, `r4-vis-sound`, `r4-vis-nest`, `r4-vis-sprite`, `r4-vis-idle` | Landed 11-20/21 | See ROUTING_ROUND4_20261120.md | none |
| `r4-txt-transfer` | Landed via a Claude rebase (decided by the orchestrator) | Recorded only | none |
| `r4-gl-mask` | Rejected (decided) | A failed export is worse than a rare wrong mask; the fix is a multipass fallback, now a Claude engine job | none |
| `r4-aud-bake` | Held until after launch (decided) | Moves audio edit positions; needs the AUD-RACE duration check first (that check is already in on `joy-creator`, corrected to the source clock, 11-20) | none |
| `audio-studio-voice` | Landed 11-23 | ROUTING_STUDIO_VOICE_20261123.md | none |
| `note9-studio-voice-20261119` | Obsolete (superseded) | The earlier prototype of the landed Studio Voice; 97% of its code lines are present, the rest is the older render file | none |
| `suite-performance-audit-20261119` | Landed 11-23 (documents) | No commits left to land | none |
| `audit-round4-20261116`, `engine-handback-20261113`, `frame-projection-foundation`, `region-routing`, `tween-wash-fidelity-20261026`, `unibrush`, `unibrush-u8-guards-20261026`, `motionblur-20261122` | Obsolete: fully on `joy-creator` | Zero commits not on `joy-creator` | none |
| `abr-frontier-fix` | Obsolete | 399 of 399 added code lines present | none |
| `brush-contact` | Obsolete | Whole patch already present | none |
| `frame-duplicate-contract-20261026`, `frame-duplicate-probe-20261026` | Obsolete | 99% present; Note 9 pass for frame duplication is on the roadmap (FRAME-DUP) | none |
| `native-init-cleanup-20261027` | Obsolete | 99% of 1,628 added code lines present | none |
| `select-lines`, `select-bench-20261025`, `select-a10-acceptance-20261026`, `select-a10-current-20261026`, `select-a10-wired-20261026`, `select-d23-presenter-20261026` | Obsolete | 98-100% of added code lines present; the rest is test or bench scaffolding variants; wired/D23 are docs or code already in. Select is on `joy-creator` | none |
| `vector-tweens`, `tween-direct-20261026` | Obsolete | 99-100% of added code lines present; the leftover is old review notes and patch files | none |
| `tween-runtime-20261026` | Obsolete (landed another way) | 55% of lines present; the rest is a component-aware tween scene that landed differently as the CLIPS merge (`lead/anim-clips-integrated`, Note 9 8/8 on 10-30, in the JoyBrush roadmap) with its own export classes | none |
| `board-recovery-docs-20261026`, `paper-recovery-docs-20261026` | Obsolete | Documents already present | none |
| `paper-approved-20261029` | **Salvaged: landed** | 0% on `joy-creator`: offline paper evaluation tools (selection list, bake lease, phone profile, builders and their tests) and the review manifests for the 45 test masters. No app code, nothing in the APK. Its own six tests pass here. Its edits to STATE and ROADMAP were not taken (stale) | 21 new files copied in; the owner's paper picks stay owed |
| `avatar-round10-review-20261121` | **Salvaged: landed (documents)** | The audit of Avatar Studio round 10 and its job card AVA-1A, not on `joy-creator` | 2 new files copied in; its STATE and ROADMAP edits not taken |
