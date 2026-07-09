# PLAN — G9 Object Linking: shared link engine (lean A)

Execution design for gesture contract §5.6 (`PLAN_GESTURE_CONTRACT_FINAL_20260706.md` lines 262-281)
and the G9 build-slice row (same doc, lines 348-350). Lean **A**: build one link engine, then
re-express G5 piggyback/stratified attachment (already shipped, commit 5192186) as a named preset
over it, instead of a second parallel grouping system. Design only — no code changes in this doc.

---

## 1. Why generalize now — the pattern G5 already proved

`WaveformOverlayInstance` (`WaveformOverlayInstance.java:40-46`) carries `attachedClipId` (host ref,
null = unlinked), `attachOffsetMs`/`attachDurationMs` (host-relative). The rider's **absolute**
`startMs`/`endMs` stay the one field every consumer reads — nothing downstream knows about the tether.

- **One write-point**: `Timeline.resyncAttachedVisualizers()` (`Timeline.java:1369-1387`) is the only
  place absolute time is recomputed from the host-relative fields. Called from
  `syncTimelineOverlays()` (`FaditorEditorActivity.java:9314`, ~90 call sites) after every mutation and
  before export. A host move/trim/split shifts `hostStart` (`segmentStartMs()`, `Timeline.java:1336-
  1344`) → rider's absolute window recomputes → consumers stay unaware. Host-deleted-in-place
  auto-detaches and freezes the last window (`Timeline.java:1374-1376`) rather than crashing/jumping.
- Mutators `attachVisualizerToHostUnderStart`/`detachVisualizer` (`Timeline.java:1397-1428`) capture
  the host-relative offset from the CURRENT absolute position at attach time, then call the
  write-point immediately.
- Storage is **tolerant / absent = unlinked**: save only writes the three fields when attached
  (`ProjectStorage.java:1652-1658`); load only reads them `if (wj.has("attachedClipId"))`
  (`ProjectStorage.java:2112-2121`). Every pre-G5 project round-trips byte-identical.

**G9's job**: lift this exact shape — host-relative fields + one absolute-time write-point + tolerant
storage — from "one payload type, one relationship" to "any payload type, arbitrary link groups,
multiple linkable properties." G5 becomes the first preset over the general engine, not a rewrite.

---

## 2. Link-group model

**What can link**: any timeline payload — master `Clip`, overlay/PiP `Clip` (`isOverlayClip()`),
`TextOverlayItem`, `SpriteOverlayItem`, `WaveformOverlayInstance`, `AudioClip` — all already have a
stable UUID `getId()` (enumerated by `TimedItem.payloadKind()`, `TimedItem.java:203-211`). A link
group references `(kind, id)` pairs, not typed fields, so any payload can link to any other.

**New model classes** (`app/.../layers/LinkGroup.java`, `LinkMember.java`, `LinkedProperty.java`):
```
LinkGroup {
  String id;                       // stable UUID, survives undo — never regenerated on redo
  List<LinkMember> members;        // 2+; <2 auto-dissolves
  Set<LinkedProperty> properties;  // TIME, POSITION, OPACITY, SCALE, ROTATION
  String presetKind;                // null = ad-hoc; "PIGGYBACK" | "STRATIFIED" = G5 preset (§4)
}
LinkMember {
  String itemKind, itemId;   // e.g. "textOverlay", <uuid>
  boolean isHost;             // true for exactly one member in a host/rider group; false in a peer group
  long hostOffsetMs, hostDurationMs;                       // host-relative TIME (non-preset groups, §3.4)
  float hostDx, hostDy, hostDScale, hostDRotationDeg, hostDOpacity; // host-relative other axes
}
```
- Lives in a new `Timeline` field `linkGroups`, same flat-list/tolerant convention as
  `waveformOverlays`/`spriteOverlays`.
- **Peer vs host/rider**: contract §5.6's dialog is symmetric ("what would you like to link?" over a
  selection) → a **peer** group, no owner, delta-propagation (§3). G5's attach is asymmetric → **host/
  rider**, exactly one host, riders computed relative to it. `isHost` distinguishes the shape.
- **v1 cap**: a payload belongs to at most one `LinkGroup` per property-axis (no nested/overlapping
  links) — keeps resync order-independent. Open question §8.3.
- **Id stability**: group/member ids are the SAME UUIDs already on the payload objects — never minted
  fresh for linking, so undo/redo of unrelated edits never needs id-remapping (mirrors
  `attachedClipId` today). A group referencing a dead id is pruned lazily by the resync write-point,
  same conservative-drop philosophy as `pruneOrphanedTrackFlags()` (`Timeline.java:988-1019`).

**Storage** (project JSON, tolerant, absent = unlinked), sibling to the `waveformOverlays` block:
```json
"linkGroups": [{ "id": "…", "properties": ["TIME"], "presetKind": null,
  "members": [{"kind":"textOverlay","id":"…","host":false}, {"kind":"textOverlay","id":"…","host":false}] }]
```
Array absent for every pre-G9 project (zero migration cost). `presetKind` set only for G5 groups.
Load-time: members that don't resolve to a live payload are dropped; a group left <2 members is
dropped entirely — logged, never a hard failure.

---

## 3. Semantics — one write-point, generalized

**`Timeline.resyncLinkGroups()`** — same call contract as `resyncAttachedVisualizers()` (called from
`syncTimelineOverlays()` after every mutation, before export):
- **Host/rider groups**: recompute rider absolute time/transform from `LinkMember.hostOffsetMs`/
  `hostDx` etc. against the host's current state — the same algorithm `resyncAttachedVisualizers`
  already runs, generalized off one payload's fields onto `LinkMember`.
- **Peer groups**: no host-relative fields; TIME/POSITION/OPACITY/SCALE/ROTATION peer-links are
  **delta-propagating** (push), not host-relative (pull) — when gesture code detects a linked item
  moved by Δ, it applies the same Δ to every other member of every group it belongs to, in the SAME
  gesture (§3.3, §6). There's no natural "host" for a symmetric link between objects with unrelated
  native transforms, so `resyncLinkGroups()` for peer groups is a no-op safety net that only
  re-validates membership (drops dead ids) — keeps "one write-point" true even though peer-linking is
  push-based.

**Cross-payload offsets (generalizing G5's host-relative fields, §3.4)**: rather than growing every
payload class with `attachOffsetMs`-shaped fields, the host-relative offset moves onto `LinkMember`
itself (see the struct above) for non-G5 host/rider groups. `WaveformOverlayInstance`'s own fields
stay untouched (§4) — the write-point branches: `presetKind=="PIGGYBACK"/"STRATIFIED"` reads
`WaveformOverlayInstance.attachOffsetMs` (delegating to the existing method, §4.2); any other
host/rider group reads `LinkMember.hostOffsetMs`.

**Trim behavior — OPEN QUESTION (§8.1).** G5 never trims (attached visualizer length rides
`attachDurationMs`, no independent handle). G9 must decide for payloads WITH trim handles (text end,
audio in/out, PiP in/out): **(a)** TIME-linked riders mirror the host's trim delta (CapCut-style), or
**(b)** TIME-links only mirror MOVE, never TRIM (simplest, matches G5's own scope exactly — the
attached visualizer's span only changes as a side effect of host edits, never a deliberate trim-
propagation feature). Lean **(b)** for v1; ship move-linking, decide trim after JoyRaptor sees it.

**Delete behavior**: deleting a member drops it from every group it's in (live, same code path
`Timeline.pruneLinkGroup(groupId)` used at load-time); <2 members left → dissolve. Deleting a HOST
(host/rider) auto-detaches every rider in place — literally `resyncAttachedVisualizers`'s existing
branch, unchanged. Deleting the last member of a peer group is already covered by the ≥2 rule.

---

## 4. G5 becomes a preset — migration/aliasing strategy

**Goal**: never touch `WaveformOverlayInstance`'s shipped fields or storage keys (§1) — every existing
project with an attached visualizer keeps loading/saving byte-identical.

**4.1 Aliasing, not migration.** After load (once `resyncAttachedVisualizers()` has run for
back-compat), synthesize a **virtual, unpersisted** `LinkGroup` per attached instance:
```java
// Timeline — mirrors migrateSpriteLayers()'s pattern; called once post-load
public void synthesizeG5PresetLinkGroups() {
    for (WaveformOverlayInstance w : waveformOverlays) {
        if (w.getAttachedClipId() == null) continue;
        LinkGroup g = new LinkGroup(/* deterministic id, e.g. "g5:" + w.getId() */);
        g.presetKind = w.isStratified() ? "STRATIFIED" : "PIGGYBACK"; // new field, §4.3
        g.properties = EnumSet.of(LinkedProperty.TIME, LinkedProperty.OPACITY /* piggyback only */);
        g.members.add(new LinkMember("clip", w.getAttachedClipId(), /*isHost*/true));
        g.members.add(new LinkMember("waveform", w.getId(), /*isHost*/false));
        syntheticPresetGroups.add(g); // separate transient list — the JSON writer never sees it
    }
}
```
Kept in a **separate transient list**, not `linkGroups`, so `ProjectStorage.save()` needs no
string-prefix convention to exclude them — it simply never touches that list. This mirrors the
existing "synchronized view, never cached" rule already documented on `Timeline`/`Track`
(`Timeline.java:24-37`): ground truth stays `attachedClipId` et al.; the `LinkGroup` view is rebuilt
every load. Net effect: the link toolbar (§5) shows visualizer-host pairs as linked via the SAME
machinery as ad-hoc groups, with zero schema risk to G5.

**4.2 Resync delegation.** `resyncLinkGroups()` special-cases `presetKind != null` whose rider is a
`WaveformOverlayInstance`: it CALLS `resyncAttachedVisualizers()` as a sub-step rather than
reimplementing the offset math. The general write-point wraps the specific one.

**4.3 Piggyback/stratified needs a real field — gap, not paper-over.** Grep confirms
`WaveformOverlayInstance` has no `isPiggyback`/`stratified` field today — only the three attach fields
exist. Contract §4 Axis-2 needs one before `presetKind` can discriminate. Lean: add
`boolean stratified` now (additive, default `false` = piggyback, matching the contract's stated
visualizer default) so `synthesizeG5PresetLinkGroups()` has something concrete to read regardless of
when G5's own stratified-toggle UI ships. Flagged as §8.2 since it technically expands G5's already-
shipped surface, not just G9's.

**4.4 Caption attach is design-only, not blocking.** Contract §4.1's caption tie-break/pin-to-source
has no code yet (`CaptionSpanRef` is a read-only view, `TimedItem.java:64-70`). The engine should be
ABLE to host a future caption-attach preset (host=transcribed clip, rider=a caption's source-binding)
but this plan doesn't design its fields — left for whichever slice ships caption attach.

---

## 5. Gesture / UI surface

**5.1 Toolbar button repurpose.** `btn_relink_media` (`activity_faditor_editor.xml:1207`, wired
`FaditorEditorActivity.java:1917`) today opens `RelinkCatalogBottomSheet` for media relink
(`FaditorEditorActivity.java:2427-2626`). Per contract, RELINK moves into the per-object hold-menu
(G2 object-specific section — coordinate with T3 S7); this button becomes LINK/UNLINK:
- Icon state derives from selection, recomputed on every `onItemSelectionChanged` transition
  (`LayerGestureController.java:150` — already fires on every selection change, no new tracking
  needed): 0–1 unlinked selected → hidden/disabled; ≥2 selected, none linked together → **LINK**;
  exactly 1 selected and it IS in a group → **UNLINK**.
- Mixed selections (some linked, some not, or multiple already-linked) are contract-deferred ("button
  reflects the dominant state," line 274) — lean: any linked member present → show UNLINK (surfacing
  "you can undo this" beats a silent no-op). Confirm with JoyRaptor (§8.4).
- The ≥2-select LINK path needs multi-select (G8) to be functional — **build-order dependency**, not
  called out explicitly in the contract's G-series list.

**5.2 Link dialog.** Checklist = `LinkedProperty` axes (Position on timeline · Position in canvas ·
Opacity · Scale · Rotation), filtered to axes every selected payload actually supports (e.g. omit
POSITION/SCALE/ROTATION when an `AudioClip` is in the selection — it has no transform). Confirm →
one ad-hoc peer `LinkGroup`, `presetKind=null`.

**5.3 Unlink dialog.** Two parts per contract: (1) which properties to drop (partial unlink — empties
`properties` → dissolve); (2) scope — this object only (member survives if group stays ≥2) vs unlink
all (dissolve). **G5-preset special case**: unlinking the visualizer↔host group calls
`Timeline.detachVisualizer()` (existing, unchanged) rather than a generic member-removal, preserving
G5's freeze-last-window semantics.

**5.4 Visual affordance (`LayerRowRenderer`, spec only).** A link-chain glyph badge in the existing
badge cluster, reusing the delete-badge's geometry/margin pattern (`LayerRowRenderer.java:1121-1167`
— same "two gaps" rule: clear of the trim handle AND the delete badge). Unlinked-selected → no badge
(keeps the cluster economical, per contract §6). Linked → badge in the app highlight tint; tapping it
opens the same unlink dialog as the toolbar (redundant affordance, matching G5's "three ways, kept in
sync" pattern). Optional polish, later sub-slice: a thin shared-hue outline across a group's rows so a
user can trace "these move together" without opening a menu (same idea as G5's caption source
indicator); a host-row "riders attached" marker distinct from the rider's badge (mirrors G5's
down-arrow/cloud-icon pair).

---

## 6. Undo strategy

One undo step per committed action (repo convention: `PendingLayerTrackUndo`/`mergedAction`,
`FaditorEditorActivity.java:10378-10429`). Three `EditActions.LambdaAction` shapes:

1. **Create link group**: redo = `timeline.addLinkGroup(group)`; undo = `removeLinkGroup(id)`. One
   step regardless of how many members/axes were checked.
2. **Unlink (partial/full)**: snapshot the group's pre-state before mutating; redo = apply the
   requested removal/property-drop; undo = restore the snapshot (recreate with the SAME id if
   dissolved — id stability matters, §2).
3. **Live move of a linked item (delta-propagation)** must fold into the SAME step as the base item's
   move, exactly like M10 folds a cross-row track-change into the position-change action
   (`mergedAction`, `FaditorEditorActivity.java:10401-10414`). `onGestureFinished`
   (`LayerGestureController.java:69`, handled `FaditorEditorActivity.java:10184-10277`) already records
   the base item's undo; before recording, it must ALSO snapshot every OTHER propagated member's
   before/after and fold them into the same lambda pair. Requires a new staged field analogous to
   `pendingLayerTrackUndo` (`FaditorEditorActivity.java:10389`) — e.g. `pendingLinkPropagationUndo`,
   populated during `onGestureLive`'s delta-propagation, consumed by `onGestureFinished`. Same shape as
   the existing M10 staging pattern; no new undo primitive. One drag of a 4-member TIME-linked group =
   one undo entry that moves/restores all 4.

---

## 7. Build slices (always-green, device-verified)

G9 needs G8 (multi-select) for the "select 2+, tap Link" entry point, but single-item UNLINK needs no
multi-select — so G9a-c can start before G8 lands; only G9d needs it.

- **G9a — `LinkGroup` model + tolerant storage, no UI.** Files: new `LinkGroup.java`/`LinkMember.java`/
  `LinkedProperty.java` (`layers/`); `Timeline.java` (`linkGroups` field, add/remove/get,
  `pruneOrphanedLinkGroups()` mirroring `Timeline.java:988-1019`); `ProjectStorage.java` (tolerant
  save/load of `linkGroups`). *Green gate:* compiles; a project with no groups round-trips
  byte-identical. *Device-verify:* adb pull the project JSON before/after an open→save→reload cycle,
  diff should be empty (aside from timestamps).

- **G9b — G5 aliasing, no UI.** Files: `Timeline.java` (`synthesizeG5PresetLinkGroups()`, called
  post-load next to the existing `resyncAttachedVisualizers()` call); `WaveformOverlayInstance.java`
  (add `stratified` boolean, §4.3). *Green gate:* a project with an attached visualizer shows exactly
  one synthesized `PIGGYBACK`/`STRATIFIED` group with correct host/rider ids (debug log); JSON diff
  still empty (never serialized). *Device-verify:* same JSON-diff check as G9a plus a log assertion on
  synthesized-group count vs known attached-visualizer count.

- **G9c — Unlink-only UI (single item, no multi-select needed).** Files: `LayerRowRenderer.java` (link
  badge); `FaditorEditorActivity.java` (button icon-state off `onItemSelectionChanged`; unlink dialog;
  undo per §6.2, with the G5-preset branch calling `detachVisualizer()`). *Green gate:* selecting an
  attached visualizer shows the badge; unlink detaches it (frozen window, matches today's behavior);
  undo restores it. *Device-verify (tap-only, fully adb-drivable):* `input tap` to select the row, tap
  badge, tap "unlink all," screenshot (badge gone); tap undo, screenshot (badge back).

- **G9d — Link-creation UI, multi-select entry (needs G8).** Files: `FaditorEditorActivity.java`
  (button LINK-mode wiring off the multi-select set); new link dialog (structurally like
  `RelinkCatalogBottomSheet`, not its content); `Timeline.java` (`addLinkGroup` + undo per §6.1).
  *Green gate:* marquee-select 2 text overlays, LINK → check TIME → confirm creates a group; badge on
  both; undo removes it. *Device-verify:* marquee-select is a drag `input swipe` can drive — fully
  adb-drivable end to end (swipe box, tap link button, tap checkbox+confirm, screenshot both badges,
  tap undo, screenshot absence).

- **G9e — Peer TIME-link delta propagation + merged undo.** Files: `FaditorEditorActivity.java` (the
  `onGestureLive`/`onGestureFinished` callback — propagation needs cross-item knowledge
  `LayerGestureController` doesn't have, since it tracks only one `activeItem`,
  `LayerGestureController.java:193`; new `pendingLinkPropagationUndo` staging per §6.3). *Green gate:*
  two TIME-linked text overlays; hold-drag one → the other visibly moves by the same delta DURING the
  live drag; release → ONE undo entry restores both. *Device-verify:* a hold-then-drag is a moving
  gesture (`input swipe` with a long down-duration) that adb CAN inject reliably — no stationary
  long-press needed for this slice. Screenshot mid-drag, post-release, post-undo.

- **G9f — Trim-linking decision + partial-unlink UI + row-tint polish.** Scoped only once §8.1
  resolves and G9c–e are stable. Files TBD (likely `LayerRowRenderer.java` tint + wherever trim-
  gesture propagation lands, mirroring G9e). Deliberately unscheduled in detail — most likely to shift
  after JoyRaptor sees G9a-e.

---

## 8. Open questions for JoyRaptor

1. **Trim propagation (§3):** TIME-links also mirror TRIM, or MOVE-only for v1 (current lean)?
2. **Piggyback/stratified field (§4.3):** OK to add `WaveformOverlayInstance.stratified` now, ahead of
   G5's own toggle UI shipping — or defer STRATIFIED state entirely until that toggle exists?
3. **Overlapping link groups (§2):** v1 caps one group per property-axis per item. Acceptable, or does
   an item need simultaneous TIME-link-to-A and OPACITY-link-to-B from day one?
4. **Toolbar dominant-state tie-break (§5.1):** "any linked member present → UNLINK" as the mixed-
   selection default, or a third disabled/neutral icon for genuinely mixed selections?
5. **Caption attach (§4.4):** confirmed out of scope here — flagging so it isn't assumed silently
   covered whenever the caption-attach slice is scheduled.
