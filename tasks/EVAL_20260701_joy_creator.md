# Joy Creator — Fresh-Eyes Evaluation & Autonomous Buildout Plan (2026-07-01)

> Written by the orchestrator after the first-video milestone and the July-1 user-feedback batch.
> Audience: the user (non-programmer) AND any AI model (Claude or not) picking up this project.
> Companion docs: `handoff.md` (tactical state), `road_map.md` (phases), `C:\ObsidianBrain\sprite plan.md` (sprite feature).

## 1. Where the project stands
FadCam's Faditor editor shipped its first real video. Export is hardened and device-verified; undo is
comprehensive. The 2026-07-01 feedback batch (11 items) is nearly complete — see scoreboard in §2.
The project is being rebranded as **Joy Creator**, a from-this-fork original product aiming to be a
best-in-class Android phone/tablet video studio.

## 2. Feedback batch scoreboard (2026-07-01)
| # | Item | Status |
|---|------|--------|
| 11 | Editable export filename | ✅ done (needs on-device dialog check on main phone someday) |
| 4 | Transcript drawer header clipping | ✅ done |
| 3 | Webcam/compact-bar vertical rotation box | ✅ done (real bug was AnnotationService compact bar) |
| 10 | Export progress stripe (barbershop) | ✅ built — but see §4 "Export work package": unreachable until minimize exists |
| 2 | Caption style apply-to-all (long-press) | ✅ built, compile-green; NOT yet device-verified |
| 1 | Undo/redo long-press history popup | ✅ done + device-verified |
| 8 | Small-screen clipping pass | ✅ done + device-verified at simulated S10e (1080×2280@480dpi). Flagged leftovers: CanvasPickerBottomSheet, VolumeControlBottomSheet, AddAssetBottomSheet, FlipPickerBottomSheet (programmatic, no scroll wrapper) |
| 5 | Transcribe-on-add dialog + editor settings gear | ✅ done + device-verified (gap: asset-browser inserts don't prompt) |
| 6 | Swipe-up all-tools drawer | 🔄 in progress (staged with #7) |
| 7 | Carousel edit mode: reorder/pin/recent | 🔄 in progress |
| 9 | Transition preview smoothness | ⬜ queued last — cheap additive mitigation only (see §5) |

## 3. Systemic findings (ground-up items, not patches)

### 3.1 The god class — FaditorEditorActivity.java (~13,000 lines / 629 KB)
Every editor feature lives in one file. Consequences: agents must run sequentially, every agent pays to
read it, every change risks unrelated breakage, and non-Claude handoffs are painful.
**Decision: no big-bang rewrite.** Instead, two standing rules for ALL future work:
- **New features get their own files** (component/controller classes in the faditor package) — the way
  ExportProgressStripeView, FaditorSettingsBottomSheet, and the new tools/ package were done.
- **Extract-on-touch:** when a task requires substantial edits to an existing section of the god class,
  extract that section into its own controller first (or as part of the change), keeping behavior identical.
Target: the file shrinks organically; measure its size monthly.

### 3.2 No version-control safety net
Standing rule so far was "no git commits". On 2026-07-01 an agent process was killed mid-task (it happened
to have written nothing — pure luck). **Recommendation (needs user OK): local checkpoint commits after every
compile-green feature.** Never pushed; purely snapshots. Makes reverts trivial, killed agents harmless, and
gives any other AI model a clean diff at handoff. Until approved, agents must continue full-file reverts.

### 3.3 UI must scale, never clip (now a standing rule)
Root cause of feedback #8: fixed vertical stacks with no scroll container. Rule: **every menu, drawer,
dialog, and panel wraps its content in a scroll container** unless provably shorter than the smallest
supported screen (assume 1080×2280 at 480dpi, i.e., a zoomed S10e). Known remaining offenders listed in §2 row 8.

### 3.4 Panel/drawer pattern convergence
The editor has accumulated several interaction patterns: top drop-down drawers, rolodexes, bottom sheets,
side drawers, the asset-browser resizable panel. The sprite plan's **snap-height bottom panel with detents**
(reusing AssetBrowserPanel's grab-handle pattern) is the best candidate for the standard going forward.
New surfaces should use it; existing ones migrate opportunistically, never in bulk.

### 3.5 project.json bloat (2.4 MB, triplicated transcripts)
Held item. Slows every save/load and inflates memory. **Do early in autopilot once the user pre-authorizes**,
with: automatic timestamped backup of project.json first, undoable operation, verify on sandbox before real
project. (Data-touching → still confirm-first as of this writing.)

## 4. The export work package (bundle, don't patch)
Three items are one feature and should ship together:
1. **Minimize-during-export** — export screen currently blocks; the #10 stripe is unreachable in the editor.
2. **Edit-safety during export** — verify (and enforce) that editing a project mid-export cannot corrupt the
   running export (export should snapshot the project state at start; audit whether it already does).
3. **Out-of-process export** (held item) — own `android:process` so big exports can't OOM the editor; requires
   replacing LocalBroadcastManager progress transport (bundle with #1's transport work anyway).
Also fold in: export quality/bitrate setting (old polish item), which belongs in the same dialog.

## 5. Transition preview jump (#9) — architectural note
The preview jump at clip boundaries is a symptom of the single-ExoPlayer preview architecture (seek/decoder
swap at boundaries). The REAL fix is the roadmap Phase 5.3 **GL preview compositor** that Layers brings.
Anything done now is a mitigation and must stay cheap + additive (e.g., pre-buffering the next clip via
ExoPlayer playlist/ConcatenatingMediaSource tuning, or a crossfade mask over the swap). Do not sink budget here.

## 6. Revised buildout order for autopilot
1. **Finish feedback batch** (#6/#7 carousel, #9 cheap mitigation). ← in progress
2. **Consolidation sprint:** transcript dedup (after user OK), scroll-wrapper leftovers (§3.3), device-verify
   caption apply-to-all, asset-browser transcribe-prompt gap, main-phone real-project re-export verification
   (still the standing "verify first" item from the previous handoff when the main phone is next plugged in).
3. **Export work package** (§4).
4. **Layers (Phase 5) — the keystone.** Schema v6 Track model → multi-row timeline → GL preview compositor →
   export mapping. This also delivers the real #9 fix. 3–5 sessions, highest risk, do with strongest models.
5. **Sprite animation Build 1** (per `sprite plan.md`) — deliberately shaped to slot into Layers.
6. **Rebrand execution** (§7) + AI features (slides, b-roll vision) + recording pipeline items.

## 7. Rebrand: FadCam → Joy Creator
- **Visible rebrand (safe, anytime):** app name, icon, splash, theme/colors, in-app strings. One contained work item.
- **applicationId (`com.fadcam.beta`) — decide ONCE, before any public release:** changing it = a brand-new app
  identity (fresh install; projects/settings don't carry over without a migration step). Plan a deliberate
  migration (export/import projects or a one-time copy) when the final id (e.g. `com.joycreator.app`) is chosen.
- **License:** FadCam is GPL-licensed open source. Private use is unrestricted, but DISTRIBUTING Joy Creator
  (Play Store, APKs to friends) requires the derivative to remain GPL (source available, attribution). Decide
  distribution posture early; it affects nothing technical today.

## 8. Standing rules for all agents (the contract)
1. ALWAYS-GREEN: watcher builds on save → build.log (UTF-16; `tr -d '\000' < build.log | tail -30`); never run
   gradle; revert if you can't compile.
2. Sequential agents only — the god class (§3.1) makes parallel edits unsafe.
3. New features = new files; extract-on-touch (§3.1).
4. Every surface scrolls (§3.3). Verify small-screen via `wm size 1080x2280` + `wm density 480` on the sandbox
   device; ALWAYS reset after (note: this Note 9 needs explicit `wm density 420`, `reset` restores a stale override).
5. Device-verify on the sandbox Note 9 (SANDBOX_SERIAL, project bdd51919…); NEVER mutate the user's real
   project (27221664…, main phone REAL_SERIAL) without explicit instruction.
6. Data-touching operations (transcripts, project.json migrations): backup first, undoable, confirm-first
   unless pre-authorized.
7. Update `handoff.md` (dated entry) after every landed item; keep entries ≤8 lines.
8. Screenshots via screencap + adb pull (PowerShell `>` corrupts binaries); `uiautomator dump` for real bounds.

## 9. Open decisions for the user
1. Local checkpoint commits — OK? (§3.2)
2. Pre-authorize for autopilot: transcript dedup (with auto-backup)? export work package? crop-during-transition GL?
3. Rebrand: start visible rebrand now? Final app id? Distribution posture (GPL)? Logo/color direction?
4. Budget cadence: preferred session length / how much to spend per autonomous run, and which items justify
   Opus-class agents (default: Sonnet for contained UI, Opus for Layers/export/GL work).
