# VisLab brief (for a future specialist session)

Written 2026-10-16 by the Studio lead from a read of the code and the audit in `tasks/audits/vislab_joybrush_audit.md`. Owner is non-technical and wants the final product to be excellent; engineering choices are the specialist's call, justified in end-user terms.

## 1. What VisLab is to Joy Creator

VisLab is the wing where sound becomes picture: music visualisers, audio-reactive shapes, waveforms. It is one of the five lobby rooms (amber/orange colour, `Studio.ROOM_VIZ`, `ROOM_VIZ_DEEP`; the lobby card says "LAST VISUALISER" / "Start"). Today **the room has no screen**: tapping it opens the Studio project list. Its real working parts live inside the Studio editor. This document is the starting point for building VisLab as a real place, and for making the visualiser far better than the first version.

## 2. What exists today (verified in code)

| Piece | Where | State |
|---|---|---|
| Visualiser as a Studio overlay (`WaveformOverlayInstance`) with layers (`VizLayer`): 8 shapes (bars, line, filled, dots, squares, peaks, ring, particles), colour, opacity, softness, gain, blend (normal/add), trails, spread, phase, mirror, glow, particle count/speed, attack/release | `app/.../ui/faditor/model/`, drawer in `FaditorEditorActivity.java` (`rebuildVisualizerLayers`) | Working, keyframeable, exported into video. Drawer is crowded (a redesign is in progress 2026-10-16). Shape icons drawn by `tools/VizGlyph.java`. |
| Styles: built-in set + user styles saved as human-editable `<id>.waveform.json` | `waveform/WaveformStyleIO.java` | Working. User styles go to the project's pinned folder, NOT one shared library and not the Joy Creator folder. |
| Audio analysis: FFT, band envelopes, onsets, beat detection, track aligner | `waveform/` (Fft, BandWaveformExtractor, BeatDetector, OnsetDetector, TrackAligner...) | Working and used by timeline beat snapping and clap sync. |
| Renderers: preview and export share style renderers | `WaveformStyleRenderer`, `WaveformOverlayView`, `ExportManager` | Working. Exports match preview. |
| Live visualiser in the recorder | `LiveVisualizer`, `FadRecHomeFragment`, `ScreenRecordingService` | Only built-in styles can be used live; a style made in Studio cannot. Preview data is synthetic. |
| Debug screen | `waveform/WaveformDebugActivity.java` | Exported in release manifest (decision pending). |
| Tests | none | None for style IO, layers, beat or live code. |

## 3. What is missing (the build list)

1. **A real VisLab room**: a screen the lobby card opens. Not a project list.
2. **A style library**: browse, preview live, favourite, duplicate, rename, share, delete. One library shared by Studio, the recorder and VisLab, stored in the Joy Creator folder under Supplies (owner ruling: presets are copied out of the app into Supplies, the user's copy wins, and every preset has "Revert to default" with a warning).
3. **Audio source picker inside VisLab** so a visualiser can be authored against any song, mic or recording without opening a video project.
4. **Send to Studio**: a finished visualiser (style + audio) drops into a chosen or new project; and the reverse (open a project's visualiser in VisLab to refine).
5. **Live use**: any saved style usable in the recorder.
6. **Quality bar**: tests for style IO and layers; Joybot hooked in as a room (`IdeaEngine.Room` has no VisLab entry).

## 4. Ideas to "soup up" the visualiser (proposals, not decisions)

- Many more shapes and a way to combine them (layers already exist; add emitters such as waveform ribbons, radial spectrum, terrain/mountains, lyric-reactive text, image/sprite emitters so a sprite or a Joy Brush drawing dances to the beat).
- Per-band control: assign bass / mids / highs to different layers, parameters, or keyframe tracks (the band extractor already exists).
- Beat-triggered events (flash, pulse, camera shake, colour swap) on a beat grid, using `BeatDetector` / `OnsetDetector`.
- Colour: palette extraction from the video behind it; gradient and cycle modes (gradient stops exist on layers).
- Post effects per visualiser: bloom/glow beyond today's radius, chromatic split, feedback trails.
- A "recipe" system: one-tap looks by mood, with a live preview strip (matches the Joy Creator habit of showing, not naming).
- Performance on a Note 9-class phone must be a design input from day one (GPU path for export already exists elsewhere in the app).

## 5. Where to get taste and visual guidance

The owner said he will provide the design direction. Existing material to read first (all in the repo):

- `tasks/design/UI-JoyCreator-grand-design.html` and `UI-Five-front-doors-options.html`: the lobby and the five rooms; VisLab's amber identity.
- `tasks/design/UI-Studio-final.html` and `The Studio Kit - Claude.htm`: the settled Studio look (old black, zinc greys, flat pills, colour only to guide). VisLab should feel like the same family.
- `tasks/design/UI-The-Marquee_lobby_and-first-run-intro.html`, `UI-The-swatch-room_pill.html`, `UI-Boards-options.html`: how rooms, swatches, and boards are presented.
- Tokens: `studiokit/src/main/java/com/fadcam/ui/faditor/Studio.java` and `app/src/main/res/values/studio_tokens.xml` (do not hard-code colours; the colour picker/helper is owner-approved and must not be touched).
- Rules the owner has already settled (also in the project memory): drawers are ~55% see-through; dp values stay as drawn (dense look is wanted); every button has a hover label; one press = one undo step; phone-first, one-handed use; every feature reaches full parity on the phone (web tools are pilots).
- Gather from the owner before design starts: 3-5 reference visualisers or music-video looks he loves; whether VisLab is a "performance" space (live, bold, big controls) or a "workbench" (precise, dense); how much of Joy Brush's drawing look he wants carried in.

## 6. Open questions for the owner

1. Is VisLab for making visualiser clips on their own (export a loop to share), for dressing a Studio video, or both?
2. Should a sprite or a Joy Brush drawing be usable as a visualiser element?
3. Live performance mode (visuals reacting to the mic in real time, on screen or recorded) in scope for launch?
4. How many built-in looks does launch need to feel generous?

## 7. Suggested plan for the specialist session

1. Read this brief, the audit, and the design files above; capture BEFORE screenshots of today's visualiser drawer and the lobby Viz Lab card (see `tasks/screens/before/`).
2. Write a one-page spec with the owner's references; get sign-off.
3. Build the room shell + style library on top of the existing engine (no engine rewrite needed for step one).
4. Add the new emitters and per-band controls behind the same layer model so old projects keep working.
5. Prove it: JVM tests for style IO/layers, phone check on the Note 9, AFTER screenshots.
