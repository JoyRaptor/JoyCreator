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
     * Validates a model reply against the contract.
     *
     * @return null if acceptable, otherwise a one-line reason for rejection.
     */
    @Nullable
    public static String validate(@NonNull String html) {
        if (!html.contains("Faditor.register(")) {
            return "missing Faditor.register(...) call";
        }
        if (countOccurrences(html, "gsap.timeline(") != 1) {
            return "must contain exactly one gsap.timeline(...)";
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
