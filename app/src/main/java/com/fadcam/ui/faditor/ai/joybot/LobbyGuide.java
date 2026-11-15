package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Joybot in the lobby: guide, tour, create, find (BOT-7, owner 11-15). In the lobby he is "your
 * wizard, butler, custodian, co-pilot and tour guide"; only inside a project does he start asking
 * about that project. This is what he says and offers there, with no typing needed to start. Pure
 * Java (IdeaEngineTest); the chat runs the buttons.
 */
public final class LobbyGuide {
    private LobbyGuide() { }

    /** FLOW names the chat carries out. */
    public static final String CREATE = "lobby_create", HELP = "lobby_help", TOUR = "lobby_tour",
            WHAT = "lobby_what", OPEN_LAST = "lobby_open_last", CONNECT = "lobby_connect";

    /** Lobby buttons are never quietened by a long-press: they are the lobby's own doors. */
    public static boolean isLobbyIdea(@NonNull Idea i) {
        return i.key.startsWith("lobby_");
    }

    /** His first words in the lobby. {@code did}: the "Did you know…" sentence. */
    @NonNull
    public static String greeting(@NonNull String did, boolean online) {
        return "Hi, I'm Joybot, your guide. I can help you make something new, find your work, or show you around. "
                + did + (online ? "" : "\n\nAll of that works right here with no AI. Connect one (there's a free one)"
                + " and you can also just tell me what you'd like in your own words.");
    }

    /** His very first words, the lobby's first run after the intro (the first walkthrough). */
    @NonNull
    public static String welcome(boolean online) {
        return "Welcome to Joy Creator! I'm Joybot, your guide. Tell me what you'd like to make and I'll set it up"
                + " in the right room for you, or I can show you around first. Tap a button to start: no typing needed."
                + (online ? "" : " All of this works with no AI and no account.");
    }

    /** The first buttons: Create something · Help · Show me around · What can I do. */
    @NonNull
    public static List<Idea> firstButtons() {
        List<Idea> l = new ArrayList<>();
        l.add(Idea.of(CREATE, Idea.Kind.FLOW, "Create something").flow(CREATE)
                .say("I want to make something").hint("Pick what to make; I'll set it up in the right room").build());
        l.add(Idea.of(HELP, Idea.Kind.FLOW, "Help").flow(HELP)
                .say("Help").hint("Answers to “how do I…”, with no AI needed").build());
        l.add(Idea.of(TOUR, Idea.Kind.FLOW, "Show me around").flow(TOUR)
                .say("Show me around").hint("A short tour of the rooms").build());
        l.add(Idea.of(WHAT, Idea.Kind.FLOW, "What can I do?").flow(WHAT)
                .say("What can I do here?").hint("What I can do for you from the lobby").build());
        return l;
    }

    /** "What can I do here?" in the lobby: the answer. */
    @NonNull
    public static String whatCanIDo(boolean online, @Nullable String lastProjectName) {
        StringBuilder b = new StringBuilder("Here in the lobby I can:\n")
                .append("• make something new and set it up for you (a drawing, a video, an animation, titles…)\n")
                .append("• find your projects, exports and recent work, and help tidy up\n")
                .append("• show you around the rooms\n")
                .append("• answer “how do I…” about anything in the app");
        if (lastProjectName != null) b.append("\n• open “").append(lastProjectName).append("” so we can work on it together");
        b.append(online ? "\n\nOr just tell me what you'd like." : "\n\nWith an AI connected you can also ask in your own words.");
        return b.toString();
    }

    /** The buttons under "What can I do?". */
    @NonNull
    public static List<Idea> whatButtons(boolean online, @Nullable String lastProjectName) {
        List<Idea> l = new ArrayList<>();
        l.add(firstButtons().get(0));
        l.add(Idea.of("lobby_find", Idea.Kind.FLOW, "Find my work").flow(CREATE).target("find")
                .say("Help me find my work").hint("Projects, exports and recent work").build());
        if (lastProjectName != null) {
            l.add(Idea.of(OPEN_LAST, Idea.Kind.FLOW, "Open “" + shortName(lastProjectName) + "”").flow(OPEN_LAST)
                    .say("Open my last project").hint("Open the last project you had open").build());
        }
        l.add(firstButtons().get(2));
        l.add(firstButtons().get(1));
        if (!online) {
            l.add(Idea.of(CONNECT, Idea.Kind.FLOW, "Connect an AI").flow(CONNECT)
                    .say("Connect an AI").hint("Set up an AI (there's a free one) so I can do more").build());
        }
        return l;
    }

    @NonNull
    private static String shortName(@NonNull String n) {
        return n.length() <= 18 ? n : n.substring(0, 17).trim() + "…";
    }

    // ── Help ────────────────────────────────────────────────────────────────

    /** Questions people ask first, each answered by the help book with no AI. */
    public static final List<String> HELP_QUESTIONS = Collections.unmodifiableList(Arrays.asList(
            "How do I edit a video?",
            "How do I start a drawing?",
            "How do I record my screen?",
            "Where are my things?",
            "How do I export?",
            "How do I connect an AI?"));

    @NonNull
    public static String helpIntro() {
        return "Ask me “how do I…” about anything, or tap a question. These all work with no AI.";
    }

    // ── Show me around ──────────────────────────────────────────────────────

    /** One stop on the tour: a room, what it is for in one sentence, and its route. */
    public static final class Stop {
        @NonNull public final String room, name, say, route;

        Stop(@NonNull String room, @NonNull String name, @NonNull String say, @NonNull String route) {
            this.room = room;
            this.name = name;
            this.say = say;
            this.route = route;
        }

        @Override public String toString() { return room; }
    }

    private static final List<Stop> STOPS = Collections.unmodifiableList(Arrays.asList(
            new Stop("studio", "Studio", "The Studio is where videos and sound come together: cut, add titles,"
                    + " captions and music, then export.", "studio"),
            new Stop("joybrush", "JoyBrush", "JoyBrush is for drawing and painting with real-feeling pencils, inks,"
                    + " watercolour and oils, and for frame-by-frame animation.", "joybrush"),
            new Stop("capture", "Capture", "Capture records your screen or your camera.", "record"),
            new Stop("spritelab", "Sprite Lab", "Sprite Lab cuts sprite sheets into frames, names them and turns them"
                    + " into animations.", "spritelab"),
            new Stop("avatar", "Avatar Studio", "Avatar Studio makes puppet characters that move and talk.", "avatar"),
            new Stop("vislab", "VisLab", "VisLab makes pictures that move with a song: music visuals.", "vislab"),
            new Stop("textlab", "TextLab", "TextLab makes film-poster words: chrome, gold, neon, fire.", "textlab"),
            new Stop("library", "Library", "The Library holds everything you've made: projects, recordings,"
                    + " drawings and exports, in one place.", "library")));

    /** The tour, without the rooms this version doesn't have ({@code hidden}: their routes). */
    @NonNull
    public static List<Stop> tour(@NonNull java.util.function.Predicate<String> hidden) {
        List<Stop> out = new ArrayList<>();
        for (Stop s : STOPS) if (!hidden.test(s.route)) out.add(s);
        return out;
    }

    /** "Show me around", "give me a tour". */
    public static boolean asksTour(@Nullable String text) {
        if (text == null) return false;
        String t = text.toLowerCase(java.util.Locale.US).replaceAll("[.!?,]", " ").replaceAll("\\s+", " ").trim();
        return t.matches("(please |joybot |can you |could you )*(show me around|give me a tour|take me on a tour|"
                + "show me the app|tour|guided tour|walk me through the app)( please)?");
    }

    @NonNull
    public static String tourIntro(int stops) {
        return "Here's the tour: " + stops + " rooms, one at a time. Tap Next, or Take me there to step inside.";
    }

    @NonNull
    public static String tourEnd() {
        return "That's the tour. Want to make something? Tap Create something, or ask me anything.";
    }
}
