package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * "Did you know?" — one cool thing about the app each time Joybot opens empty (owner, 10-28). Works
 * with no internet and no AI. Every tip is a feature STATE.md says is built (🟢 or 🟡 ✔ as of
 * 10-28) and on joy-creator; never a plan. Tips lean toward the wings the person really uses, with
 * a few from the others so they discover them. Pure Java (tested in IdeaEngineTest).
 */
public final class TipBook {
    private TipBook() { }

    /** The parts of the app a tip can be about (also the usage buckets). */
    public enum Wing { STUDIO, VISLAB, JOYBRUSH, SPRITELAB, CAPTURE, LIBRARY, JOYBOT }

    public static final class Tip {
        @NonNull public final String id;
        @NonNull public final Wing wing;
        @NonNull public final String text;
        /** Where "Take me there" goes (the chat's route() names), or null. */
        @Nullable public final String route;

        Tip(@NonNull String id, @NonNull Wing wing, @NonNull String text, @Nullable String route) {
            this.id = id;
            this.wing = wing;
            this.text = text;
            this.route = route;
        }
    }

    private static Tip t(String id, Wing w, String text, String route) {
        return new Tip(id, w, text, route);
    }

    public static final List<Tip> TIPS = Collections.unmodifiableList(Arrays.asList(
            // ── Studio ──
            t("studio_undo", Wing.STUDIO, "every press of Undo takes back exactly one step, and your undo history is still there after you close and reopen a project.", "studio"),
            t("studio_transitions", Wing.STUDIO, "the Studio has more than 35 transitions, and you can drop your own .glsl transitions into a folder to add more.", "studio"),
            t("studio_greenscreen", Wing.STUDIO, "the Studio can key out a green screen right on your phone.", "studio"),
            t("studio_bend", Wing.STUDIO, "pictures and sprites can bend: warp them with a mesh, or pin their corners onto something in the video.", "studio"),
            t("studio_keyframes", Wing.STUDIO, "almost anything can be keyframed with easing, so titles and pictures can glide, grow and fade on their own.", "studio"),
            t("studio_masks", Wing.STUDIO, "masks let one clip show through a shape in another.", "studio"),
            t("studio_fx_layer", Wing.STUDIO, "an adjustment layer puts one look over everything under it, for as long as you stretch it.", "studio"),
            t("studio_vislab_fx", Wing.STUDIO, "VisLab's finishes work in the Studio too: VHS, glitch, scanlines, kaleidoscope, CRT bend and more, and their sliders can follow the music.", "studio"),
            t("studio_hold_drawer", Wing.STUDIO, "holding a picture or a title opens its drawer, with everything you can change about it.", "studio"),
            t("studio_export_name", Wing.STUDIO, "the name you type when you export is the file's real name.", "studio"),
            // ── Sound and words ──
            t("sound_transcribe", Wing.STUDIO, "transcription runs entirely on your phone: no internet, no account, no upload.", "studio"),
            t("sound_ums", Wing.STUDIO, "I can find every um and uh in your talking and cut them all in one go, offline.", "studio"),
            t("sound_silences", Wing.STUDIO, "I can find the long quiet gaps in a recording and cut them out for you.", "studio"),
            t("sound_fix", Wing.STUDIO, "one tap cleans up speech: it cuts the rumble, lowers the hiss and evens out the loudness.", "studio"),
            t("sound_captions", Wing.STUDIO, "captions come in five animated styles, timed word by word from your own transcript.", "studio"),
            t("sound_beats", Wing.STUDIO, "the Studio can find the beats in your music, so cuts land right on them.", "studio"),
            t("sound_voiceover", Wing.STUDIO, "you can record a voice-over straight onto the timeline while the video plays.", "studio"),
            // ── VisLab ──
            t("vislab_room", Wing.VISLAB, "VisLab has 42 looks that move to music: play the demo song, your microphone or any song.", "vislab"),
            t("vislab_build", Wing.VISLAB, "you can build a VisLab look from nothing: stack bars, particles, lasers, stars and words, then make any ♪ slider follow the beat.", "vislab"),
            t("vislab_words", Wing.VISLAB, "VisLab turns a song's words into art: spirals, ribbons, tunnels and flights, each line in its own font.", "vislab"),
            t("vislab_midi", Wing.VISLAB, "a MIDI or .mod file can drive a VisLab look with its exact drum hits; .kar files even bring the lyrics.", "vislab"),
            t("vislab_float", Wing.VISLAB, "a visualiser can float over any app while you record your screen.", "record"),
            t("vislab_in_studio", Wing.VISLAB, "any VisLab look can be a layer in your video: add a Visualizer in the Studio and pick it in the Style tab.", "studio"),
            t("vislab_parts", Wing.VISLAB, "you can save one layer of a VisLab look as a part and reuse it in any other look.", "vislab"),
            t("vislab_follow", Wing.VISLAB, "parts of a VisLab look can follow a picture or title in your video as it moves.", "vislab"),
            t("vislab_show", Wing.VISLAB, "a picture layer in a VisLab look can show a picture, a title, a sprite or the main video from your Studio project.", "studio"),
            t("vislab_video", Wing.VISLAB, "you can play one of your own videos inside a VisLab look.", "vislab"),
            t("vislab_heat", Wing.VISLAB, "VisLab watches your phone's temperature and eases off by itself if it gets hot.", "vislab"),
            // ── JoyBrush ──
            t("joybrush_layers", Wing.JOYBRUSH, "a JoyBrush drawing can have up to 40 layers, so sketch, colour and shading never mix.", "joybrush"),
            t("joybrush_reference", Wing.JOYBRUSH, "you can pin a reference picture beside your JoyBrush drawing; hold the pin for its options.", "joybrush"),
            t("joybrush_brush_settings", Wing.JOYBRUSH, "holding the Brush, Smudge or Eraser button opens that tool's settings straight away.", "joybrush"),
            t("joybrush_joybot", Wing.JOYBRUSH, "I can help inside JoyBrush too: add or rename layers, make animation frames, or draw simple shapes.", "joybrush"),
            // ── SpriteLab and puppets ──
            t("sprite_grid", Wing.SPRITELAB, "SpriteLab finds the grid of a sprite sheet for you, and remembers a name for every cell.", "spritelab"),
            t("sprite_animations", Wing.SPRITELAB, "a sprite sheet can hold saved animations, ready to drop into a video.", "spritelab"),
            t("sprite_dope", Wing.SPRITELAB, "SpriteLab has a dope sheet for timing every frame.", "spritelab"),
            t("puppet_bend", Wing.SPRITELAB, "a plain picture can become a puppet: pin it, add bones, and it bends smoothly.", "studio"),
            t("puppet_keys", Wing.SPRITELAB, "you can perform a puppet live and keep the take as keyframes.", "studio"),
            // ── Capture ──
            t("capture_screen", Wing.CAPTURE, "Capture records your screen, with your face in a webcam bubble if you like.", "record"),
            t("capture_background", Wing.CAPTURE, "Capture can keep recording video with the screen off.", "record"),
            t("capture_dual", Wing.CAPTURE, "Capture can film with the front and back cameras at the same time.", "record"),
            t("capture_remote", Wing.CAPTURE, "you can control Capture from another device on the same Wi-Fi.", "record"),
            // ── Library and your files ──
            t("library_everything", Wing.LIBRARY, "the Library shows everything you've made in one place: projects, drawings, sprite sheets, recordings and exports.", "library"),
            t("library_folder", Wing.LIBRARY, "your work lives in your own Joy Creator folder, as files you can see, so nothing is lost if the app goes.", "library"),
            t("library_share_in", Wing.LIBRARY, "you can share pictures and videos into Joy Creator from any other app, and drop them straight onto your timeline.", "library"),
            t("library_trash", Wing.LIBRARY, "deleted things wait in Recently deleted, so you can bring them back.", "trash"),
            t("library_zip", Wing.LIBRARY, "a whole project can be zipped up with all its pictures, sounds and fonts, and opened on another phone.", "library"),
            // ── Joybot himself ──
            t("joybot_offline", Wing.JOYBOT, "most of my buttons work with no internet and no AI at all: transcribing, cutting ums, captions, transitions and slides.", null),
            t("joybot_recipes", Wing.JOYBOT, "after I do something for you, Save as a recipe lets you run it again on any project in one tap.", null),
            t("joybot_plan", Wing.JOYBOT, "for a big job I show you a short plan first, and afterwards a list of every change with Undo from here.", null),
            t("joybot_one_undo", Wing.JOYBOT, "everything I do for one request is a single Undo.", null),
            t("joybot_locked", Wing.JOYBOT, "if you lock something, I won't change it unless you tell me to unlock it.", null),
            t("joybot_how", Wing.JOYBOT, "you can ask me “how do I…” about anything in the app, and I'll show you the way.", null)));

    /**
     * One tip, leaning toward the wings this person uses ({@code uses}: any counts per wing; empty
     * = no idea yet, so every wing alike). About one time in four it's from a wing they haven't
     * touched, so they find it. Tips already seen are skipped until every tip has been seen.
     */
    @NonNull
    public static Tip pick(@NonNull Map<Wing, Integer> uses, @NonNull Set<String> seen, @NonNull Random rnd) {
        List<Tip> fresh = new ArrayList<>();
        for (Tip x : TIPS) if (!seen.contains(x.id)) fresh.add(x);
        if (fresh.isEmpty()) fresh = new ArrayList<>(TIPS);

        int total = 0;
        for (Integer n : uses.values()) total += Math.max(0, n == null ? 0 : n);
        List<Tip> unused = new ArrayList<>();
        for (Tip x : fresh) {
            Integer n = uses.get(x.wing);
            if (x.wing != Wing.JOYBOT && (n == null || n <= 0)) unused.add(x);
        }
        if (total > 0 && !unused.isEmpty() && rnd.nextInt(4) == 0) {
            return unused.get(rnd.nextInt(unused.size()));
        }
        // Weight: 1 for every tip, plus up to 8 by the wing's share of what they make.
        double[] w = new double[fresh.size()];
        double sum = 0;
        for (int i = 0; i < fresh.size(); i++) {
            Tip x = fresh.get(i);
            Integer n = uses.get(x.wing);
            double share = total == 0 ? 0 : Math.max(0, n == null ? 0 : n) / (double) total;
            w[i] = 1 + 8 * share + (x.wing == Wing.JOYBOT ? 0.5 : 0);
            sum += w[i];
        }
        double r = rnd.nextDouble() * sum;
        for (int i = 0; i < fresh.size(); i++) {
            r -= w[i];
            if (r <= 0) return fresh.get(i);
        }
        return fresh.get(fresh.size() - 1);
    }

    /** "Did you know …" as Joybot says it. */
    @NonNull
    public static String said(@NonNull Tip tip) {
        return "Did you know " + tip.text;
    }

    /**
     * The no-internet message, ending on what still works (owner, 10-28: "it's not a world-ending
     * thing"): what Joybot does offline, then a tip.
     */
    @NonNull
    public static String offlineHope(@NonNull String problem, @NonNull Tip tip) {
        return problem + "\n\nBut I'm still right here. With no internet I can transcribe, cut the ums and "
                + "silences, add captions and transitions, make slides, and answer “how do I…”.\n\n"
                + said(tip);
    }
}
