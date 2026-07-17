# Lane badges + item previews — JoyRaptor spec (2026-07-14, verbal during device-verify walkthrough)

**Supersedes/amends** `PLAN_GESTURE_CONTRACT_FINAL_20260706.md §4.5` ("Lanes are NAMELESS… minimal row
gutter = solo + twirl only"). JoyRaptor re-affirmed layers-as-substrate and REPLACED "no label at all" with
"**kind badge instead of a name**." The current row gutter (name labels "Text/Sprite/Audio/PiP/VIZ/CC"
+ per-row eye/lock/etc icons) predates §4.5 and implements neither — it must migrate to this.

## 1. Row gutter: names → kind badges
Layers are substrate; they are not named, typed entities. Each row's gutter shows a small **badge**
(icon) for the kind of content currently on it, NOT a text label:

| Kind | Badge (JoyRaptor's sketch) |
|---|---|
| Closed captions | CC badge (standard closed-captioning glyph) |
| Waveform / visualizer | waveform badge |
| Sprite | "stickman ring" (stick figure in a circle) |
| Text | "T in a box" or "abc" |
| Image | mountain-in-a-frame, or a camera |
| Video | little filmstrip WITH sprocket holes |

- Videos / images / sprites already read visually via their preview images (see §2), so their badges
  are arguably redundant — but "for consistency's sake, we still should have them." → ALL rows badge.
- Everything else from §4.5 stands: no per-layer eye/lock (those are per-OBJECT), solo is the only
  per-layer control, lanes appear on demand and vanish when empty.

## 2. Item preview images (videos / images / sprites)
- **Video items**: preview renders ACROSS the item ("so we can clearly see where the video is at any
  given time") — i.e. filmstrip thumbnails along the item body, like the master strip already has.
  Applies to video items on overlay/PiP lanes.
- **Sprite items**: show an updated preview image **wherever there is a keyframe** (the pose/frame at
  each ✦, drawn at that keyframe's x-position).
- **Image items**: one thumbnail at the item's start.

## 3. Pinned-thumbnail scroll behavior (the "trash-can mirror")
The preview image sits at the item's left edge by default ("before the badges so they just stay
there"). When the user scrolls right and the item's START goes off-screen while the item still spans
the viewport, the thumbnail **slides along the left-hand edge** of the screen — pinned, riding the
left edge — "similar to the trash-can behavior, except on the other side of the tape."
(I.e. the same edge-pinning affordance the trash target uses on its side.)

## Implementation notes (scoping, not yet built)
- Row gutter rendering lives in `LayerRowRenderer` (track headers) + `EditorTimelineView` (master/audio
  rows). The name labels come from track kind display names — replace with badge glyphs (vector-drawn
  or icon-font, match existing gutter icon style).
- Master-strip filmstrip extraction (T1 `78a6c6b` sequential MediaCodec sweep) is the machinery to
  reuse for §2 video-item previews on overlay lanes.
- Sprite keyframe poses: the dope-sheet/pin-warp data already knows the frame at each keyframe; render
  its sheet cell at the ✦ x.
- §3 pinning = draw-time x-clamp of the thumb to max(itemLeft, viewportLeft) while itemRight − thumbW
  > viewportLeft (mirror of the trash-can clamp).
- Suggested order: (1) badges [small, pure gutter change], (2) image-item thumbnail + §3 pinning,
  (3) sprite keyframe poses, (4) video-item strip previews [heaviest — extraction cost/caching].

**Status (2026-07-17):** §1 BADGES BUILT — `LayerRowRenderer.drawKindBadge` replaces the gutter
name text with canvas-drawn kind glyphs (filmstrip+sprockets video, T-box text, mountain-frame
image, stickman-ring sprite, CC box captions, bars visualizer, mirrored-bars audio). Device
visual-verify owed.

**§2 + §3 BUILT (2026-07-17):** item preview images + pinned-thumbnail scroll.
- Architecture: `LayerRowRenderer` stays PURE-DRAW. Three provider interfaces
  (`ImagePreviewProvider`, `SpriteCellProvider`, `VideoFilmstripProvider`) mirror the existing
  tape/HD-waveform provider pattern; `EditorTimelineView` OWNS all decode/extraction + async
  load + LRU cache + invalidate. Nothing decodes/allocates on the draw path; every preview is
  culled to the visible viewport (45-min-project / scrub-fps rule).
- **§2 image items** (`TextOverlayItem.isImage()`): one down-sampled thumbnail decoded once
  (`decodeBoundedImage`, LRU cap 48) drawn at the item start. Subject to §3 pinning.
- **§2 sprite items**: the sheet cell at each `FrameTrack` keyframe, drawn at that key's x via
  `SpriteSheetRenderer.drawCell` (decode-once sheet renderer cached by sheet id; cell resolved
  via the canonical `SpriteFrameResolver` for preset keys, direct index for cell keys). Sprite
  sheets fed to the view via new `EditorTimelineView.setSpriteSheets(...)`, wired in
  `FaditorEditorActivity` next to `setLayerTracks`.
- **§2 video items** (overlay/PiP clips): filmstrip thumbnails tiled across the body, REUSING
  the master T1 pipeline (`extractVideoThumbnails` + `thumbnailsCache` + the filmstrip disk
  cache) via the shared `hash+"_src"` source key — an overlay sharing a source with a master
  segment reuses those thumbs for free. No new extractor built.
- **§3 pinned thumbnail scroll** (`drawPinnedThumb`): draw-time x-clamp
  `thumbX = clamp(viewportLeft, itemLeft, itemRight-thumbW)` where viewportLeft = the pinned
  badge-gutter's right edge in content-x (`lastHScrollOffsetPx + HEADER_WIDTH`). Mirror of the
  trash-can's right-edge pin (`deleteBadgeCx`). Badges stay put; only the image thumb slides.
  Video filmstrips already cover the left edge (viewport-culled tiling) so they need no pin.

NOT yet done: the §4.5 eye/lock→per-object migration (needs per-object visibility controls to
exist first or users lose function — left in place deliberately). Device visual-verify of §2/§3
owed (queued separately). Sprite-cell previews assume direct/preset keys resolve via the shared
resolver; avatar-rig sprite performances render their first-frame cell only.
