package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "How do I…?" for the whole studio, answered on the phone with no AI.
 *
 * <p>Every answer names controls by the words on screen. The Studio's tool-strip labels come from
 * {@code FaditorToolRegistry} and the Add sheet's from {@code AddAssetBottomSheet} (read
 * 2026-10-14); wing entry points from tasks/joybot/WINGS_INVENTORY_20261014.md. When a label
 * changes there, change it here — a help answer that names a button that isn't there is worse
 * than no answer.</p>
 *
 * <p>Each topic can carry a way to act: a {@code route} (take me there) and/or an {@code ideaKey}
 * (Joybot can just do it). Pure Java so the matching is tested off-device.</p>
 */
public final class HelpBook {

    private HelpBook() {}

    public static final class Topic {
        @NonNull public final String id;
        @NonNull final String[] keys;
        @NonNull public final String answer;
        /** Where "Take me there" goes ("studio", "record", "library", "setup", "joybrush"), or null. */
        @Nullable public final String route;
        /** An idea key Joybot can run for them ("transcribe"…), a flow ("slide", "picture"), or null. */
        @Nullable public final String doIt;

        Topic(@NonNull String id, @NonNull String keys, @NonNull String answer,
              @Nullable String route, @Nullable String doIt) {
            this.id = id;
            this.keys = keys.split("\\|");
            this.answer = answer;
            this.route = route;
            this.doIt = doIt;
        }
    }

    private static final String STRIP = "the Studio's tool strip (tap “more” at its end to see every tool)";

    public static final List<Topic> TOPICS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Topic("transcribe", "transcri|subtitle|speech to text|words of",
                    "Select a clip in the Studio and tap Transcript in " + STRIP + ". It runs on the phone, "
                            + "no internet needed after the one-time model download. Or I can do it for you.",
                    "studio", "transcribe"),
            new Topic("captions", "caption",
                    "Tap Captions in " + STRIP + ". Captions need a transcript first — I can do both in one go.",
                    "studio", "captions_new"),
            new Topic("ums", "um |ums|filler|uh |stutter",
                    "Open Transcript and tap Clean fillers, or just ask me to cut the ums.",
                    "studio", "cut_ums"),
            new Topic("silence", "silen|quiet part|dead air|pause|gap",
                    "Tap Clean in " + STRIP + " to find the quiet stretches and cut them. Each cut can be tapped back.",
                    "studio", "silences"),
            new Topic("split", "split|cut a clip|cut the clip|cut in two|chop",
                    "Move the playhead to where you want the cut and tap Split in the tool strip.",
                    "studio", null),
            new Topic("delete", "delete|remove a clip|get rid of",
                    "Select the clip and tap Delete in the tool strip. Undo (top bar) brings it back.",
                    "studio", null),
            new Topic("duplicate", "duplicate|copy a clip|copy the clip",
                    "Select the clip and tap Duplicate in the tool strip.", "studio", null),
            new Topic("speed", "speed|slow mo|slow-mo|faster|slower|timelapse",
                    "Select the clip and tap the speed button in the tool strip (it shows 1x).", "studio", null),
            new Topic("volume", "volume|louder|quieter|mute|sound level",
                    "Select the clip and tap Sound in the tool strip for volume and mute. Or tell me, e.g. "
                            + "“make the music quieter”.", "studio", null),
            new Topic("fixaudio", "fix audio|clean audio|noise|hiss|echo|bad audio",
                    "Select the clip and tap Fix audio in " + STRIP + ".", "studio", null),
            new Topic("text", "text|title text|words on screen|lower third",
                    "Tap Text in the tool strip. For an animated title card, ask me to make a slide.",
                    "studio", null),
            new Topic("image", "image|picture|photo|logo|sticker|overlay",
                    "Tap Image in the tool strip, or Add → Image as new layer. I can also make a picture for you.",
                    "studio", "picture"),
            new Topic("add", "add a clip|add video|add audio|music|voiceover|voice over|black clip|pip|picture in picture|import",
                    "Tap Add in the tool strip: Video clip, Image as new layer, Black clip, Video overlay (PiP), "
                            + "Audio, Record voiceover, AI slide and more. From another app, use Share → Joy Creator.",
                    "studio", null),
            new Topic("transitions", "transition|crossfade|fade between|wipe",
                    "Tap Transitions in the tool strip. Or I can put a gentle crossfade on every cut.",
                    "studio", "transitions"),
            new Topic("visualizer", "visuali|waveform|spectrum|bars that move|audio bars",
                    "Tap Visualizer in " + STRIP + ". On a music project I can add one for you.",
                    "studio", "visualizer"),
            new Topic("crop", "crop|vertical|portrait|9:16|16:9|aspect|tiktok|reel|square",
                    "Crop trims the picture; Canvas changes the video's shape (9:16, 16:9, square…). "
                            + "Both are in the tool strip.", "studio", null),
            new Topic("rotate", "rotate|flip|mirror|upside",
                    "Rotate and Flip are in the tool strip.", "studio", null),
            new Topic("opacity", "opacity|transparen|see through|fade a clip",
                    "Opacity is in the tool strip.", "studio", null),
            new Topic("effects", "filter|color|colour|effect|fx|adjust|grade|blur",
                    "Filter is in the tool strip; Adjust adds an effects layer over everything below it.",
                    "studio", null),
            new Topic("beats", "beat|rhythm|on the beat",
                    "Beats in " + STRIP + " finds the beat of the music.", "studio", null),
            new Topic("loop", "loop|repeat",
                    "Select the clip and tap Loop in the tool strip.", "studio", null),
            new Topic("keyframes", "keyframe|animate|move over time|motion",
                    "Settings that can move over time show a ◆: move the playhead, tap ◆ to pin "
                            + "the value there, then pin another value at a different time.",
                    "studio", null),
            new Topic("slides", "slide|title card|intro card|chapter card|credits|end card",
                    "I can make an animated slide: title, chapter, quote, list or credits, in five moods. "
                            + "Or use Add → AI slide.", "studio", "slide"),
            new Topic("export", "export|save the video|render|share the video|post|upload",
                    "Tap Export at the top right of the Studio, next to me. Exports land in your "
                            + "Joy Creator folder.", "studio", null),
            new Topic("undo", "undo|go back|mistake|redo|history",
                    "The undo arrow is in the Studio's top bar; hold it to see the history. Everything I do is "
                            + "one undo step — or just tell me “undo that”.", "studio", null),
            new Topic("sprites", "sprite|spritelab|sprite sheet|walk cycle|character animation",
                    "SpriteLab cuts a sprite sheet into named cells and animations. Open it from the lobby, or "
                            + "tap Sprites in the Studio's tool strip to place one on your video.", "studio", null),
            new Topic("joybrush", "joybrush|joy brush|draw|paint|sketch|illustrat|brush",
                    "JoyBrush is the painting and animation wing. Your drawings are in its library; "
                            + "“Send to Studio” brings a drawing into a video.", "joybrush", null),
            new Topic("avatar", "avatar|puppet|vtuber|face track|lip sync",
                    "Avatar Studio turns a drawn character into a puppet that follows your face and voice. "
                            + "Open it from the lobby (it needs a project).", null, null),
            new Topic("record", "record|screen record|camera|film|capture",
                    "Capture records your screen or your camera. Recordings land in the Library.",
                    "record", null),
            new Topic("library", "library|recordings|my videos|find my|where are my",
                    "The Library has your recordings and exports; open one in the Studio from there.",
                    "library", null),
            new Topic("share", "share to|share in|from chatgpt|from gemini|from another app|from gallery",
                    "In any app, tap Share → Joy Creator. It's saved in your Joy Creator folder and, if a "
                            + "project is open, dropped at the playhead.", null, null),
            new Topic("folder", "folder|storage|where are my files|backup|sd card",
                    "Everything lives in your Joy Creator folder, a folder you chose — so nothing is lost if "
                            + "the app is uninstalled. Change it in Setup → Storage.", "setup", null),
            new Topic("trash", "trash|deleted|bin|recover",
                    "Hold the Joy Creator logo at the top of the lobby to open the Trash.", null, null),
            new Topic("ai", "api key|key|openrouter|model|ai setting|connect|free ai",
                    "Tap ⚙ at the top of this chat. Paste an OpenRouter key (the free model costs nothing), "
                            + "or the address of any OpenAI-compatible service.", null, null),
            new Topic("joybot", "what can you do|who are you|help me|what do you do",
                    "Right here on the phone I can transcribe, add captions, cut ums and silences, add "
                            + "transitions and visualizers, make slides, and answer “how do I”. With an AI "
                            + "connected I can take on anything you describe, look at pictures, and make new ones.",
                    null, null)));

    /** The best topic for a question, or null. */
    @Nullable
    public static Topic find(@NonNull String question) {
        String q = " " + question.toLowerCase(Locale.US).replaceAll("[“”\"?!.,]", " ")
                .replaceAll("\\s+", " ") + " ";
        Topic best = null;
        int bestLen = 0;
        for (Topic t : TOPICS) {
            for (String k : t.keys) {
                // Short keys only at a word start: "ums" must not match "drums".
                boolean hit = k.length() <= 3 ? q.contains(" " + k) : q.contains(k);
                if (hit && k.length() > bestLen) {
                    best = t;
                    bestLen = k.length();
                }
            }
        }
        return best;
    }

    /** True for "how do I…", "where is…", "can I…" — a question, not an order. */
    public static boolean isQuestion(@NonNull String text) {
        String t = text.toLowerCase(Locale.US).trim();
        return t.startsWith("how") || t.startsWith("where") || t.startsWith("can i") || t.startsWith("what")
                || t.startsWith("is there") || t.startsWith("why") || t.endsWith("?")
                || t.startsWith("help");
    }

    /**
     * The person asked for the project to CHANGE ("name every cell with its expression", "fade the
     * title in") rather than to be told something. Joybot's agent uses it to notice a request that
     * ended without a change: on the Note 9 (2026-10-15) a model looked at a sprite sheet, LISTED
     * the sixteen names and stopped — nothing was named. Telling, describing and "don't change"
     * requests are never change requests.
     */
    public static boolean wantsChange(@NonNull String text) {
        String t = text.toLowerCase(Locale.US).trim();
        if (isQuestion(t)) return false;
        if (t.matches("(?s).*\\b(tell me|describe|explain|show me|list|do not|don't|dont|without changing)\\b.*")) {
            return false;
        }
        return t.matches("(?s).*\\b(name|label|rename|tag|add|make|put|set|cut|trim|remove|delete|move"
                + "|change|fade|mute|unmute|split|create|animate|build|apply|fix|replace|insert|caption"
                + "|transcribe|speed up|slow down|shorten|lengthen|crop|zoom|resize|reorder|sort|turn)\\b.*");
    }

    /** "How do I…", "where is…": a request for directions, which the book answers best. */
    public static boolean isHowTo(@NonNull String text) {
        String t = text.toLowerCase(Locale.US).trim();
        return t.startsWith("how do") || t.startsWith("how can i") || t.startsWith("how to")
                || t.startsWith("how would i") || t.startsWith("where is") || t.startsWith("where's")
                || t.startsWith("where are") || t.startsWith("where do") || t.startsWith("where can")
                || t.startsWith("what can you do") || t.startsWith("help me find");
    }

    /** A compact index for the model, so its "how do I" answers name the real buttons. */
    @NonNull
    public static String forModel() {
        StringBuilder b = new StringBuilder("HOW THE APP IS LAID OUT (use these exact names when explaining):\n");
        List<String> seen = new ArrayList<>();
        for (Topic t : TOPICS) {
            if (seen.contains(t.answer)) continue;
            seen.add(t.answer);
            b.append("- ").append(t.answer).append('\n');
        }
        return b.toString();
    }
}
