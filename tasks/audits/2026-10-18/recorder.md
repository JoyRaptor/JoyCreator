# Floating visualiser / screen recorder integration audit — 2026-10-18

Scope: checked-out Joy Creator HEAD `24fc23790`, including the new Visualiser row beside Webcam in the recording toolbar. No application edits or Gradle runs. Initial control-flow findings were checked against Android's documented behavior; subsequent owner-authorized Note 9 testing reproduced R1 and R3. R2 needs Android 12+ and remains source/platform verified. See [phone verification and build identity](phone.md). Existing recorder code is cited when it completes a failure path introduced by the new overlay.

## R1 — P1: Hearing the phone can terminate the recording already in progress

**Primary location:** `app/src/main/java/com/fadcam/vislab/overlay/VisOverlayService.java:174–205` (especially 191–205).

**Trigger:** On Android 10+, start a screen recording, enable the floating visualiser, tap its speaker button, and approve Android's capture dialog. Microphone permission must already be granted.

The overlay already receives the recorder's PCM through `RecordingTap`, and `OverlaySound.choose()` selects RECORDING while capture is running. Nevertheless, the speaker button always opens `PhoneAudioConsent` unless a separate phone projection already exists. The returned consent creates a second MediaProjection at line 205 without rechecking recorder activity. Android can stop the existing projection when another starts. The recorder's registered `onStop()` at `ScreenRecordingService.java:393–406` then calls `handleStopRecording()`. Asking the visualiser to hear the phone can therefore end the user's recording. Paused recordings and a recording started while the consent dialog is open need the same protection.

**Fix:** Keep recording capture ownership in one coordinator. While recording/paused/starting, use the recorder tap and explain the source in the speaker control; do not request another projection. Recheck immediately before accepting a consent result, and discard an obsolete grant. Test both source-switch directions and the consent/start race.

**Evidence:** [Android MediaProjection callback contract](https://developer.android.com/reference/android/media/projection/MediaProjection.Callback#onStop()) describes another projection starting as a reason for the current projection stopping. The Note 9 subsequently reproduced this exact path: speaker consent followed by `MediaProjection stopped externally` and finalization of the 61.6-second test recording. See [phone evidence](phone.md).

## R2 — P1: Full-screen pass-through can block touches to other apps on Android 12+

**Primary location:** `app/src/main/java/com/fadcam/vislab/overlay/VisOverlayService.java:283–290`.

**Trigger:** Enable the visualiser, switch it to full screen, and try interacting with a different app underneath on Android 12+.

The window is `TYPE_APPLICATION_OVERLAY`, fills the screen, and receives `FLAG_NOT_TOUCHABLE`. Its `LayoutParams.alpha` remains at the default 1. The translucent pixel format and shader transparency do not change this window opacity. Android's untrusted-touch protection uses window alpha; the input can be blocked before it reaches the other application's window. The mode's user-facing promise that touches pass through is consequently false on affected OS versions. Same-UID testing inside Joy Creator and testing only on the Android 10 Note 9 would miss this.

**Fix:** Set the full-screen window opacity within `InputManager.getMaximumObscuringOpacityForTouch()` on supported versions, considering combined opacity where multiple pass-through overlay windows overlap. Restore the intended box opacity on exit. Keep the small controls handle touchable. Verify on a distinct app UID on Android 12+, with webcam/annotation overlays enabled as well.

**Platform evidence:** [Android 12 untrusted touch changes](https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events) and [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE) define the opacity gate (0.8 by default on Android 12). No system security settings should be disabled to make this mode work.

## R3 — P2: The recorder's Visualiser On/Off label becomes stale after external changes

**Primary location:** `app/src/main/java/com/fadcam/fadrec/ui/AnnotationService.java:1324–1331`.

**Trigger:** Turn the visualiser on using the recording toolbar, close it using its own close button or notification, then reopen the recorder menu. Alternatively, enable it through JoyBot after the recorder menu is constructed.

`updateVisualiserToggleUi()` is called only when the row is constructed and 300 ms after a click on that row. The overlay's own close, notification, and JoyBot paths update the service state without notifying the row. `performMenuToggle()` refreshes Webcam but not Visualiser. A stale On label remains after the visualiser has stopped; tapping it to turn it off instead starts it again because `toggle()` reads the current service state. The symmetric stale Off case also occurs.

**Fix:** Publish observable service state, including starting/stopping/failed, and render the row from it. Refresh on opening as a minimum; replace the one-shot delay with lifecycle-aware state observation. Test close from all entry points and failed starts.

**Phone result:** Reproduced on the Note 9: after closing the floating visualiser, its service was absent and projection null, but the toolbar still displayed green On. [Screenshot and test details](phone.md).

## Additional coverage to require before release

- Android 10 and Android 12+; full-display capture and Android's single-app capture.
- Mic, phone audio, muted/no audio, record pause/resume, projection revoked by Android, overlay permission revoked, app process death.
- VisLab room plus overlay together; webcam plus visualiser plus recording; external app touches while all overlays are present.
- Observe output video and its audio, rather than judging only the preview and toolbar label.

The separate VisLab report covers render/audio workload, image source parity, GL resources, and saving.
