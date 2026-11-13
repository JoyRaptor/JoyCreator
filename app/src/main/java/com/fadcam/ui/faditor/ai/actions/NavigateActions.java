package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;

import java.util.Arrays;
import java.util.List;

/**
 * "Take me there" as an action (gap F2, app-wide host). The rooms are the ones the chat's own
 * route() can open — the action only names one; the chat screen goes there on the main thread
 * once the action finishes ({@link AppActions#takePendingRoute}).
 */
final class NavigateActions {
    private NavigateActions() { }

    private static final String FAMILY = "navigate";

    /** What open_room accepts, in the chat's route() spelling ("record" is the Capture room). */
    static final List<String> ROOMS = Arrays.asList(
            "studio", "spritelab", "avatar", "library", "record", "joybrush", "space", "setup", "trash", "vislab", "textlab",
            "remote", "finder", "supplies");

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("open_room", "Open a part of the app")
                .help("Takes the person to another part of Joy Creator and closes the chat. Use when they ask"
                        + " to go somewhere or to do something that only happens there: studio (the video"
                        + " editor and the project list), spritelab (sprite sheets: cells, animations), avatar"
                        + " (puppet characters), library (recordings, exports and pictures), record (Capture:"
                        + " screen and camera recording), joybrush (drawing and frame-by-frame animation),"
                        + " space (what takes room on the phone, clearing caches), setup (settings), trash"
                        + " (Recently deleted: bring things back), vislab (the music visualiser: looks that move with a song), textlab (film-poster words: chrome, gold, neon, fire), remote (Remote Studio: watch and control the phone's"
                        + " camera from a browser on the same Wi-Fi), finder (Smart Detection: people, vehicles, pets and"
                        + " objects found in recordings; the lobby calls it Detect), supplies (fonts, looks and installed packs in the Library)."
                        + " Say where you're taking them first. spritelab needs a project"
                        + " open (else the Studio); avatar without one opens the character library.")
                .wing("APP", FAMILY).effect(Effect.NAVIGATE)
                .where("Lobby › the room tiles")
                .choice("room", true, "Where to go", ROOMS)
                .build(), NavigateActions::openRoom);
    }

    @NonNull
    private static ActionResult openRoom(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        String room = a.choice("room", ROOMS);
        if (room == null) throw new ActionError("open_room needs a room: " + ROOMS + ".");
        // Without a project these two would open on nothing; the chat's route() sends them to the
        // Studio then, and saying so here keeps the model's next sentence honest.
        boolean needsProject = "spritelab".equals(room);
        ctx.route = room;
        return ActionResult.unchanged(needsProject && ctx.projectId == null
                ? "No project is open, so I'm opening the Studio (" + room + " needs a project)."
                : "Opening " + words(room) + ".");
    }

    @NonNull
    private static String words(@NonNull String room) {
        switch (room) {
            case "spritelab": return "Sprite Lab";
            case "avatar": return "Avatar Studio";
            case "record": return "Capture";
            case "joybrush": return "JoyBrush";
            case "space": return "the Space screen";
            case "trash": return "Recently deleted";
            case "vislab": return "VisLab";
            case "textlab": return "TextLab";
            case "remote": return "Remote";
            case "finder": return "Smart Detection";
            case "supplies": return "Fonts and supplies";
            case "setup": return "Setup";
            case "library": return "the Library";
            default: return "the Studio";
        }
    }
}
