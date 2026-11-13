package cc.joycreator.textlab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Joybot's hands in the TextLab room: read the look on screen, pick another, turn its sliders, add or take away
 * pieces, change its words, font and background, save it. Every call runs on the main thread (the room's) and
 * waits for it; a request's changes are ONE undo step (the request's token groups them, as in VisLab).
 * When the room is not open, only the shelf can be read; changes say so in words.
 */
public final class TextLabRoom {

    /** What Joybot is told when it cannot do the thing (words for the person, not an error code). */
    public static final class Refused extends Exception {
        public Refused(String why) {
            super(why);
        }
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    @Nullable private static volatile String lastGroup;

    private TextLabRoom() {}

    public static boolean isOpen() {
        TextLabActivity a = TextLabActivity.current();
        return a != null && !a.isFinishing();
    }

    /** The looks on the shelf (any thread). */
    @NonNull
    public static List<String> lookNames(@NonNull Context c) {
        TextLabActivity a = TextLabActivity.current();
        if (a != null) {
            try {
                return onUi(null, r -> r.lookNames());
            } catch (Refused ignored) { }
        }
        List<String> out = new ArrayList<>();
        for (TextLookShelf.Item it : TlLookFiles.merged(TextLookShelf.builtIn(c), TextLookShelf.inFolder(c))) {
            out.add(it.name + (it.from == TextLookShelf.From.MINE ? " (yours)" : ""));
        }
        return out;
    }

    /** The look on screen in words: its name, words, pieces, slider groups and changed sliders. */
    @NonNull
    public static String describe() throws Refused {
        return onUi(null, TextLabActivity::describe);
    }

    /** One slider group's sliders: label (name), range, value now. */
    @NonNull
    public static String slot(@NonNull String slot) throws Refused {
        return onUi(null, r -> {
            TlParts parts = r.parts();
            String match = null;
            for (String s : parts.slots) if (s.equalsIgnoreCase(slot.trim())) match = s;
            if (match == null) return "No slider group called " + slot + ". Groups: " + parts.slots;
            JSONObject p = r.look().optJSONObject("p");
            StringBuilder b = new StringBuilder(match).append(" sliders:\n");
            for (TlParts.Knob k : parts.inSlot(match)) {
                Object v = p != null && p.has(k.name) ? p.opt(k.name) : k.colour ? k.defColour : (Object) k.def;
                b.append("• ").append(k.label).append(" (").append(k.name).append(") = ").append(v);
                if (!k.colour) b.append(String.format(Locale.US, " [%s…%s]", trim(k.min), trim(k.max)));
                else b.append(" [a colour #rrggbb]");
                b.append('\n');
            }
            return b.toString();
        });
    }

    /** The pieces a look can be built from, and which this look has. */
    @NonNull
    public static String pieces() throws Refused {
        return onUi(null, r -> {
            StringBuilder b = new StringBuilder("Pieces (★ = this look has it): ");
            java.util.Set<String> has = TlParts.features(r.look());
            for (TlParts.Piece p : r.parts().pieces) b.append(has.contains(p.feature) ? "★" : "").append(p.slot).append(", ");
            return b.substring(0, b.length() - 2) + ".";
        });
    }

    public static boolean pick(@NonNull String look, @Nullable String group) throws Refused {
        return onUi(group, r -> r.chooseByName(look));
    }

    /**
     * Apply lines: "<slider label or name> = <number or #colour>", "piece <name> on|off", "words = …",
     * "font = <name>", "background = <name>". Returns one line per change, in words.
     */
    @NonNull
    public static List<String> change(@NonNull List<String> lines, @Nullable String group) throws Refused {
        return onUi(group, r -> {
            List<String> done = new ArrayList<>();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                String low = line.toLowerCase(Locale.US);
                if (low.startsWith("piece ")) {
                    String rest = line.substring(6).trim();
                    boolean on = !rest.toLowerCase(Locale.US).endsWith(" off");
                    String name = rest.replaceAll("(?i)\\s+(on|off)$", "").trim();
                    String s = r.setPieceFromJoybot(name, on);
                    done.add(s == null ? "No piece called " + name + "." : (on ? "Added " : "Took away ") + s + ".");
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    done.add("Not understood: " + line);
                    continue;
                }
                String key = line.substring(0, eq).trim(), val = line.substring(eq + 1).trim();
                String k = key.toLowerCase(Locale.US);
                if (k.equals("words") || k.equals("text")) {
                    r.setWordsFromJoybot(val.replace("\\n", "\n"));
                    done.add("Words: " + val);
                } else if (k.equals("font")) {
                    done.add(r.setFontFromJoybot(val));
                } else if (k.equals("background") || k.equals("stage")) {
                    done.add(r.setStageFromJoybot(val));
                } else {
                    Object v = val.startsWith("#") ? val : (Object) val;
                    String label = r.setKnobFromJoybot(key, v);
                    done.add(label == null ? "No slider called " + key + " (textlab_describe lists them)." : label + " = " + val);
                }
            }
            return done;
        });
    }

    /** Save the look on screen (over itself, or as a new look named {@code asNew}). */
    @NonNull
    public static String save(@Nullable String asNew) throws Refused {
        return onUi(null, r -> r.saveFromJoybot(asNew));
    }

    // ---- main thread --------------------------------------------------------------------------------

    private static <T> T onUi(@Nullable String group, @NonNull Function<TextLabActivity, T> job) throws Refused {
        TextLabActivity a = TextLabActivity.current();
        if (a == null || a.isFinishing()) {
            throw new Refused("The TextLab room isn't open (open_room textlab opens it).");
        }
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<RuntimeException> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Runnable run = () -> {
            try {
                // one request, one undo step: the first change of a request remembers how the look was
                boolean first = group == null || !group.equals(lastGroup);
                if (group != null) lastGroup = group;
                a.joybotStep(group != null && first, group != null);
                out.set(job.apply(a));
            } catch (RuntimeException e) {
                err.set(e);
            } finally {
                a.joybotStep(false, false);
                done.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) run.run();
        else {
            UI.post(run);
            try {
                if (!done.await(5, TimeUnit.SECONDS)) throw new Refused("TextLab is busy; try again in a moment.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Refused("Interrupted.");
            }
        }
        if (err.get() != null) throw new Refused("That didn't work: " + err.get().getMessage());
        return out.get();
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.3f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
