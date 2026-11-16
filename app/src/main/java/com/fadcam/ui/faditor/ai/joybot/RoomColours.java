package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;

import java.util.Locale;

/**
 * Room colours carry intention (BOT-7, owner 11-15): the colour of Joybot's message or button
 * shows WHERE it will happen. Studio's greens for something done in the Studio, VisLab's orange
 * for VisLab, and so on: the lobby's own room gradients (LobbyFragment's table, Studio's ROOM_*
 * tokens). In the lobby, his default is his own colours (his orb). Pure Java; {@link RoomTint}
 * reads JoyBrush's live pair from its tokens (the owner may change those) and paints views.
 */
public final class RoomColours {
    private RoomColours() { }

    /** Joybot himself (his orb, the lobby's "Joybot" colour). */
    public static final String JOYBOT = "joybot";

    /** A gradient (start → end) and the ink that reads on it. */
    public static final class Pair {
        @NonNull public final String room;
        public final int start, end, ink;

        public Pair(@NonNull String room, int start, int end, int ink) {
            this.room = room;
            this.start = start;
            this.end = end;
            this.ink = ink;
        }

        @Override public String toString() {
            return room + String.format(Locale.US, " #%06X→#%06X", start & 0xFFFFFF, end & 0xFFFFFF);
        }
    }

    private static final int BLACK = 0xFF000000, WHITE = 0xFFFFFFFF;
    /** JoyBrush's pair as shipped (jb_tokens.xml); {@link RoomTint#forRoom} reads the live one. */
    public static final int JB_START = 0xFF5C43FD, JB_END = 0xFF4397FD;

    /**
     * The colours for {@code room}: a route or room name ("studio", "vislab", "STUDIO",
     * "record"…). Unknown or empty: Joybot's own.
     */
    @NonNull
    public static Pair of(@Nullable String room) {
        String r = norm(room);
        switch (r) {
            case "studio": return new Pair(r, Studio.ROOM_STUDIO, Studio.GO_END, BLACK);
            case "capture": return new Pair(r, Studio.ROOM_CAPTURE, Studio.ROOM_SPRITE, WHITE);
            case "spritelab": return new Pair(r, Studio.ROOM_SPRITE, Studio.ROOM_AVATAR, WHITE);
            case "avatar": return new Pair(r, Studio.ROOM_AVATAR, Studio.ROOM_AVATAR_DEEP, WHITE);
            case "vislab": return new Pair(r, Studio.ROOM_VIZ, Studio.ROOM_VIZ_DEEP, BLACK);
            case "textlab": return new Pair(r, Studio.ROOM_TEXTLAB, Studio.ROOM_TEXTLAB_DEEP, WHITE);
            case "library": return new Pair(r, Studio.VIDEO, Studio.ARMED_LIGHT, BLACK);
            case "joybrush": return new Pair(r, JB_START, JB_END, WHITE);
            default: return new Pair(JOYBOT, Studio.ROOM_AVATAR, Studio.ORB_DEEP, WHITE);
        }
    }

    /**
     * The chat's own colours, where Joybot was opened: his own in the lobby (and the Library),
     * the room's inside a room (Studio's greens in the Studio). The default for his messages and
     * the send arrow.
     */
    @NonNull
    public static String home(@NonNull IdeaEngine.Room opened) {
        switch (opened) {
            case STUDIO: return "studio";
            case SPRITELAB: return "spritelab";
            case AVATAR: return "avatar";
            case JOYBRUSH: return "joybrush";
            case VISLAB: return "vislab";
            case TEXTLAB: return "textlab";
            case LOBBY:
            case LIBRARY:
            default: return JOYBOT;
        }
    }

    /** Route spellings ("record") and room names ("SPRITELAB") as one name. */
    @NonNull
    static String norm(@Nullable String room) {
        if (room == null) return "";
        String r = room.trim().toLowerCase(Locale.US);
        switch (r) {
            case "record": case "capture": return "capture";
            case "studio": case "studio_audio": case "projects": return "studio";
            case "sprites": case "sprite": case "spritelab": return "spritelab";
            case "avatar": case "puppet": return "avatar";
            case "viz": case "vislab": return "vislab";
            case "textlab": return "textlab";
            case "library": case "supplies": case "finder": case "trash": case "space": return "library";
            case "joybrush": case "drawing": return "joybrush";
            default:
                if (r.startsWith("project:")) return "studio";
                return r;
        }
    }

    // ── which room a message is about ───────────────────────────────────────

    /**
     * The room a message of Joybot's belongs to: {@code home} unless it is about something in ONE
     * other room. Two rooms named ("send it to Studio or TextLab?") keep the home colour; one room
     * named ("send this to Studio?", "Take me there" to VisLab) wears that room's.
     */
    @NonNull
    public static String about(@NonNull String home, @Nullable String text) {
        if (text == null) return home;
        String t = text.toLowerCase(Locale.US);
        String found = null;
        int count = 0;
        for (String[] r : NAMES) {
            for (int i = 1; i < r.length; i++) {
                if (java.util.regex.Pattern.compile("\\b" + r[i] + "\\b").matcher(t).find()) {
                    if (!r[0].equals(found)) { count++; found = r[0]; }
                    break;
                }
            }
        }
        return count == 1 ? found : home;
    }

    /** Room → the words that name it in a sentence. */
    private static final String[][] NAMES = {
            {"studio", "the studio", "studio project", "in studio", "to studio"},
            {"vislab", "vislab", "vis lab"},
            {"textlab", "textlab", "text lab"},
            {"joybrush", "joybrush", "joy brush"},
            {"spritelab", "sprite lab", "spritelab"},
            {"avatar", "avatar studio"},
            {"capture", "capture"},
            {"library", "the library"}};
}
