package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An action's arguments, already checked against its {@link ActionSpec}. Getters are forgiving the
 * way models are ("2000" is a number, "true" is a boolean); a missing optional comes back as null
 * so a handler can tell "not asked for" from "asked for zero".
 */
public final class Args {
    @NonNull private final Map<String, Object> m;

    public Args(@NonNull Map<String, Object> m) {
        this.m = m;
    }

    public boolean has(@NonNull String k) {
        Object v = m.get(k);
        return v != null && !(v instanceof String && ((String) v).isEmpty());
    }

    @Nullable
    public String str(@NonNull String k) {
        return has(k) ? String.valueOf(m.get(k)) : null;
    }

    @Nullable
    public Double num(@NonNull String k) {
        return has(k) ? ActionSpec.asNumber(m.get(k)) : null;
    }

    @Nullable
    public Long whole(@NonNull String k) {
        Double d = num(k);
        return d == null ? null : Math.round(d);
    }

    @Nullable
    public Boolean bool(@NonNull String k) {
        if (!has(k)) return null;
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : Boolean.valueOf("true".equalsIgnoreCase(String.valueOf(v)));
    }

    /** #RRGGBB (opaque) or #AARRGGBB as an Android colour int; null when absent. */
    @Nullable
    public Integer color(@NonNull String k) {
        String s = str(k);
        return s == null ? null : parseColor(s);
    }

    /** A list argument; a single string is treated as a one-item list (models do that). */
    @NonNull
    public List<String> list(@NonNull String k) {
        if (!has(k)) return Collections.emptyList();
        Object v = m.get(k);
        List<String> out = new ArrayList<>();
        if (v instanceof List) for (Object o : (List<?>) v) out.add(String.valueOf(o));
        else out.add(String.valueOf(v));
        return out;
    }

    /** ENUM values are matched case-insensitively by the spec; this hands back the spec's spelling. */
    @Nullable
    public String choice(@NonNull String k, @NonNull List<String> values) {
        String s = str(k);
        if (s == null) return null;
        for (String v : values) if (v.equalsIgnoreCase(s)) return v;
        return s;
    }

    @NonNull
    public Map<String, Object> raw() {
        return m;
    }

    @Nullable
    public static Integer parseColor(@NonNull String s) {
        String h = s.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            if (h.length() == 6) return (int) (0xFF000000L | Long.parseLong(h, 16));
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
        } catch (NumberFormatException ignored) { }
        return null;
    }
}
