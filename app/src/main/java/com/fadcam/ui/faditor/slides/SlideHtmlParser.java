package com.fadcam.ui.faditor.slides;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route (a) Restricted HTML → styled runs.
 *
 * <p>Accepted subset (documented per SPEC §3.1):
 * <ul>
 *   <li>{@code <b>}, {@code <strong>} → bold</li>
 *   <li>{@code <i>}, {@code <em>} → italic</li>
 *   <li>{@code <br>} → newline</li>
 *   <li>{@code <p>} → paragraph (newline before if not at start)</li>
 *   <li>{@code <span style="color:#RRGGBB|rgb()|named; font-size:NNpx|NN%|NNem; font-family:'name'">}</li>
 *   <li>All other tags stripped. Attributes other than style are ignored. Unknown style keys ignored.</li>
 * </ul>
 * This keeps drawing deterministic with StaticLayout/Canvas and identical in preview/export.</p>
 */
public final class SlideHtmlParser {

    private SlideHtmlParser() {}

    // Matches tags
    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([a-zA-Z0-9]+)([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_COLOR = Pattern.compile("color\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_SIZE = Pattern.compile("font-size\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_FAMILY = Pattern.compile("font-family\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTR = Pattern.compile("style\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @NonNull
    public static List<SlideDeck.StyledRun> parse(@NonNull String html) {
        List<SlideDeck.StyledRun> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        // Stack of style contexts
        class Ctx {
            boolean bold; boolean italic; int color; float scale = 1f; String family;
            Ctx copy() { Ctx c=new Ctx(); c.bold=bold; c.italic=italic; c.color=color; c.scale=scale; c.family=family; return c; }
        }
        List<Ctx> stack = new ArrayList<>();
        stack.add(new Ctx());

        StringBuilder buf = new StringBuilder();
        Ctx cur = stack.get(0);

        // Helper to flush buffer as a run
        java.util.function.Consumer<Boolean> flush = (force) -> {
            if (buf.length()==0) return;
            String txt = buf.toString();
            // Unescape minimal entities
            txt = txt.replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'");
            if (txt.isEmpty()) { buf.setLength(0); return; }
            SlideDeck.StyledRun r = new SlideDeck.StyledRun(txt);
            r.bold = cur.bold; r.italic = cur.italic; r.color = cur.color; r.fontSizeScale = cur.scale; r.fontFamily = cur.family;
            out.add(r);
            buf.setLength(0);
        };

        Matcher m = TAG.matcher(html);
        int last = 0;
        // Track open tags for proper nesting (simple)
        List<String> openTags = new ArrayList<>();

        while (m.find()) {
            // Text before tag
            if (m.start() > last) {
                String txt = html.substring(last, m.start());
                // Decode entities and append
                txt = txt.replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");
                buf.append(txt);
            }
            String slash = m.group(1);
            String tag = m.group(2).toLowerCase();
            String attrs = m.group(3);

            boolean closing = slash != null && slash.equals("/");

            if (!closing) {
                if (tag.equals("br")) {
                    flush.accept(true);
                    SlideDeck.StyledRun nl = new SlideDeck.StyledRun("\n");
                    nl.isNewline = true;
                    out.add(nl);
                } else if (tag.equals("p")) {
                    // newline before paragraph if not at start
                    if (!out.isEmpty() || buf.length()>0) {
                        flush.accept(true);
                        SlideDeck.StyledRun nl = new SlideDeck.StyledRun("\n");
                        nl.isNewline = true;
                        out.add(nl);
                    }
                    Ctx next = cur.copy();
                    stack.add(next); cur = next; openTags.add("p");
                } else if (tag.equals("b") || tag.equals("strong")) {
                    flush.accept(true);
                    Ctx next = cur.copy(); next.bold = true; stack.add(next); cur = next; openTags.add(tag);
                } else if (tag.equals("i") || tag.equals("em")) {
                    flush.accept(true);
                    Ctx next = cur.copy(); next.italic = true; stack.add(next); cur = next; openTags.add(tag);
                } else if (tag.equals("span")) {
                    flush.accept(true);
                    Ctx next = cur.copy();
                    Matcher sm = STYLE_ATTR.matcher(attrs);
                    if (sm.find()) {
                        String style = sm.group(2);
                        Matcher cm = STYLE_COLOR.matcher(style);
                        if (cm.find()) next.color = parseColor(cm.group(1).trim());
                        Matcher sz = STYLE_SIZE.matcher(style);
                        if (sz.find()) next.scale = parseSizeScale(sz.group(1).trim());
                        Matcher fm = STYLE_FAMILY.matcher(style);
                        if (fm.find()) {
                            String fam = fm.group(1).trim().replace("'","").replace("\"","").split(",")[0].trim();
                            // Keep as-is; renderer will resolve file: via FontLibrary
                            next.family = fam;
                        }
                    }
                    stack.add(next); cur = next; openTags.add("span");
                } else {
                    // Unknown tag: ignore but push context to keep stack balanced for its close
                    // Treat as transparent wrapper
                    flush.accept(true);
                    Ctx next = cur.copy(); stack.add(next); cur = next; openTags.add(tag);
                }
            } else {
                // Closing tag
                flush.accept(true);
                // Pop until matching tag
                for (int i = openTags.size()-1; i>=0; i--) {
                    if (openTags.get(i).equals(tag)) {
                        // pop to that level
                        int pops = openTags.size() - i;
                        for (int k=0;k<pops;k++) { stack.remove(stack.size()-1); openTags.remove(openTags.size()-1); }
                        cur = stack.get(stack.size()-1);
                        break;
                    }
                }
                if (tag.equals("p")) {
                    // paragraph end = newline
                    SlideDeck.StyledRun nl = new SlideDeck.StyledRun("\n");
                    nl.isNewline = true;
                    out.add(nl);
                }
            }
            last = m.end();
        }
        if (last < html.length()) {
            String txt = html.substring(last);
            txt = txt.replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");
            buf.append(txt);
        }
        flush.accept(true);
        // Collapse consecutive newlines? Keep as-is, renderer will handle.
        return out;
    }

    private static int parseColor(String s) {
        s = s.trim().toLowerCase();
        try {
            if (s.startsWith("#")) {
                String h = s.substring(1);
                if (h.length()==3) h = ""+h.charAt(0)+h.charAt(0)+h.charAt(1)+h.charAt(1)+h.charAt(2)+h.charAt(2);
                if (h.length()==6) return 0xFF000000 | Integer.parseInt(h,16);
                if (h.length()==8) return (int)Long.parseLong(h,16);
            } else if (s.startsWith("rgb")) {
                // rgb(255,0,0) or rgba
                String nums = s.replaceAll("[^0-9,\\.]", " ").trim();
                String[] parts = nums.split("[,\\s]+");
                int r = parts.length>0? (int)Float.parseFloat(parts[0]):0;
                int g = parts.length>1? (int)Float.parseFloat(parts[1]):0;
                int b = parts.length>2? (int)Float.parseFloat(parts[2]):0;
                int a = 255;
                if (parts.length>3) a = Math.round(Float.parseFloat(parts[3])*255);
                return (a<<24)|(r<<16)|(g<<8)|b;
            } else {
                // named colors minimal set
                switch(s) {
                    case "white": return 0xFFFFFFFF;
                    case "black": return 0xFF000000;
                    case "red": return 0xFFFF0000;
                    case "green": return 0xFF00FF00;
                    case "blue": return 0xFF0000FF;
                    case "yellow": return 0xFFFFFF00;
                    case "gold": return 0xFFFFD700;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static float parseSizeScale(String s) {
        s = s.toLowerCase().trim();
        try {
            if (s.endsWith("px")) {
                float px = Float.parseFloat(s.substring(0,s.length()-2).trim());
                // Assume base 24px → scale
                return Math.max(0.5f, Math.min(3f, px/24f));
            } else if (s.endsWith("%")) {
                float p = Float.parseFloat(s.substring(0,s.length()-1).trim());
                return Math.max(0.5f, Math.min(3f, p/100f));
            } else if (s.endsWith("em")) {
                float e = Float.parseFloat(s.substring(0,s.length()-2).trim());
                return Math.max(0.5f, Math.min(3f, e));
            }
        } catch (Exception ignored) {}
        return 1f;
    }
}
