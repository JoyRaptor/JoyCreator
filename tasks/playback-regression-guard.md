# Playback Reset Regression Guard

Last confirmed fix: 2026-06-17

## Symptom to watch for

Saved/reopened projects play from 0 even when the timeline playhead is somewhere else. After splitting, playback can also start from the first clip/project start or stick at the beginning of the second clip.

## Required invariants

1. Saved-project load must use the seekable playback URI.
   - `continueLoadFromSavedProject()` must call `loadClipForPlayback(getSelectedClip())`.
   - `resolvePlaybackUri()` must remux old fragmented MP4s even when no cached remux exists yet.
   - Do not leave ExoPlayer loaded with the original source URI when the source is a fragmented MP4/content URI.

2. Segment switches must use the same playback URI resolver.
   - `selectSegment()`, `onPlayheadSeeked()`, `onPlayheadDragFinished()`, and `advanceToSegment()` must call `loadClipForPlayback(clip)`.
   - Do not call `playerManager.loadClip(clip)` directly for video preview playback.

3. The play button must load the segment under the playhead before seeking.
   - In the play listener, compute `editorTimeline.getSegmentAtPlayhead()`.
   - If that segment differs from `selectedClipIndex`, call `selectSegment(playSegment)` before converting the playhead to a relative seek.

4. The playhead updater must not use `playWhenReady` as proof that video is playing.
   - Use `playerManager.isPlaying()`.
   - Buffering after a seek must not overwrite the timeline playhead with stale position 0.

5. `FaditorPlayerManager.play()` must not override a just-issued seek.
   - Keep the seek-in-flight guard around `play()`.
   - The trim-start correction in `play()` must be skipped while a seek is still landing.

6. Split-boundary detection must be half-open.
   - `EditorTimelineView.getSegmentAtPlayhead()` must return the next segment once the playhead is past the boundary, not keep returning the previous segment.

## Quick regression checks

Run these from `C:\+Projects\Screenrecorder\FadCam`:

```powershell
.\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon
.\gradlew.bat assembleDefaultDebug --no-daemon
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s REAL_SERIAL install -r "C:\+Projects\Screenrecorder\FadCam\app\build\outputs\apk\default\debug\app-default-arm64-v8a-debug.apk"
```

Manual playback check:

1. Open a saved project.
2. Move the playhead away from 0.
3. Press play.
4. Confirm video starts from the playhead and the playhead scrolls with playback.
5. Split the video.
6. Move the playhead just past the split into the second clip.
7. Press play.
8. Confirm the second clip is loaded and playback starts inside the second clip, not from the first clip/project start.
