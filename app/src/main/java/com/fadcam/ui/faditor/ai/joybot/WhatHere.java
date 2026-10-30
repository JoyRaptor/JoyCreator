package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "What can I do here?" (BOT-2): what Joybot can really do in this room, in plain words, read from
 * the action list itself so it never promises a tool that isn't there. Pure Java (IdeaEngineTest).
 */
public final class WhatHere {
    private WhatHere() { }

    /** One action as the registry describes it. */
    public static final class Action {
        @NonNull public final String tool;
        @NonNull public final String title;
        @NonNull public final String wing;
        public final boolean reads;

        public Action(@NonNull String tool, @NonNull String title, @NonNull String wing, boolean reads) {
            this.tool = tool;
            this.title = title;
            this.wing = wing;
            this.reads = reads;
        }
    }

    /** At most this many lines, then "and N more". */
    static final int SHOWN = 14;

    /** "What can I do here", "what can you do in here", "what can I make here?". */
    public static boolean asks(@Nullable String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.US).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return t.matches("(so )?what (else )?can (i|you|we) (do|make)( with you)?( here| in here| in this room| on this screen| now)")
                || t.matches("what can (i|you|we) (do|make) here.*")
                || t.equals("what can i do") || t.equals("what can i make");
    }

    /** Whether {@code a} belongs in {@code room}. */
    static boolean fits(@NonNull Action a, @NonNull IdeaEngine.Room room, boolean project) {
        boolean vis = a.tool.startsWith("vislab_"), jb = a.tool.startsWith("joybrush_");
        switch (room) {
            case VISLAB: return vis;
            case JOYBRUSH: return jb;
            case STUDIO:
            case SPRITELAB:
            case AVATAR:
                return project && !vis && !jb && "STUDIO".equals(a.wing);
            case LIBRARY:
            case LOBBY:
            default:
                return "APP".equals(a.wing) && !vis && !jb;
        }
    }

    /** The answer, as Joybot says it. {@code online}: an AI is connected (the actions need one). */
    @NonNull
    public static String answer(@NonNull IdeaEngine.Room room, boolean project, boolean online,
                                @NonNull List<Action> all) {
        List<Action> writes = new ArrayList<>(), reads = new ArrayList<>();
        for (Action a : all) {
            if (!fits(a, room, project)) continue;
            (a.reads ? reads : writes).add(a);
        }
        List<Action> list = new ArrayList<>(writes);
        list.addAll(reads);
        StringBuilder sb = new StringBuilder();
        if (room == IdeaEngine.Room.STUDIO && project) {
            sb.append("Right here, with no AI at all: transcribe, add captions, cut the ums and silences, add "
                    + "transitions and make slides (the buttons below).\n\n");
        }
        if (list.isEmpty()) {
            return sb.append("Ask me “how do I…” about anything here and I'll show you the way.").toString();
        }
        sb.append(online ? "Just ask, and I'll do any of these " + where(room) + ":\n"
                : "With an AI connected, I can also do any of these " + where(room) + ":\n");
        int n = 0;
        for (Action a : list) {
            if (n == SHOWN) break;
            sb.append("• ").append(a.title).append('\n');
            n++;
        }
        if (list.size() > SHOWN) sb.append("…and ").append(list.size() - SHOWN).append(" more.\n");
        sb.append("\nOr ask me “how do I…” about anything here.");
        return sb.toString();
    }

    @NonNull
    private static String where(@NonNull IdeaEngine.Room room) {
        switch (room) {
            case VISLAB: return "in VisLab";
            case JOYBRUSH: return "in JoyBrush";
            case STUDIO:
            case SPRITELAB:
            case AVATAR: return "in your project";
            default: return "around the app";
        }
    }
}
