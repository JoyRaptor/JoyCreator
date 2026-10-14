package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Help me make a slide": four quick questions, answered with buttons or typing, that end in a
 * {@code generate_slide} call.
 *
 * <p>Brief §3: the slide engine exists (HTML/CSS/GSAP rendered to frames, with a built-in design
 * when there is no AI); the ASKING part is what was missing. Joybot asks what it's for, what it
 * says, the mood, and where it goes — each as buttons, with "type it" always possible — and only
 * then builds. Pure Java so the whole conversation is tested off-device.</p>
 */
public final class SlideFlow {

    public enum Stage { KIND, TEXT, MOOD, PLACE, DONE }

    /** One button. {@code id} is what {@link #answer} expects back. */
    public static final class Choice {
        @NonNull public final String id;
        @NonNull public final String label;
        @NonNull public final String hint;

        Choice(@NonNull String id, @NonNull String label, @NonNull String hint) {
            this.id = id;
            this.label = label;
            this.hint = hint;
        }
    }

    /** A button for any of Joybot's guided questions. */
    @NonNull
    public static Choice choiceOf(@NonNull String id, @NonNull String label, @NonNull String hint) {
        return new Choice(id, label, hint);
    }

    /** What Joybot says and offers at the current stage. */
    public static final class Question {
        @NonNull public final String text;
        @NonNull public final List<Choice> choices;
        /** True when typing is the main way to answer (the slide's words). */
        public final boolean wantsTyping;
        /** A suggestion to put in the text box, or null. */
        @Nullable public final String prefill;

        Question(@NonNull String text, @NonNull List<Choice> choices, boolean wantsTyping,
                 @Nullable String prefill) {
            this.text = text;
            this.choices = Collections.unmodifiableList(choices);
            this.wantsTyping = wantsTyping;
            this.prefill = prefill;
        }
    }

    /** What kind of slide, with how long it holds and how it is laid out. */
    public static final class Kind {
        @NonNull public final String id, label, hint, ask, layout;
        public final long durationMs;
        public final boolean multiLine;

        Kind(String id, String label, String hint, String ask, long durationMs, boolean multiLine,
             String layout) {
            this.id = id; this.label = label; this.hint = hint; this.ask = ask;
            this.durationMs = durationMs; this.multiLine = multiLine; this.layout = layout;
        }
    }

    public static final List<Kind> KINDS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Kind("title", "Title", "The name of the video, big, at the start",
                    "What should the title say?", 3500, false,
                    "a title card: one big headline, centred, generous space"),
            new Kind("chapter", "Chapter", "A short card between parts of the video",
                    "What's this chapter called?", 2500, false,
                    "a chapter card: a small chapter label above a short heading"),
            new Kind("quote", "Quote", "Someone's words, with who said them",
                    "What's the quote? (Add who said it on a new line.)", 5000, true,
                    "a quote card: the quote large, in quotation marks, the speaker smaller beneath"),
            new Kind("list", "List", "A few points, one after another",
                    "What are the points? One per line.", 6000, true,
                    "a list card: each line appears in turn, left aligned"),
            new Kind("credits", "Credits", "Names at the end, rolling up",
                    "Who should the credits list? One per line.", 8000, true,
                    "end credits: lines roll slowly upward, centred")));

    /** Mood → the words the slide designer (model or built-in) is given. */
    public static final class Mood {
        @NonNull public final String id, label, hint, style;

        Mood(String id, String label, String hint, String style) {
            this.id = id; this.label = label; this.hint = hint; this.style = style;
        }
    }

    public static final List<Mood> MOODS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Mood("calm", "Calm", "Soft fades, light type, lots of space",
                    "calm: soft slow fades, light-weight type, muted colours, lots of space"),
            new Mood("bold", "Bold", "Heavy type that punches in",
                    "bold: heavy condensed type, high contrast, quick punchy entrances"),
            new Mood("playful", "Playful", "Bouncy, bright and round",
                    "playful: rounded type, bright colours, bouncy springy motion"),
            new Mood("cinematic", "Cinematic", "Dark, wide, slow and dramatic",
                    "cinematic: dark background, wide letter spacing, slow dramatic reveal, gold accents"),
            new Mood("clean", "Clean", "Simple and modern, nothing extra",
                    "clean: simple modern sans-serif, white on near-black, crisp short motion")));

    private final String projectName;
    private final int clipCount;
    /** Index of the clip under the playhead, or -1 when not known (opened from the lobby). */
    private final int clipAtPlayhead;

    private Stage stage = Stage.KIND;
    @Nullable private Kind kind;
    @Nullable private String text;
    @Nullable private Mood mood;
    private int insertAt = -1;
    @NonNull private String placeWords = "";

    public SlideFlow(@NonNull String projectName, int clipCount, int clipAtPlayhead) {
        this.projectName = projectName.trim();
        this.clipCount = Math.max(0, clipCount);
        this.clipAtPlayhead = clipAtPlayhead;
    }

    /** Start already knowing the kind (the "Make a title card" idea). */
    @NonNull
    public SlideFlow startingWith(@NonNull String kindId) {
        answer(kindId);
        return this;
    }

    @NonNull public Stage stage() { return stage; }

    @NonNull
    public Question question() {
        switch (stage) {
            case KIND: {
                List<Choice> c = new ArrayList<>();
                for (Kind k : KINDS) c.add(new Choice(k.id, k.label, k.hint));
                return new Question("What's the slide for?", c, false, null);
            }
            case TEXT: {
                Kind k = kindOrTitle();
                List<Choice> c = new ArrayList<>();
                String prefill = null;
                if ("title".equals(k.id) && !projectName.isEmpty()) {
                    c.add(new Choice("use_name", "Use “" + projectName + "”",
                            "Put the project's name on the title"));
                    prefill = projectName;
                }
                return new Question(k.ask, c, true, prefill);
            }
            case MOOD: {
                List<Choice> c = new ArrayList<>();
                for (Mood m : MOODS) c.add(new Choice(m.id, m.label, m.hint));
                return new Question("What mood?", c, false, null);
            }
            case PLACE: {
                List<Choice> c = new ArrayList<>();
                c.add(new Choice("start", "At the start", "Before your first clip"));
                if (clipAtPlayhead > 0 && clipAtPlayhead < clipCount) {
                    c.add(new Choice("here", "Before this clip", "Just before the clip under the playhead"));
                }
                c.add(new Choice("end", "At the end", "After your last clip"));
                return new Question("Where should it go?", c, false, null);
            }
            default:
                return new Question("", new ArrayList<>(), false, null);
        }
    }

    /**
     * Take a button id or typed words for the current stage.
     *
     * @return false when the answer did not fit this stage (Joybot asks again)
     */
    public boolean answer(@NonNull String raw) {
        String a = raw.trim();
        if (a.isEmpty()) return false;
        switch (stage) {
            case KIND: {
                Kind k = matchKind(a);
                if (k == null) return false;
                kind = k;
                stage = Stage.TEXT;
                return true;
            }
            case TEXT:
                text = "use_name".equals(a) ? projectName : a;
                if (text.isEmpty()) return false;
                stage = Stage.MOOD;
                return true;
            case MOOD: {
                Mood m = matchMood(a);
                if (m == null) return false;
                mood = m;
                // An intro title goes first; nothing to ask.
                if ("title".equals(kindOrTitle().id)) {
                    place("start");
                    stage = Stage.DONE;
                } else {
                    stage = Stage.PLACE;
                }
                return true;
            }
            case PLACE:
                if (!place(a.toLowerCase(Locale.US))) return false;
                stage = Stage.DONE;
                return true;
            default:
                return false;
        }
    }

    private boolean place(String a) {
        if (a.equals("start") || a.contains("start") || a.contains("beginning")) {
            insertAt = 0; placeWords = "at the start";
        } else if (a.equals("end") || a.contains("end") || a.contains("last")) {
            insertAt = clipCount; placeWords = "at the end";
        } else if ((a.equals("here") || a.contains("this") || a.contains("playhead"))
                && clipAtPlayhead > 0 && clipAtPlayhead < clipCount) {
            insertAt = clipAtPlayhead; placeWords = "before the clip under the playhead";
        } else {
            return false;
        }
        return true;
    }

    /** The user's own sentence for the summary bubble, once done. */
    @NonNull
    public String summary() {
        Kind k = kindOrTitle();
        return "Make a " + (mood == null ? "" : mood.label.toLowerCase(Locale.US) + " ")
                + k.label.toLowerCase(Locale.US) + " slide " + placeWords + ".";
    }

    /** The {@code generate_slide} arguments. Only meaningful at {@link Stage#DONE}. */
    @NonNull
    public Map<String, Object> toolArgs() {
        Kind k = kindOrTitle();
        Mood m = mood == null ? MOODS.get(4) : mood;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("mode", "fullscreen");
        args.put("title_or_text", text == null ? projectName : text);
        args.put("style_hint", k.layout + "; " + m.style);
        args.put("duration_ms_hint", k.durationMs);
        args.put("kind", k.id);
        args.put("mood", m.id);
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("insertAtClipIndex", Math.max(0, insertAt));
        args.put("placement", placement);
        return args;
    }

    @NonNull
    private Kind kindOrTitle() {
        return kind == null ? KINDS.get(0) : kind;
    }

    @Nullable
    private static Kind matchKind(String a) {
        String l = a.toLowerCase(Locale.US);
        for (Kind k : KINDS) if (k.id.equals(l) || l.contains(k.id) || l.contains(k.label.toLowerCase(Locale.US))) return k;
        if (l.contains("intro") || l.contains("name")) return KINDS.get(0);
        if (l.contains("section") || l.contains("part")) return KINDS.get(1);
        if (l.contains("bullet") || l.contains("point")) return KINDS.get(3);
        if (l.contains("outro") || l.contains("thank")) return KINDS.get(4);
        return null;
    }

    @Nullable
    private static Mood matchMood(String a) {
        String l = a.toLowerCase(Locale.US);
        for (Mood m : MOODS) if (m.id.equals(l) || l.contains(m.id)) return m;
        if (l.contains("fun") || l.contains("cute")) return MOODS.get(2);
        if (l.contains("dramatic") || l.contains("epic") || l.contains("movie")) return MOODS.get(3);
        if (l.contains("simple") || l.contains("minimal") || l.contains("modern")) return MOODS.get(4);
        if (l.contains("soft") || l.contains("gentle") || l.contains("quiet")) return MOODS.get(0);
        if (l.contains("loud") || l.contains("strong") || l.contains("punch")) return MOODS.get(1);
        return null;
    }
}
