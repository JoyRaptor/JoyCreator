package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joybot's tool list, read from the one text that describes it ({@code
 * AIToolExecutor.getToolDescriptions()}), so the model's native tool definitions and the
 * JSON-in-text instructions can never disagree. Pure Java (tested in IdeaEngineTest).
 *
 * <p>An entry is a line {@code "  12. add_text_overlay — what it does"} plus every following line
 * indented deeper (or blank). A line back at the entry's own indent ends it — that is how the
 * "EditScript ops for the above" block stays out of the tool before it.</p>
 */
public final class ToolDocs {

    private ToolDocs() {}

    /** One tool: its name and everything the docs say about it. */
    public static final class Doc {
        @NonNull public final String name;
        @NonNull public final String text;

        Doc(@NonNull String name, @NonNull String text) {
            this.name = name;
            this.text = text;
        }
    }

    /** Longest description sent per tool: providers cap them, and the model needs the gist. */
    public static final int MAX_DESCRIPTION = 1000;

    private static final Pattern ENTRY =
            Pattern.compile("^(\\s*)\\d+[a-z]?\\.\\s+([a-z][a-z0-9_]*)\\s+[\\u2014\\-]\\s*(.*)$");

    /** Every tool in the docs, in order; a repeated name keeps its first entry. */
    @NonNull
    public static List<Doc> parse(@NonNull String docs) {
        List<Doc> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        String[] lines = docs.split("\r?\n", -1);   // a Windows checkout reads the source as CRLF
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ENTRY.matcher(lines[i]);
            if (!m.matches()) continue;
            int indent = m.group(1).length();
            String name = m.group(2);
            StringBuilder text = new StringBuilder(m.group(3).trim());
            int j = i + 1;
            for (; j < lines.length; j++) {
                String l = lines[j];
                if (l.trim().isEmpty()) continue;
                if (leading(l) <= indent) break;
                text.append('\n').append(l.trim());
            }
            if (!seen.contains(name)) {
                seen.add(name);
                String t = text.toString().trim();
                if (t.length() > MAX_DESCRIPTION) t = t.substring(0, MAX_DESCRIPTION - 1).trim() + "…";
                out.add(new Doc(name, t));
            }
            i = j - 1;
        }
        return out;
    }

    private static int leading(@NonNull String l) {
        int n = 0;
        while (n < l.length() && l.charAt(n) == ' ') n++;
        return n;
    }
}
