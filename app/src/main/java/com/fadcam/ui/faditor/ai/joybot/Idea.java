package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One suggestion Joybot can offer as a button.
 *
 * <p>A button always says, as the user's own words, what tapping it asks for ({@link #say}), so a
 * tap and typing the same sentence land in the same conversation. What happens next depends on
 * {@link #kind}: a {@link Kind#DIRECT} idea runs its tool steps on the phone with no model call
 * (instant, free, works offline); {@link Kind#ASK} hands the sentence to the model; the others
 * open a guided flow, go somewhere in the app, or just focus the text box.</p>
 */
public final class Idea {

    public enum Kind {
        /** Run {@link #steps} directly through the tool executor. */
        DIRECT,
        /** Send {@link #say} to the model as if typed. */
        ASK,
        /** Start a guided flow named by {@link #flow} ("slide", …). */
        FLOW,
        /** Go somewhere in the app, named by {@link #flow} ("record", "export", …). */
        ROUTE,
        /** "Something else?" — focus the text box. */
        TYPE
    }

    /** One tool call: the same {name, args} pair the model would emit. */
    public static final class Step {
        @NonNull public final String tool;
        @NonNull public final Map<String, Object> args;

        public Step(@NonNull String tool, @NonNull Map<String, Object> args) {
            this.tool = tool;
            this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }

        @NonNull
        public static Step of(@NonNull String tool, Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
            return new Step(tool, m);
        }

        @Override public String toString() { return tool + args; }
    }

    /** What kind of suggestion this is, stable across sessions: the unit of "stay quiet". */
    @NonNull public final String key;
    /** The thing it is about (a clip id, a sheet id), or "" for the whole project. */
    @NonNull public final String target;
    /** The button text: a few words. */
    @NonNull public final String label;
    /** The user's bubble when tapped: a sentence they could have typed. */
    @NonNull public final String say;
    /** Joybot's "I noticed…" sentence, built only from measured facts. Null for a secondary button. */
    @Nullable public final String opener;
    /** Hover label: what the button will do, in plain words. */
    @NonNull public final String hint;
    /** The undo-history label for a DIRECT idea ("Cut 12 filler words"). */
    @NonNull public final String undoLabel;
    @NonNull public final Kind kind;
    @NonNull public final List<Step> steps;
    /** FLOW / ROUTE name. */
    @NonNull public final String flow;
    /** Needs a model (a key or a custom service). */
    public final boolean needsKey;
    /** Higher shows first. */
    public final int priority;

    private Idea(Builder b) {
        key = b.key;
        target = b.target;
        label = b.label;
        say = b.say == null ? b.label : b.say;
        opener = b.opener;
        hint = b.hint == null ? say : b.hint;
        undoLabel = b.undoLabel == null ? label : b.undoLabel;
        kind = b.kind;
        steps = Collections.unmodifiableList(new ArrayList<>(b.steps));
        flow = b.flow;
        needsKey = b.needsKey;
        priority = b.priority;
    }

    /** {@code key:target} — what the quiet list stores. */
    @NonNull
    public String quietId() {
        return target.isEmpty() ? key : key + ":" + target;
    }

    @NonNull
    public static Builder of(@NonNull String key, @NonNull Kind kind, @NonNull String label) {
        return new Builder(key, kind, label);
    }

    @Override public String toString() { return label + " [" + quietId() + "]"; }

    public static final class Builder {
        private final String key;
        private final Kind kind;
        private final String label;
        private String target = "";
        private String say, opener, hint, undoLabel;
        private final List<Step> steps = new ArrayList<>();
        private String flow = "";
        private boolean needsKey;
        private int priority;

        private Builder(String key, Kind kind, String label) {
            this.key = key;
            this.kind = kind;
            this.label = label;
        }

        public Builder target(@NonNull String t) { target = t; return this; }
        public Builder say(@NonNull String s) { say = s; return this; }
        public Builder opener(@Nullable String s) { opener = s; return this; }
        public Builder hint(@NonNull String s) { hint = s; return this; }
        public Builder undo(@NonNull String s) { undoLabel = s; return this; }
        public Builder step(@NonNull Step s) { steps.add(s); return this; }
        public Builder flow(@NonNull String f) { flow = f; return this; }
        public Builder needsKey() { needsKey = true; return this; }
        public Builder priority(int p) { priority = p; return this; }
        public Idea build() { return new Idea(this); }
    }
}
