package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What Joybot may do with the camera recorder (audit BOT-A29), decided off the phone. The rules:
 * starting the camera takes the PERSON's own words asking to record (a model's guess or a
 * computer on the bridge never starts it); Android's camera and microphone permissions are the
 * person's tap, so a missing one is reported, not worked round; pause / resume / stop only make
 * sense in the matching state; dual recording can't pause from here. Pure Java, IdeaEngineTest.
 */
public final class CaptureRules {
    private CaptureRules() { }

    public enum Verdict {
        /** Do it. */ GO,
        /** Report the state only. */ STATUS,
        /** The person didn't ask to record in their own words. */ NOT_ASKED,
        /** Camera or microphone permission missing: only the person can allow it. */ NO_PERMISSION,
        /** Already recording (start) / not recording (pause, resume, stop). */ WRONG_STATE,
        /** Dual camera recording has no pause here. */ NO_DUAL_PAUSE,
        /** Not a known step. */ UNKNOWN
    }

    private static final Pattern ASKED_TO_RECORD = Pattern.compile(
            "(?i)\\b(record|recording|film|filming|start (the )?camera|roll (the )?camera|capture (a |some )?video)\\b");

    /** The person's own words ask for recording to start ("start recording", "film me"). */
    public static boolean askedToRecord(@Nullable String personSaid) {
        return personSaid != null && ASKED_TO_RECORD.matcher(personSaid).find()
                && !personSaid.toLowerCase(Locale.US).matches("(?s).*\\b(stop|pause|don't|do not|end)\\b.*record.*");
    }

    @NonNull
    public static Verdict decide(@Nullable String step, boolean recording, boolean paused, boolean dual,
                                 boolean permissionsOk, @Nullable String personSaid) {
        String s = step == null ? "status" : step.trim().toLowerCase(Locale.US);
        switch (s) {
            case "status": return Verdict.STATUS;
            case "start":
                if (recording) return Verdict.WRONG_STATE;
                if (!askedToRecord(personSaid)) return Verdict.NOT_ASKED;
                return permissionsOk ? Verdict.GO : Verdict.NO_PERMISSION;
            case "pause":
                if (!recording || paused) return Verdict.WRONG_STATE;
                return dual ? Verdict.NO_DUAL_PAUSE : Verdict.GO;
            case "resume":
                if (!recording || !paused) return Verdict.WRONG_STATE;
                return dual ? Verdict.NO_DUAL_PAUSE : Verdict.GO;
            case "stop":
                return recording ? Verdict.GO : Verdict.WRONG_STATE;
            default:
                return Verdict.UNKNOWN;
        }
    }
}
