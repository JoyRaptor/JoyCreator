# The 46 white-to-INK sites that want a look on a phone

Joy Creator's INK is `0xFFF2F2F5` — deliberately a touch below white, so no veil or
hairline out-ranks the brightest *text* in the app. 69 sites in the faditor package were
painting raw `0xFFFFFF` at an alpha; all 69 now ask for `Studio.alpha(Studio.INK, ..)`.

The worst-case channel gap between white and INK is 13/255, scaled by the alpha. 23 of the
69 land under 4/255, which nobody can see. These 46 are the rest, each with the largest
change it could possibly show. None exceeds 12/255 — under 5% brightness on a hairline —
but none of them has been looked at on a screen, so they are listed rather than assumed.

The sandbox dropped off adb before this pass could be verified. Everything here is
**compile-verified only**.

- `FaditorEditorActivity.java:17884` — `0x66FFFFFF`, max delta 5.2/255
- `FaditorEditorActivity.java:19901` — `0x55FFFFFF`, max delta 4.3/255
- `FaditorEditorActivity.java:19905` — `0x55FFFFFF`, max delta 4.3/255
- `FaditorEditorActivity.java:20207` — `0x55FFFFFF`, max delta 4.3/255
- `FaditorEditorActivity.java:33846` — `0x55FFFFFF`, max delta 4.3/255
- `FaditorEditorActivity.java:40886` — `0x80FFFFFF`, max delta 6.5/255
- `VolumeBarView.java:51` — `0x88FFFFFF`, max delta 6.9/255
- `VolumeDragFaderView.java:38` — `0x66FFFFFF`, max delta 5.2/255
- `WordScrubView.java:46` — `0x88FFFFFF`, max delta 6.9/255
- `WordScrubView.java:47` — `0x66FFFFFF`, max delta 5.2/255
- `avatar\AvatarStudioActivity.java:510` — `0x99FFFFFF`, max delta 7.8/255
- `avatar\AvatarStudioActivity.java:898` — `0x66FFFFFF`, max delta 5.2/255
- `avatar\AvatarStudioActivity.java:1044` — `0x99FFFFFF`, max delta 7.8/255
- `avatar\AvatarStudioActivity.java:1080` — `0x99FFFFFF`, max delta 7.8/255
- `avatar\LifeSignals.java:130` — `0x7FFFFFFF`, max delta 6.5/255
- `avatar\PoseMatrixView.java:54` — `0x66FFFFFF`, max delta 5.2/255
- `avatar\SyntheticTrackingSource.java:73` — `0x7FFFFFFF`, max delta 6.5/255
- `avatar\SyntheticTrackingSource.java:75` — `0x7FFFFFFF`, max delta 6.5/255
- `layers\LayerRowRenderer.java:173` — `0x66FFFFFF`, max delta 5.2/255
- `layers\LayerRowRenderer.java:1103` — `0x88FFFFFF`, max delta 6.9/255
- `layers\LayerRowRenderer.java:2098` — `0x88FFFFFF`, max delta 6.9/255
- `layers\LayerRowRenderer.java:2145` — `0x66FFFFFF`, max delta 5.2/255
- `layers\LayerRowRenderer.java:2145` — `0xE6FFFFFF`, max delta 11.7/255
- `layers\LayerRowRenderer.java:2172` — `0x66FFFFFF`, max delta 5.2/255
- `layers\LayerRowRenderer.java:2172` — `0xE6FFFFFF`, max delta 11.7/255
- `layers\LayerRowRenderer.java:2789` — `0x66FFFFFF`, max delta 5.2/255
- `layers\LayerRowRenderer.java:2859` — `0xCCFFFFFF`, max delta 10.4/255
- `layers\LayerRowRenderer.java:2863` — `0xCCFFFFFF`, max delta 10.4/255
- `layers\LayerRowRenderer.java:2874` — `0xCCFFFFFF`, max delta 10.4/255
- `layers\LayerRowRenderer.java:2875` — `0xCCFFFFFF`, max delta 10.4/255
- `layers\LayerRowRenderer.java:2899` — `0xCCFFFFFF`, max delta 10.4/255
- `layers\LayerRowRenderer.java:3203` — `0xB3FFFFFF`, max delta 9.1/255
- `overlay\PreviewHandlesOverlay.java:310` — `0x99FFFFFF`, max delta 7.8/255
- `player\PreviewPipController.java:773` — `0x66FFFFFF`, max delta 5.2/255
- `player\TransitionPreviewCardView.java:156` — `0x66FFFFFF`, max delta 5.2/255
- `timeline\EditorTimelineView.java:243` — `0xBBFFFFFF`, max delta 9.5/255
- `timeline\EditorTimelineView.java:251` — `0xBBFFFFFF`, max delta 9.5/255
- `timeline\EditorTimelineView.java:2084` — `0xDDFFFFFF`, max delta 11.3/255
- `timeline\EditorTimelineView.java:2152` — `0xCCFFFFFF`, max delta 10.4/255
- `timeline\EditorTimelineView.java:2156` — `0xCCFFFFFF`, max delta 10.4/255
- `timeline\EditorTimelineView.java:3965` — `0x99FFFFFF`, max delta 7.8/255
- `timeline\EditorTimelineView.java:7080` — `0xCCFFFFFF`, max delta 10.4/255
- `timeline\EditorTimelineView.java:7086` — `0xCCFFFFFF`, max delta 10.4/255
- `timeline\EditorTimelineView.java:7152` — `0xAAFFFFFF`, max delta 8.7/255
- `transcript\CaptionOverlayView.java:885` — `0x50FFFFFF`, max delta 4.1/255
- `transcript\TranscriptPanelView.java:180` — `0xAAFFFFFF`, max delta 8.7/255
