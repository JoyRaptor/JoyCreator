package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Can this action run HERE, before Joybot says he'll do it (BOT-7, owner 11-15)?
 *
 * <p>The owner asked for "a new blank Studio project, 3:1"; Joybot said "doing it", then "I can't
 * make a new blank project folder with a 3.1 aspect ratio here", and nothing changed. He tapped
 * "make a transition" in the lobby and Joybot made one in a project the owner had never opened.
 * Both are the same mistake: acting (or promising to) before checking that the thing can happen
 * where the person is, on something they can see. This class is that check. Pure Java (tested in
 * IdeaEngineTest), so every room and every kind of action is covered off the phone.</p>
 *
 * <h3>Three answers</h3>
 * <ul>
 *   <li>{@link Verdict.Kind#GO}: run it.</li>
 *   <li>{@link Verdict.Kind#ASK}: it can run, but somewhere the person can't see right now (a
 *       Studio project from the lobby, a VisLab look from Studio). Ask first; "yes" opens that
 *       place; nothing happens until then.</li>
 *   <li>{@link Verdict.Kind#CANT}: it can't run from here. One plain sentence, and the nearest
 *       thing he CAN do ({@link Verdict#nearestRoute}, a place to open, or a create kind).</li>
 * </ul>
 */
public final class Preflight {
    private Preflight() { }

    /** Where the person is and what is open. */
    public static final class Here {
        @NonNull public final IdeaEngine.Room room;
        /** A Studio project is open for Joybot to work on (not just "the newest one exists"). */
        public final boolean projectOpen;
        /** The last project the person had open, for the lobby's "in this one?" question; or null. */
        @Nullable public final String lastProjectId;
        @Nullable public final String lastProjectName;

        public Here(@NonNull IdeaEngine.Room room, boolean projectOpen,
                    @Nullable String lastProjectId, @Nullable String lastProjectName) {
            this.room = room;
            this.projectOpen = projectOpen;
            this.lastProjectId = lastProjectId;
            this.lastProjectName = lastProjectName;
        }
    }

    /** What the action is, as the registry describes it. */
    public static final class What {
        @NonNull public final String tool;
        /** "STUDIO" (works on one project), "APP" (rooms, the Library…), or "" for the older tools (project tools). */
        @NonNull public final String wing;
        /** Only reads: never changes anything. */
        public final boolean reads;
        /** A few words for the person: "set transition", from the spec's title when there is one. */
        @NonNull public final String title;

        public What(@NonNull String tool, @NonNull String wing, boolean reads, @Nullable String title) {
            this.tool = tool;
            this.wing = wing;
            this.reads = reads;
            this.title = title == null || title.trim().isEmpty() ? tool.replace('_', ' ') : title.trim();
        }
    }

    public static final class Verdict {
        public enum Kind { GO, ASK, CANT }

        @NonNull public final Kind kind;
        /** What Joybot says (ASK: the question; CANT: the one plain sentence). Empty for GO. */
        @NonNull public final String say;
        /** ASK/CANT: the route that is the way forward ("studio", "joybrush", "vislab"…), or null. */
        @Nullable public final String nearestRoute;
        /** The button for {@link #nearestRoute}, e.g. "Open JoyBrush". */
        @Nullable public final String nearestLabel;
        /** ASK about a project: the project "yes" opens. */
        @Nullable public final String projectId;
        /** CANT with no project anywhere: a create-tree kind to offer instead ({@link CreateTree}). */
        @Nullable public final String createKind;
        /** The room this happens in, for its colour (BOT-7 room colours): "studio", "vislab"… */
        @NonNull public final String room;

        Verdict(@NonNull Kind kind, @NonNull String say, @Nullable String nearestRoute, @Nullable String nearestLabel,
                @Nullable String projectId, @Nullable String createKind, @NonNull String room) {
            this.kind = kind;
            this.say = say;
            this.nearestRoute = nearestRoute;
            this.nearestLabel = nearestLabel;
            this.projectId = projectId;
            this.createKind = createKind;
            this.room = room;
        }

        public boolean go() { return kind == Kind.GO; }

        @Override public String toString() { return kind + (say.isEmpty() ? "" : ": " + say); }
    }

    /**
     * The lobby's "open your last project?" when the model asked it in its own words (no tool was
     * held back): the same Open it / Not now buttons, so the answer is one tap.
     */
    @NonNull
    public static Verdict askProject(@NonNull String projectId, @NonNull String say) {
        return new Verdict(Verdict.Kind.ASK, say, "studio", "Open it", projectId, null, "studio");
    }

    /** Things that live inside a Studio project. */
    private static final java.util.regex.Pattern PROJECT_THING = java.util.regex.Pattern.compile(
            "\\b(transitions?|crossfades?|clips?|cuts?|captions?|subtitles?|titles?|music|audio|sound|volume|fades?"
                    + "|transcri\\w*|ums|uhs|fillers?|silences?|pauses|text|slides?|speed|trim|split|crop|zoom|b-?roll"
                    + "|keyframes?|layers?|timeline|intro|outro|voice ?over|visuali[sz]er|the video|my video|this video)\\b");

    /**
     * In the lobby: words asking to change something inside a project ("make a transition", "add
     * captions"), answered with the "in the last project you had open?" question at once, before
     * any AI is asked (a free model answered "Which cut should it go on?" about a project no one
     * could see, Note 9 11-15). Not a request for something NEW (the create tree has those).
     */
    public static boolean lobbyProjectChange(@Nullable String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.US);
        if (CreateTree.Words.start(t) != null || CreateTree.Words.anything(t)) return false;
        return HelpBook.wantsChange(t) && PROJECT_THING.matcher(t).find();
    }

    /** The lobby's question for {@code asked}, about the last project; null when there is none. */
    @Nullable
    public static Verdict lobbyAsk(@NonNull String asked, @Nullable String lastId, @Nullable String lastName) {
        if (lastId == null) return null;
        return check(new What("lobby_request", "STUDIO", false, null),
                new Here(IdeaEngine.Room.LOBBY, false, lastId, lastName), asked);
    }

    /** The model's own reply offers to open {@code lastName} (it names it and asks). */
    public static boolean offersToOpen(@Nullable String reply, @Nullable String lastName) {
        if (reply == null || lastName == null || lastName.trim().isEmpty()) return false;
        String r = reply.toLowerCase(Locale.US);
        return r.contains(lastName.trim().toLowerCase(Locale.US)) && r.contains("?")
                && (r.contains("open") || r.contains("go to") || r.contains("take you"));
    }

    private static final Verdict GO = new Verdict(Verdict.Kind.GO, "", null, null, null, null, "");

    /** The check. */
    @NonNull
    public static Verdict check(@NonNull What w, @NonNull Here h) {
        return check(w, h, null);
    }

    /**
     * The check, with the person's own words ({@code asked}, "make a transition") for the lobby's
     * question, which reads better in their words than in the action's title.
     */
    @NonNull
    public static Verdict check(@NonNull What w, @NonNull Here h, @Nullable String asked) {
        String t = w.tool;
        // JoyBrush's hands work on the drawing on screen; there is none outside JoyBrush.
        if (t.startsWith("joybrush_")) {
            if (w.reads && "joybrush_drawings".equals(t)) return GO;   // the list of drawings is readable anywhere
            if (h.room == IdeaEngine.Room.JOYBRUSH) return GO;
            return cant("I can only " + lower(w.title) + " in JoyBrush, with the drawing open there.",
                    "joybrush", "Open JoyBrush", "joybrush");
        }
        // VisLab and TextLab change the look last open in their room. From anywhere else that is a
        // change the person can't see: ask first, and open the room on yes.
        if (t.startsWith("vislab_") || t.startsWith("textlab_")) {
            boolean vis = t.startsWith("vislab_");
            IdeaEngine.Room own = vis ? IdeaEngine.Room.VISLAB : IdeaEngine.Room.TEXTLAB;
            if (w.reads || h.room == own) return GO;
            String name = vis ? "VisLab" : "TextLab";
            String room = vis ? "vislab" : "textlab";
            return new Verdict(Verdict.Kind.ASK, "That happens in " + name + ", which isn't open. Shall we go there and "
                    + lower(w.title) + " together?", room, "Open " + name, null, null, room);
        }
        if ("APP".equals(w.wing)) return GO;
        // Everything else works on one Studio project.
        if (h.projectOpen) return GO;
        if ((h.room == IdeaEngine.Room.LOBBY || h.room == IdeaEngine.Room.LIBRARY) && h.lastProjectId != null) {
            String n = h.lastProjectName == null || h.lastProjectName.trim().isEmpty() ? "the last project you had open"
                    : "“" + h.lastProjectName.trim() + "”, the last project you had open";
            String doing = asked != null && !asked.trim().isEmpty() && asked.trim().length() <= 60
                    ? asked.trim().replaceAll("[.!?]+$", "") : w.title;
            return new Verdict(Verdict.Kind.ASK, sentenceStart(doing) + " in " + n + "? I'll open it so we can look at it together.",
                    "studio", "Open it", h.lastProjectId, null, "studio");
        }
        return cant("No Studio project is open, so I can't " + lower(w.title) + " here.",
                "studio", "Open the Studio", "video");
    }

    @NonNull
    private static Verdict cant(@NonNull String say, @Nullable String route, @Nullable String label,
                                @Nullable String createKind) {
        return new Verdict(Verdict.Kind.CANT, say, route, label, null, createKind, route == null ? "" : route);
    }

    @NonNull
    static String lower(@NonNull String title) {
        if (title.length() > 1 && Character.isUpperCase(title.charAt(0)) && !Character.isUpperCase(title.charAt(1))) {
            return Character.toLowerCase(title.charAt(0)) + title.substring(1);
        }
        return title;
    }

    @NonNull
    static String sentenceStart(@NonNull String title) {
        if (title.isEmpty()) return title;
        return title.substring(0, 1).toUpperCase(Locale.US) + title.substring(1);
    }

    /**
     * What the model is told when a call didn't run because of a verdict: plain, and never a
     * "done". The person sees the verdict itself (with its button) from the chat.
     */
    @NonNull
    public static String toolAnswer(@NonNull Verdict v) {
        if (v.kind == Verdict.Kind.ASK) {
            return "Not run: " + v.say + " That question and its button are on the person's screen now. NOTHING has"
                    + " happened. Don't call any more tools for this; tell them in one short sentence that it waits"
                    + " for their answer, and never say it's done.";
        }
        return "Error: not run: " + v.say + (v.nearestLabel != null ? " (Offer instead: " + v.nearestLabel + ".)" : "")
                + " Say so in one plain sentence; never say it's done or that you're doing it.";
    }
}
