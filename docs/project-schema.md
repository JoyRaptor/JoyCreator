# Faditor Project JSON Schema

**Schema Version:** 5  
**Last Updated:** 2026-06-19

## Overview

A Faditor project is a single JSON file stored in the app's project directory.
It describes a complete video editing timeline: clips, trims, effects,
transcripts, overlays, transitions, audio tracks, and export settings.

External tools (AI agents, CLI scripts, desktop editors) can read and modify
project JSON as long as they follow this schema. Unknown fields should be
preserved but ignored.

## Top-Level Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `schemaVersion` | int | yes | Schema version (currently 2). Old projects default to 0. |
| `id` | string | yes | UUID. Unique project identifier. |
| `name` | string | yes | User-visible project name. |
| `createdAt` | long | yes | Unix epoch milliseconds. |
| `lastModified` | long | yes | Unix epoch milliseconds. |
| `timeline` | object | yes | Timeline container (clips, audio, overlays, transitions). |
| `canvasPreset` | string | no | Aspect ratio: `original`, `16:9`, `9:16`, `1:1`, `4:5`. |
| `exportSettings` | object | no | Export configuration. |

## Timeline Object

| Field | Type | Description |
|---|---|---|
| `clips` | array | Video/image clips in playback order. |
| `audioClips` | array | Audio track clips (separate from video). |
| `textOverlays` | array | Text and image overlays. |
| `transitions` | array | Transitions between/at clips. |

## Clip Object

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `id` | string | yes | — | UUID. Referenced by EditScripts. |
| `sourceUri` | string | yes | — | URI to source media (`file://`, `content://`). |
| `inPointMs` | long | yes | 0 | Trim start (ms within source). |
| `outPointMs` | long | yes | sourceDuration | Trim end (ms within source). |
| `sourceDurationMs` | long | yes | — | Total duration of source media (ms). |
| `speedMultiplier` | float | no | 1.0 | Playback speed (0.1–10.0). |
| `audioMuted` | bool | no | false | Whether audio is muted. |
| `volumeLevel` | float | no | 1.0 | Volume multiplier (0.0–2.0). |
| `rotationDegrees` | int | no | 0 | Rotation: 0, 90, 180, 270. |
| `flipHorizontal` | bool | no | false | Mirror horizontally. |
| `flipVertical` | bool | no | false | Mirror vertically. |
| `cropPreset` | string | no | `"none"` | Crop preset: `none`, `16:9`, `9:16`, `4:3`, `3:4`, `1:1`, `21:9`, `custom`. |
| `cropLeft` | float | no | 0.0 | Custom crop left (0.0–1.0). |
| `cropTop` | float | no | 0.0 | Custom crop top (0.0–1.0). |
| `cropRight` | float | no | 1.0 | Custom crop right (0.0–1.0). |
| `cropBottom` | float | no | 1.0 | Custom crop bottom (0.0–1.0). |
| `removedSpans` | array | no | `[]` | Non-destructive cut spans: `[[startMs, endMs], ...]` in source time. |
| `transcripts` | array | no | `[]` | Transcript versions (Vosk/Whisper). See below. |
| `activeTranscript` | int | no | -1 | Index into `transcripts`. |
| `displayName` | string | no | null | Friendly name for relink UI. |
| `captionsEnabled` | bool | no | false | Whether animated captions are on. |
| `captionStyleId` | string | no | `"pop"` | Caption style: `pop`, `zoom`, `bounce`, `boxed`, `hot`. |
| `captionCenterX` | float | no | 0.5 | Caption center X (0.0–1.0). |
| `captionCenterY` | float | no | 0.82 | Caption center Y (0.0–1.0). |
| `captionSizeFraction` | float | no | 0.060 | Caption height as fraction of video height. |
| `duckAmount` | float | no | 0.0 | Audio ducking: 0 = off, 0.3 = duck to 30%. **(v2)** |
| `zoomLevel` | float | no | 1.0 | Punch-in zoom: 1.0 = none, 2.0 = 2x. **(v2)** |
| `zoomCenterX` | float | no | 0.5 | Zoom center X (0.0–1.0). **(v2)** |
| `zoomCenterY` | float | no | 0.5 | Zoom center Y (0.0–1.0). **(v2)** |
| `effectStack` | object | no | — | Per-clip color/effect stack (exposure, contrast, LUT, etc.). **(v4)** |
| `generatedSource` | object | no | null | Present only for AI-authored fullscreen animated slides. See below. **(v5)** |

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
| `sourceModel` | string\|null | OpenRouter model id that authored the slide (null = built-in template). |

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
| `sourceUri` | string | URI to audio file. |
| `sourceDurationMs` | long | Total duration (ms). |
| `inPointMs` | long | Trim start (ms). |
| `outPointMs` | long | Trim end (ms). |
| `offsetMs` | long | Position on project timeline (ms). |
| `volumeLevel` | float | Volume (0.0–2.0). |
| `muted` | bool | Whether muted. |
| `label` | string | Display name. |
| `waveform` | array | (optional) Downsampled waveform peaks (0–255). |

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
| `imageUri` | string | (optional) Image URI for image overlays. |
| `startMs` | long | (optional) Timeline start. Default: 0. |
| `endMs` | long | (optional) Timeline end. Default: `Long.MAX_VALUE`. |
| `keyframes` | object | (optional) Animation tracks. See below. |

## Keyframe Object (inside `keyframes`)

Each key is a property name (e.g. `"centerX"`, `"scale"`, `"rotation"`).
Value is an array of keyframe entries:

| Field | Type | Description |
|---|---|---|
| `t` | long | Time in timeline ms. |
| `v` | float | Value at this time. |
| `e` | string | Easing: `LINEAR`, `EASE_IN`, `EASE_OUT`, `EASE_IN_OUT`. |

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
| `resolution` | string | `ORIGINAL`, `FHD_1080P`, `HD_720P`, `SD_480P`. |
| `quality` | string | `HIGH`, `MEDIUM`, `LOW`. |
| `format` | string | `MP4`, `WEBM`. |
| `cleanAudio` | bool | Enable Clean Audio v2 post-pass. |

## Schema Version History

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
