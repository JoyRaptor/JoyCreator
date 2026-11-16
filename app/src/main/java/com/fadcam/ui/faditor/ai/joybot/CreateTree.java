package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Create something": Joybot's wizard in the lobby (BOT-7, the owner's table of 11-15). One bubble
 * per kind of thing to make, ordered by what this person makes most; each kind asks only what it
 * needs, as buttons with the common choices plus "type your own"; then it lands in the right room
 * already set up. Fully offline. Pure Java (IdeaEngineTest); the chat shows it and carries out the
 * {@link Landing}.
 */
public final class CreateTree {
    private CreateTree() { }

    // ── what each question asks ─────────────────────────────────────────────

    public enum Ask { SHAPE, VIDEO_SHAPE, PERSPECTIVE, FPS, TILE, GRID, SHEET, FIND }

    /** One button: what it stands for ({@link #id}), what it says, and its hover label. */
    public static final class Choice {
        @NonNull public final String id, label, hint;

        public Choice(@NonNull String id, @NonNull String label, @NonNull String hint) {
            this.id = id;
            this.label = label;
            this.hint = hint;
        }

        @Override public String toString() { return label; }
    }

    /** One kind of thing to make: a bubble in the tree. */
    public static final class Kind {
        @NonNull public final String id, label, hint;
        /** The room it lands in, which is also the bubble's colour ({@link RoomColours}). */
        @NonNull public final String room;
        /** Its icon: a placeholder Material Symbols name until ICON-1's pictures land. */
        @NonNull public final String icon;
        @NonNull public final List<Ask> asks;

        Kind(@NonNull String id, @NonNull String label, @NonNull String hint, @NonNull String room,
             @NonNull String icon, Ask... asks) {
            this.id = id;
            this.label = label;
            this.hint = hint;
            this.room = room;
            this.icon = icon;
            this.asks = Collections.unmodifiableList(Arrays.asList(asks));
        }

        @Override public String toString() { return id; }
    }

    /** The owner's table, in his order: the order for someone who hasn't made anything yet. */
    public static final List<Kind> KINDS = Collections.unmodifiableList(Arrays.asList(
            new Kind("paint", "Drawing or painting", "A new drawing in JoyBrush, on a board the shape you pick",
                    "joybrush", "palette", Ask.SHAPE),
            new Kind("perspective", "Drawing in perspective", "A new JoyBrush drawing with a 1, 2 or 3-point perspective guide on",
                    "joybrush", "view_in_ar", Ask.PERSPECTIVE, Ask.SHAPE),
            new Kind("animation", "Hand animation", "Frame-by-frame animation in JoyBrush, at the speed you pick",
                    "joybrush", "animation", Ask.SHAPE, Ask.FPS),
            new Kind("tile", "Tile", "A tile that repeats seamlessly, drawn in JoyBrush with tiling on",
                    "joybrush", "grid_view", Ask.TILE),
            new Kind("sprite_new", "Sprite (make one)", "A sprite sheet drawn in JoyBrush on a grid of cells",
                    "joybrush", "apps", Ask.GRID),
            new Kind("sprite_edit", "Sprite (edit one)", "Open one of your sprite sheets in Sprite Lab",
                    "spritelab", "directions_run", Ask.SHEET),
            new Kind("video", "Video", "A new Studio project, the shape you pick (3:1 banners too)",
                    "studio", "movie", Ask.VIDEO_SHAPE),
            new Kind("audio", "Audio project", "A new Studio project for sound only: a podcast, music, a voice-over",
                    "studio", "music_note"),
            new Kind("music_visuals", "Music visuals", "VisLab: pictures that move with a song",
                    "vislab", "graphic_eq"),
            new Kind("titles", "Titles", "TextLab: film-poster words, chrome, gold, neon, fire",
                    "textlab", "title"),
            new Kind("character", "Character or avatar", "Avatar Studio: a puppet character that moves and talks",
                    "avatar", "accessibility_new"),
            new Kind("find", "Find and tidy", "The Library: find projects, exports and recent work; tidy up and free space",
                    "library", "folder_open", Ask.FIND)));

    @Nullable
    public static Kind kind(@Nullable String id) {
        if (id == null) return null;
        for (Kind k : KINDS) if (k.id.equals(id)) return k;
        return null;
    }

    /**
     * The kinds, the ones this person makes most first ({@code counts}: kind id → times made, kept
     * on the phone). Equal counts keep the owner's order, so a new person sees his table as drawn.
     */
    @NonNull
    public static List<Kind> ordered(@NonNull Map<String, Integer> counts) {
        List<Kind> out = new ArrayList<>(KINDS);
        out.sort((a, b) -> Integer.compare(count(counts, b.id), count(counts, a.id)));   // stable
        return out;
    }

    private static int count(Map<String, Integer> m, String id) {
        Integer n = m.get(id);
        return n == null ? 0 : n;
    }

    // ── the questions ───────────────────────────────────────────────────────

    /** One question: Joybot's words, the common answers, and what typing your own looks like. */
    public static final class Question {
        @NonNull public final Ask ask;
        @NonNull public final String text;
        @NonNull public final List<Choice> choices;
        /** The text box's hint for "type your own", or null when typing makes no sense. */
        @Nullable public final String typeHint;

        Question(@NonNull Ask ask, @NonNull String text, @NonNull List<Choice> choices, @Nullable String typeHint) {
            this.ask = ask;
            this.text = text;
            this.choices = Collections.unmodifiableList(choices);
            this.typeHint = typeHint;
        }
    }

    /** "Type your own": focuses the text box. */
    public static final String TYPE_OWN = "type_own";

    @NonNull
    static Question question(@NonNull Ask a, @NonNull List<Choice> sheets) {
        List<Choice> c = new ArrayList<>();
        switch (a) {
            case SHAPE:
                c.add(new Choice("1:1", "Square", "1:1, the same width and height"));
                c.add(new Choice("4:3", "Landscape 4:3", "A little wider than tall, like a sketchbook"));
                c.add(new Choice("3:4", "Portrait 3:4", "A little taller than wide"));
                c.add(new Choice("16:9", "Wide 16:9", "Screen-shaped, like a TV or a YouTube video"));
                c.add(new Choice("9:16", "Tall 9:16", "Phone-shaped, for stories and reels"));
                return new Question(a, "What shape should the board be?", c, "A shape, like 3:1 or 2:3");
            case VIDEO_SHAPE:
                c.add(new Choice("16:9", "Wide 16:9", "YouTube, TV and computer screens"));
                c.add(new Choice("9:16", "Tall 9:16", "Phone-shaped: stories, reels, shorts"));
                c.add(new Choice("1:1", "Square 1:1", "Square, for feeds"));
                c.add(new Choice("4:5", "Feed 4:5", "A little taller than square, for photo feeds"));
                c.add(new Choice("21:9", "Cinema 21:9", "Extra wide, like a film"));
                return new Question(a, "What shape should the video be?", c, "A shape, like 3:1 or 2:3");
            case PERSPECTIVE:
                c.add(new Choice("1", "1-point", "One vanishing point: a road or a hallway heading away"));
                c.add(new Choice("2", "2-point", "Two vanishing points: a building seen from its corner"));
                c.add(new Choice("3", "3-point", "Three: looking up at a tower or down from one"));
                return new Question(a, "Which perspective?", c, null);
            case FPS:
                c.add(new Choice("12", "12 a second", "Classic hand animation (\"on twos\")"));
                c.add(new Choice("8", "8 a second", "Choppy and hand-made, few drawings"));
                c.add(new Choice("24", "24 a second", "Film-smooth, a drawing for every frame"));
                c.add(new Choice("30", "30 a second", "Video-smooth"));
                return new Question(a, "How many frames a second?", c, "A number from 1 to 60");
            case TILE:
                c.add(new Choice("256", "256 px", "Small: game tiles, patterns"));
                c.add(new Choice("512", "512 px", "Medium: most patterns"));
                c.add(new Choice("1024", "1024 px", "Large: detailed textures"));
                return new Question(a, "How big should the tile be?", c, "A size in pixels, like 128 or 300x200");
            case GRID:
                c.add(new Choice("4x4@64", "4 × 4 cells, 64 px", "16 small frames: a walk cycle with room to spare"));
                c.add(new Choice("8x1@64", "8 in a row, 64 px", "One strip of 8 frames"));
                c.add(new Choice("8x8@32", "8 × 8 cells, 32 px", "64 tiny pixel-art frames"));
                c.add(new Choice("4x2@128", "4 × 2 cells, 128 px", "8 big frames"));
                return new Question(a, "What grid?", c, "Columns x rows, then cell size: 6x2 64");
            case SHEET:
                c.addAll(sheets);
                c.add(new Choice("all_sheets", "Show all my sheets", "Your sprite sheets in the Library"));
                return new Question(a, sheets.isEmpty() ? "I can't see any sprite sheets yet. Look in the Library?"
                        : "Which sheet?", c, null);
            case FIND:
            default:
                c.add(new Choice("recent", "My recent work", "Everything you made lately, newest first"));
                c.add(new Choice("projects", "My projects", "Your Studio projects"));
                c.add(new Choice("exports", "My exports", "Videos and pictures you saved out"));
                c.add(new Choice("space", "Free up space", "What takes room on the phone, and clearing it safely"));
                return new Question(Ask.FIND, "What shall we find, or tidy?", c, "What to look for: a name or a word");
        }
    }

    /**
     * What a typed answer (or a button's id) means for {@code a}, normalised; null when it doesn't
     * answer it. SHAPE "3:1"; FPS "12"; TILE "512" or "300x200"; GRID "6x2@64"; PERSPECTIVE "2".
     */
    @Nullable
    public static String read(@NonNull Ask a, @NonNull String raw) {
        String t = raw.toLowerCase(Locale.US).trim();
        if (t.isEmpty()) return null;
        switch (a) {
            case SHAPE:
            case VIDEO_SHAPE: {
                Shape s = Shape.parse(t);
                return s == null ? null : s.ratio();
            }
            case PERSPECTIVE: {
                // "2-point" in a sentence; a bare "2" or "two" as an answer. Never the 3 of "3:1".
                Matcher m = Pattern.compile("\\b([123]|one|two|three)[- ]?points?\\b").matcher(t);
                if (!m.find()) m = Pattern.compile("^([123]|one|two|three)$").matcher(t);
                else m = Pattern.compile("^(.*)$").matcher(m.group(1));
                if (!m.find()) return null;
                switch (m.group(1)) {
                    case "one": return "1";
                    case "two": return "2";
                    case "three": return "3";
                    default: return m.group(1);
                }
            }
            case FPS: {
                // "12 fps", "at 24 frames a second"; a bare "12" as an answer. Never the 16 of "16:9".
                Matcher m = Pattern.compile("\\b(\\d{1,3})\\s*(?:fps|frames?|a second|per second)\\b").matcher(t);
                if (!m.find()) m = Pattern.compile("^(\\d{1,3})$").matcher(t);
                if (!m.find(0)) return null;
                int n = Integer.parseInt(m.group(1));
                return n >= 1 && n <= 60 ? String.valueOf(n) : null;
            }
            case TILE: {
                Matcher wh = Pattern.compile("\\b(\\d{2,4})\\s*(?:x|×|by)\\s*(\\d{2,4})\\b").matcher(t);
                if (wh.find()) {
                    int w = Integer.parseInt(wh.group(1)), h = Integer.parseInt(wh.group(2));
                    return ok(w) && ok(h) ? w + "x" + h : null;
                }
                Matcher m = Pattern.compile("\\b(\\d{2,4})\\b").matcher(t);
                if (!m.find()) return null;
                int n = Integer.parseInt(m.group(1));
                return ok(n) ? String.valueOf(n) : null;
            }
            case GRID: {
                Matcher m = Pattern.compile("\\b(\\d{1,2})\\s*(?:x|×|by)\\s*(\\d{1,2})\\b(?:.*?\\b(\\d{2,4})\\b)?").matcher(t.replace('@', ' '));
                if (!m.find()) return null;
                int cols = Integer.parseInt(m.group(1)), rows = Integer.parseInt(m.group(2));
                int px = m.group(3) == null ? 64 : Integer.parseInt(m.group(3));
                if (cols < 1 || rows < 1 || cols * rows > 256 || px < 8 || px > 1024) return null;
                return cols + "x" + rows + "@" + px;
            }
            case SHEET:
            case FIND:
            default:
                return raw.trim();
        }
    }

    private static boolean ok(int px) {
        return px >= 16 && px <= 4096;
    }

    // ── where it lands ──────────────────────────────────────────────────────

    /** Where the person lands, already set up. */
    public static final class Landing {
        @NonNull public final Kind kind;
        /** The answers, by question: "shape" → "3:1", "fps" → "12", "grid" → "4x4@64"… */
        @NonNull public final Map<String, String> answers;
        /** Joybot's one sentence as he takes them there. */
        @NonNull public final String say;

        Landing(@NonNull Kind kind, @NonNull Map<String, String> answers, @NonNull String say) {
            this.kind = kind;
            this.answers = Collections.unmodifiableMap(answers);
            this.say = say;
        }

        @Nullable public String get(@NonNull String key) { return answers.get(key); }

        @Override public String toString() { return kind.id + answers; }
    }

    @NonNull
    static String key(@NonNull Ask a) {
        switch (a) {
            case SHAPE: case VIDEO_SHAPE: return "shape";
            case PERSPECTIVE: return "perspective";
            case FPS: return "fps";
            case TILE: return "tile";
            case GRID: return "grid";
            case SHEET: return "sheet";
            default: return "find";
        }
    }

    @NonNull
    static String say(@NonNull Kind k, @NonNull Map<String, String> a) {
        switch (k.id) {
            case "paint": return "Opening JoyBrush with a " + shapeWords(a) + " board.";
            case "perspective": return "Opening JoyBrush with a " + a.get("perspective") + "-point perspective guide on a "
                    + shapeWords(a) + " board.";
            case "animation": return "Opening JoyBrush for animation: a " + shapeWords(a) + " board at "
                    + a.get("fps") + " frames a second.";
            case "tile": return "Opening JoyBrush with a " + a.get("tile").replace("x", " × ") + " px tile, tiling on.";
            case "sprite_new": {
                String[] g = a.get("grid").split("@");
                return "Opening JoyBrush with a sprite board: " + g[0].replace("x", " × ") + " cells of " + g[1] + " px.";
            }
            case "sprite_edit": return "all_sheets".equals(a.get("sheet")) ? "Opening your sprite sheets in the Library."
                    : "Opening it in Sprite Lab.";
            case "video": return "Making a new " + shapeWords(a) + " Studio project.";
            case "audio": return "Opening a new audio project in the Studio.";
            case "music_visuals": return "Opening VisLab.";
            case "titles": return "Opening TextLab.";
            case "character": return "Opening Avatar Studio.";
            case "find":
            default: {
                String f = a.get("find");
                if ("space".equals(f)) return "Opening Space: what takes room on the phone.";
                if ("projects".equals(f)) return "Opening your projects.";
                if ("recent".equals(f) || "exports".equals(f)) return "Opening the Library.";
                return "Looking in the Library for “" + f + "”.";
            }
        }
    }

    @NonNull
    private static String shapeWords(@NonNull Map<String, String> a) {
        String s = a.get("shape");
        Shape sh = s == null ? null : Shape.parse(s);
        return sh == null ? "16:9" : sh.words();
    }

    // ── one walk through the tree ───────────────────────────────────────────

    /**
     * One person's way through: pick a kind (or start with one already said), answer its
     * questions, land. Answers already given in their words ("a 3:1 video") are not asked again.
     */
    public static final class Flow {
        @NonNull private final List<Kind> order;
        @NonNull private final List<Choice> sheets;
        @Nullable private Kind kind;
        @NonNull private final Map<String, String> answers = new LinkedHashMap<>();

        public Flow(@NonNull Map<String, Integer> counts, @NonNull List<Choice> sheets) {
            this.order = ordered(counts);
            this.sheets = new ArrayList<>(sheets);
        }

        /** Starts at {@code k} with whatever {@code said} already answers. */
        @NonNull
        public Flow startingWith(@NonNull Kind k, @Nullable String said) {
            kind = k;
            if (said != null) {
                for (Ask a : k.asks) {
                    if (a == Ask.SHEET || a == Ask.FIND) continue;   // a whole sentence is not a sheet's name
                    String v = read(a, said);
                    if (v != null) answers.put(key(a), v);
                }
            }
            return this;
        }

        /** The person's sprite sheets (read from the Library after the walk began). */
        public void setSheets(@NonNull List<Choice> s) {
            sheets.clear();
            sheets.addAll(s);
        }

        /** The kinds, in this person's order (the bubbles). */
        @NonNull public List<Kind> kinds() { return order; }

        @Nullable public Kind kind() { return kind; }

        /** The question to ask now: null while picking a kind, or when done. */
        @Nullable
        public Question question() {
            Ask a = pending();
            return a == null ? null : CreateTree.question(a, sheets);
        }

        public boolean picking() { return kind == null; }

        public boolean done() { return kind != null && pending() == null; }

        @Nullable
        private Ask pending() {
            if (kind == null) return null;
            for (Ask a : kind.asks) if (!answers.containsKey(key(a))) return a;
            return null;
        }

        /**
         * A tap or typed words. While picking: a kind's id or words naming one. Then: a button's id
         * or the person's own answer. False when it doesn't answer what was asked (ask again).
         */
        public boolean answer(@NonNull String raw) {
            if (kind == null) {
                Kind k = CreateTree.kind(raw);
                if (k == null) k = Words.kindIn(raw);
                if (k == null) return false;
                startingWith(k, raw);
                return true;
            }
            Ask a = pending();
            if (a == null) return false;
            if (a == Ask.SHEET) {
                for (Choice c : sheets) if (c.id.equals(raw) || c.label.equalsIgnoreCase(raw.trim())) {
                    answers.put("sheet", c.id);
                    return true;
                }
                if ("all_sheets".equals(raw)) { answers.put("sheet", raw); return true; }
                return false;
            }
            String v = read(a, raw);
            if (v == null) return false;
            answers.put(key(a), v);
            return true;
        }

        /** Where they land; only once {@link #done()}. */
        @NonNull
        public Landing landing() {
            if (!done() || kind == null) throw new IllegalStateException("not done");
            return new Landing(kind, answers, say(kind, answers));
        }
    }

    // ── words that start the tree ───────────────────────────────────────────

    /** Recognising "I want to make something" and "a new blank Studio project, 3:1" in what was said. */
    public static final class Words {
        private Words() { }

        private static final Pattern CREATE_ANYTHING = Pattern.compile(
                "^(?:(?:i (?:want|would like|'d like) to|let's|lets|can (?:i|we|you)|help me) )?"
                        + "(?:create|make|start|build|draw|begin) (?:something|anything|a thing|stuff)(?: new)?\\??$"
                        + "|^create( something)?$|^(?:something )?new$|^i want to (?:create|make)\\??$");

        /** A verb of making something NEW, so "make the title red" in a project never starts the tree. */
        private static final Pattern NEW_THING = Pattern.compile(
                "\\b(?:new|blank|fresh|empty|start(?:ing)?|begin|create|i want to (?:make|draw|paint|animate)|"
                        + "let'?s (?:make|draw|paint)|make (?:a|an|me a|me an)|draw (?:a|an)|paint (?:a|an))\\b");

        /**
         * Kinds a "new …" request starts from inside a room too: whole new projects and drawings.
         * Titles, characters, music visuals and sheets mean something in the room you're in ("a new
         * title" in the Studio is a title card in this project), so only the lobby starts those.
         */
        public static boolean newAnywhere(@NonNull Kind k) {
            switch (k.id) {
                case "video": case "audio": case "paint": case "perspective": case "animation": case "tile": case "sprite_new":
                    return true;
                default:
                    return false;
            }
        }

        /**
         * A how-to question the help book answers ("how do I make a board") is a question, not an
         * order: it gets the answer, never the create questions (Note 9 11-15, BOT-A8).
         */
        public static boolean helpQuestion(@Nullable String text) {
            return text != null && HelpBook.isHowTo(text) && HelpBook.find(text) != null;
        }

        private static final Pattern LEAVE = Pattern.compile(
                "^(?:stop|cancel|never ?mind|forget it|no thanks|not now|quit|exit)\\b");
        private static final Pattern ELSEWHERE = Pattern.compile(
                "^(?:how (?:do|can|does|would|to)|where (?:is|are|do|can)|where's|what|why|who|when|can you|could you"
                        + "|tell me|show me|help\\b|open\\b|take me)");

        /** Mid-create, "stop", "never mind": nothing is made. */
        public static boolean stops(@Nullable String text) {
            return text != null && LEAVE.matcher(norm(text)).find();
        }

        /**
         * Mid-create, words that are plainly something else (a question, "open the Library"): the
         * create questions let go and the words are handled as usual. Before, every later message got
         * "Sorry, I didn't catch that. Try a shape" (Note 9 11-15, BOT-A8). No answer to a create
         * question starts like these ("3:1", "12", "a walk cycle", "Hero sheet").
         */
        public static boolean elsewhere(@Nullable String text) {
            return text != null && ELSEWHERE.matcher(norm(text)).find();
        }

        /** "Create something" with nothing more: show the bubbles. */
        public static boolean anything(@Nullable String text) {
            if (text == null) return false;
            String t = norm(text);
            return CREATE_ANYTHING.matcher(t).find();
        }

        /**
         * The kind a request to make something new names, or null. Only a request for something
         * NEW counts: "make the title red" names titles but makes nothing new.
         */
        @Nullable
        public static Kind start(@Nullable String text) {
            if (text == null) return null;
            String t = norm(text);
            if (!NEW_THING.matcher(t).find()) return null;
            return kindIn(t);
        }

        /** The kind {@code text} names ("perspective", "a podcast"), new or not; null if none. */
        @Nullable
        static Kind kindIn(@NonNull String text) {
            String t = norm(text);
            if (has(t, "perspective|vanishing point|1-point|2-point|3-point|one point|two point|three point")) return kind("perspective");
            if (has(t, "tiles?|tileable|seamless|repeating pattern")) return kind("tile");
            if (has(t, "sprite|sprites|sprite sheet")) {
                return has(t, "edit|open|load|change|fix|my") && !has(t, "new|make|draw|create|blank") ? kind("sprite_edit") : kind("sprite_new");
            }
            if (has(t, "animation|animate|animating|flipbook|flip book|frame by frame|cartoon")) return kind("animation");
            if (has(t, "podcast|audio project|audio only|sound project|music project|voice ?over|song project")) return kind("audio");
            if (has(t, "visuali[sz]er|vislab|vis lab|music visuals?")) return kind("music_visuals");
            if (has(t, "titles?|textlab|text lab|poster words|title card")) return kind("titles");
            if (has(t, "avatar|character|puppet")) return kind("character");
            if (has(t, "video|studio project|studio|film|movie|clip|reel|vlog")
                    || has(t, "project") && !has(t, "drawing|painting|sketch")) return kind("video");
            if (has(t, "drawing|painting|draw|paint|sketch|illustration|picture|artwork|canvas|board")) return kind("paint");
            if (has(t, "find|tidy|clean up|organi[sz]e|free (?:up )?space")) return kind("find");
            return null;
        }

        private static boolean has(@NonNull String t, @NonNull String words) {
            return Pattern.compile("\\b(?:" + words + ")\\b").matcher(t).find();
        }

        @NonNull
        static String norm(@NonNull String s) {
            return s.toLowerCase(Locale.US).replace('’', '\'').replaceAll("[.!,]", " ").replaceAll("\\s+", " ").trim();
        }
    }
}
