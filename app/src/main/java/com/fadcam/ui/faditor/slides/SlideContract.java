package com.fadcam.ui.faditor.slides;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The slide-generation contract: the system-prompt template sent to the model,
 * the validation pass run on its reply, and the built-in fallback template used
 * when no model is involved (Phase 1 inserts) or when a model reply fails
 * validation twice (Phase 3).
 *
 * <p>The contract is deliberately strict so that instruction-following — not raw
 * model quality — is what matters, letting any reasonably competent model author
 * a working slide.</p>
 */
public final class SlideContract {

    private SlideContract() { }

    /**
     * Version of the slide contract (runtime API + validation rules). Embedded in
     * the copyable external prompt and echoed back by the authored HTML as an
     * HTML comment, so a future renderer can detect slides authored against a
     * stale prompt.
     */
    public static final int CONTRACT_VERSION = 1;

    /** Marker comment the external prompt asks the model to include verbatim. */
    public static final String CONTRACT_MARKER = "faditor-slide-contract v";

    /** Default fullscreen background. */
    public static final String DEFAULT_BG = "#0b0e14";

    /**
     * Builds the per-call system prompt. The user message is just the requested
     * title/text. The model must return raw HTML only.
     */
    @NonNull
    public static String buildSystemPrompt(int width, int height, @NonNull String mode,
                                           @NonNull String titleOrText,
                                           @NonNull String styleHint, long durationHintMs) {
        String bg = MODE_OVERLAY.equals(mode) ? "transparent" : DEFAULT_BG;
        return "You are an animation engineer generating a single self-contained HTML file\n"
                + "for a mobile video editor named Faditor. Your output becomes one short\n"
                + "animated clip (\"slide\") that gets rasterized into real video frames.\n"
                + "Follow these rules exactly.\n\n"
                + "OUTPUT FORMAT\n"
                + "- Return ONLY raw HTML. No markdown code fences, no explanation, no\n"
                + "  commentary before or after the HTML.\n"
                + "- Reference exactly these two local scripts, in this order, nothing else\n"
                + "  external:\n"
                + "  <script src=\"gsap.min.js\"></script>\n"
                + "  <script src=\"faditor_runtime.js\"></script>\n"
                + "- No other external resource of any kind: no CDNs, no Google Fonts links,\n"
                + "  no remote images, no fetch/XMLHttpRequest/WebSocket. The renderer may be\n"
                + "  fully offline.\n\n"
                + "CANVAS\n"
                + "- The stage is a div with id=\"stage\" sized exactly " + width + "x" + height + "\n"
                + "  pixels. Fill it edge-to-edge. Use fixed pixel values, not vw/vh.\n"
                + "- Mode is \"" + mode + "\". If \"overlay\": <body> and #stage background MUST be\n"
                + "  transparent so this composites over existing video. If \"fullscreen\":\n"
                + "  design a complete background — it fully replaces the video frame for its\n"
                + "  duration.\n\n"
                + "TIMING — THE MOST IMPORTANT RULE\n"
                + "- Build exactly one GSAP timeline. Never use setInterval, setTimeout,\n"
                + "  requestAnimationFrame, infinite/auto-running CSS animations, or\n"
                + "  Date.now() to drive visual state. The host app owns time: it will call\n"
                + "  Faditor.seek(ms) with arbitrary, possibly out-of-order timestamps. Your\n"
                + "  animation must look correct at any single timestamp, not just when\n"
                + "  played start-to-finish.\n"
                + "- When your timeline is fully built, call exactly once:\n"
                + "  Faditor.register(timeline, durationMs);\n"
                + "  where durationMs is your own authored length (aim for roughly\n"
                + "  " + durationHintMs + "ms, exact precision not required — author for what\n"
                + "  looks good).\n"
                + "- The host app may hold your final frame longer than your authored\n"
                + "  duration, or cut you off early. Design an ending that looks fine frozen.\n\n"
                + "CONTENT\n"
                + "- Requested content: \"" + sanitize(titleOrText) + "\"\n"
                + "- Style direction: \"" + sanitize(styleHint) + "\"\n"
                + "- Fonts: system-safe only (-apple-system, system-ui, Arial, Georgia,\n"
                + "  monospace). Do not @import or link any other font.\n\n"
                + "SKELETON TO FOLLOW\n"
                + "<!doctype html><html><head><meta charset=\"utf-8\">\n"
                + "<style>\n"
                + "  html,body{margin:0;padding:0;background:" + bg + ";}\n"
                + "  #stage{width:" + width + "px;height:" + height + "px;position:relative;overflow:hidden;}\n"
                + "  /* your CSS here */\n"
                + "</style></head>\n"
                + "<body>\n"
                + "<div id=\"stage\">\n"
                + "  <!-- your markup here -->\n"
                + "</div>\n"
                + "<script src=\"gsap.min.js\"></script>\n"
                + "<script src=\"faditor_runtime.js\"></script>\n"
                + "<script>\n"
                + "  const tl = gsap.timeline({ paused: true });\n"
                + "  // tl.from(...).to(...) etc.\n"
                + "  Faditor.register(tl, /* durationMs */ " + durationHintMs + ");\n"
                + "</script>\n"
                + "</body></html>";
    }

    public static final String MODE_FULLSCREEN = "fullscreen";
    public static final String MODE_OVERLAY = "overlay";

    /**
     * The API-less path: a prompt the user copies into ANY external chatbot
     * (including ones with no API access). It teaches the same contract the
     * renderer consumes — single self-contained HTML, fixed duration, animation
     * purely a function of time — and asks the model to echo the contract-version
     * marker so a future import can detect stale prompts. The user pastes the
     * resulting HTML back through the import entry, which feeds the exact same
     * HTML→MP4 pipeline as the in-app AI path.
     */
    @NonNull
    public static String buildExternalPrompt(int width, int height, long durationHintMs) {
        return "I'm using a mobile video editor (Faditor) that can turn one self-contained\n"
                + "HTML file into a short animated video clip (\"slide\") — a chapter card,\n"
                + "stylized title, that kind of thing. Please author that HTML file for me.\n"
                + "The editor rasterizes it into real video frames, so follow these rules\n"
                + "exactly.\n\n"
                + "OUTPUT FORMAT\n"
                + "- Return ONE complete HTML file, nothing else. A single code block is\n"
                + "  fine, but no explanation mixed into the HTML.\n"
                + "- Include this exact comment on the line right after <!doctype html>:\n"
                + "  <!-- " + CONTRACT_MARKER + CONTRACT_VERSION + " -->\n"
                + "- Reference exactly these two local scripts, in this order, nothing else\n"
                + "  external (the editor provides both files at render time — do NOT\n"
                + "  inline or substitute them, and don't worry that they won't load in a\n"
                + "  desktop browser preview):\n"
                + "  <script src=\"gsap.min.js\"></script>\n"
                + "  <script src=\"faditor_runtime.js\"></script>\n"
                + "- No other external resource of any kind: no CDNs, no Google Fonts links,\n"
                + "  no remote images, no fetch/XMLHttpRequest/WebSocket. The renderer may be\n"
                + "  fully offline.\n"
                + "- EMBEDDED assets are fine and encouraged: inline <svg> (the best way to\n"
                + "  draw diagrams, maps, scenes — GSAP animates SVG shapes/paths/groups\n"
                + "  directly), images as base64 data: URIs, and @font-face fonts as data:\n"
                + "  URIs. Everything must live inside this one file.\n\n"
                + "CANVAS\n"
                + "- The stage is a div with id=\"stage\" sized exactly " + width + "x" + height + "\n"
                + "  pixels. Fill it edge-to-edge. Use fixed pixel values, not vw/vh.\n"
                + "- Design a complete background — the slide fully replaces the video frame\n"
                + "  for its duration.\n\n"
                + "TIMING — THE MOST IMPORTANT RULE\n"
                + "- Drive ALL visual state from GSAP timelines (gsap is provided by\n"
                + "  gsap.min.js). Never use setInterval, setTimeout, requestAnimationFrame,\n"
                + "  infinite/auto-running CSS animations, or Date.now() to drive visual\n"
                + "  state. The editor owns time: it calls Faditor.seek(ms) with arbitrary,\n"
                + "  possibly out-of-order timestamps. Your animation must look correct at\n"
                + "  any single timestamp, not just when played start-to-finish.\n"
                + "- Nested/child timelines are fine (master.add(childTl, atSeconds)) for\n"
                + "  complex choreography. You can place tweens at absolute times\n"
                + "  (tl.to(target, {...}, 2.5)) — useful to sync text reveals to a\n"
                + "  narration transcript if I give you one with timestamps.\n"
                + "- When your MASTER timeline is fully built, call exactly once:\n"
                + "  Faditor.register(masterTimeline, durationMs);\n"
                + "  (Faditor is provided by faditor_runtime.js.) durationMs is your own\n"
                + "  authored length as a plain integer literal — aim for roughly\n"
                + "  " + durationHintMs + "ms, exact precision not required.\n"
                + "- The editor may hold your final frame longer than your authored\n"
                + "  duration, stretch the whole animation over a longer clip, or cut it\n"
                + "  off early. Design a start and an ending that look fine frozen.\n\n"
                + "CONTENT\n"
                + "- Fonts: system-safe (-apple-system, system-ui, Arial, Georgia,\n"
                + "  monospace) or an embedded data:-URI @font-face. No linked/remote fonts.\n"
                + "- NO interactivity: no buttons, hover states, or click handlers — the\n"
                + "  result is rasterized into plain video frames.\n"
                + "- ONE scene per file. If I ask for a multi-slide deck, produce one\n"
                + "  complete HTML file per slide, each with its own Faditor.register call.\n"
                + "- I'll describe what the slide should say and how it should look in my\n"
                + "  next message. If I haven't yet, ask me.\n\n"
                + "SKELETON TO FOLLOW\n"
                + "<!doctype html>\n"
                + "<!-- " + CONTRACT_MARKER + CONTRACT_VERSION + " -->\n"
                + "<html><head><meta charset=\"utf-8\">\n"
                + "<style>\n"
                + "  html,body{margin:0;padding:0;background:" + DEFAULT_BG + ";}\n"
                + "  #stage{width:" + width + "px;height:" + height + "px;position:relative;overflow:hidden;}\n"
                + "  /* your CSS here */\n"
                + "</style></head>\n"
                + "<body>\n"
                + "<div id=\"stage\">\n"
                + "  <!-- your markup here -->\n"
                + "</div>\n"
                + "<script src=\"gsap.min.js\"></script>\n"
                + "<script src=\"faditor_runtime.js\"></script>\n"
                + "<script>\n"
                + "  const tl = gsap.timeline({ paused: true });\n"
                + "  // tl.from(...).to(...) etc.\n"
                + "  Faditor.register(tl, /* durationMs */ " + durationHintMs + ");\n"
                + "</script>\n"
                + "</body></html>";
    }

    /**
     * Contract version echoed in the HTML's marker comment, or -1 when absent
     * (in-app-authored slides and pre-marker prompts don't carry one).
     */
    public static int extractContractVersion(@NonNull String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(CONTRACT_MARKER) + "(\\d+)")
                .matcher(html);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    /**
     * The authored duration from the HTML's {@code Faditor.register(tl, N)} call,
     * or {@code fallbackMs} when it can't be parsed (non-literal expression).
     */
    public static long extractAuthoredDurationMs(@NonNull String html, long fallbackMs) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Faditor\\.register\\s*\\(\\s*[^,]+,\\s*(?:/\\*[^*]*\\*/\\s*)?(\\d+)")
                .matcher(html);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) { }
        }
        return fallbackMs;
    }

    /**
     * Validates a model reply against the contract.
     *
     * @return null if acceptable, otherwise a one-line reason for rejection.
     */
    @Nullable
    public static String validate(@NonNull String html) {
        if (countOccurrences(html, "Faditor.register(") != 1) {
            return html.contains("Faditor.register(")
                    ? "must call Faditor.register(...) exactly once"
                    : "missing Faditor.register(...) call";
        }
        // Complex slides legitimately nest child timelines inside a master
        // (tl.add(childTl)) — require at least one, register exactly one.
        if (countOccurrences(html, "gsap.timeline(") < 1) {
            return "must contain at least one gsap.timeline(...)";
        }
        String[] banned = {"fetch(", "XMLHttpRequest", "setInterval(", "setTimeout(",
                "requestAnimationFrame(", "Date.now(", "<script src=\"http",
                "@import url(http"};
        for (String b : banned) {
            if (html.contains(b)) return "contains forbidden token: " + b;
        }
        return null;
    }

    /** Strips markdown fences a model may wrap around the HTML. */
    @NonNull
    public static String stripFences(@NonNull String reply) {
        String s = reply.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) s = s.substring(0, lastFence);
        }
        return s.trim();
    }

    /**
     * The built-in minimal slide: centered text that fades in, holds, fades out.
     * Mirrors the Phase 0 sample fixture, parameterized for dimensions / mode /
     * text. Always passes {@link #validate}.
     */
    @NonNull
    public static String buildFallbackHtml(int width, int height, @NonNull String mode,
                                           @NonNull String text, long durationMs) {
        boolean overlay = MODE_OVERLAY.equals(mode);
        String bg = overlay ? "transparent" : DEFAULT_BG;
        long holdMs = Math.max(400, durationMs - 1100);
        double holdSec = holdMs / 1000.0;
        int fontSize = Math.max(28, Math.round(width * 0.06f));
        String esc = htmlEscape(text);
        return "<!doctype html><html><head><meta charset=\"utf-8\">\n"
                + "<style>\n"
                + "  html,body{margin:0;padding:0;background:" + bg + ";}\n"
                + "  #stage{width:" + width + "px;height:" + height + "px;position:relative;overflow:hidden;\n"
                + "         display:flex;align-items:center;justify-content:center;}\n"
                + "  #label{font-family:-apple-system,system-ui,Arial,sans-serif;\n"
                + "         font-size:" + fontSize + "px;color:#f5f5f0;font-weight:600;opacity:0;\n"
                + "         text-align:center;padding:0 8%;transform:translateY(30px);}\n"
                + "</style></head>\n"
                + "<body>\n"
                + "<div id=\"stage\"><div id=\"label\">" + esc + "</div></div>\n"
                + "<script src=\"gsap.min.js\"></script>\n"
                + "<script src=\"faditor_runtime.js\"></script>\n"
                + "<script>\n"
                + "  const tl = gsap.timeline({ paused: true });\n"
                + "  tl.to(\"#label\", { opacity: 1, y: 0, duration: 0.6, ease: \"power2.out\" })\n"
                + "    .to(\"#label\", { duration: " + holdSec + " })\n"
                + "    .to(\"#label\", { opacity: 0, y: -20, duration: 0.5, ease: \"power2.in\" });\n"
                + "  Faditor.register(tl, " + durationMs + ");\n"
                + "</script>\n"
                + "</body></html>";
    }

    private static int countOccurrences(@NonNull String haystack, @NonNull String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @NonNull
    private static String sanitize(@NonNull String s) {
        return s.replace("\"", "'").replace("\n", " ").trim();
    }

    @NonNull
    private static String htmlEscape(@NonNull String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
