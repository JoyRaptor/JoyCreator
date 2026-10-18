# VisLab spec (live)

Lane: `VISLAB_20261016`. Owner: JoyRaptor. Written 2026-10-17; this file is the truth for VisLab,
the brief (`tasks/VISLAB_BRIEF.md`) is history.

**What is left, as a checklist with the code places scouted: `tasks/VISLAB_TODO.md`.**

## 1. What VisLab is

A music-visualiser studio inside Joy Creator, built for three places (owner, 10-16):

1. **The VisLab room** (lobby: Viz Lab): see looks move to a demo song, the mic or your own song;
   edit, combine and make looks from scratch; brand colours; Joybot in the top right.
2. **Studio**: a look as an overlay on a video (music video, podcast), dragged in like the other
   overlays, scrubbable, with a mini editor. Words from the transcript can drive it (karaoke,
   captions in the look). **Not built yet** (section 7).
3. **The screen recorder**: a floating visualiser over any app, heard from what is being
   recorded (phone audio, mic, or both) or the mic. Built (section 8).

Asks that shape everything: jaw-dropping, rave / game FX / glitch / sophisticated-brandable;
full screen, dynamic background, or contained ("bleed" vs "contained, no seam"); scrub in Studio
and real time live; strong primitives that combine.

## 2. How it is built

| Piece | Where | Notes |
|---|---|---|
| Shaders (GLSL ES 3.00) | `app/src/main/assets/vislab/shaders/` | One file per primitive + common/echo/bloom/composite. **Shared with the web pilot**, byte for byte. |
| Primitive registry | `assets/vislab/types.json` | Every type, its knobs (`uP[12]` slots like `"3w"`), ranges, choices. |
| Looks | `assets/vislab/scenes/*.vis.json` + `index.json` | 29 built-ins. `tools/jvm-harness/VisLooksTest` requires each to need **zero** validator fixes. |
| Engine | `app/.../vislab/gl/VisRenderer` | ES 3.0; HDR (RGBA16F), living layers (particles, slime, reaction-diffusion) on a fixed 60 Hz tick grid, echo, bloom, finish pass. `render(features, time, dt, fbo, w, h)` draws into any framebuffer: the export path can use it as is. |
| Sound analysis | `vislab/audio/FeatureExtractor` (+ JS twin) | 128 log bins 30 Hz-16 kHz, 5 bands each normalised to its own range, kick/snare/hat onsets, tempo + beat/bar phase, scope, 64-row history. `VisFeatureParityTest` keeps Java = JS. |
| Live view | `vislab/view/VisTextureView` | Own GL thread; auto-quality shrinks the **window buffer** (section 5). |
| Room | `vislab/room/*` | `VisLabActivity`, `KnobPanel` drawer, `LookStrip`, slant selectors. |
| Session | `vislab/room/VisLabSession` | The one set of looks for the room, Joybot and later Studio. Grouped undo (one request = one undo). |
| Files | `vislab/room/LookFiles` | Section 4. |
| Overlay | `vislab/overlay/*` | Section 8. |
| Web pilot | `tools/vislab-web/` | Same shaders + JS analyser; fastest place to design looks. Dev URL `http://localhost:8770/tools/vislab-web/index.html#<look-id>`, `window.__vislab.reload()` reloads assets. |

Conventions: scene units (short side spans -1..1, `EXT` = half extents); linear light,
premultiplied alpha, one blend (ONE, ONE_MINUS_SRC_ALPHA); a knob is a number or
`{"v": base, "mod": {"kick": 0.4}}` (amount = share of the knob's range). Signals: bass low mid
high air level voice bright kick snare hat beat beatSine bar energy slow wave.

**Speeds (`"rate": true`, 49 knobs, 10-18).** A shader that does `TIME * SPIN` jumps by the whole
elapsed time whenever SPIN changes: with a music link, Spiral Banner spun whole turns on every
kick. Knobs a shader multiplies by TIME are marked `"rate"`; with a music link the host keeps
the distance travelled and puts distance / TIME in the slot, so `TIME * slot` = distance and the
beat speeds it up smoothly. Shaders unchanged. An edit carries the distance on
(`KnobSet.carryFrom`, pilot `engine.travel`). New shader using `TIME * knob`: mark the knob.

**Every layer's own controls (Lab, 10-18).** types.json `"layer"`: Opacity, Reactivity (scales
every music link of the layer, 0 calm .. 2 wild), Softness, and Place (Move across, Move up,
Size, Turn). Stored on the layer itself (`"turn": {"v": 10, "mod": {...}}` works). Place is NOT
a moved picture: the layer is made in that space (`uView`; `placeIn` in fullscreen.vert,
`placeOut` in the point/line/particle shaders), so an aurora keeps its curtains to the screen
edge and a particle system flies as one. Living layers' simulations stay in their own space;
grid effects (pattern growth, slime, spectrogram) end at their grid's edge. Softness alone
needs the layer drawn on its own (half size) and laid back with `soften.frag`.

## 3. Look file format (`joy.vis/1`)

Plain, hand-editable JSON: `id`, `name`, `family`, `description`, `palette` (5 hex), `background`
(hex or null = see-through), `layers` [{`type`, `name`, `opacity`, `add` (0 paint / 1 light),
`echo` (under the trails?), `res`, `seed`, `palette`, `image`, `p` {knobs}}], `echo` {`p`},
`post` {`p`}. Anything out of range is fixed on load by `LookValidator` with a plain-language
note (never fatal).

**What a build does not know, it keeps (10-18).** An unknown layer type, setting or music
signal stays in the file untouched (reported, not drawn). Why: the app writes back what it
loaded, and on 2026-10-18 a build from another lane without the Words layer (installed on the
Note 9 at 05:42) opened VisLab and deleted the words from all ten lyrics looks in
`Supplies/Looks`. Restored (the damaged copies are in the folder's Trash, "(words lost)").
**Builds from before this fix still strip**: any lane installing a branch without VisLab's
latest on the Note 9 should merge `joy-creator` first.

## 4. Where looks live (owner's supplies ruling, 10-16)

- Every look is `Supplies/Looks/<Name>.vis.json` in the Joy Creator folder (`FileKind.LOOK` has the
  `.vis.json` ending). Built-ins are copied out on first use.
- The person's copy wins. A copy nobody touched (fingerprint in prefs `vislab/factory_<id>`)
  follows app updates. **Revert to default** (drawer footer, with the owner's warning wording)
  puts the factory version back; it is an undo step.
- Changes are written ~0.8 s after they happen and at once when the room pauses. A look an
  undo removes, or Delete, goes to the folder's Trash. Duplicate / Rename (the file follows the
  name) / Delete (own looks only) in the footer.
- Unreachable folder: `files/vislab/looks/` and a log line.

## 5. Performance (Note 9, 1080x2220, measured 10-17)

- **The finish pass is full screen.** It cost 22 ms a frame on its own while the old scaler only
  shrank the passes before it, so every look sat at the lowest scale at ~30 fps. The scaler now
  shrinks the TextureView's buffer (`SurfaceTexture.setDefaultBufferSize`) and Android scales it
  up for free. Result: most looks 45-60 fps at 55-98% (was 16-45 fps at 40%).
- Scaler: 75th-percentile frame time per second (catches the every-third-frame stutter),
  hysteresis (a failed scale is not retried for 10 s, doubling).
- Debug builds log `gpu living/layers/bloom/finish` ms per stage (`GpuTimer`).
- Heavy looks: Crystal (Voronoi; second pass now 3x3), Gyroid Dive (raymarch), Ink and Meltdown
  (echo). Next lever if needed: per-layer `res` on the procedural layers.
- **Words (10-18).** The three-part split was only pilot-tested and cost the Note 9 dearly
  (Written in Stars 11-19 fps, Smoke Words 17-25, Cigar Smoke 12-27). Fixes: a reach test per
  word sized by its age (`inReach`), no swirl (curl2 = 4 noise samples) on letters that are not
  leaving, 2D noise for the sparkle drift, sines for the rising path's per-word wander. Now all
  lyrics looks 30-57 fps. Rule: never put per-word noise on every pixel; a constant per word is
  not hoisted by the GPU.
- **Heat (Note 9, 10-18, 24 min of looks back to back, charging at 100%).** Back of the phone
  36 -> 40 C, battery 31 -> 37 C, graphics chip 55-67 C, CPU peak 82 C. Android said SEVERE for
  ~2.5 min at the 18-minute mark and cut the graphics clock 710 -> 414 MHz; the auto-quality
  absorbed it (frame rate held, picture softer) and the phone cooled back to normal on lighter
  looks. **Heat guard** (`view/HeatGuard`): from SEVERE until LIGHT the visualiser caps itself at
  half resolution so the phone cools without the clock cut (which also slows a screen
  recording). The log line shows `heat N`. Seen working 10-18 04:10 after hours of testing:
  `heat 3` -> scale held at 50%, 60 fps.

## 6. Joybot (JOYBOT lane wires it)

`VisLabSession` public API: `currentLookJson`, `lookIds`, `lookNames`, `lookJson`, `typesJson`,
`isRoomOpen`, `brand`, `pickLook`, `apply`, `setBrand`, `undo`, `redo`, `undoLabel`,
`duplicate`, `rename`, `revertToDefault`, `deleteLook`, `isBuiltIn`, `isCustomised`, `lookFile`,
`flush`. Changes take `undoLabel` + `group` (same group = one undo step). Joybot never sets a
layer `image` the person did not pick (enforced on their side).

## 7. Studio integration (for the Studio specialist)

Nothing here is built in Studio yet. What VisLab offers, and what Studio already has:

**7.1 Sound.** Studio's tape (`faditor/waveform/BandWaveformExtractor`) decodes once (chunked,
disk-cached) and splits into 4 bands with biquads at 110 / 950 / 6300 Hz, RMS per 128-sample hop.
`BeatDetector` / `OnsetDetector` find beats for snapping. VisLab's `FeatureExtractor` needs more
than band RMS (128-bin spectrum, 5 normalised bands, kick/snare/hat onsets, beat and bar phase,
scope, a 64-row history for the terrain/waterfall looks). Proposal:
- Reuse Studio's decode (same MediaCodec streamer + remuxed-copy trick) to run VisLab's
  `SpectrumAnalyzer` + `FeatureExtractor` offline at **60 Hz** into a **feature track** (one
  `Features` per 1/60 s; a few KB per second), disk-cached beside the band cache with the same
  key scheme. The overlay then reads features by time: scrubbing is a lookup, export is exact.
- Feed Studio's beat grid (`BeatDetector.Result`) in as the beat/bar phase when present, so the
  look's pulse lands on the same beats the editor snaps to.
- The 4 tape bands and VisLab's 5 bands differ on purpose (tape: what you see; VisLab: what moves
  the art). No need to merge them.

**7.2 Scrubbing.** Looks with memory (particles, slime, reaction-diffusion, echo) are simulated on
an absolute 60 Hz tick grid. On a seek: `VisRenderer.reset()`, then pre-roll ~2 s of ticks from
the feature track (cheap: state textures only), then draw. Stateless looks need no pre-roll.
**Speeds with a music link (`rate`, §2) carry state too:** after a seek the distance restarts
as base speed x time, so a music-linked spin lands slightly differently from continuous play.
For preview = export, pre-roll from a fixed point (or the start) so the distance is replayed.

**7.3 The overlay item.** A `WaveformOverlayInstance` (or a sibling) gains a VisLab look id (the
look file's `id`, resolved through `VisLabSession`). Old waveform styles stay selectable (owner:
keep the old behaviour selectable).

**7.4 Preview = export.** Preview: a `VisTextureView` laid over the player, fed from the feature
track at the playhead. Export: `CompositeExportOverlay` gets a VisLab slot that renders with the
same `VisRenderer` into an offscreen ES 3 framebuffer at the export size (no auto-quality) and
reads it back, or shares the texture if the export compositor is GL. Same shaders, same features,
same tick grid: the frames match.

**7.5 Words (karaoke, captions in the look).** Owner, 10-16: the pulsing JOY puck (Halo Drop)
should be able to show the transcript's words, plus "sick karaoke". `Transcript` /
`TranscriptWord(text, startMs, endMs)` / `CaptionPhrases` already give timed words and phrases.
**Owner ruling, 10-17: words as art, not captions.** Studio already has stylised TikTok-style
captions, and those are linear, in one place. VisLab words are artistic, procedural, wowing:
words on rotating, spiralling banners; words flying at you through a star field; a ticker
running along a tunnel wall as you travel through it; words floating up and dissolving into
smoke; "ideas like that". Never a line of text along the bottom.

Plan: a `words` primitive whose modes are those scenes, each a 3D placement of the current and
coming words (from `TranscriptWord` timings), drawn from a glyph atlas so any word is cheap:
- **Spiral banner**: words wrapped on a ribbon that coils around the view and turns with the beat.
- **Star-field flight**: each word starts far away and rushes past the camera on its start time.
- **Tunnel ticker**: words run along the inside wall of the tunnel looks (Hyperdrive, Gyroid Dive).
- **Smoke**: the word holds, then breaks into particles that rise and curl (the particle and
  slime layers already simulate this kind of motion).
- **Puck**: the word replaces JOY in Halo Drop's centre, pulsing with the kick.
Word size, glow and colour take music links like every knob.

**Built 10-17/18** (`words` primitive, `shaders/words.frag`, both engines). Since 10-18 it is
**three parts that mix freely** (owner: everything is parts for building new visualisers):
- **Path** (where words live): Ribbon (coming at you / going away, rotate, lean, ripple, reach,
  closest, side), Spiral, Ring, Tunnel, Street, Flight, Centre, Scatter, Rising.
- **Behaviour** (how a word arrives and leaves): Karaoke, Pop, Neon flicker, Smoke, Burst (keeps
  opening as it falls), Sparkle. Works on any path, in the word's own space.
- **Letters**: Glow, Neon tube, Outline, Stars, Sparks, Constellation.
Each knob shows only for the parts it belongs to (`showFor` in types.json). Ten looks in the
Lyrics family are presets of the parts; looks saved with the old single `mode` are converted on
load (`LookValidator.wordsScene`). Words come from `WordFeed` (Java) / `vislab-words.js`
(pilot): timed words to a glyph atlas + per-word timing uniforms; the demo song's sample words
are `assets/vislab/words/demo-lyrics.json`. **For Studio:** `renderer.setWords(feed)` with
`feed.setWords(transcriptWords, 0)` and `renderer.setWordsTime(clipSeconds)` per frame.
Phone rules learned: build one small GPU program per part combination (`#define WPATH`,
`BEHAVIOUR`, `LETTER_STYLE`; one program with everything ran at 5 fps), and keep word drawing
out of long loops (find the word, then draw it once).

## 8. Screen recorder overlay (built 10-17)

- `VisOverlayService`: a floating window over everything. **Box** (drag, resize from the top-right
  handle, soft-edged frame so it never shows a seam; tap for controls) or **full screen**
  (see-through, touches pass to the app below; a small handle keeps the controls). Controls:
  previous / next look, what it hears, box / full screen, close. Remembers look, mode, size, place.
- Recorded too: the recorder captures the screen, so the overlay is in the video.
- Sound (`OverlaySound`, chosen once a second): during a recording, exactly what is recorded,
  from `RecordingTap` (one line in `ScreenRecordingPipeline` after the existing visualizer tap);
  otherwise the microphone. **The microphone is never opened while a recording runs**, paused
  included (on Android 10 the newest listener takes the mic and the recording would go silent).
- Toggle: the recording toolbar (`annotation_toolbar_unified.xml`), "Visualiser" row under
  "Webcam overlay". The old in-recording strip (`LiveVisualizer`) is untouched and still selectable.
- Not yet: phone audio without a recording (needs a MediaProjection consent of its own, or the
  20 Hz `Visualizer` API); a look picker sheet (prev/next only for now).

## 9. Status

| Item | State |
|---|---|
| Engine, 25 primitives, 29 looks, web pilot | ✅ |
| Room: drawer, layers, music links, colours, brand, undo/redo, pictures | ✅ |
| Looks kept in Supplies/Looks, revert, duplicate, rename, delete | ✅ device-tested 10-17 |
| Joybot in VisLab | ✅ (JOYBOT lane) |
| 45-60 fps on Note 9 | ✅ most looks; Crystal, Gyroid Dive lower |
| Floating overlay + recording toggle | ✅ device-tested 10-17 (incl. a real recording) |
| Studio overlay, scrubbing, export, mini editor | ❌ section 7 |
| Words layer (art, not captions), 10 Lyrics looks | ✅ pilot + Note 9, 30-57 fps after the 10-18 fixes; Studio feed owed (7.5) |
| Spiral Banner: log spiral filled with the song, slow turn (no beat jumps) | ✅ Note 9 10-18 |
| Every layer: Reactivity, Softness, Place (made in its own space) | ✅ engine + pilot + app drawer, Note 9 (Turn tried on Spiral Banner's space) |
| Speeds with music links move smoothly (`rate`) | ✅ tests + pilot + Note 9 |
| Heat guard | ✅ seen working on the Note 9 10-18 04:10: `heat 3` (SEVERE) -> half resolution, 60 fps |
| A build keeps what it does not know in a look | ✅ tests; the ten lyrics looks restored on the Note 9 |
| Lab: per-layer colours, start from nothing, parts shelf, my parts | ❌ next |
| Lobby Viz Lab hero shows the last look | ❌ |

## 10. Owner rulings in force

Drawers ~55% see-through; slant selectors (first chip flat-left, gradient icons, selected =
gradient with black punch-out); fully rounded pills inside drawers; in portrait the drawer must
not cover the core of the preview; one press = one undo; every button has a hover label;
phone-first; looks are files the person owns; revert-to-default with a warning.
