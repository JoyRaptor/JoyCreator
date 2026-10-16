package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Type;
import com.fadcam.ui.faditor.audio.DuckApplier;
import com.fadcam.ui.faditor.audio.Ducker;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.VolumeKeyframe;
import com.fadcam.ui.faditor.transcript.Transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The audio mix family (gap F2, audio): the audio drawer's plain controls, one for one.
 * Ducking under a voice, the volume envelope's points, pan, and the per-clip voice clean-up.
 *
 * <p>Left out on purpose: crossfade pills (the export does not render them yet, so they would
 * be a silent success), speed and pitch (an audio clip has no such field), and extracting audio
 * from a video (a media job, not a model edit).</p>
 */
final class AudioMixActions {
    private AudioMixActions() { }

    private static final String FAMILY = "audio";
    private static final String CLIP_HELP = "Which audio clip: its id, or its name";
    /** The drawer's own tolerance for "the point under the playhead" (AudioDrawerTabs.KEY_TOLERANCE_MS). */
    private static final long KEY_TOLERANCE_MS = 40;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("set_ducking", "Make the music quieter under a voice")
                .help("Duck music under speech: the music's clips dip while the voice on another lane talks and"
                        + " come back up after. The voice clip needs a transcript (its words) to work out where"
                        + " it speaks. Replaces any volume points the music already has, so ask first if it has"
                        + " its own. Use when they say the music is too loud over their voice.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap a music lane's menu › Duck under…")
                .id("music", "audio", true, "The music clip. Every clip on its lane is ducked.")
                .id("voice", "audio", true, "A clip on another lane with the voice. Its transcript is used.")
                .build(), AudioMixActions::setDucking);

        r.add(ActionSpec.of("set_volume_point", "Set the loudness at one moment of a clip")
                .help("Add or move a volume point on an audio clip: how loud it is at one moment, counted in"
                        + " milliseconds from the start of that clip. Points make an envelope, which takes over"
                        + " from the clip's flat level. remove=true deletes the point near that moment instead."
                        + " Use for \"quieter at the end\" or \"fade it down here\".")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap an audio clip › Level › ◇ (drop a point) and the Level slider")
                .id("clip", "audio", true, CLIP_HELP)
                .param("atMs", Type.TIME_MS, true, "Where in the clip, in milliseconds from its start")
                .number("level", false, "Loudness there: 0 silent, 1 as set, 2 twice as loud (200%)", 0.0, 2.0)
                .flag("remove", false, "true to delete the point at that moment instead")
                .build(), AudioMixActions::setVolumePoint);

        r.add(ActionSpec.of("clear_volume_points", "Remove all the volume points from a clip")
                .help("Clear the whole volume envelope on an audio clip, so it plays at its flat level again. Fades"
                        + " are points too, so they go as well. Removes everything the person drew; propose it"
                        + " first unless they asked for exactly this. One Undo brings it back.")
                .wing("STUDIO", FAMILY).effect(Effect.DESTRUCTIVE)
                .where("Studio › tap an audio clip › Level › Clear")
                .id("clip", "audio", true, CLIP_HELP)
                .build(), AudioMixActions::clearVolumePoints);

        r.add(ActionSpec.of("set_audio_mix", "Set an audio clip's pan or voice clean-up")
                .help("Pan an audio clip left or right (-100 full left, 0 centre, 100 full right), and/or turn the"
                        + " voice clean-up on or off for that clip. Voice clean-up is for speech only; it cuts music"
                        + " tails, so keep it off for music.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap an audio clip › Level (pan) and FX (voice clean-up)")
                .id("clip", "audio", true, CLIP_HELP)
                .number("pan", false, "Left or right: -100 full left, 0 centre, 100 full right", -100.0, 100.0)
                .flag("voiceClean", false, "Voice clean-up on this clip (speech only)")
                .build(), AudioMixActions::setAudioMix);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult setDucking(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        AudioClip music = audioClip(ctx, a.str("music"));
        AudioClip voice = audioClip(ctx, a.str("voice"));
        String musicLane = laneOf(music);
        String voiceLane = laneOf(voice);
        if (musicLane.equals(voiceLane)) {
            throw new ActionError("\"" + label(music) + "\" and \"" + label(voice) + "\" are on the same lane."
                    + " Ducking needs the voice on a different lane.");
        }
        List<AudioClip> voiceClips = lane(ctx, voiceLane);
        if (!anyWords(voiceClips)) {
            throw new ActionError("\"" + label(voice) + "\" has no transcript, so there is no way to tell where the"
                    + " voice speaks. Give that clip a transcript first, then try again.");
        }

        // Same door as the lane menu's "Duck under…": Ducker reads the voice's words, DuckApplier
        // writes the curve as ordinary volume points, and the one Undo step is the caller's.
        Ducker.Params params = new Ducker.Params();
        List<String> touched = new ArrayList<>();
        int ducked = 0, written = 0, replaced = 0;
        for (AudioClip tgt : lane(ctx, musicLane)) {
            List<VolumeKeyframe> curve = null;
            for (AudioClip kc : voiceClips) {
                Transcript tr = kc.getTranscript();
                if (tr == null || tr.words.isEmpty()) continue;
                curve = Ducker.computeFromWords(tr.words, kc.getOffsetMs(), kc.getInPointMs(),
                        tgt.getOffsetMs(), tgt.getTrimmedDurationMs(), params);
                if (!curve.isEmpty()) break;   // first voice clip that speaks
            }
            // The editor applies an empty curve too, which wipes the clip's points. Here an empty
            // result means "no speech found", so the clip keeps whatever points it already has.
            if (curve == null || curve.isEmpty()) continue;
            DuckApplier.Result res = DuckApplier.apply(tgt, curve);
            res.action.execute();
            ducked++;
            written += res.writtenKeyframes;
            replaced += res.replacedKeyframes;
            touched.add(tgt.getId());
        }
        if (ducked == 0) {
            return ActionResult.unchanged("No speech was found on \"" + label(voice) + "\", so the music was left alone.");
        }
        String sum = "The music is now quieter while \"" + label(voice) + "\" talks."
                + (replaced > 0 ? " It replaced " + replaced + " volume point(s) that were already there." : "");
        return ActionResult.changed(sum, touched).withDetail("Wrote " + written + " volume points across "
                + ducked + " clip(s).");
    }

    @NonNull
    private static ActionResult setVolumePoint(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        AudioClip ac = audioClip(ctx, a.str("clip"));
        long at = a.whole("atMs");
        long dur = ac.getTrimmedDurationMs();
        if (at < 0 || at > dur) {
            throw new ActionError("That moment is outside \"" + label(ac) + "\", which lasts "
                    + StudioCtx.clock(dur) + ". Use a time from 0 to " + dur + " ms.");
        }
        if (Boolean.TRUE.equals(a.bool("remove"))) {
            VolumeKeyframe near = null;
            for (VolumeKeyframe kf : ac.getVolumeKeyframes()) {
                if (Math.abs(kf.timeMs - at) <= KEY_TOLERANCE_MS) { near = kf; break; }
            }
            if (near == null) {
                return ActionResult.unchanged("There is no volume point at " + StudioCtx.clock(at)
                        + " on \"" + label(ac) + "\".");
            }
            long t = near.timeMs;
            ac.removeVolumeKeyframeAt(t);
            return ActionResult.changed("Removed the volume point at " + StudioCtx.clock(t)
                    + " on \"" + label(ac) + "\".", ids(ac));
        }
        if (!a.has("level")) {
            throw new ActionError("How loud should \"" + label(ac) + "\" be at that moment? Give level from 0 (silent)"
                    + " to 2 (200%), or set remove to true to delete the point.");
        }
        float gain = a.num("level").floatValue();
        // Same as the drawer's "drop a point here": the envelope stores a multiplier of the clip's level.
        ac.addOrUpdateVolumeKeyframe(at, multiplierFor(ac, gain));
        return ActionResult.changed("\"" + label(ac) + "\" is at " + percent(gain) + " at "
                + StudioCtx.clock(at) + ".", ids(ac));
    }

    @NonNull
    private static ActionResult clearVolumePoints(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        AudioClip ac = audioClip(ctx, a.str("clip"));
        if (!ac.hasVolumeKeyframes()) {
            return ActionResult.unchanged("\"" + label(ac) + "\" has no volume points to clear.");
        }
        int n = ac.getVolumeKeyframes().size();
        ac.clearVolumeKeyframes();
        return ActionResult.changed("Cleared " + n + " volume point(s) on \"" + label(ac)
                + "\". It plays at its own level again.", ids(ac));
    }

    @NonNull
    private static ActionResult setAudioMix(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        AudioClip ac = audioClip(ctx, a.str("clip"));
        Double pan = a.num("pan");
        Boolean voice = a.bool("voiceClean");
        if (pan == null && voice == null) {
            throw new ActionError("Say what to change on \"" + label(ac) + "\": its pan (-100 to 100), or voice"
                    + " clean-up on or off.");
        }
        boolean changed = false;
        List<String> said = new ArrayList<>();
        if (pan != null) {
            float was = ac.getPan();
            float now = (float) Math.max(-1.0, Math.min(1.0, pan / 100.0));
            ac.setPan(now);
            if (ac.getPan() != was) changed = true;
            int pct = Math.round(Math.abs(now) * 100f);
            said.add(pct == 0 ? "centred" : "panned " + pct + "% " + (now < 0 ? "left" : "right"));
        }
        if (voice != null) {
            boolean on = voice;
            if (ac.isVoiceFxEnabled() != on) changed = true;
            ac.setVoiceFxEnabled(on);
            said.add("voice clean-up " + (on ? "on" : "off"));
        }
        if (!changed) return ActionResult.unchanged("\"" + label(ac) + "\" already has those settings.");
        return ActionResult.changed("\"" + label(ac) + "\": " + join(said) + ".", ids(ac));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * An audio clip matching {@code ref}: its id, else its name. Several by name is an error
     * that lists them, so the model asks which one.
     */
    @NonNull
    private static AudioClip audioClip(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        List<AudioClip> all = ctx.project.getTimeline().getAudioClips();
        if (all.isEmpty()) throw new ActionError("This project has no audio clips.");
        String r = ref.trim();
        for (AudioClip ac : all) if (r.equals(ac.getId())) return ac;
        String want = norm(r);
        List<AudioClip> exact = new ArrayList<>(), part = new ArrayList<>();
        for (AudioClip ac : all) {
            String name = norm(ac.getLabel());
            if (name.equals(want)) exact.add(ac);
            else if (!want.isEmpty() && name.contains(want)) part.add(ac);
        }
        List<AudioClip> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            throw new ActionError("No audio clip is called \"" + r + "\". The audio clips are: " + list(all) + ".");
        }
        throw new ActionError(hit.size() + " audio clips match \"" + r + "\": " + list(hit)
                + ". Ask which one, or use its id.");
    }

    /** The lane an audio clip sits on. Same rule the timeline uses to group audio lanes. */
    @NonNull
    private static String laneOf(@NonNull AudioClip ac) {
        return ac.getLayerId() != null ? ac.getLayerId() : "audio";
    }

    @NonNull
    private static List<AudioClip> lane(@NonNull StudioCtx ctx, @NonNull String laneId) {
        List<AudioClip> out = new ArrayList<>();
        for (AudioClip ac : ctx.project.getTimeline().getAudioClips()) {
            if (laneId.equals(laneOf(ac))) out.add(ac);
        }
        return out;
    }

    private static boolean anyWords(@NonNull List<AudioClip> clips) {
        for (AudioClip ac : clips) {
            Transcript tr = ac.getTranscript();
            if (tr != null && !tr.words.isEmpty()) return true;
        }
        return false;
    }

    /** Desired FINAL gain to the stored envelope multiplier, as the drawer does (B1.Q). Silent clip: 0. */
    private static float multiplierFor(@NonNull AudioClip ac, float finalGain) {
        float lvl = ac.getVolumeLevel();
        if (lvl < 0.0001f) return 0f;
        return Math.max(0f, Math.min(2f, finalGain / lvl));
    }

    @NonNull
    private static List<String> ids(@NonNull AudioClip ac) {
        List<String> out = new ArrayList<>();
        out.add(ac.getId());
        return out;
    }

    @NonNull
    private static String label(@NonNull AudioClip ac) {
        String s = ac.getLabel() == null ? "" : ac.getLabel().replace('\n', ' ').trim();
        if (s.isEmpty()) s = "Audio";
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    @NonNull
    private static String list(@NonNull List<AudioClip> clips) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (AudioClip ac : clips) {
            if (n++ == 12) { sb.append(", … ").append(clips.size() - 12).append(" more"); break; }
            if (sb.length() > 0) sb.append(", ");
            sb.append('"').append(label(ac)).append("\" (id ").append(ac.getId()).append(", ")
                    .append(StudioCtx.clock(ac.getOffsetMs())).append(')');
        }
        return sb.toString();
    }

    @NonNull
    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String percent(float gain) {
        return Math.round(gain * 100f) + "%";
    }

    @NonNull
    private static String join(@NonNull List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }
}
