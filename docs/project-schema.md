# Faditor Project JSON Schema

**Schema Version:** 10 (dual-write stamped — see "Schema Version Stamping" below)
**Last Updated:** 2026-07-07

## Overview

A Faditor project is a single JSON file stored in the app's project directory.
It describes a complete video editing timeline: clips, trims, effects,
transcripts, overlays, transitions, audio tracks, layers/tracks, sprite
animation, avatar rigs, waveform visualizers, and export settings.

External tools (AI agents, CLI scripts, desktop editors) can read and modify
project JSON as long as they follow this schema.

**Read/write is hand-written (not reflective Gson field-mapping).**
`ProjectStorage.java` implements custom `JsonSerializer`/`JsonDeserializer`s
that build/read `JsonObject`s field-by-field with `.has(key)` guards. This
means:
- **Reads are tolerant**: a missing or absent field on load always falls back
  to a sensible default — old projects load fine, and hand-edited JSON with a
  field omitted won't crash.
- **Unrecognized fields are NOT preserved on save** — unlike a fully generic
  schema-less store, the serializer only re-emits fields it explicitly knows
  about. Any genuinely foreign top-level or object key present in hand-edited
  JSON is silently dropped the next time the app saves the project. If you
  need to round-trip custom metadata, there is currently no "extras" bag to
  put it in.

## Schema Version Stamping (dual-write, not monotonic-only)

`FaditorProject.SCHEMA_VERSION = 12` is the current build's ceiling, but the
serializer does **not** always stamp 11. It stamps the **minimum version the
project's actual content needs**, so older app builds can still open
projects that don't use newer features (`ProjectStorage.java` —
`ProjectSerializer.serialize`, ~line 1450):

```
usesAvatarRigs   -> stamp 10
else usesSprites -> stamp 9
else usesLayers  -> stamp 8
else             -> stamp 7

then, as a FLOOR over the result:
  max(stamp, TrackKind.minSchemaVersion()) for every LayerTrackDef
  -> a LAYER-kind (neutral) lane forces 11
```

The floor exists because a lane **kind** an older build cannot represent is not
covered by the ladder above. `TrackKind.fromName()` maps an unknown kind to
`VIDEO`, which stops the old build crashing but does not stop it re-serializing
the coerced kind on its next autosave — and kind decides emission phase, which
decides band position, which is paint order. A v8/v9/v10 stamp sails straight
past that build's downgrade guard (which only fires on on-disk > running), so
the stamp itself is the only thing that can refuse the file. See
`TrackKind.minSchemaVersion()`; reproduced offline in
`tasks/schema_layer_stamp.py`. **Any new `TrackKind` must be given a
`minSchemaVersion()`** — "the fromName fallback makes it storage-free" is the
reasoning that shipped this hole (audit 1.2).

`usesLayerFeatures()` (~line 910) checks whether the project has any
layer/track-only state (extra layer tracks, track flags, overlay/PiP clips,
etc.) beyond the always-present flat lists. All newer JSON blocks (layers,
sprites, avatar rigs, waveform overlays) are written additively regardless
of the stamped number — an older build simply never looks for keys it
doesn't know, and a project that later gains e.g. a sprite gets re-stamped
to 9 on its next save.

On load, `schemaVersion` is read with a default of 0 for legacy
(pre-versioning) projects, and every loaded project effectively upgrades to
whatever the running build's `SCHEMA_VERSION` supports the next time it's
saved (the stamp is recomputed from content, not carried forward blindly).

## Top-Level Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `schemaVersion` | int | yes | Minimum schema version this project's content needs (7–11, or lower for legacy). See stamping rule above. |
| `id` | string | yes | UUID. Unique project identifier. |
| `name` | string | yes | User-visible project name. |
| `createdAt` | long | yes | Unix epoch milliseconds. |
| `lastModified` | long | yes | Unix epoch milliseconds. |
| `timeline` | object | yes | Timeline container (clips, audio, overlays, layers, transitions). |
| `canvasPreset` | string | no | Aspect ratio preset key, or `custom_<w>_<h>` for a user-entered resolution. |
| `exportSettings` | object | no | Export configuration. |
| `pinnedAssetDir` | string | no | SAF tree URI of the user's pinned asset browser folder. **(v3)** |
| `assetDirHistory` | array | no | Recently-used asset folder URIs. **(v3)** |
| `spriteSheets` | array | no | Project-level sprite sheet definitions. See below. **(v9)** |
| `avatarRigs` | array | no | Project-level avatar rig definitions. See below. **(v10)** |

## Timeline Object

| Field | Type | Description |
|---|---|---|
| `clips` | array | Video/image clips in playback order (the master track). |
| `audioClips` | array | Audio track clips (separate from video). |
| `textOverlays` | array | Text and image overlays. |
| `transitions` | array | Transitions between/at clips. |
| `waveformOverlays` | array | Placed audio waveform/spectrum visualizer instances. **(v7)** |
| `spriteOverlays` | array | Placed sprite-sheet animation instances. **(v9)** |
| `overlayClips` | array | Floating video overlay clips (PiP) — same `Clip` shape as `clips`, distinguished by a non-null `layerId`. **(v8)** |
| `rippleMode` | string | `"ripple"` or `"gap"` — how downstream clips react to trims/deletes. **(v8)** |
| `trackFlags` | object | Map of `layerId` → per-track UI state (collapsed/hidden/locked/muted/zIndex/customName). Persistent side-table, keyed by track id. **(v8)** |
| `extraLayerTracks` | array | User-created empty layer track definitions (id/kind/name) that exist even with no items yet. **(v8, "M10")** |

Note: `timeline.layers` (a `masterTrack`/`layers[]`/`audioTracks[]`/`trackDefs[]`/`trackNames{}`
block) may also appear in the JSON — this is a **derived view mirror** written
for convenience/debugging, rebuilt fresh from the flat lists above on every
save. It is not a separate source of truth; do not hand-edit it expecting
the change to stick — edit the flat lists (`clips`, `textOverlays`,
`spriteOverlays`, `overlayClips`, etc.) instead.

## Clip Object

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `id` | string | yes | — | UUID. Referenced by EditScripts. |
| `sourceUri` | string | yes | — | URI to source media (`file://`, `content://`, or `project://<relative>` for in-bundle assets). **(v6)** |
| `inPointMs` | long | yes | 0 | Trim start (ms within source). |
| `outPointMs` | long | yes | sourceDuration | Trim end (ms within source). |
| `sourceDurationMs` | long | yes | — | Total duration of source media (ms). |
| `speedMultiplier` | float | no | 1.0 | Playback speed (0.1–10.0). |
| `pitchCompensation` | bool | no | true | Whether pitch is corrected to normal when speed ≠ 1.0 (unchecked = "chipmunk"/"slow-mo drawl"). Written only when `false`. |
| `audioMuted` | bool | no | false | Whether audio is muted. |
| `volumeLevel` | float | no | 1.0 | Volume multiplier (0.0–2.0). |
| `duckAmount` | float | no | 0.0 | Audio ducking: 0 = off, 0.3 = duck to 30%. **(v2)** |
| `zoomLevel` | float | no | 1.0 | Punch-in zoom: 1.0 = none, 2.0 = 2x. **(v2)** |
| `zoomCenterX` | float | no | 0.5 | Zoom center X (0.0–1.0). **(v2)** |
| `zoomCenterY` | float | no | 0.5 | Zoom center Y (0.0–1.0). **(v2)** |
| `rotationDegrees` | int | no | 0 | Rotation: 0, 90, 180, 270. |
| `flipHorizontal` | bool | no | false | Mirror horizontally. |
| `flipVertical` | bool | no | false | Mirror vertically. |
| `cropPreset` | string | no | `"none"` | Crop preset: `none`, `1:1`, `4:5`, `4:3`, `3:4`, `16:9`, `9:16`, `21:9`, `custom`. |
| `cropLeft` | float | no | 0.0 | Custom crop left (0.0–1.0). |
| `cropTop` | float | no | 0.0 | Custom crop top (0.0–1.0). |
| `cropRight` | float | no | 1.0 | Custom crop right (0.0–1.0). |
| `cropBottom` | float | no | 1.0 | Custom crop bottom (0.0–1.0). |
| `imageClip` | bool | no | false | True if this clip's source is a still image, not video. |
| `removedSpans` | array | no | `[]` | Non-destructive cut spans: `[[startMs, endMs], ...]` in source time. |
| `transcripts` | array | no | `[]` | Transcript versions (Vosk/Whisper). See below. |
| `activeTranscript` | int | no | -1 | Index into `transcripts`. |
| `displayName` | string | no | null | Friendly name for relink UI. |
| `layerId` | string | no | null | If non-null, this clip is a **floating overlay clip** (PiP) living in `timeline.overlayClips` on the named track, not the master `clips` list. Never null for a genuine master-track clip. **(v8)** |
| `overlayStartMs` | long | no (overlay clips only) | — | Absolute timeline position (ms) for an overlay clip. **(v8)** |
| `overlayTransform` | object | no | null | Keyframe-animatable position/scale/rotation for the overlay (same `KeyframeSet` shape as text overlay keyframes). **(v8)** |
| `overlayBlendMode` | string | no | `"NORMAL"` | `NORMAL`, `MULTIPLY`, `SCREEN`, `OVERLAY`, `ADD` — GL blend mode composited against the accumulated frame. **(v8)** |
| `compositing` | object | no | null | Masks / chroma-key / track-matte spec. See below. **(v8)** |
| `captionsEnabled` | bool | no | false | Whether animated captions are on. |
| `captionStyleId` | string | no | `"pop"` | Caption style: `pop`, `zoom`, `bounce`, `boxed`, `hot`, `meme`, `bright`. |
| `captionCenterX` | float | no | 0.5 | Caption center X (0.0–1.0). |
| `captionCenterY` | float | no | 0.82 | Caption center Y (0.0–1.0). |
| `captionSizeFraction` | float | no | 0.060 | Caption height as fraction of video height. |
| `captionAnimPreset` | string | no | `"NONE"` | Text-animation preset NAME (`CaptionAnimator.Preset`): `NONE`, `TYPEWRITER`, `FADE`, `RISE`, `GHOST`, `BEAM`. Stored as a name, not an ordinal, so reordering the enum cannot re-point existing projects; an unknown name degrades to `NONE`. **No schema bump** — the field is written only when non-default, and its default is the pre-feature behaviour. |
| `captionAnimGranularity` | string | no | `"WORD"` | What animates as one unit (`CaptionAnimator.Granularity`): `LETTER`, `WORD`, `SENTENCE`, `BLOCK`. Orthogonal to the preset. Unknown → `WORD`. |
| `captionAnimInPct` | float | no | 0 | Entrance zone as a **FRACTION OF EACH CAPTION LINE's own duration**, 0…0.5. Not a duration — the value is applied to every line as a share of that line, which is what lets one setting cover a thirty-minute video. 0 is the natural "off", which is why there is no separate enable flag. Clamped to `[0, 0.5]` on read and write; NaN and negatives collapse to 0. Set by the **Timing** sliders in the caption style drawer. |
| `captionAnimOutPct` | float | no | 0 | Exit zone, same units. At 0.5/0.5 the entrance ends exactly where the exit begins, at every line length — the 0.5 cap is the model, not a safety rail. Because each is capped at 0.5 independently their sum can never exceed 1, so unlike the old ms form there is no cross-constraint. |
| ~~`captionAnimInMs`~~ / ~~`captionAnimOutMs`~~ | long | — | — | **REMOVED 2026-07-29 (`d71b614`). Read and IGNORED**, never written. They held a source-ms duration; there is no honest conversion to a fraction, because dividing by "the length of a line" would have to pick one phrase's length and be wrong for every other. They only ever existed in a sandbox build, never in a shipped project, so the worst case is that a sandbox clip's animation returns to its default "off". |
| `loopMode` | string | no | null | Loop-extension mode when a still/short clip should visually fill more timeline than its source. |
| `loopBeforeMs` / `loopAfterMs` | long | no | 0 | Loop padding before/after the trimmed content. |
| `opacityKeyframes` | object | no | — | Keyframe track for opacity (same shape as overlay keyframes). |
| `captionStyleKeyframes` | object | no | — | Keyframe track for caption style property animation. |
| `volumeKeyframes` | object | no | — | Keyframe track for volume automation. |
| `effectStack` | object | no | — | Per-clip color/effect stack (exposure, contrast, LUT, etc.). **(v4)** |
| `generatedSource` | object | no | null | Present only for AI-authored fullscreen animated slides. See below. **(v5)** |

## Compositing Spec Object (inside a Clip's `compositing`)

Additive mask / chroma-key / track-matte spec, all optional and independent
of each other. **(v8)**

| Field | Type | Description |
|---|---|---|
| `masks` | array | Array of mask shapes (see below). Omitted if empty. |
| `invertMasks` | bool | Only present when `masks` is non-empty. Inverts the combined mask (window mode vs. cutout mode). |
| `keyEnabled` | bool | Gates the `chromaKey` object — true = chroma keying active. |
| `chromaKey` | object | `{ "color": "#RRGGBB", "tolerance": float, "fuzziness": float, "offset": float }` — RGB-distance keying with smoothstep tolerance/fuzziness. |
| `mattePeerId` | string | Gates the `matte` object — id of the peer clip supplying the luma track matte. |
| `matte` | object | `{ "peerId": "<clip id>", "mode": "luma" }` — the serving peer is hidden at export, its luma×alpha gates this clip's visibility. |
| `feather` | float | Optional, omitted while 0. Softens the combined mask edge. A property of the whole STACK, not of one shape. |
| `maskKeys` | object | Optional `KeyframeSet` animating the mask. Track names below. |

### Mask Object (inside `masks` array)

| Field | Type | Description |
|---|---|---|
| `cx` / `cy` | float | Mask center (0.0–1.0, canvas-normalized). |
| `w` / `h` | float | Mask width/height (0.0–1.0). |
| `corner` | float | Optional corner radius fraction (rounded-rect masks). |
| `rot` | float | Optional rotation in degrees. |
| `sub` | bool | Optional — if true, this mask subtracts from (notches out of) the combined shape instead of adding to it. |
| `mode` | int | Optional, **v13**. `2` = INTERSECT. Written *only* for intersect; add and subtract are still carried by `sub`, so pre-v13 projects serialize byte-identically. |
| `slot` | int | Optional, **v13**. The shape's stable identity, written only once it diverges from the array index (i.e. after a shape has been deleted). Keyframe tracks are named off this, never off the index. |
| `link` | bool | Optional, omitted while false. The mask travels with the object instead of staying pinned to the frame. |
| `linkBaseX` / `linkBaseY` / `linkBaseScale` / `linkBaseRotDeg` | float | Present only with `link`. The object pose captured when linking was switched on — "relative to the object" needs an origin, or the mask jumps the first time the object leaves its default pose. |

#### Mask keyframe track names (inside `maskKeys`)

Six per-shape tracks plus one stack-level track.

| Shape | Tracks |
|---|---|
| slot 0 | `maskCx`, `maskCy`, `maskW`, `maskH`, `maskCorner`, `maskRotation` |
| slot *n* > 0 | `mask<n>.cx`, `mask<n>.cy`, `mask<n>.w`, `mask<n>.h`, `mask<n>.corner`, `mask<n>.rotation` |
| the stack | `maskFeather` |

**Slot 0 keeps the flat names forever.** That is what every project on disk
already carries, and it is why multi-shape masks needed no migration: an older
build reading a multi-shape spec still animates shape 0 correctly, and
`KeyframeCodec` round-trips the `mask<n>.*` names it does not understand.
Times are ABSOLUTE timeline ms — the same base a PiP's `overlayTransform` uses,
so one clip never carries two time conventions.

## Generated Source Object (inside a Clip's `generatedSource`)

Describes an AI-authored animated slide. The authored HTML (`htmlUri`) is the
**source of truth**; the rendered artifact (`renderCacheUri`) is a regenerable,
content-addressed cache — same rule as remuxed media paths, never the truth.

| Field | Type | Description |
|---|---|---|
| `kind` | string | `"html_slide"` (only value for now). |
| `mode` | string | `"fullscreen"` (Clip) or `"overlay"` (TextOverlay, Phase 4). |
| `htmlUri` | string | `file://` URI to the authored HTML under `<project dir>/slides/<id>.html`. Source of truth. |
| `contentHash` | string | sha256 of HTML bytes + width + height + durationMs. Cache key. |
| `renderCacheUri` | string\|null | `file://` URI to the rendered MP4 (fullscreen). Regenerable / may be stale. |
| `renderSequenceDir` | string\|null | `file://` URI to the rendered PNG sequence dir (overlay). Regenerable. |
| `authoredDurationMs` | long | Duration the slide's GSAP timeline was authored for. |
| `width` / `height` | int | Pixel dimensions the HTML was authored/hashed against. |
| `styleHint` | string\|null | Style direction given to the AI; kept for "regenerate". |
| `sourceModel` | string\|null | OpenRouter model id that authored the slide (null = built-in template; `external-paste`/`external-file` = imported via the API-less copy-a-prompt path). |
| `freezeStartMs` | long | How long the slide holds its FIRST frame before its animation runs, in clip-window (timeline) ms. Set by the inner cyan carets on a selected slide clip. Note the unit differs from the caption `captionAnim*Pct` zones, which are a fraction of each caption line and carry no unit at all. |
| `freezeEndMs` | long | How long the slide holds its LAST frame after its animation ends, same units. Clamped so `freezeStartMs + freezeEndMs` never exceeds the trimmed duration. |

Slides can also be authored WITHOUT an API key: the editor's Add-asset → "AI
slide" flow copies a contract-teaching prompt (embedding a
`faditor-slide-contract v<N>` marker, currently v1 — see
`SlideContract.CONTRACT_VERSION`) for any external chatbot, and the paste/file
import validates the returned HTML against the same contract and emits the
same `ADD_GENERATED_SLIDE` operation. Rendering happens in the editor process
(`SlideRenderer`) — opportunistically in the background and always as an
export pre-pass — because the `:export` process cannot host the WebView
capture.

## Transcript Version Object (inside `transcripts` array)

| Field | Type | Description |
|---|---|---|
| `id` | string | Version ID. |
| `label` | string | Human label (e.g. "Vosk", "Whisper"). |
| `engine` | string | Engine name: `vosk`, `whisper`. |
| `words` | array | Word objects. |

## Word Object (inside `words` array)

| Field | Type | Description |
|---|---|---|
| `t` | string | The word text. |
| `s` | long | Start time in source ms. |
| `e` | long | End time in source ms. |
| `x` | bool | (optional) If true, word is struck (marked for cut). |

## Audio Clip Object (inside `audioClips` array)

| Field | Type | Description |
|---|---|---|
| `id` | string | UUID. |
| `sourceUri` | string | URI to audio file (`project://` if bundled). |
| `sourceDurationMs` | long | Total duration (ms). |
| `inPointMs` | long | Trim start (ms). |
| `outPointMs` | long | Trim end (ms). |
| `offsetMs` | long | Position on project timeline (ms). |
| `volumeLevel` | float | Volume (0.0–2.0). |
| `muted` | bool | Whether muted. |
| `label` | string | Display name. |
| `waveform` | array | (optional) Downsampled waveform peaks (0–255), extracted once and disk-cached (see `WaveformExtractor`). |

## Text Overlay Object (inside `textOverlays` array)

| Field | Type | Description |
|---|---|---|
| `id` | string | UUID. |
| `text` | string | Display text (or image filename). |
| `colorInt` | int | ARGB color. |
| `centerX` | float | Center X (0.0–1.0). |
| `centerY` | float | Center Y (0.0–1.0). |
| `sizeFraction` | float | Size as fraction of video height. |
| `rotationDeg` | float | Rotation in degrees. |
| `fontFamily` | string | (optional) Font key. Default: `"default"`. |
| `imageUri` | string | (optional) Image URI for image overlays (`project://` if bundled). |
| `startMs` | long | (optional) Timeline start. Default: 0. |
| `endMs` | long | (optional) Timeline end. Default: `Long.MAX_VALUE`. |
| `layerId` | string | (optional) Layer/track membership id. Omitted for the default track. **(v8, "M10")** |
| `keyframes` | object | (optional) Animation tracks. See below. |

## Keyframe Object (inside `keyframes`)

Each key is a property name (e.g. `"centerX"`, `"scale"`, `"rotation"`).
Value is an array of keyframe entries:

| Field | Type | Description |
|---|---|---|
| `t` | long | Time in timeline ms. |
| `v` | float | Value at this time. |
| `e` | string | Easing: `LINEAR`, `EASE_IN`, `EASE_OUT`, `EASE_IN_OUT`. |

## Waveform Overlay Object (inside `timeline.waveformOverlays` array)

A placed audio-reactive waveform/spectrum visualizer. **(v7)**

| Field | Type | Description |
|---|---|---|
| `id` | string | UUID. |
| `styleId` | string | Visualizer style/preset key. |
| `audioSourceRef` | string | (optional) Which audio source drives this visualizer. |
| `startMs` / `endMs` | long | (optional) Timeline range. Defaults: 0 / `Long.MAX_VALUE`. |
| `centerX` / `centerY` | float | Center position (0.0–1.0). |
| `widthFraction` / `heightFraction` | float | Size as a fraction of canvas dimensions. |
| `rotationDeg` | float | (optional) Rotation. |
| `justify` | string | (optional) Alignment. |
| `dataMode` | string | (optional) Amplitude vs. frequency-band data source. |
| `horizontalMirror` | bool | (optional, JSON key `hMirror`) Mirror left/right. |
| `centerMode` | string | (optional) Layout mode. |
| `renderMode` | string | (optional) Bars/line/radial rendering style. |
| `radialRingSize` | float | (optional) Radial-mode ring thickness. |
| `frequencyRangeLowHz` / `frequencyRangeHighHz` | float | (optional, JSON keys `freqLowHz`/`freqHighHz`) Frequency band filter. |
| `bandCountOverride` | int | (optional, JSON key `bandCount`) Override the default bar/band count. |
| `colorOverride` | int | (optional) ARGB override. |
| `sensitivityOverride` | float | (optional, JSON key `sensitivity`) Amplitude sensitivity. |
| `gradientStartOverride` / `gradientEndOverride` | int | (optional, JSON keys `gradStart`/`gradEnd`) Written together only. |

## Sprite Sheet Object (top-level `spriteSheets` array)

Project-level sprite sheet definitions (the source art + grid layout).
**(v9)** Has its own independent nested `SPRITE_SCHEMA_VERSION` (currently 1)
— do not confuse with the project `schemaVersion`.

| Field | Type | Description |
|---|---|---|
| `id` | string | UUID. |
| `name` | string | Display name. |
| `sheetUri` | string | Image URI (`project://` if bundled). |
| `cols` / `rows` | int | Grid dimensions. |
| `marginX` / `marginY` | float | (optional) Outer margin. |
| `spacingX` / `spacingY` | float | (optional) Cell spacing. |
| `order` | string | (optional) Cell enumeration order. Default: `"row-major"`. |
| `fps` | float | Default playback frame rate. |
| `bgKeyColor` | int | (optional) Background chroma-key color for the sheet. |
| `keyTolerance` | float | (optional) Chroma-key tolerance. |
| `pivotX` / `pivotY` | float | (optional) Cell pivot point. |
| `cells` | array | Per-cell metadata: `{ "index": int, "name": string, "tags": [string] (opt), "enabled": bool (opt) }`. |
| `presets` | array | Named frame-sequence presets: `{ "id", "name", "type", "fps" (opt), "frames": [int] }`. |

## Sprite Overlay Object (inside `timeline.spriteOverlays` array)

A placed, animatable instance of a sprite sheet. **(v9)**

| Field | Type | Description |
|---|---|---|
| `id` | string | UUID. |
| `sheetId` | string | References a `spriteSheets[].id`. |
| `centerX` / `centerY` | float | Position (0.0–1.0). |
| `sizeFraction` | float | Size as a fraction of video height. |
| `rotationDeg` | float | (optional) Rotation. |
| `opacity` | float | (optional) Opacity. |
| `flipH` / `flipV` | bool | (optional) Mirror. |
| `startMs` / `endMs` | long | (optional) Timeline range. |
| `layerId` | string | (optional) Track membership — one lane per sprite by default (deterministic `sprite-<id>`) so multiple sprites don't overlap; see migration note below. |
| `endBehavior` | string | (optional) What happens after the last frame key. Default: `"hold"`. |
| `frameTrack` | array | Cell-swap keyframes: `[{ "t": long, "c": int }]` (cell index) or `[{ "t": long, "p": string }]` (preset id reference). |
| `keyframes` | object | (optional) Same `{property: [{t,v,e}]}` shape as text overlay keyframes (position/scale/rotation/opacity). |

**Migration note:** `Timeline.migrateSpriteLayers()` runs on load and splits
any legacy lane sharing 2+ sprites (pre-dating per-sprite lanes) into one
lane per sprite, using the deterministic id `sprite-<itemId>`. Idempotent —
safe to run on an already-migrated project.

## Avatar Rig Object (top-level `avatarRigs` array)

Project-level rigged-puppet definitions for the sprite Avatar Studio.
**(v10)** Has its own independent nested `RIG_SCHEMA_VERSION` (currently 1)
— do not confuse with the project `schemaVersion`.

| Field | Type | Description |
|---|---|---|
| `id` | string | UUID. |
| `name` | string | Display name. |
| `parts` | array | Rig parts (limbs/pieces). See below. |
| `domains` | array | Pose-space domains (e.g. a yaw/pitch grid of pose cells). See below. |
| `visemeMap` | object | `{ "<visemeClass>": <cellIndex> }` — lip-sync viseme → pose cell mapping. |

### Part Object (inside `parts`)

| Field | Type | Description |
|---|---|---|
| `id` | string | Part id. |
| `sheetId` | string | References a `spriteSheets[].id` for this part's art. |
| `parentId` | string | (optional) Parent part id, for hierarchical rigs. |
| `anchorX` / `anchorY` | float | (optional) Attachment anchor point. |
| `followWeight` | float | (optional) How strongly this part follows its parent's motion. Default: 1. |
| `z` | float | (optional) Z-order / layering hint. |
| `dangle` | bool | (optional) Enables verlet-chain dangle physics (hair/tails) on this part. |
| `warpSegments` | int | (optional) Pin-warp mesh density (band count). |
| `restPins` | array | Rest-pose pin chain: `[[x, y], ...]` pairs, art-space normalized. |

### Pose Domain Object (inside `domains`)

| Field | Type | Description |
|---|---|---|
| `id` | string | Domain id. |
| `driverX` | string | Driver signal for the horizontal axis (e.g. head yaw). |
| `driverY` | string | (optional) Driver signal for the vertical axis. |
| `cols` / `rows` | int | Grid dimensions. |
| `cells` | array | Pose cells: `{ "col", "row", "poses": [PartPose, ...] }`. |

### Part Pose Object (inside a cell's `poses`)

| Field | Type | Description |
|---|---|---|
| `partId` | string | References a `parts[].id`. |
| `x` / `y` | float | (optional) Position offset. |
| `scale` | float | (optional) Scale. |
| `rotationDeg` | float | (optional, JSON key `rot`) Rotation. |
| `cellIndex` | int | (optional, JSON key `cell`) Sprite cell for this pose. |
| `z` | float | (optional) Z-order override. |
| `flipH` / `flipV` | bool | (optional) Mirror. |
| `pins` | array | (optional) Posed pin chain `[[x, y], ...]`, overriding the part's rest pins for this cell. |

## Transition Object (inside `transitions` array)

| Field | Type | Description |
|---|---|---|
| `type` | string | Transition type enum name. |
| `durationMs` | long | Duration in ms. Default: 500. |
| `clipIndex` | int | Clip index the transition applies to. |
| `fuzziness` | float | Edge softness (0.0–1.0). Default: 0. |

Valid types: `FADE_IN_FROM_BLACK`, `FADE_OUT_TO_BLACK`, `FADE_IN_FROM_WHITE`,
`FADE_OUT_TO_WHITE`, `CROSS_DISSOLVE`, `WIPE_LEFT`, `WIPE_RIGHT`, `WIPE_UP`,
`WIPE_DOWN`, `PUSH_LEFT`, `PUSH_RIGHT`, `PUSH_UP`, `PUSH_DOWN`.

## Export Settings Object

| Field | Type | Description |
|---|---|---|
| `resolution` | string | `ORIGINAL`, `FHD_1080P`, `HD_720P`, `SD_480P`. **Not currently read by the export pipeline** — `ExportManager` has no code path consuming this enum yet (canvas preset governs actual output dimensions instead). |
| `quality` | string | `HIGH`, `MEDIUM`, `LOW`. **Same caveat as `resolution`** — persisted but not yet wired into the encoder. |
| `format` | string | `MP4`, `WEBM`. |
| `cleanAudio` | bool | Enable Clean Audio v2 post-pass. |

## Schema Version History

### v13 (multi-shape masks)
- `masks[]` becomes genuinely multi-shape from the UI. New per-shape `mode`
  (add / subtract / **intersect**) and stable `slot`.
- **Stamped conditionally, on two triggers**, via `CompositingSpec.needsSchema13()`:
  a shape using INTERSECT, or a `slot` that has diverged from its array index
  after a delete. Anything else keeps its older stamp and byte-identical JSON —
  including key order.
- Non-additive, which is why the stamp exists: an older build reads an intersect
  shape as plain additive and reads no slot at all, then autosaves that back,
  turning an intersection into a union and renumbering keyframe tracks onto the
  wrong shapes. Reproduced, then shown prevented, in
  `tasks/schema_mask_stamp.py`.
- No migration needed: shape 0 keeps the flat `maskCx`/`maskCy`/… track names it
  always had, and `KeyframeCodec` round-trips the new `mask<n>.*` names for free.

### v12 (transcript pool)
- Added top-level `transcriptPool`; a pooled file carries no per-clip
  `transcripts`. Stamped only when pooling actually pays (some transcript
  instance sits on more than one owner), so an un-duplicated project keeps the
  inline shape and its old stamp.
- *(Backfilled from `TranscriptPoolCodec` and `ProjectStorage` — this entry was
  missing when v13 was written.)*

### v11 (lane kinds)
- Lane `kind` on `LayerTrackDef`. A kind an older build cannot represent is its
  own floor on the stamp, via `TrackKind.minSchemaVersion()` — an unknown kind
  otherwise coerces to `VIDEO` and the next autosave writes that coercion back,
  permanently changing paint order.
- *(Backfilled from `TrackKind` and `ProjectStorage` — also missing.)*

### v10 (avatar rigs)
- Added top-level `avatarRigs[]` to `FaditorProject` — rigged sprite puppets
  (pin-warp mesh deformation, dangle physics, pose domains, viseme map).
  Stamped only when the project actually has a rig; sprite/layer/plain
  projects keep stamping 9/8/7.
- No `Clip`/`Timeline` field additions for v10 — purely the new rig array.

### v9 (sprite animation)
- Added top-level `spriteSheets[]` (sheet definitions: grid, cells, presets,
  chroma-key) and `timeline.spriteOverlays[]` (placed, keyframe-animatable
  instances with cell-swap `frameTrack`).
- `Timeline.migrateSpriteLayers()` splits any pre-migration shared sprite
  lane into one lane per sprite (deterministic `sprite-<id>`), idempotent.
- Stamped only when sprites/sheets are actually present.

### v8 (layers/tracks + PiP compositing)
- Added `timeline.overlayClips[]` — floating video overlay (PiP) clips,
  same `Clip` shape as the master `clips` list, distinguished by a non-null
  `layerId`. New `Clip` fields: `layerId`, `overlayStartMs`,
  `overlayTransform`, `overlayBlendMode`.
- Added `Clip.compositing` (masks / chroma-key / track-matte spec — one
  additive model, not three separate bolt-ons).
- Added `timeline.rippleMode`, `timeline.trackFlags` (persistent per-track
  UI state), `timeline.extraLayerTracks` (user-created empty tracks), and
  `TextOverlayItem.layerId` (track membership).
- A derived `timeline.layers` view-mirror block may also be present
  (rebuilt from the flat lists on every save — not authoritative).
- Stamped only when the project actually uses any layer feature
  (`usesLayerFeatures()`); a project with only master clips/audio/text
  keeps stamping 7.

### v7 (waveform visualizers)
- Added `timeline.waveformOverlays[]` — placed audio-reactive
  waveform/spectrum visualizer instances (bars/line/radial render modes,
  frequency-band filtering, gradient/color overrides).
- Baseline "dual-write" stamp floor — this is the lowest version any
  build running the current serializer will stamp.

### v6 (2026-06-19)
- **Relative asset paths.** Assets living inside the project directory are now stored
  in `project.json` as `project://<relative>` (e.g. `project://assets/<uuid>.png`) and
  resolved to absolute `file://` at load time. Makes a project a portable bundle
  (project.json + `assets/`) that survives reinstall and moves to another device.
  Applies to clip `sourceUri`, audio `sourceUri`, and overlay `imageUri`. v5 projects
  load unchanged (absolute URIs are only rewritten once an asset lives in the project).
- **Hybrid-on-insert copy.** Inserted images (and any asset ≤25 MB) are copied into
  `<projectDir>/assets/` and referenced internally; large videos stay referenced.
- Added EditScript ops `SPLIT_CLIP_AT_TIME`, `REORDER_CLIPS`, `INSERT_BROLL_CUTAWAY`
  (see EditScript section). No `Clip`/`Timeline` field changes for these.

### v5 (2026-06-19)
- Added `generatedSource` to Clip for AI-authored fullscreen animated HTML slides
  (chapter cards, stylized titles), rasterized to real video at export.
- Added `ADD_GENERATED_SLIDE` and `REGENERATE_SLIDE` EditScript operations.
- Overlay-mode (transparent composited) slides reserved for Phase 4.

### v4
- Added per-clip `effectStack` (color grading, LUT) and GL transition params.

### v3
- Added `pinnedAssetDir`, `assetDirHistory` (asset browser).

### v2 (2026-06-17)
- Added `duckAmount`, `zoomLevel`, `zoomCenterX`, `zoomCenterY` to clip.
- Added Clean Audio v2 (two-pass loudnorm + dynaudnorm).
- Added AI job foreground service.
- Added relink catalog with validation.

### v1
- Initial schema with clips, transcripts, overlays, transitions, audio clips,
  captions, canvas presets, and export settings.

### v0 (legacy)
- Pre-versioning projects. Deserialized with defaults for missing fields.

## EditScript

An EditScript is a separate JSON object that describes a batch of edit
operations to apply to a project. It is validated and applied atomically.

```json
{
  "version": 1,
  "description": "Remove silence and add title",
  "operations": [
    {"type": "REMOVE_SPAN", "clipId": "...", "startMs": 1000, "endMs": 3000},
    {"type": "ADD_TEXT_OVERLAY", "text": "Hello", "centerX": 0.5, "centerY": 0.1, "sizeFraction": 0.08, "startMs": 0, "endMs": 5000},
    {"type": "SET_CLIP_SPEED", "clipId": "...", "speed": 1.5},
    {"type": "SET_CLIP_MUTED", "clipId": "...", "muted": true},
    {"type": "ADD_OPACITY_KEYFRAME", "overlayId": "...", "timelineMs": 0, "opacity": 0.0}
  ]
}
```

### Supported Operation Types

- `REMOVE_SPAN` — Add a non-destructive cut to a clip
- `ADD_TEXT_OVERLAY` — Add a text overlay
- `REMOVE_TEXT_OVERLAY` — Remove an overlay by id
- `SET_OVERLAY_RANGE` — Set overlay time range
- `SET_OVERLAY_POSITION` — Set overlay position
- `SET_OVERLAY_TEXT` — Set overlay text
- `SET_CLIP_SPEED` — Set clip playback speed
- `SET_CLIP_VOLUME` — Set clip volume
- `SET_CLIP_MUTED` — Mute/unmute a clip
- `SET_CAPTIONS_ENABLED` — Enable/disable captions
- `SET_CAPTION_STYLE` — Set caption style
- `SET_CANVAS_PRESET` — Set canvas aspect ratio
- `SET_EXPORT_SETTING` — Set an export parameter
- `MOVE_KEYFRAME` — Move a keyframe to a new time
- `ADD_KEYFRAME` — Add a keyframe
- `ADD_OPACITY_KEYFRAME` — Add a fade in/out keyframe
- `CLEAR_KEYFRAMES` — Clear all keyframes for an overlay
- `ADD_GENERATED_SLIDE` — Insert an AI-authored animated slide. Params: `mode` (`"fullscreen"`), `title_or_text`, `styleHint`, `durationMsHint`, optional `insertAtClipIndex`, and (when emitted by `generate_slide`) `clipId`, `htmlUri`, `contentHash`, `sourceModel`. Creates a Clip whose source points at the deterministic render-cache MP4; the export pre-pass renders the HTML if the cache is missing.
- `REGENERATE_SLIDE` — Re-author an existing slide clip in place. Params: `clipId`, optional `newStyleHint`, `htmlUri`, `contentHash`, `durationMsHint`.
- `SPLIT_CLIP_AT_TIME` — Split one clip into two at a source-time point, partitioning `removedSpans` and transcript words by time. Params: `clipId`, `atSourceMs` (must be ≥100 ms inside the trim), optional `firstClipId` (defaults to the original id) and `secondClipId` (defaults to a new UUID) so a follow-up `REORDER_CLIPS` can reference the children. Rejected for image/slide clips.
- `REORDER_CLIPS` — Rebuild the clip array to exactly `newOrder` (array of clip ids), in that order. **Any current clip id omitted from `newOrder` is deleted.** Transitions are kept only if their two flanking clips remain adjacent in the new order (others dropped); surviving `clipIndex` values are re-derived. Params: `newOrder`.
- `INSERT_BROLL_CUTAWAY` — Documentary cutaway: b-roll replaces the visible frame for a span while the original narration keeps playing underneath. Splits the target clip at the span start/end, replaces the span's video with the b-roll clip (`audioMuted:true`, trimmed to fit), and adds the original span audio as an `audioClips` entry positioned at `offsetMs=atMs`. Params: `atMs` (timeline ms), `durationMs` (1500–8000), `assetUri`. v1 requires the span to lie within a single 1× clip with no removed spans, and not in the first/last 2 s.
- `ADD_VISUALIZER` — Place a waveform/spectrum visualizer overlay. Params: `presetId`/`styleId`, `audioSourceRef`/`clipId`, `startMs`, `durationMs`. **(rides v7's `waveformOverlays`)**

**Coverage gap (as of v10):** sprite overlays, avatar rigs, PiP/video overlay
clips, compositing (masks/chroma-key/matte), and layer-track creation have
no EditScript operations yet — those v8-v10 model features are not
currently AI-scriptable; they're authored only through the editor UI.
