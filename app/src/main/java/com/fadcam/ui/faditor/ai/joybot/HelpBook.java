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
        /** Where "Take me there" goes ("studio", "record", "library", "setup", "joybrush", "spritelab", "avatar", "space"), or null. */
        @Nullable public final String route;
        /** An idea key Joybot can run for them ("transcribe"…), a flow ("slide", "picture"), or null. */
        @Nullable public final String doIt;
        /**
         * The FaditorToolRegistry id of the Studio tool-strip button this answer is about ("speed",
         * "sticker"…), or null. "Show me how" points at that button so the person taps it themselves.
         */
        @Nullable public final String tool;

        Topic(@NonNull String id, @NonNull String keys, @NonNull String answer,
              @Nullable String route, @Nullable String doIt) {
            this(id, keys, answer, route, doIt, null);
        }

        Topic(@NonNull String id, @NonNull String keys, @NonNull String answer,
              @Nullable String route, @Nullable String doIt, @Nullable String tool) {
            this.id = id;
            this.keys = keys.split("\\|");
            this.answer = answer;
            this.route = route;
            this.doIt = doIt;
            this.tool = tool;
        }
    }

    private static final String STRIP = "the Studio's tool strip (tap “more” at its end to see every tool)";

    public static final List<Topic> TOPICS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Topic("transcribe", "transcri|subtitle|speech to text|words of",
                    "Select a clip in the Studio and tap Transcript in " + STRIP + ". It runs on the phone, "
                            + "no internet needed after the one-time model download. Or I can do it for you.",
                    "studio", "transcribe", "transcript"),
            new Topic("captions", "caption",
                    "Tap Captions in " + STRIP + ". Captions need a transcript first — I can do both in one go.",
                    "studio", "captions_new", "captions"),
            new Topic("ums", "um |ums|filler|uh |stutter",
                    "Open Transcript and tap Clean fillers, or just ask me to cut the ums.",
                    "studio", "cut_ums", "transcript"),
            new Topic("silence", "silen|quiet part|dead air|pause|gap",
                    "Tap Clean in " + STRIP + " to find the quiet stretches and cut them. Each cut can be tapped back.",
                    "studio", "silences", "silence"),
            new Topic("split", "split|cut a clip|cut the clip|cut in two|chop",
                    "Move the playhead to where you want the cut and tap Split in the tool strip.",
                    "studio", null, "split"),
            new Topic("delete", "delete|remove a clip|get rid of",
                    "Select the clip and tap Delete in the tool strip. Undo (top bar) brings it back.",
                    "studio", null, "delete"),
            new Topic("duplicate", "duplicate|copy a clip|copy the clip",
                    "Select the clip and tap Duplicate in the tool strip.", "studio", null, "duplicate"),
            new Topic("speed", "speed|slow mo|slow-mo|faster|slower|timelapse",
                    "Select the clip and tap the speed button in the tool strip (it shows 1x).", "studio", null, "speed"),
            new Topic("volume", "volume|louder|quieter|mute|sound level",
                    "Select the clip and tap Sound in the tool strip for volume and mute. Or tell me, e.g. "
                            + "“make the music quieter”.", "studio", null, "mute"),
            new Topic("fixaudio", "fix audio|clean audio|noise|hiss|echo|bad audio",
                    "Select the clip and tap Fix audio in " + STRIP + ".", "studio", null, "fix_audio"),
            new Topic("text", "text|title text|words on screen|lower third",
                    "Tap Text in the tool strip. For an animated title card, ask me to make a slide.",
                    "studio", null, "text"),
            new Topic("image", "image|picture|photo|logo|sticker|overlay",
                    "Tap Image in the tool strip, or Add → Image as new layer. I can also make a picture for you.",
                    "studio", "picture", "sticker"),
            new Topic("add", "add a clip|add video|add audio|music|voiceover|voice over|black clip|pip|picture in picture|import",
                    "Tap Add in the tool strip: Video clip, Image as new layer, Black clip, Video overlay (PiP), "
                            + "Audio, Record voiceover, AI slide and more. From another app, use Share → Joy Creator.",
                    "studio", null, "add_asset"),
            new Topic("transitions", "transition|crossfade|fade between|wipe",
                    "Tap Transitions in the tool strip. Or I can put a gentle crossfade on every cut.",
                    "studio", "transitions", "transitions"),
            new Topic("visualizer", "visuali|waveform|spectrum|bars that move|audio bars",
                    "Tap Visualizer in " + STRIP + ". On a music project I can add one for you.",
                    "studio", "visualizer", "visualizer"),
            new Topic("crop", "crop|vertical|portrait|9:16|16:9|aspect|tiktok|reel|square",
                    "Crop trims the picture; Canvas changes the video's shape (9:16, 16:9, square…). "
                            + "Both are in the tool strip.", "studio", null, "crop"),
            new Topic("rotate", "rotate|flip|mirror|upside",
                    "Rotate and Flip are in the tool strip.", "studio", null, "rotate"),
            new Topic("opacity", "opacity|transparen|see through|fade a clip",
                    "Opacity is in the tool strip.", "studio", null, "opacity"),
            new Topic("effects", "filter|color|colour|effect|fx|adjust|grade|blur",
                    "Filter is in the tool strip. Adjust opens the effects for whatever is selected. For a layer that "
                            + "grades everything below it, tap Add, then FX Adjustment Layer.",
                    "studio", null, "filter"),
            new Topic("beats", "beat|rhythm|on the beat",
                    "Beats in " + STRIP + " finds the beat of the music.", "studio", null, "beats"),
            new Topic("loop", "loop|repeat",
                    "Select the clip and tap Loop in the tool strip.", "studio", null, "loop"),
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
                            + "tap Sprites in the Studio's tool strip to place one on your video.", "spritelab", null, "sprites"),
            new Topic("joybrush", "joybrush|joy brush|draw|paint|sketch|illustrat|brush",
                    "JoyBrush is the painting and animation wing. Open it from the lobby's Joy Brush room, "
                            + "and your drawings open from its library.", "joybrush", null),
            new Topic("avatar", "avatar|puppet|vtuber|face track|lip sync",
                    "Avatar Studio turns a drawn character into a puppet that follows your face and voice. "
                            + "Open it from the lobby (it needs a project).", "avatar", null),
            new Topic("record", "record|screen record|camera|film|capture",
                    "Capture records your screen or your camera. Recordings land in the Library.",
                    "record", null),
            new Topic("library", "library|recordings|my videos|find my|where are my",
                    "The Library has your recordings and exports. To edit a recording, tap Add in the Studio, "
                            + "then Video clip.",
                    "library", null),
            new Topic("share", "share to|share in|from chatgpt|from gemini|from another app|from gallery",
                    "In any app, tap Share → Joy Creator. It's saved in your Joy Creator folder and, if a "
                            + "project is open, dropped at the playhead.", null, null),
            new Topic("folder", "folder|storage|where are my files|backup|sd card",
                    "Everything lives in your Joy Creator folder, a folder you chose — so nothing is lost if "
                            + "the app is uninstalled. Change it in Setup → Storage Location.", "setup", null),
            new Topic("space", "free up|free some|free space|how much space|space left|clear cache|clear the cache"
                            + "|storage full|out of space|takes room|biggest files|room on",
                    "Setup → Storage Location has a Space row. It shows what takes room on the phone, lets you clear the "
                            + "app's rebuildable caches, and lists your biggest files so an unused one can go to Trash.",
                    "space", null),
            new Topic("trash", "trash|deleted|bin|recover",
                    "Hold the Joy Creator logo at the top of the lobby to open the Trash.", "trash", null),
            new Topic("ai", "api key|key|openrouter|model|ai setting|connect|free ai",
                    "Tap ⚙ at the top of this chat. Paste an OpenRouter key (the free model costs nothing), "
                            + "or the address of any OpenAI-compatible service. More than one subscription? Tap the "
                            + "model name at the top → Add another AI account, then switch between them there.", null, null),
            new Topic("joybot", "what can you do|who are you|help me|what do you do",
                    "Right here on the phone I can transcribe, add captions, cut ums and silences, add "
                            + "transitions and visualizers, make slides, and answer “how do I”. With an AI "
                            + "connected I can take on anything you describe, look at pictures, and make new ones.",
                    null, null),

            // ── Wing help (G3, 2026-10-15): every answer names a control confirmed in the code or strings ──
            new Topic("sprite_open", "open sprite|sprite lab|new sprite sheet|make a sprite sheet",
                    "Open Sprite Lab from the lobby's Sprite Lab room. Or in the Studio, tap Sprites in the tool strip, "
                            + "then “+ New sprite sheet”. Sprite Lab works inside a project, so open one first.",
                    "spritelab", null, "sprites"),
            new Topic("sprite_slice", "slice my sprite sheet|slice a sprite sheet|slice the sprite sheet|slice the sheet"
                            + "|slice my sheet|slice|cut up the sheet|cut the sheet|split the sheet|sprite grid"
                            + "|grid on the sheet|find the grid|detect the grid|set the grid",
                    "In Sprite Lab, open Grid & slicing and tap Detect to find the grid. If it guesses wrong, set the "
                            + "rows and columns with the steppers. Drag with Pan, Pivot, Swap or Ripple to move cells.",
                    "spritelab", null),
            new Topic("sprite_names", "name the cells in|name cells in|name the cells|name cells|name each cell"
                            + "|name the cell|label the cells|label each cell|cell names|name the frames|expression",
                    "In Sprite Lab, open Grid & slicing and tap Name many… to name cells one after another. "
                            + "Tap one cell to name just that one.",
                    "spritelab", null),
            new Topic("sprite_animation", "make an animation|make a sprite animation|sprite animation|animate the sprite"
                            + "|animate a sprite|save an animation|sequence of frames|play the frames",
                    "In Sprite Lab, open the Sequence section and tap cells in the order you want them. Use Hold + and "
                            + "Hold − for pauses, then Play all to check it. Tap Save clip and name it to keep the animation.",
                    "spritelab", null),
            new Topic("sprite_onion", "onion skin on|onion on my sprite|onion on the sprite|onion on a sprite"
                            + "|onion skin|onion|ghost the frames|ghost frames|tracing paper|see the frame before",
                    "In Sprite Lab, turn on Onion in the bar under the preview to see faint frames before and after the "
                            + "current one. Slide the past and future number pills to choose how many, and tap a pill to pick its colour.",
                    "spritelab", null),
            new Topic("sprite_bake", "bake a clean sprite sheet|bake a sprite sheet|bake my sprite sheet"
                            + "|bake the sprite sheet|bake a clean sheet|bake a new sheet|bake the sheet|bake|clean sheet"
                            + "|flatten the sheet",
                    "In Sprite Lab, open Bake a new sheet and pick Fit to art, Keep cell size or Fill. Choose PNG or JPG, "
                            + "then tap Bake sheet + JSON. The original sheet is never changed.",
                    "spritelab", null),
            new Topic("sprite_export", "sprite json|export a sprite|export the sprite|export sprite|export my sprite"
                            + "|sprite file|sidecar|save the sprite",
                    "In Sprite Lab, open the Export section and tap Write .sprite.json. The file is saved next to the "
                            + "sheet image, and the JSON shows on screen with a Copy button.",
                    "spritelab", null),
            new Topic("sprite_place", "place a sprite|place the sprite|place my sprite|put a sprite|put the sprite"
                            + "|sprite on my video|sprite on the video|sprite onto",
                    "Tap Sprites in the Studio's tool strip, pick your sheet, and tap Place on video. "
                            + "Then drag the sprite on the preview.", "studio", null, "sprites"),
            new Topic("joybrush_open", "open a drawing|open my drawing|my drawings|drawing library|saved drawing"
                            + "|open joy brush|open joybrush",
                    "Open Joy Brush from the lobby, then tap a drawing in its library to open it. Your drawings also sit "
                            + "in your Joy Creator folder, with thumbnails.", "joybrush", null),
            new Topic("record_screen", "screen recording|record my screen|record the screen|record a screen|record my phone",
                    "Open Capture from the lobby, pick Screen beside Camera and Mic, then start. Stop it from the "
                            + "notification or the floating quick menu.", "record", null),
            new Topic("record_webcam", "webcam bubble|webcam overlay|camera bubble|webcam|face cam|facecam|show my face|bubble",
                    "While you record your screen, open the floating quick menu and tap Webcam overlay to turn your "
                            + "camera bubble On or Off.", "record", null),
            new Topic("record_background", "screen off|background recording|record in background|keep recording"
                            + "|phone in my pocket|lock the screen",
                    "Camera recordings keep going with the screen off, so you can put the phone away. "
                            + "Start one in Capture as usual.", "record", null),
            new Topic("library_open_studio", "edit a recording|edit my recording|edit a video|edit my video"
                            + "|open a recording|open my recording|recording in the studio|recording into the studio",
                    "In the Studio, tap Add, then Video clip, and pick the recording from Your Recordings.",
                    "studio", null, "add_asset"),
            new Topic("library_exports", "where is my export|where did my export|my exports|exported video|exported file"
                            + "|find my export|export file",
                    "Recordings and exports both show in the Library. An export is also saved in your Joy Creator folder.",
                    "library", null),
            new Topic("trash_restore", "restore a|restore something|restore my|restore|put back|bring back"
                            + "|recently deleted|get back|undo delete|undo a delete",
                    "Open Setup, then Storage Location, and tap Recently deleted. Pick what you want back and tap restore.",
                    "setup", null),
            new Topic("chroma", "green screen|greenscreen|chroma|blue screen|key out|remove the green",
                    "Double-tap a picture or video overlay, open its Chroma key tab, and turn on Key out a colour. "
                            + "Tap the colour to remove, then adjust Tolerance and Edge softness until the edges look clean.",
                    "studio", null),
            new Topic("mask", "mask|spotlight|show only inside|shape over|cut out a shape|circle around",
                    "Double-tap a picture or video overlay and open its Mask tab. Set Center X, Center Y, Width and Height, "
                            + "and turn on Show only inside the box to hide everything outside it. On a picture, the mask "
                            + "is applied when you export.",
                    "studio", null),
            new Topic("puppet", "puppet|bend a picture|bend the picture|bend my picture|bend a photo|rig a picture"
                            + "|rig a character|pins on|make a character move|move a character",
                    "Double-tap a picture and open its Puppet tab. Drag pins onto the picture's joints, then drag a pin to "
                            + "bend it. Start over, near the bottom of the tab, clears the rig, and one undo brings it back.",
                    "studio", null),
            new Topic("text_animation", "text animation|animate text|animate the text|animated text|title animation"
                            + "|animate a title|text motion",
                    "Double-tap the title, open its Text tab, and pick an Animation under Style. Text motion range sets "
                            + "when the animation starts and ends.", "studio", null),
            new Topic("text_font", "text's font|text font|texts font|change the font|change a font|font style"
                            + "|choose a font|pick a font|import a font|typeface|new font|different font",
                    "Double-tap the title, then tap the font name at the top of its Text drawer to pick a font. "
                            + "Import font… adds one of your own.", "studio", null),
            new Topic("voiceover", "record a voiceover|record voiceover|record my voice|record voice|narrate|narration"
                            + "|talk over the video",
                    "Tap Add in the tool strip, then Record voiceover. The video plays while you talk, so your words "
                            + "line up with the picture.", "studio", null, "add_asset"),
            new Topic("extract_audio", "extract audio|extract the audio|separate the audio|split the audio"
                            + "|audio from the video|audio from a video|take the sound out|pull the sound out",
                    "Select the video clip and tap Extract audio in the tool strip to split its sound onto its own track.",
                    "studio", null, "audio"),
            new Topic("blend", "blend mode|blend|blending|multiply|screen mode|overlay mode",
                    "Double-tap a picture or video overlay (hold a visualizer), open its Blend tab, and pick a mode such as "
                            + "Multiply or Screen. Pictures and visualizers show their blend in the preview too; a video "
                            + "overlay's blend shows in the export.", "studio", null),
            new Topic("fx_layer", "adjustment layer|fx layer|fx adjustment|effect over everything|effects over everything"
                            + "|effect on everything|effects on everything|grade everything|adjust everything",
                    "Tap Add in the tool strip, then FX Adjustment Layer. It grades everything beneath it, and its drawer "
                            + "has Effects, Mask and Blend tabs.", "studio", null, "add_asset"),
            new Topic("share_add", "add to timeline|drop it on the timeline|onto the timeline|shared clip|shared video"
                            + "|shared picture|after sharing",
                    "After you share a clip or picture into Joy Creator, tap Add to on the share screen to drop it at "
                            + "the playhead on your timeline. It is saved in your Joy Creator folder either way.",
                    null, null),
            new Topic("recipes", "recipe|routine|do it again|every time",
                    "After I do something for you, tap Save as a recipe. Then My recipes runs it again in one tap, "
                            + "on any project. Recipes are kept as small files in your Joy Creator folder, under "
                            + "Joybot/recipes.", null, null),
            new Topic("hands_free", "hands free|hands-free|voice only|without touching|talk to you",
                    "Hold the mic, say what you want and stop talking — it sends itself. I'll read my answer "
                            + "out and listen for 'yes', 'undo' or 'stop'.", null, null)));

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

    /**
     * The reply says plainly that it can't be done, or that nothing was changed: an honest no.
     * "I can't draw on the video itself, so I haven't changed anything" is not a claim of a change
     * (its "changed" fooled the word check) and must not be pushed to "make it now" (bench, 10-24).
     */
    public static boolean saysItCant(@NonNull String text) {
        String t = text.toLowerCase(Locale.US).replace('’', '\'');
        return t.matches("(?s).*\\b(can't|cannot|can not|unable to|not able to|isn't possible|is not possible"
                + "|not possible|no tool|there's no way|there is no way|doesn't let me|does not let me"
                + "|haven't changed|have not changed|didn't change|did not change|nothing (has |was )?changed"
                + "|left it|won't be able|i'm not able)\\b.*");
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
