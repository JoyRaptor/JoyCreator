package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Joybot's idea buttons know about the drawing open in JoyBrush (audit BOT-A9). Read from
 * {@code JoybotHands.describe()} — the same words the model reads — so the buttons and the model
 * agree, and JoyBrush's own files are untouched. The format is pinned by a test that reads
 * JoybotHands.kt (IdeaEngineTest): if JoyBrush changes it, the test says so. Pure Java.
 */
public final class DrawingFacts {
    /** Layer names, bottom first. */
    @NonNull public final List<String> layers = new ArrayList<>();
    public int hiddenLayers;
    /** An animation board exists. */
    public boolean animation;
    /** Frames on the first animation board, and which one is shown (1-based); 0 when none. */
    public int frames;
    public int onFrame;
    public int boards;

    /** Facts from a describe() text, or null when it isn't one (JoyBrush closed, an error). */
    @Nullable
    public static DrawingFacts parse(@Nullable String describe) {
        if (describe == null || !describe.startsWith("Drawing “")) return null;
        DrawingFacts d = new DrawingFacts();
        Pattern layer = Pattern.compile("^[★•] (\\d+)\\. (.*?) — (shown|hidden), .*$");
        Pattern anim = Pattern.compile("^Board “.*” \\(animation\\): (\\d+) frames?, on frame (\\d+),.*$");
        List<String[]> found = new ArrayList<>();
        for (String raw : describe.split("\r?\n")) {
            String l = raw.trim();
            Matcher m = layer.matcher(l);
            if (m.matches()) {
                found.add(new String[]{m.group(1), m.group(2)});
                if ("hidden".equals(m.group(3))) d.hiddenLayers++;
                continue;
            }
            if (l.startsWith("Board “")) {
                d.boards++;
                Matcher a = anim.matcher(l.replace(" ★", ""));
                if (a.matches() && !d.animation) {
                    d.animation = true;
                    d.frames = Integer.parseInt(a.group(1));
                    d.onFrame = Integer.parseInt(a.group(2));
                }
            }
        }
        // describe() lists top to bottom; keep bottom first, by the number it gives.
        found.sort((x, y) -> Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0])));
        for (String[] f : found) d.layers.add(f[1]);
        return d;
    }

    /** A layer whose name says it is for colour already exists. */
    public boolean hasColourLayer() {
        for (String n : layers) {
            String l = n.toLowerCase(Locale.US);
            if (l.contains("colour") || l.contains("color") || l.contains("paint") || l.contains("flat")) return true;
        }
        return false;
    }
}
