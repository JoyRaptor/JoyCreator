package com.fadcam.ui.faditor.ai.actions;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.joybot.CaptureRules;

/**
 * Capture's camera recorder from Joybot (audit BOT-A29): start, pause, resume, stop and "is it
 * recording". Every step goes through a door the app already has, so Joybot does exactly what the
 * person's own buttons do: start through {@code RecordingStartActivity} (the home-screen shortcut's
 * door, back / front / dual camera), pause and resume through the recording service's own intents
 * (as the Remote Studio page sends them), stop through {@code RecordingStopActivity} (which knows
 * dual from single). The rules ({@link CaptureRules}): the camera starts only on the person's own
 * words, and permissions stay the person's tap. Screen recording is not covered yet.
 */
final class CaptureActions {
    private CaptureActions() { }

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("recording", "Start, pause, resume or stop a camera recording")
                .help("Capture's camera recorder. step: status (is it recording), start, pause, resume, stop."
                        + " camera (start only): back (default), front or dual. The camera starts only when the person"
                        + " asked in their own words to record; Android's camera and microphone permissions are theirs"
                        + " to allow (if missing, say so and offer to open Capture with open_room record). Screen"
                        + " recording is not covered: send them to Capture for that.")
                .wing("APP", "capture").effect(Effect.EDIT)
                .where("Capture › the record button")
                .choice("step", true, "What to do", java.util.Arrays.asList("status", "start", "pause", "resume", "stop"))
                .choice("camera", false, "Which camera, for start", java.util.Arrays.asList("back", "front", "dual"))
                .build(), CaptureActions::run);
    }

    @NonNull
    private static ActionResult run(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        String step = a.choice("step", java.util.Arrays.asList("status", "start", "pause", "resume", "stop"));
        com.fadcam.SharedPreferencesManager sp = com.fadcam.SharedPreferencesManager.getInstance(ctx.app);
        boolean recording = sp.isRecordingInProgress();
        boolean paused = recording && com.fadcam.streaming.RemoteStreamManager.getInstance().isPaused();
        boolean dual = sp.getCameraSelection() != null && sp.getCameraSelection().isDual();
        // Permissions only matter for a start the person asked for; reading them otherwise is wasted.
        boolean perms = !"start".equals(step) || !CaptureRules.askedToRecord(AIChatState.liveAsk)
                || (granted(ctx, Manifest.permission.CAMERA) && granted(ctx, Manifest.permission.RECORD_AUDIO));
        switch (CaptureRules.decide(step, recording, paused, dual, perms, AIChatState.liveAsk)) {
            case STATUS:
                return ActionResult.unchanged(!recording ? "Not recording." : paused ? "Recording, paused." : "Recording now.");
            case NOT_ASKED:
                throw new ActionError("Only you can start the camera.\nNot done: the person's own words didn't ask to"
                        + " record. Ask them whether to start recording; start only after they say so in their words.");
            case NO_PERMISSION:
                throw new ActionError("Joy Creator isn't allowed the camera and microphone yet.\nNot done: only the person"
                        + " can allow them; offer to open Capture (open_room record), where Android asks.");
            case WRONG_STATE:
                throw new ActionError(recording ? (paused ? "The recording is already paused." : "Already recording.")
                        : "Nothing is recording.");
            case NO_DUAL_PAUSE:
                throw new ActionError("A dual-camera recording can't pause from here; it can stop.");
            case UNKNOWN:
                throw new ActionError("step is status, start, pause, resume or stop.");
            default:
                break;
        }
        switch (step) {
            case "start": {
                String cam = a.choice("camera", java.util.Arrays.asList("back", "front", "dual"));
                Intent i = new Intent(ctx.app, com.fadcam.RecordingStartActivity.class)
                        .putExtra(com.fadcam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                "front".equals(cam) ? com.fadcam.RecordingStartActivity.CAMERA_MODE_FRONT
                                        : "dual".equals(cam) ? com.fadcam.RecordingStartActivity.CAMERA_MODE_DUAL
                                        : com.fadcam.RecordingStartActivity.CAMERA_MODE_BACK)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.app.startActivity(i);
                return ActionResult.changed("Recording started with the " + (cam == null ? "back" : cam) + " camera."
                        + " Stop it from the notification, Capture, or ask me.", new java.util.ArrayList<>());
            }
            case "pause":
            case "resume": {
                Intent i = new Intent(ctx.app, com.fadcam.services.RecordingService.class).setAction(
                        "pause".equals(step) ? com.fadcam.Constants.INTENT_ACTION_PAUSE_RECORDING
                                : com.fadcam.Constants.INTENT_ACTION_RESUME_RECORDING);
                com.fadcam.utils.ServiceStartPolicy.startRecordingAction(ctx.app, i);
                return ActionResult.changed("pause".equals(step) ? "Recording paused." : "Recording again.",
                        new java.util.ArrayList<>());
            }
            default: {   // stop
                ctx.app.startActivity(new Intent(ctx.app, com.fadcam.RecordingStopActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return ActionResult.changed("Recording stopped; it's in your Library.", new java.util.ArrayList<>());
            }
        }
    }

    private static boolean granted(@NonNull AppCtx ctx, @NonNull String perm) {
        return ContextCompat.checkSelfPermission(ctx.app, perm) == PackageManager.PERMISSION_GRANTED;
    }
}
