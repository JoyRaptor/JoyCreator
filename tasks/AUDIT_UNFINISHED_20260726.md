# AUDIT: unfinished / deferred / half-landed work across all planning docs

**Run 2026-07-26 against HEAD `108cff9`** (BEFORE that night's transcript-sharing commit —
see the note on item 1.1). Method: sweep all ~80 docs in `tasks/` for unfinished-work
markers, then **verify each candidate against the code** rather than trusting the doc. Docs
are frequently stale in the "still says TODO but was built" direction, so Tier 5 lists
things that read as open and are actually done — do not re-scope those.

**Why this exists:** `PLAN_transcript_windowing.md` described a 5-step plan; steps 1, 2, 4
and 5 landed, step 3 never did, and no migration was ever written for projects the old
behaviour had already damaged. Nobody knew until a user hit it. This audit hunts every
remaining instance of that pattern.

> **UPDATE (same night, after this audit ran):** item 1.1's missing migration is now partly
> addressed — `TranscriptSharing` + `ProjectStorage.shareTranscriptsWithBackup` collapse
> per-clip forks and reconstruct legacy partitions (see `SPEC_TRANSCRIPT_SHARING.md`).
> **Step 3 (the panel) is still open**, and that spec argues it should NOT be implemented as
> originally written.

> ## STATUS BOARD (updated 2026-07-26 ~03:10)
>
> | item | state | commit |
> |---|---|---|
> | 1.2 LAYER schema hole | **CLOSED** — stamp v11, offline-proved + device-verified | `d77daf3` |
> | 2.1 caption size in preview | **CLOSED** — device A/B 3%↔20%, plus the re-bind-blanks-captions bug found doing it | `eaff34b` |
> | 2.2 audio caption size persisted | **CLOSED** — device round-trip 0.15 in → 0.15 out | `eaff34b` |
> | 1.3 `layerId: null` | **CLOSED for the 4 named sites**; ~219 sibling reads still exposed, see the commit | `7a09eb6` |
> | 1.4 downgrade drill | **RAN 7/7 on device** — guard holds, dialog fires, file byte-identical, export works. Found one real gap: the undo-history sidecar was written for a read-only project | see below |
> | guard hygiene | zero-match now a hard failure in both python guards | `b8b6234` |
> | 2.7 neutral substrate | items **2, 3** (preview/eye, device A/B) and **8** (export frame diff) now RUN and pass; 4 re-confirmed. Items 5–7 still open (gesture injection drifts) | `c8cee20`, `9c8e8bc` |
> | 2.6 cross-type Z | **CLOSED** — acceptance 1, 2 and 4 all run on device. Z3's visual debt and Z4's frame-diff debt are cleared | see `SPEC_CROSSTYPE_Z.md` |
> | 2.5 PiP audio | **export leg PROVED** — fitted gain 0.993, corr 0.999, silent before the offset, and no audio track at all when not opted in. So "doubled audio" is ruled out. **Preview leg still UNVERIFIED (needs a human to listen); acceptance 4 still open (no dual-stream pair project exists)** | `cc1691a` |
>
> | 2.4 captions after a split | **CLOSED, and the audit had the direction backwards** — the EXPORT is correct (it windows per clip); the PREVIEW over-rendered the neighbouring clip's words. Observed on a real transcript, fixed, device-verified | `1a4bcc8` |
> | timer export (handoff §7) | **ANSWERED** — timer renders and counts in an export: 0:04/0:03/0:02/0:01 at the right times, 4/4 | `21f518a` |
>
> New reusable tooling: `tasks/export_ab_diff.py` (absolute-geometry export frame diff, with a
> `--check-asym` gate that refuses fixtures symmetric enough to hide a flip),
> `tasks/export_audio_probe.py` (fits a source's amplitude inside an export: 1.0 = once,
> 2.0 = doubled) and `tasks/schema_layer_stamp.py`.
>
> Everything else below is untouched and still open. Next by the recommended order: **2.3**
> (preset crops during transitions — read the F12 RE-SCOPED block, NOT the older one-line note;
> fixing only the export leg CREATES a divergence), then **2.4** (captions after a split: export
> windows the transcript, preview does not — the straddle trigger was reasoned, never observed,
> and `export_ab_diff.py` can now settle it), then the Tier 3 items.

---

## TIER 1 — DATA-LOSS RISK

### 1.1 Transcript windowing: step 3 never landed, and no repair for damaged projects
`PLAN_transcript_windowing.md:39-59`. Steps 1 (`Transcript.java:51`), 2 (`partitionWords`
`Timeline.java:369` and `EditScriptApplier.partitionTranscripts:780` both have ZERO callers),
4 (`AIToolExecutor.java:737,986`) and 5 (`FaditorEditorActivity.java:21315`) all landed.
**Step 3 VERIFIED NOT DONE** — all 7 panel binds still pass the full transcript
(`FaditorEditorActivity.java:7886, 20617, 20632, 21069, 21084, 21219, 21236`); no
`bindTranscriptPanel` helper exists; `TranscriptPanelView.setTranscript:122` takes no
in/out. **No transcript migration existed** (grepped every `migrate*` in
Timeline/ProjectStorage). Live consequence: the exact regression baking was added to prevent
now ships — after a split the panel wraps ALL words sequentially, so each half shows the
other's words misaligned.
**Risk:** DATA-LOSS (historical) + SILENT WRONGNESS (current). **VERIFIED-OPEN.**
*(Migration since built; step 3 still open.)*

### 1.2 `TrackKind.LAYER` shipped with NO schema bump — an older build silently rewrites lanes
`SPEC_NEUTRAL_SUBSTRATE.md:180-198` declared "Storage: FREE… No schema bump", relying on
`TrackKind.fromName`'s VIDEO fallback (`layers/TrackKind.java:64-70`). The safety argument
does not hold: `FaditorProject.SCHEMA_VERSION` is still **10** (`FaditorProject.java:30`), so
creating a LAYER lane never raises it, so the downgrade guard
(`ProjectStorage.java:2080-2085`, fires only when on-disk > running) **never trips**. An
older v10 build coerces `kind=LAYER` to `VIDEO` and re-serializes it that way on autosave.
Since kind decides emission phase = band position, and band position IS paint order after
cross-type Z, the round-trip permanently changes what paints over what in preview AND
export. Same shape as the seeded-lane pre-emption bug.
**Risk:** DATA-LOSS (irreversible lane-identity rewrite). **VERIFIED-OPEN.**
**Cheapest real fix in this audit:** bump SCHEMA_VERSION, or stamp on first LAYER def.

### 1.3 Explicit `layerId: null` makes the loader drop the object
`handoff.md` section 2. All four deserializers do `if (obj.has("layerId")) …getAsString()` —
`ProjectStorage.java:1518, 2142, 2239, 2457`. `JsonNull.getAsString()` throws; in the sprite
path it swallows the whole sprite. Only reachable via hand-edited JSON today, but any future
writer that serializes nulls (repair tool, import path, AI-generated project) silently
deletes objects. Four `isJsonNull()` checks. **VERIFIED-OPEN.**

### 1.4 Schema downgrade-guard drill has never been run end-to-end
`DRILL_SCHEMA_DOWNGRADE.md`; ship-blocker #3 in `road_map.md:31`. The guard is real and
reviewed; the 7-step drill was never executed (`road_map.md:208`). Reviewing the guard is
what surfaced "the refusal was completely silent, so the user loses a session's work" — the
doc itself argues review is insufficient. Note item 1.2 is a hole this drill as written would
NOT catch, because the guard never fires. **VERIFIED-OPEN.**

---

## TIER 2 — PREVIEW/EXPORT DIVERGENCE

### 2.1 Caption size slider does nothing in preview, everything in export
`DIAG_20260626.md:125`, open a month. `CaptionOverlayView.java:49` declares
`sizeFraction = 0.060f` and line 177 is its ONLY other use — no setter; `bindCaptionData`
(`FaditorEditorActivity.java:19446-19452`) never touches it. The control is fully wired
(`applyCaptionSize` writes model + undo + autosave) and export reads the model
(`CompositeExportOverlay.java:236, 267`). Net: the user sizes captions blind.
**VERIFIED-OPEN.**

### 2.2 Audio-clip caption size is never persisted
Same doc line. `ProjectStorage.java:1224` writes `captionSizeFraction` for CLIPS only; the
audio serializer writes styleId + centerX/Y (`:1702-1703`) but not size; reader has a clip
match at `:1427`, none for audio. Reverts to 0.060 on reload after changing the export.
**VERIFIED-OPEN.**

### 2.3 Named-preset crops ignored during transitions
`PERF_SPEC_LONGFILE_20260718.md` "F12 LIMIT RE-SCOPED", explicitly NOT ATTEMPTED. The
`custom` path is done; the preset path short-circuits at all three sites —
`FaditorEditorActivity.java:8958`, `:9186`, `gltransitions/GlTransitionFrameOverlay.java:216`.
A preset-cropped clip blends UNCROPPED then snaps at the cut, identically in both paths.
**Fixing only the export leg CREATES a divergence** — read the RE-SCOPED block, not the older
one-line note. **VERIFIED-OPEN.**

### 2.4 Captions after a split: export windows the transcript, preview does not
Consequence of 1.1. Export builds captions from `t.windowed(in,out)`
(`CompositeExportOverlay.java:233, 265`); preview binds the raw full transcript
(`FaditorEditorActivity.java:19450`). A phrase straddling a split boundary can render in
preview and be dropped in export. **VERIFIED-OPEN** (asymmetry confirmed; straddle trigger
reasoned, not observed).

### 2.5 PiP audio: authority verified, plumbing on both sides is not
`SPEC_PIP_AUDIO.md:128-145`, acceptance 2 and 4 open. All code exists and the decision layer
is device-verified across four gate states — but nobody has listened to a PiP or exported
one. Failure mode is doubled audio in an export the user may not re-check. **VERIFIED-OPEN.**

### 2.6 Cross-type Z built in both paths, proven in neither
`SPEC_CROSSTYPE_Z.md` Z3/Z4. Z1/Z2 simulation-proven over 11 projects; the
absolute-geometry A/B frame diff was never run. Their own recorded lesson is that symmetric
proofs miss flips — exactly what a two-bucket partition can get wrong. **VERIFIED-OPEN.**

### 2.7 Neutral substrate: 7 of 8 validation-queue items never run
`SPEC_NEUTRAL_SUBSTRATE.md:258-291`. Items 1 and 4 done; open are #2 preview renders all
payload types, #3 lane eye hides all types, #5-#7 the gesture half, and **#8 the export A/B
frame diff**. Weight: item 1 alone found a real device bug (`4eda119`) that code review had
missed twice. **VERIFIED-OPEN.**

---

## TIER 3 — SILENT WRONGNESS / FUNCTIONAL GAPS

- **3.1 Dual-stream webcam file has no segment rollover.**
  `feature-dual-stream-recording-spec.md:89`; `WebcamEncoderPipeline.java:45-47`. On a long
  recording the screen side becomes N files and the webcam one — pair auto-detect and every
  mirrored op assume 1:1. **VERIFIED-OPEN.**
- **3.2 Dual-stream Phase-4 deferrals.** Trim mirroring is same-speed only and silently
  one-sided across a speed mismatch; no linked badge on clip blocks; split mirror untested
  against per-half `removedSpans`; auto-detect offered only on playhead-insert. LIKELY-OPEN.
- **3.3 `EditScriptApplier` excluded from the transition-index audit.**
  `AUDIT_TRANSITION_INDEX_UNDO.md`. The `:927-928` double-shift worry IS settled (deliberate:
  two clips inserted for a b-roll span). What remains: none of these paths use the
  `snapshotTransitions()`/`restoreTransitions()` discipline the rest of the audit
  established. Same subsystem that produced the session's biggest bug. LIKELY-OPEN.
- **3.4 "Consolidate Project" never built.** `PLAN_asset_browser_v2_layers.md:38-58` calls the
  review/manifest screen "the safety feature — REQUIRED". Grep finds no packaging feature.
  Narrower than it reads: `project://` paths + copy-on-insert DID land, so new inserts are
  self-contained; only retroactive pack-up/portability is missing. UX GAP. **VERIFIED-OPEN.**
- **3.5 Old audio path still double-rendered.** `LANES.md:344-350`. Doc estimates "~9"
  `getSelectedAudioIndex` sites to re-anchor; there are **63**. Significantly under-scoped.
  **VERIFIED-OPEN.**
- **3.6 Legacy `AudioClip.waveform int[]`** — a known schema-adjacent migration with no plan
  and no owner (`Opencode-work.md:740-750`). Inert now; DATA-LOSS if attempted without one.
- **3.7 Overlapping items in existing projects were never migrated**
  (`PLAN_LAYER_GESTURE_CONTRACT.md:63-64`) while `SPEC_NEUTRAL_SUBSTRATE.md:145-154` asserts
  one-lane-one-track as an invariant. Legacy overlaps exist in a state the resolver assumes
  impossible. LIKELY-OPEN.

---

## TIER 4 — UX gaps with open checklists

`PLAYHEAD_KINEMASTER_20260719.md:96-110` (10-item device checklist, all unchecked; bookmark
persistence reads as unwired) · `PLAN_LOOP_PINGPONG.md:110-121` (7 unchecked acceptances
incl. export parity frame-compare; the failure drill was **attempted 0719 and ABORTED at
~90% staged** on human presence, recipe recorded, never re-run — failure mode is whole-player
blackout) · `PLAN_LAYER_GESTURE_CONTRACT.md:136-138` (temp `ROWGESTURE` logging still to be
stripped) · `feature-visualizer-studio-spec.md:128-170,186` (live-viz draw 4.5-6.25ms vs 4ms
budget; `VISUALIZER_STRIP_FRACTION` hardcoded) · `PLAN_G9_LINK_ENGINE.md:175,189,301,310` ·
`GL_TRANSITIONS_HANDOFF_20260718.md:153` · `PERF_SPEC_LONGFILE_20260718.md:125` ·
`LONGFILE_FEEDBACK_20260716.md:50,58,141-152` (ANR root-cause owed — evidence captured, never
diagnosed) · `FEEDBACK_20260620.md` (oldest, some may be stale) · `road_map.md:615-734`
(phases 5.x/7.x/8.3 NOT STARTED) · `SPEC_NEUTRAL_SUBSTRATE.md:78-86` (audio sub-drawer on
video/master rows — not built, own lane) · `SPEC_PIP_AUDIO.md:114-126` (three deliberate
gaps: preview plays at most ONE PiP's audio; no volume ENVELOPE on export; loop extension not
reflected).

**Do not "fix" the ~34 `TODO(strings)` markers** — `road_map.md:44-48` freezes them behind
the rebrand / de-politicize decisions.

---

## TIER 5 — STALE DOCS (read as open, actually done — do NOT re-scope)

| Doc | Claims | Reality |
|---|---|---|
| `DIAG_assetbrowser_20260626.md:108-110` | MISSING state/badge/export-block "not started" | All three built; export IS blocked (`FaditorEditorActivity.java:9701-9707`) |
| `FEEDBACK_20260706_audio_and_delineation.md:41` | audio-only export not built | Built (`ExportService.java:175,253,289,335`) |
| `PERF_SPEC_LONGFILE_20260718.md:365` | speed-change gapless deferred | Landed `3de40a2` |
| `PERF_SPEC_LONGFILE_20260718.md` F11/F12/F13 | "Uncommitted" | Committed; only the F12 preset sub-case (2.3) is open |
| `LANE_BADGES_AND_PREVIEWS_SPEC_20260714.md:92` + `FEEDBACK_20260717…:283` | section 4.5 migration not done | Contradicted at `:54` in the same file; `Timeline.migrateTrackEyeLockToObjects():2057` exists |
| `gl_transitions_params.md:6`, `PLAN_filters_color_text_transitions.md:91` | GL transitions not built | Long since built and device-proven |
| `HANDOFF_NEXT_20260622.md:56`, `…0623.md:55` | clip opacity keyframes not implemented | Probably stale (shipped `96b99d5`) — worth one grep before re-scoping |
| `todo_asset_browser.md` | 23 unchecked boxes | Superseded by the v2 plan + shipped browser; historical |

---

## Recommended order

1. **1.1 step 3** (~30 min mechanical; route all 7 binds through one `bindTranscriptPanel`)
   — but see `SPEC_TRANSCRIPT_SHARING.md`: show the whole source with the current clip
   highlighted, NOT the windowing the plan specified.
2. **1.2 LAYER schema hole** — cheapest real data-loss fix here; then 1.4's drill validates
   both at once.
3. **2.1 + 2.2 caption size** — one setter, one bind call, two serializer lines.
4. **1.4 downgrade drill**, then the standing device-verify block (2.5, 2.6, 2.7).
5. **2.3 preset crop** — read the F12 RE-SCOPED block, not the older note.
6. **1.3** — four `isJsonNull()` guards.
