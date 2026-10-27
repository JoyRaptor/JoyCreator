package com.fadcam.vislab.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picture layers of a VisLab look that show a Studio picture or title instead of their own
 * picture (roadmap R9), so the look's picture effects (pump, shake, halo, RGB split, tint) and a
 * follow (R8) work on Studio's own items.
 *
 * <p>Each shown layer gets a picture reference for {@code VisRenderer.setLayerImages}: a picture's
 * own file, which VisLab already loads, or {@code studio-text:<id>#<signature>} for a title, drawn
 * by Studio's title renderer ({@link #textItems}). The signature changes with the words and the
 * style, so a title is redrawn only when it changes.</p>
 */
public final class VisShow {

    /** The prefix of a title's picture reference. */
    public static final String TEXT = "studio-text:";

    /** {@code [layerIndex, ref]} pairs for the look shown now, or null. */
    @Nullable public final String[] pairs;
    /** The titles those references name, by reference. */
    @NonNull public final Map<String, TextOverlayItem> textItems;

    private VisShow(@Nullable String[] pairs, @NonNull Map<String, TextOverlayItem> textItems) {
        this.pairs = pairs;
        this.textItems = textItems;
    }

    @NonNull
    public static VisShow at(@NonNull WaveformOverlayInstance o, long timelineMs) {
        Map<String, TextOverlayItem> texts = new HashMap<>();
        Map<String, com.fadcam.ui.faditor.model.LinkPose> refs = o.getVisShowRefs();
        String look = refs.isEmpty() ? null : o.visLookAt(timelineMs);
        if (look == null) return new VisShow(null, texts);
        String prefix = look + "/";
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, com.fadcam.ui.faditor.model.LinkPose> e : refs.entrySet()) {
            if (!e.getKey().startsWith(prefix) || !(e.getValue() instanceof TextOverlayItem)) continue;
            String index = e.getKey().substring(prefix.length());
            TextOverlayItem item = (TextOverlayItem) e.getValue();
            String ref;
            if (item.isImage()) {
                ref = item.getImageUri();
            } else {
                ref = TEXT + item.getId() + "#" + signature(item);
                texts.put(ref, item);
            }
            if (ref == null) continue;
            out.add(index);
            out.add(ref);
        }
        return new VisShow(out.isEmpty() ? null : out.toArray(new String[0]), texts);
    }

    /** What a title looks like: its words and style, so a change redraws it. */
    @NonNull
    static String signature(@NonNull TextOverlayItem o) {
        return Integer.toHexString((o.getText() + '|' + o.getFontFamily() + '|' + o.getTextAlign()
                + '|' + o.getColorInt() + '|' + o.getStrokeColorInt() + '|' + o.getStrokeWidthPx()
                + '|' + o.getShadowColorInt() + '|' + o.getShadowRadiusPx() + '|' + o.getGlowColorInt()
                + '|' + o.getBackgroundColorInt()).hashCode());
    }
}
