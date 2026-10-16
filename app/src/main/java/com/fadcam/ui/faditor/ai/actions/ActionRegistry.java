package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one list of actions (gap F2). Each entry is a spec (what it is, its typed arguments) and a
 * handler (what it does to the model it is handed). Pure Java: {@code C} is whatever the host hands
 * a handler — the Studio passes its loaded project, tests pass a fake.
 *
 * <p>The registry checks arguments before a handler ever sees them, so a handler can trust its
 * {@link Args}; it never saves and never touches views — the host does that once per call.</p>
 */
public final class ActionRegistry<C> {

    /** What an action does. Works on {@code ctx}'s model only; never saves. */
    public interface Handler<C> {
        @NonNull ActionResult run(@NonNull C ctx, @NonNull Args args) throws ActionError;
    }

    private static final class Entry<C> {
        final ActionSpec spec;
        final Handler<C> handler;
        Entry(ActionSpec spec, Handler<C> handler) { this.spec = spec; this.handler = handler; }
    }

    private final Map<String, Entry<C>> entries = new LinkedHashMap<>();

    @NonNull
    public ActionRegistry<C> add(@NonNull ActionSpec spec, @NonNull Handler<C> handler) {
        if (entries.containsKey(spec.tool)) throw new IllegalStateException("registered twice: " + spec.tool);
        entries.put(spec.tool, new Entry<>(spec, handler));
        return this;
    }

    public boolean has(@NonNull String tool) {
        return entries.containsKey(tool);
    }

    @Nullable
    public ActionSpec spec(@NonNull String tool) {
        Entry<C> e = entries.get(tool);
        return e == null ? null : e.spec;
    }

    @NonNull
    public List<ActionSpec> specs() {
        List<ActionSpec> out = new ArrayList<>();
        for (Entry<C> e : entries.values()) out.add(e.spec);
        return out;
    }

    /**
     * Checks {@code args} against the spec, then runs the handler. Bad arguments come back as one
     * {@link ActionError} listing every problem, so the model can fix them all in one go.
     */
    @NonNull
    public ActionResult run(@NonNull C ctx, @NonNull String tool, @NonNull Map<String, Object> args)
            throws ActionError {
        Entry<C> e = entries.get(tool);
        if (e == null) throw new ActionError("There's no action called \"" + tool + "\".");
        List<String> problems = e.spec.check(args);
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder(tool).append(": ");
            for (int i = 0; i < problems.size(); i++) sb.append(i > 0 ? "; " : "").append(problems.get(i));
            throw new ActionError(sb.toString());
        }
        return e.handler.run(ctx, new Args(args));
    }

    /** Native tool definitions as a JSON array's ELEMENTS (no brackets), for splicing into the request. */
    @NonNull
    public List<String> nativeToolsJson() {
        List<String> out = new ArrayList<>();
        for (Entry<C> e : entries.values()) {
            ActionSpec s = e.spec;
            out.add("{\"type\":\"function\",\"function\":{\"name\":" + ActionSpec.quote(s.tool)
                    + ",\"description\":" + ActionSpec.quote(s.description())
                    + ",\"parameters\":" + s.schemaJson() + "}}");
        }
        return out;
    }

    /** The numbered-text form, continuing the legacy list's numbering at {@code firstNumber}. */
    @NonNull
    public String docsText(int firstNumber) {
        StringBuilder sb = new StringBuilder();
        int n = firstNumber;
        for (Entry<C> e : entries.values()) sb.append(e.spec.docsEntry(String.valueOf(n++)));
        return sb.toString();
    }
}
