# Adversarial review — JB-0.02b "New enum constant ⇒ version bump" rule (docs + guard test)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `321e23f1`.
- Spec reviewed: `tasks/joybrush/specs/JB-0.02b_enum_version_rule.md` + LEAD_RULINGS R3.
- §5b checks: diff touches only KDoc on the four serialised enums, NEW `EnumFreezeTest.kt`, and the spec's Questions appendix — inside the owner area, behaviour/names/values untouched per "Do not". Suite evidence: `EnumFreezeTest` 10/10, 0 failures (verification run 2026-09-28 19:11; see top note). No BLOCKER or MAJOR open.
- Top note (applies to all eight files in this pass): a fresh `./gradlew -p joybrush :core:jvmTest` re-run is currently blocked by unrelated UNTRACKED in-flight work — `joybrush/core/.../export/` (JB-4.03a) fails `compileKotlinJvm` (`SpritePacker.kt:214-215`) — plus `brush/imports/` (JB-8.03). Shared-tree conflict: reported, not touched, not fixed. Suite numbers below come from the last full green verification run after these eight tasks landed, plus the builders' pasted outputs at commit time.

## Findings: none. Verified instead (proof for each spec step)

1. **Step 1 — the sentence is on all four enums.** Grep `SERIALISED` in `commonMain`: `DocModel.kt:49` (BoardKind), `:85` (LayerKind), `:97` (BlendMode), `BrushPreset.kt:10` (BrushInput). The brush-side KDoc additionally pins the `BRUSH_VERSION` + `BrushPreset.version` second-literal trap (spec Q3) and the "validate says newer" reading (spec Q4) — both correct statements about the code as it stands.
2. **Step 2 — freeze lists + versions.** `EnumFreezeTest.kt:38-85`: exact ordered lists for all four enums, `DOC_VERSION == 1`, `BRUSH_VERSION == 1`, and `BrushPreset(...).version == BRUSH_VERSION`. Any rename/delete/append or version drift fails the build.
3. **Step 3 — refusal pinned, with controls.** Unknown `HOLOGRAM` board kind / `TELEPATHY` layer kind / `HOLOGRAM` blend → `DocException` (`:170-194`, each with a decode-the-control-first guard so the test cannot pass on a broken fixture); unknown `telepathy` brush input → `BrushException` (`:214-219`). The token-in-file test (`:90-103`) proves the NAME is the on-disk token and every constant round-trips, so the freeze lists mean what they claim.
4. **R3's promise holds end to end on the document path:** `DocJson.decode` refuses unknown constants (above) and `JbArchive.read` (JB-0.08a) runs `DocOps.validate`, which is where the "from a newer Joy Brush" version sentence lives — so an old app shown a new file says so in words instead of misreading it. (The brush half still has no load-path caller of `validate`; that is filed under JB-0.03b, not here.)

## Explicitly not filed
- Spec Q1 (KDoc sentence itself untestable from commonTest), Q2 (tripwire-not-enforcement), Q5 (cosmetic blank line), Q6 (KDoc link to a file another agent is editing): all acknowledged in the spec/test comments already; re-filing would duplicate triage. Q2's suggested `V1_*`-list enforcement remains a good follow-up.

## Recommendation
No send-back. No BLOCKER or MAJOR open against JB-0.02b.
