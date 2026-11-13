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
        /** Where "Take me there" goes ("studio", "record", "library", "setup", "joybrush", "spritelab", "avatar", "space", "remote", "finder"...), or null. */
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
                    "Tap Clean in " + STRIP + " to find the quiet stretches and cut them. Each cut can be tapped back. "
                            + "With a song or voice clip selected, Clean works on that clip instead: Cut gaps takes its "
                            + "quiet gaps out and closes them up, and one Undo puts it back.",
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
            new Topic("export_safety", "cancel an export|cancel the export|stop an export|stop the export"
                    + "|export stuck|export is stuck|export failed|export went wrong|export overwrote|export replaced"
                    + "|same name as|not enough space to export|phone is full|too full to export",
                    "While a video exports, Cancel stops it and leaves no half-made file. If the phone is clearly too "
                            + "full, you're told before it starts. Exporting again under the same name keeps the first "
                            + "file and saves the new one as “name 2”. If copying into your Joy Creator folder fails, "
                            + "the message says where the finished file is.", "studio", null),
            new Topic("studio_marks", "bookmark|timeline mark|drop a mark|add a mark|marker|marks and regions"
                    + "|marks & regions|a region|regions|in and out point|in point|out point"
                    + "|loop a part|loop a region|loop part of|export a region|export just part|export part of"
                    + "|export only part",
                    "In the Studio, tap the playhead time on the timeline to drop a bookmark; hold it for Marks & "
                            + "regions. There you can rename a mark, jump Previous / Next, use Set In and Set Out to "
                            + "make a region, turn Loop on to replay a region while it plays, and tick regions to "
                            + "Export just those parts (video or sound only). Every change is one Undo. Or ask me to "
                            + "drop a mark where the playhead is.", "studio", null),
            new Topic("studio_title_rename", "project title|title at the top|rename this project|rename the project here"
                    + "|change the project name|rename it in the studio|rename in the studio",
                    "Tap the project's title at the top of the Studio: Rename project opens at once (holding it works "
                            + "too).", "studio", null),
            new Topic("studio_arrange_tools", "arrange the tools|arrange my tools|rearrange the tools|rearrange tools|reorder the tools"
                    + "|reorder tools|move a tool|move the tools|pin a tool|pin my tool|tool order|tools in a different order",
                    "Tap Edit at the end of the Studio's tool row. Every tool sits on a raised pill: hold one and drag "
                            + "it to a new place, or drag it past the bar to pin it. Tap Done when you're finished. "
                            + "Tool order in the Studio's settings picks your own order or most-recent first.",
                    "studio", null),
            new Topic("studio_drawer_back", "drawer back|drawer again|reopen the drawer|drawer closed|get the drawer"
                    + "|bring back the drawer|open the drawer again",
                    "Closed a text's or picture's drawer? Tap the selected text or picture once more, on its timeline "
                            + "strip or its box in the preview, and its drawer comes back.", "studio", null),
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
                    "Say “connect an AI” and I'll walk you through it, step by step (there's a free one). "
                            + "Or tap ⚙ at the top of this chat: paste an OpenRouter key (the free model costs nothing), "
                            + "or the address of any OpenAI-compatible service. More than one subscription? Tap the "
                            + "model name at the top → Add another AI account, then switch between them there.", null, null),
            new Topic("bridge", "claude code|codex|opencode|hermes|mcp|my computer|from my computer|"
                    + "computer's ai|bridge|my pc|my mac|my laptop",
                    "Claude Code, Codex or any AI app that speaks MCP can use my tools from your computer: "
                            + "your video, VisLab, JoyBrush. Open “Connect your computer”, then say the words it "
                            + "shows to Claude Code or Codex. Your phone and computer talk over your own Wi-Fi "
                            + "(or a USB cable); nothing goes through us. Locked things stay locked, deletes wait for "
                            + "your tap here, and each burst of changes is one Undo.", "bridge", null),
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
            // ── JoyBrush, Sprite Lab and Library (BOT-2, 11-04; from STATE 5.6, 5.6b, 5, 7.5, 7.6) ──
            new Topic("joybrush_brushes", "brush settings|hold a brush|hold the brush|brush options|smudge settings"
                    + "|eraser settings|unibrush|sharp turns|palette knife|which brushes",
                    "In JoyBrush, hold the Brush, Smudge or Eraser button to open that brush's settings. Unibrush has "
                            + "Quick, Tip and All editors, also under a hold. Oil brushes, the fan and Palette knife 1 fold "
                            + "under the pen at a sharp corner; a brush's settings → Sharp turns keeps the old swing instead.",
                    "joybrush", null),
            new Topic("joybrush_lines", "editable lines|lines per brush|make lines editable|brush makes lines|brush mode"
                    + "|pixels or lines|lines or pixels|drawer badge",
                    "In JoyBrush, hold a brush and use the switch in its settings to choose whether it draws editable "
                            + "lines (which Select can change) or plain pixels. A badge in the drawer shows which. Brushes "
                            + "that can only make pixels stay pixels.",
                    "joybrush", null),
            new Topic("joybrush_select", "select lines|select a line|select my lines|move a line|move my lines|resize a line"
                    + "|nudge|copy and paste lines|copy lines|paste lines|select tool|joybrush select|edit a line",
                    "In JoyBrush, use Select to pick editable lines, then move or resize them, Nudge them a step at a "
                            + "time, Copy and Paste them, and pan with a finger. Select works on editable lines; selecting "
                            + "plain pixels isn't finished.",
                    "joybrush", null),
            new Topic("joybrush_paper", "paper surface|paper background|drawing paper|paper texture|change the paper"
                    + "|choose paper|paper circle|paper sheet|paper grain|checkerboard",
                    "In JoyBrush, tap the Paper circle in the layer column to open the Paper sheet. You choose the "
                            + "background and a custom colour separately from the surface, the physical tooth the brush "
                            + "bites into (29 surfaces). Background None keeps the surface, shows a checkerboard on screen "
                            + "and leaves paper out of exports.",
                    "joybrush", null),
            new Topic("joybrush_boards", "boards menu|make a board|new board|create a board|animation board|image board"
                    + "|sprite board|lock a board|resize a board|size a board|joybrush regions",
                    "In JoyBrush, open the menu and tap Boards to create an Image, Animation or Sprite region. "
                            + "Then size it, move it and lock it.",
                    "joybrush", null),
            new Topic("joybrush_onion", "onion skin in joy|onion in joy|joybrush onion|onion peg|held layer|hold a layer"
                    + "|layer held|onion on my drawing|onion skin on my drawing|onion on a drawing",
                    "In JoyBrush, select a board: the layer previews, held layers (marked on the layer) and onion skin "
                            + "with its onion peg then apply to that board only.",
                    "joybrush", null),
            new Topic("joybrush_clips", "animation clip|my clips|clips in joy|play all|stop all|combine clips"
                    + "|combine my clips|when it ends|ping-pong|ping pong|flatten clips|overlapping clips|clip end",
                    "A JoyBrush drawing can hold several animation clips, each with its own speed. A clip animates only "
                            + "the layer you are on, and overlapping clips on one layer are refused. Pick When it ends: Loop, "
                            + "Ping-pong, Hold or Blank. Play all runs every clip at once and Stop all stops them. Combine "
                            + "joins clips into one, and Flatten turns overlapping clips into plain frames; each is one Undo.",
                    "joybrush", null),
            new Topic("joybrush_tile", "repeating pattern|tile pattern|pattern preview|seamless|tileable|wrap strokes"
                    + "|pattern limits",
                    "On a JoyBrush Image board, the pattern switch previews a repeating pattern and wraps strokes into "
                            + "it. Realistic pencil, watercolour and oil are refused for wrapping, with a message.",
                    "joybrush", null),
            new Topic("joybrush_sprite_roll", "sprite cells in joy|joybrush sprite|roll the cells|roll cells|cell order in joy"
                    + "|swap cells in joy|arm a swap|size the cells",
                    "In JoyBrush, select a Sprite board and size its cells. Lock the board, then roll cells with a "
                            + "finger to preview their order. Swaps are armed separately, and the stylus keeps painting.",
                    "joybrush", null),
            new Topic("joybrush_export", "send to studio|send a drawing to studio|send to the studio|export a board"
                    + "|export my animation|export an animation|export a gif|gif|animation to studio|send to sprite lab"
                    + "|send to spritelab|export a drawing",
                    "In JoyBrush, the board's export icon exports an image, a range, a frame, an animation, a GIF or a "
                            + "sheet. Send to Studio asks New project or a project by name; in an existing one the "
                            + "animation lands as one more looping sprite layer, half the canvas wide and centred, and the "
                            + "project's clips stay as they were. It can open in Sprite Lab too. A GIF runs up to 50 fps; "
                            + "a scene export stops at 600 frames and says so.",
                    "joybrush", null),
            new Topic("joybrush_symmetry", "symmetry|mirror my brush|mirror the brush|kaleidoscope|radial brush"
                    + "|mirror drawing",
                    "In JoyBrush, open Helpers and tap Symmetry: a line the brush is mirrored across. Drag its ends or "
                            + "body, or turn it with two fingers. It gives a mirror ×2, a radial ×8 or a 16-copy "
                            + "kaleidoscope. Tap one copy to select the whole set; one Undo takes back a stroke. Pencil, "
                            + "watercolour and oil don't support it on this build.",
                    "joybrush", null),
            new Topic("joybrush_layers", "lock a layer|lock the layer|lock my layer|lock layer|unlock a layer"
                    + "|unlock the layer|merge down|merge layers|merge a layer|merge two layers|flatten|flatten lower"
                    + "|freeze layer|freeze a layer|freeze the layer|freeze my layer|clip to layer|clip to the layer"
                    + "|clear a layer|clear the layer|clear layer|layer options|layer menu|joybrush layer",
                    "In JoyBrush, tap a layer in the layer column to paint on it, then tap it again (the ringed one) "
                            + "for its options: Lock keeps its paint and lines from changing (opacity, blend and "
                            + "hiding still work); Merge down joins it into the layer under it; Freeze layer turns its "
                            + "editable lines and realistic paint into plain pixels and frees memory; Flatten makes "
                            + "every visible layer one, exactly as it looks (it asks about hidden layers first); there "
                            + "are also Clip to layer below, Duplicate, Clear and Delete. Each is one Undo. In the "
                            + "Studio, a layer's drawer has its own Lock.",
                    "joybrush", null),
            new Topic("sprite_align", "align the frames|align my frames|align each frame|line up the frames|centre the sprite"
                    + "|center the sprite|plant feet|plant the feet|auto-centre|auto centre|auto-center|frame alignment"
                    + "|jitter between frames",
                    "In Sprite Lab, open Alignment. Drag a number pill to change it or tap it to type a value. Auto-centre "
                            + "centres the frame, Plant feet lines up the feet, Reset clears one frame and Reset all clears "
                            + "the sheet. Alignment is saved with the sheet as numbers; no pixels are changed.",
                    "spritelab", null),
            new Topic("sprite_sheets", "sheets in this project|manage sprite sheets|rename a sprite sheet|rename a sheet"
                    + "|remove a sprite sheet|remove a sheet|delete a sprite sheet|cant remove sheet|can't remove sheet",
                    "In Sprite Lab, the Export section has a “Sheets in this project” panel: open, rename or remove a "
                            + "sheet. Remove is refused while a sprite on the timeline or an avatar rig still uses the "
                            + "sheet, and it says what does.",
                    "spritelab", null),
            new Topic("sprite_reorder", "reorder the frames|reorder frames|reorder my frames|drag to reorder|reorder clips"
                    + "|reorder the clips|film strip",
                    "In Sprite Lab, drag a frame in the film strip, or a clip on the Clips shelf, to put it in a new "
                            + "order.",
                    "spritelab", null),
            new Topic("library_find", "filter my library|search my library|search the library|find in the library"
                    + "|library chips|sort the library|sort my library|grid or list|list view|library view|shared in"
                    + "|find a recording|classic records",
                    "In the Library, the chips across the top filter by kind and show a count for each. Search sits above "
                            + "the keyboard, and “shared in” shows what you shared into Joy Creator. The header buttons "
                            + "cycle the sort and switch between grid and list. The old Records tab is still there as "
                            + "Classic Records.",
                    "library", null),
            new Topic("library_projects", "new project|make a project|start a project|create a project|rename a project"
                    + "|rename my project|projects view|my projects",
                    "Open the Library and switch to Projects: the New project pill starts one. To rename a project, hold "
                            + "it and tap Rename; the editor's title follows.",
                    "library", null),
            new Topic("library_import", "import files|import a picture|import pictures|import a video|import a sound"
                    + "|bring files in|bring in files|import from my phone|import media|import row",
                    "In the lobby, tap Import to open the phone's file picker for pictures, videos and sounds. What you "
                            + "pick is filed like a share into Joy Creator, with readable names, in the Assets folder or at "
                            + "the top of your Joy Creator folder, and it shows in the Library at once.",
                    null, null),
            new Topic("library_look", "open a look|look file|vis.json|fonts and supplies|my looks in the library",
                    "In the Library, tap a look file (.vis.json) under “Fonts and supplies” to open VisLab on that look.",
                    "library", null),
            // ── Settings hub and Remote (BOT-2, 11-04; from STATE Settings hub v2, Remote rows) ──
            new Topic("settings_find", "find a setting|search the settings|search settings|settings search|settings hub"
                    + "|jump chips|classic settings|where is the setting|where are the settings",
                    "The Settings hub groups settings into sections, with chips across the top that jump to a "
                            + "section. Search sits above the keyboard, and each row shows its live status. The classic "
                            + "settings screens are still there, under About.",
                    "setup", null),
            new Topic("settings_location", "location in video|location in my video|gps in|embed location|location metadata"
                    + "|put my location|location in the file",
                    "In Settings, under Recording, the “Location in video files” row opens Location & Privacy > "
                            + "Embed Location Metadata, the switch that puts the GPS position in your videos.",
                    "setup", null),
            new Topic("settings_tips", "camera tips|tips carousel|tips in settings|the tips row|tips row",
                    "In Settings, under About and help, the Tips row opens a carousel of 11 tips. Settings search "
                            + "finds it too. They are older camera tips, not yet rewritten for Joy Creator.",
                    "setup", null),
            new Topic("remote_lan", "remote control|remote camera|watch my phone camera|control the phone camera|lan remote"
                    + "|remote streaming|stream to my computer|stream to a browser|server room",
                    "Remote lets you watch and control the phone's camera from a browser on the same Wi-Fi. It "
                            + "works on your own network only: the old cloud parts were removed. The viewer's computer "
                            + "needs internet for the page to look right. In the lobby it's the Remote word on the floor.",
                    "remote", null),
            new Topic("smart_detection", "smart detection|finder|detect people|detection|find people in|find a person"
                    + "|find cars|find a car|find my pet|events timeline|activity summary|snapshot gallery|who came",
                    "Smart Detection (the lobby's Finder) finds people, vehicles, pets and objects in your camera "
                            + "recordings: an Events Timeline you can filter and jump from to the exact moment, an "
                            + "Activity Summary and a Snapshot Gallery. Switch it on or off in Settings › Smart Detection.",
                    "finder", null),
            // ── VisLab and Director (BOT-2, 10-28; from STATE 5.5 / 6) ──
            // ── TextLab (TEXT-1, 11-12): film-poster words ──
            new Topic("textlab", "textlab|text lab|film title|poster text|poster words|movie title|chrome text"
                    + "|gold text|neon text|fire text|3d text|fancy title|title look",
                    "TextLab makes words look like a film poster: chrome, gold, steel, neon, fire, ice, rust and more, "
                            + "drawn live on your phone. Open TextLab from the lobby, pick a look from the pictures, type "
                            + "your words, and change any slider in the tabs below. + Pieces adds fire, rust, glass or "
                            + "lightning to any look. Tap me there and I can change a look for you.",
                    "textlab", null),
            new Topic("textlab_title", "look on a title|look on my text|give my title a look|film look on a title"
                    + "|text look in studio|title wear a look|look row",
                    "In Studio, tap your text, then pick a picture in the Look row (Plain takes it off). The words stay "
                            + "typed and editable. The look is live in the preview and exact in the export. To change "
                            + "the look itself, tap Edit in TextLab beside the row.",
                    "studio", null),
            new Topic("textlab_light", "tilt the light|move the light|light follows|record tilt|sway the light"
                    + "|still light|where the light",
                    "A TextLab look has a light. Still keeps it where you drag the little pad; Sway lets it drift. In "
                            + "Studio, Record tilt: arm it, hold your thumb on the picture and tilt the phone, and the "
                            + "light follows as the video plays. In the TextLab room, Live follows your phone as you "
                            + "tilt it.",
                    "textlab", null),
            new Topic("textlab_save", "save a look|my own look|save my look|rename a look|delete a look"
                    + "|back to the original|reset a look",
                    "In TextLab, Save keeps your look in the Joy Creator folder, Supplies, Looks, with a moving "
                            + "picture. Change a built-in look and save it, and your copy takes its place; the menu's "
                            + "Back to the original brings the original back. Rename and Delete are in the menu too "
                            + "(deleted looks wait 30 days in the Trash).",
                    "textlab", null),
            new Topic("textlab_export", "export a title|export the look|title as a video|title as a picture"
                    + "|sprite sheet of a title|transparent title|title png",
                    "In TextLab, tap the export button: pick Picture, Video or Sheet (a moving sprite sheet), the "
                            + "shape (wide, square or tall) and the size, up to 4K. A picture or sheet can be "
                            + "see-through. It lands in your Joy Creator folder, where Studio's exports go.",
                    "textlab", null),
            new Topic("textlab_pack", "joy pack|joypack|install a pack|look pack|more looks|pack of looks",
                    "A Joy pack (.joypack) brings new looks. Open the file on your phone (or share it to Joy Creator) "
                            + "and tap Install: its looks join TextLab and Studio's Look row. Packs live in your Joy "
                            + "Creator folder under Supplies, Packs.",
                    "textlab", null),
            new Topic("vislab", "vislab|vis lab|viz lab|music visual|visuals that react|react to the music"
                    + "|music video look",
                    "VisLab is the music visualiser lab: looks that move to a song. Open Viz Lab from the lobby. "
                            + "Play it to the demo song, your microphone, or A song (MIDI and .mod files too), swipe the "
                            + "strip at the bottom to change look, and tap Edit to change one. Tap me there and I can "
                            + "change a look for you.",
                    "vislab", null),
            new Topic("vislab_build", "build a look|make my own look|new look|from scratch|look from nothing"
                    + "|add a layer to the look|layers in vislab",
                    "In VisLab tap Edit, then Layers › Add a layer: bars, particles, lasers, stars, words and more. "
                            + "Stack them, and any slider with a ♪ can follow the music. Looks save themselves as files "
                            + "in your Joy Creator folder, under Supplies/Looks. Save as a part keeps a layer to reuse "
                            + "in any look.",
                    "vislab", null),
            new Topic("vislab_music", "follow the music|move with the music|react to the kick|hit on the kick"
                    + "|kick drum|music link|bass hit|pulse to the beat",
                    "In VisLab, tap the ♪ beside a slider and pick what it listens to (bass, kick, snare, hi-hats, "
                            + "beat, loudness…) and how hard. Sliders marked ♪ only act on the music, so they dim "
                            + "while it's quiet. In Studio, every effect slider has a ♪ music row that does the same.",
                    "vislab", null),
            new Topic("vislab_words", "words layer|lyric visual|lyrics visual|words as art|spiral words"
                    + "|words that fly|words on a spiral|moving lyrics",
                    "In VisLab add a Words layer: the song's words, or your own typed words, as art. Nine paths "
                            + "(ribbon, spiral, ring, tunnel, flight…), six behaviours (karaoke, pop, neon flicker, "
                            + "smoke, burst, sparkle) and six letter styles; each Words layer can use its own font. "
                            + "In Studio, a lyrics look shows your clip's own transcript.",
                    "vislab", null),
            new Topic("vislab_midi", "midi|.mid file|karaoke file|.kar|tracker file|.mod file|module file",
                    "In VisLab tap A song and pick a MIDI (.mid or .kar) or tracker (.mod) file. It plays with a "
                            + "built-in synth (a .mod with its own samples), its exact drum hits and notes drive the "
                            + "look, and .kar lyrics become the song's words.",
                    "vislab", null),
            new Topic("vislab_float", "over other apps|on top of other apps|floating visual|float the visual"
                    + "|visualiser while recording|visualizer while recording|visualiser over|visualizer over",
                    "A visualiser can float over any app, in a box or full screen: use the recorder menu's "
                            + "Visualiser switch, or ask me. It needs Android's “Display over other apps” (Samsung "
                            + "calls it “Appear on top”), which only you can switch on; I can open that page for you.",
                    "record", null),
            new Topic("vislab_in_studio", "vislab look in studio|look on my video|visualiser style"
                    + "|visualizer style|vislab in studio|look as a layer",
                    "In Studio, add a Visualizer, then hold it and open its Style tab: pick any VisLab look, and "
                            + "keyframe which look shows when. Its Blend tab mixes it with the video (Screen, "
                            + "Difference…), and the preview matches the export.",
                    "studio", null, "visualizer"),
            new Topic("vislab_follow", "follow a dummy|follow an object|follows an object|logo follow|move the logo"
                    + "|look layer follow|keep its place|snaps to it|follow my picture|follow a sprite",
                    "In Studio, hold a visualiser and open its Style tab: under “Layers can follow an object”, pick "
                            + "a dummy, picture, title or sprite for any part of the look (its logo, rings, sparks…). "
                            + "Animate the object and that part goes with it. The chip beside it chooses Snaps to it, "
                            + "or Keeps its place (it stays where the look draws it and moves only as the object moves). "
                            + "The “copies” row under it picks what goes with it: Position, Size, Rotation.",
                    "studio", null, "visualizer"),
            new Topic("vislab_show", "picture in the look|title in the look|sprite in the look|video in the logo"
                    + "|logo shows|show my picture in|show a studio picture|look shows|put my title in the look",
                    "A look's picture layer can show something from your project: hold the visualiser, Style tab, "
                            + "then that layer's “shows” row. Pick a picture, title, sprite, video overlay or the main "
                            + "video; it plays inside the look with the look's pump, shake, halo and colour effects.",
                    "studio", null, "visualizer"),
            new Topic("vislab_video", "video in a look|video inside the look|video in vislab|video clip layer"
                    + "|use a video|my video in the visual",
                    "In VisLab tap Edit, then Layers › Add a layer › Video clip, and Use a video to pick one of your "
                            + "videos: it plays round and round inside the look, at its own speed. Any picture layer "
                            + "can Use a video too.",
                    "vislab", null),
            new Topic("vislab_effects", "glitch tear|vhs|scanline|kaleidoscope|crt bend|zoom blur|wet floor"
                    + "|lens split|echo trail|vhs effect|vhs look|retro effect",
                    "Studio has VisLab's finishes as effects for any clip or adjustment layer: Lens split, "
                            + "Scanlines, Zoom blur, Glitch tear, VHS, CRT bend, Kaleidoscope, Mirror, Shake, Wet "
                            + "floor, Glow, Flash, Vignette and more. Open the clip's or adjustment layer's Effects "
                            + "drawer and tap Add effect; their sliders can follow the music too.",
                    "studio", null),
            new Topic("director", "big job|whole video|do it all|plan first|step by step|director mode"
                    + "|show me a plan|several changes",
                    "For a big job I first show a short plan. Tap Go ahead and I do it, up to 40 steps, then a "
                            + "Review card lists every change with Show me and Undo from here.",
                    null, null),
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
