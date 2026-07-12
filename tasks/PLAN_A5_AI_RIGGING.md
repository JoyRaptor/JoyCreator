# PLAN — A5 AI RIGGING + FF-B AI sprite tools (design pass)

Mission: the AI-assisted avatar-rig authoring loop (A5, PLAN_AVATAR_STUDIO §"AI rigging")
plus the sprite AI tool `describe_sprite_sheet` (FF-B, PLAN_SPRITE_ANIMATION). Mirror the
existing AIToolExecutor conventions exactly (declare in `getToolDescriptions()`, dispatch in
`executeTool`, validate tolerantly, propose-then-confirm for the destructive insert, key-gated
at the chat entry).

## Doctrine (binding, from the plans)
- AI does STRUCTURE, human does TASTE (author_avatar_rig lands a rig; user nudges pivots/extremes
  in Avatar Studio). No new chat UI — reuse the existing proposal-card surface.
- Propose-then-confirm, undoable, key-gated (PLAN_AVATAR_STUDIO §"AI rigging").
- Canonical biped part ids: head / body / armL / armR / handL / handR / mouth; default anchors;
  default 3x3 head pose grid; empty 1D strips for limbs (the schema contract the tool validates against).
- Single-authority reuse: SpriteGridDetector for grid detect, SpriteSheetRenderer.cellRectSource for
  cell geometry, AvatarRig.fromJson tolerance as the validation base.

## Files
NEW (all allowed — avatar/ new files are fine, only the listed avatar/* are locked):
- `avatar/AvatarRigTemplates.java` — canonical biped template (rig JSON + AvatarRig builder) +
  `CANONICAL_PART_IDS`. The schema contract `author_avatar_rig` validates against.
- `avatar/AvatarRigValidator.java` — PURE validator (no Android deps): `validate(AvatarRig,
  Set<String> knownSheetIds) → List<String> reasons` (empty = valid). JVM-testable.
- `tools/jvm-harness/AvatarRigAuthorTest.java` — template round-trips via AvatarRig.fromJson;
  validator accept/reject cases.

EDIT:
- `ai/AIToolExecutor.java` — add `describe_sprite_sheet`, `author_avatar_rig`, `apply_avatar_rig`
  to dispatch + `getToolDescriptions()`.
- `ai/ChatAssistantActivity.java` (NOT locked) — render the `@@PROPOSAL:avatar_rig@@` card, Apply →
  `apply_avatar_rig`. Mirrors buildNarrativeCard / buildBrollCard exactly.

## Tool 1 — describe_sprite_sheet  (FF-B; deterministic, no network)
args: `{"sheetId":"..."}` (a project SpriteSheet) OR `{"imageUri":"file://…|content://…|project://…"}`.
- sheetId path: load project, find sheet, decode its uri; use the sheet's STORED grid; include cell names.
- imageUri path: decode; run SpriteGridDetector.detect → a SUGGESTED grid (no names).
Returns a structured JSON string: `{ imageWidth, imageHeight, source, grid:{cols,rows,marginX,marginY,
spacingX,spacingY, detected:bool}, cells:[ {index, name?, x,y,w,h, occupancy } ] }` where occupancy =
fraction of non-transparent pixels in the cell rect (alpha coverage), and x/y/w/h are SOURCE-pixel
bounds from `SpriteSheetRenderer.cellRectSource`. Bitmap decoded once, downsampled (inSampleSize) for
the occupancy scan. Grid cell cap so a pathological grid can't blow up.

## Tool 2 — AvatarRigTemplates (biped template)
`bipedTemplate(name, sheetId)` → AvatarRig with parts head/body(root)/armL/armR/handL/handR/mouth,
parent hierarchy (body root; head+arms parent=body; hands parent to their arm; mouth parent=head),
default anchors, one 3x3 head PoseDomain (driverX=yaw, driverY=pitch, 9 empty cells the user fills),
and empty 1-D strips (5-cell, driverX=angle) for armL/armR/body. `templateJson()` → the JSON the model
emits against. `CANONICAL_PART_IDS` = the validator's known-name set.

## Tool 3 — author_avatar_rig  (propose)  +  apply_avatar_rig  (confirm)
`author_avatar_rig` args: `{"rig": <object|string>}`.
1. Parse via `AvatarRig.fromJson` (tolerant — malformed elements drop themselves).
2. `AvatarRigValidator.validate(rig, project sheet ids)`:
   - empty parts → reject.
   - unknown part id (not in CANONICAL_PART_IDS) → reject-with-reason (biped contract).
   - part.sheetId empty or not a project sheet → reject (missing sheet).
   - duplicate part ids → reject.
   - parentId referencing a non-existent part → reject.
   - domain malformed: cols<1/rows<1, or a cell (col,row) outside [0,cols)×[0,rows), or a pose's
     partId not a rig part → reject.
   - reject list non-empty → return the reasons so the MODEL can fix and re-emit.
3. valid → return `@@PROPOSAL:avatar_rig@@{"rig":<rigJson>,"name":..,"partCount":..,"domainCount":..}`
   + human text (WAIT for the user to tap Apply; or apply_avatar_rig on a chat go-ahead).
`apply_avatar_rig` args: `{"rig": <object|string>}`. Re-validate (defensive), then load project, add the
rig to `FaditorProject.avatarRigs` (idempotent by id — replace an existing same-id rig), `storage.save`,
`AIChatState.signalModified`. ONE undoable step = the editor's reload-on-modified (same granularity as
apply_narrative_proposal / apply_broll_proposal). Insert only registers the rig; the user opens Avatar
Studio to nudge pivots + author extremes (AI=structure, human=taste).

## Key-gating
The AI assistant is gated at the ChatAssistantActivity ENTRY (PLAN_SPRITE_ANIMATION decision 4: no key →
key dialog). Tools that make their OWN OpenRouter call re-check `apiKeyModel()`; tools that don't
(split_clip, add_text_overlay, …) do not. describe_sprite_sheet / author_avatar_rig / apply_avatar_rig
make NO network call (the MODEL emits the rig), so they follow the non-network tool pattern — gated at
entry, no spurious per-tool key check. Documented so it's a deliberate choice, not an omission.

## Verify
- JVM harness AvatarRigAuthorTest: template parses + round-trips; validator green on the template,
  red (with the right reason) on unknown-part / missing-sheet / malformed-domain / dup-id rigs.
- Build-green via the watcher's build.log BUILD SUCCESSFUL.
