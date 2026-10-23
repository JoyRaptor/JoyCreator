package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which of Joybot's tools a request is sent with. Pure Java (tested in IdeaEngineTest).
 *
 * <p>Measured on the Note 9 (2026-10-16): every round sent all ~100 tool definitions — 75k of a
 * 90k-character request, ~22k tokens, for "how long is my video". Slow on free models, costly on
 * paid ones. Now a request carries the {@link #CORE} family (reading, looking, planning, rooms)
 * plus the families its words point to; {@code more_tools} lets the model load any other family
 * mid-request. A tool not sent can still run if called — the router only decides what is
 * advertised, never what is allowed.</p>
 */
public final class ToolRouter {
    private ToolRouter() { }

    public static final String CORE = "core";
    /** The meta-tool that loads another family for the rest of the request. */
    public static final String MORE_TOOLS = "more_tools";

    /** Family → what it covers (shown to the model in more_tools). Order = how they're listed. */
    public static final Map<String, String> FAMILIES;
    private static final Map<String, Pattern> WORDS = new LinkedHashMap<>();

    static {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("clips", "the video clips: split, delete, trim, speed, mute, move, crop, rotate, flip, loop, zoom");
        f.put("transcript", "what's said: transcribe, words, ums and fillers, silences, chapters, fix words");
        f.put("text", "text layers: add, words, font, colour, outline, shadow, glow, text animation");
        f.put("captions", "captions / subtitles: on, off, style, look");
        f.put("audio", "sound: volume, fades, music under the voice (ducking), fix audio, visualizers");
        f.put("transitions", "transitions between clips");
        f.put("slides", "AI title slides: make, read, rewrite");
        f.put("media", "pictures and video to add: B-roll, the Library's media, relinking missing files");
        f.put("motion", "movement over time: keyframes for position, size, rotation, opacity");
        f.put("looks", "how things look: colour grade, LUTs, blend modes, green screen, position and size of layers");
        f.put("organise", "layers: order, duplicate, hide, lock, link, markers");
        f.put("sprites", "sprite sheets: grid, cell names, animations, sequences, placing a sprite on the video");
        f.put("avatar", "avatar puppet rigs");
        f.put("story", "the story: structure, best parts, B-roll and chapter proposals");
        f.put("project", "the whole project: export, canvas shape, health check");
        f.put("vislab", "VisLab, the music visualiser room: its looks, their layers and knobs, music links, brand colours");
        f.put("joybrush", "JoyBrush, the drawing room: the open drawing's layers (add, rename, hide, reorder), animation frames, brush and colour, drawing simple shapes");
        FAMILIES = Collections.unmodifiableMap(f);

        w("clips", "clips?|cut|split|trim|speed|faster|slower|slow( it)?|mute|unmute|crop|rotate|flip|mirror|loop|"
                + "zoom|delete|remove the|freeze|pitch|reorder|shorten|lengthen|first|second|last");
        w("transcript", "transcri\\w*|words?|said|says|saying|ums?|uhs?|filler\\w*|silen\\w*|pauses?|chapters?|"
                + "enhance|spoken|talk\\w*");
        w("text", "text|title|font|typeface|bold|italic|outline|shadow|glow|letters?|headline|label|wording");
        w("captions", "captions?|subtitles?|cc");
        w("audio", "audio|sound|music|song|volume|loud\\w*|quiet\\w*|duck\\w*|fades?|fading|voice|noise|"
                + "visuali[sz]er|bass|mix|echo");
        w("transitions", "transitions?|dissolve|wipe|crossfade|between( the)? clips");
        w("slides", "slides?|title card|intro|outro|credits|end card|opening");
        w("media", "b-?roll|pictures?|photos?|images?|stock|media|footage|relink|missing|library|recordings?");
        w("motion", "motion|animat\\w*|keyframes?|spin|bounce|fly|slide (it )?in|zoom (it )?in|zoom out|"
                + "move\\w* .*(over|across|from|to)|grow|shrink");
        w("looks", "looks?|colou?r grade|grade|lut|filter|blend|chroma|green ?screen|key out|mask|warm\\w*|cool\\w*|"
                + "bright\\w*|contrast|saturat\\w*|vintage|cinematic|bigger|smaller|position|corner");
        w("organise", "layers?|order|in front|behind|bring .* front|send .* back|duplicate|copy of|lock|hide|"
                + "unhide|markers?|bookmark|link|group");
        w("sprites", "sprites?|sprite sheet|sheet|cells?|frames?|walk cycle|character|expressions?");
        w("avatar", "avatar|puppet|rig");
        w("story", "story|narrative|structure|best (part|bits|moments)|highlights?|b-?roll|chapters?");
        w("vislab", "vislab|vis lab|visuali[sz]er looks?|music visuali[sz]er");
        w("joybrush", "joy ?brush|draw\\w*|paint\\w*|brush\\w*|sketch\\w*|strokes?|canvas|doodle|circle|rectangle|ellipse");
        w("project", "export|render|save|share|post|upload|health|broken|check|canvas|aspect|vertical|"
                + "square|landscape|portrait|tiktok|reels|youtube");
    }

    private static void w(@NonNull String family, @NonNull String words) {
        WORDS.put(family, Pattern.compile("\\b(" + words + ")\\b"));
    }

    /**
     * The families to send for this request: always {@link #CORE}; the families its words name;
     * and, when they name none, the everyday set (clips, text, audio) so a vague request still
     * has hands.
     */
    @NonNull
    public static Set<String> pick(@NonNull String request) {
        return pick(request, false);
    }

    /**
     * As {@link #pick(String)}; asked from inside VisLab, the request is about the look on screen:
     * core + vislab only (more_tools reaches the rest).
     */
    @NonNull
    public static Set<String> pick(@NonNull String request, boolean inVisLab) {
        return pick(request, inVisLab, false);
    }

    /** As above; asked from inside JoyBrush, the request is about the open drawing: core + joybrush only. */
    @NonNull
    public static Set<String> pick(@NonNull String request, boolean inVisLab, boolean inJoyBrush) {
        String t = request.toLowerCase(Locale.US);
        Set<String> out = new LinkedHashSet<>();
        out.add(CORE);
        if (inJoyBrush) {
            // "layers", "frames", "brush" mean the drawing here, not the Studio's tools of those names.
            out.add("joybrush");
            return out;
        }
        if (inVisLab) {
            // "brighter", "echo", "bass" mean the look here, not the Studio's tools of those names.
            out.add("vislab");
            return out;
        }
        for (Map.Entry<String, Pattern> e : WORDS.entrySet()) {
            if (e.getValue().matcher(t).find()) out.add(e.getKey());
        }
        if (out.size() == 1) {
            out.add("clips");
            out.add("text");
            out.add("audio");
        }
        return out;
    }

    /** Typed actions: their registry family → the router's. */
    private static final Map<String, String> SPEC_FAMILY = new HashMap<>();
    /** The older hand-written tools, by name. Anything not listed is CORE (sent always: safe). */
    private static final Map<String, String> LEGACY = new HashMap<>();

    static {
        String[][] spec = {
                {"audio", "audio"}, {"visualizer", "audio"}, {"captions", "captions"}, {"care", "project"},
                {"export", "project"}, {"inspect", CORE}, {"library", CORE}, {"navigate", CORE},
                {"looks", "looks"}, {"transform", "looks"}, {"motion", "motion"}, {"organise", "organise"},
                {"slides", "slides"}, {"sources", "media"}, {"transitions", "transitions"},
                {"text_look", "text"}, {"clip_shape", "clips"}, {"sprite_video", "sprites"}, {"director", CORE}, {"vislab", "vislab"},
                {"joybrush", "joybrush"}};
        for (String[] p : spec) SPEC_FAMILY.put(p[0], p[1]);

        legacy("transcript", "generate_transcript", "detect_silence", "get_transcript", "correct_transcript",
                "retime_words", "synthesize_transcript", "cut_all_fillers", "merge_transcripts",
                "ai_merge_transcript", "ai_enhance", "remove_span", "remove_silence", "auto_chapters");
        legacy("clips", "split_clip", "delete_clip", "set_clip_speed", "set_clip_muted", "set_clip_zoom",
                "auto_zoom", "move_clip_to", "reorder_clips_by_name", "rename_clip", "describe_clip",
                "apply_edit_script");
        legacy("text", "add_text_overlay", "remove_text_overlay", "resize_overlay", "add_opacity_keyframe");
        legacy("captions", "toggle_captions", "set_caption_style");
        legacy("audio", "set_audio_volume", "set_audio_fade", "fix_audio", "set_fades", "add_visualizer");
        legacy("transitions", "add_transition", "remove_transitions");
        legacy("slides", "generate_slide");
        legacy("media", "list_broll", "add_broll_overlay", "tag_broll_assets", "rename_asset");
        legacy("story", "analyze_narrative_structure", "apply_narrative_proposal", "suggest_broll_placements",
                "apply_broll_proposal");
        legacy("sprites", "describe_sprite_sheet", "set_sprite_grid", "label_sprite_cells",
                "author_sprite_animation", "apply_sprite_proposal", "describe_sequence", "edit_sequence");
        legacy("avatar", "author_avatar_rig", "apply_avatar_rig");
        legacy("project", "set_canvas_preset", "export_project", "health_check");
    }

    private static void legacy(@NonNull String family, @NonNull String... tools) {
        for (String t : tools) LEGACY.put(t, family);
    }

    /** The router family of a tool: from its registry family when typed, else by name. */
    @NonNull
    public static String familyOf(@NonNull String tool, @Nullable String specFamily) {
        if (specFamily != null) {
            String f = SPEC_FAMILY.get(specFamily);
            if (f != null) return f;
        }
        String f = LEGACY.get(tool);
        return f != null ? f : CORE;
    }

    /** The more_tools definition (JSON text) — the family names and what each covers. */
    @NonNull
    public static String moreToolsJson() {
        StringBuilder enumJson = new StringBuilder();
        StringBuilder desc = new StringBuilder("Load more of your tools for the rest of this request. You were"
                + " given only the tools this request seemed to need; if the job needs another kind, call this"
                + " with that family, then use its tools. Families: ");
        int i = 0;
        for (Map.Entry<String, String> e : FAMILIES.entrySet()) {
            enumJson.append(i++ > 0 ? "," : "").append('"').append(e.getKey()).append('"');
            desc.append(e.getKey()).append(" (").append(e.getValue()).append("); ");
        }
        return "{\"type\":\"function\",\"function\":{\"name\":\"" + MORE_TOOLS + "\",\"description\":\""
                + desc.toString().trim().replace("\"", "'") + "\",\"parameters\":{\"type\":\"object\",\"properties\":"
                + "{\"family\":{\"type\":\"string\",\"enum\":[" + enumJson + "],\"description\":\"Which kind of"
                + " tools to load\"}},\"required\":[\"family\"],\"additionalProperties\":false}}}";
    }
}
