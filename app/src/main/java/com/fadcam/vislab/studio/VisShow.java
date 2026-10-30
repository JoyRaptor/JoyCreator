package com.fadcam.vislab.studio;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picture layers of a VisLab look that show a Studio item instead of their own picture (roadmap
 * R9), so the look's picture effects (pump, shake, halo, RGB split, tint) and a follow (R8) work
 * on Studio's own things: a picture, a title, a sprite (or a performing avatar), an overlay video,
 * or the main video, each as it is at this moment of the timeline.
 *
 * <p>Each shown layer gets a picture reference for {@code VisRenderer.setLayerImages}:</p>
 * <ul>
 *   <li>a picture: its own file, which VisLab already loads;</li>
 *   <li>a title: {@code studio-text:<id>#<signature>}, drawn by Studio's title renderer
 *       ({@link #textItems}); the signature changes with the words and the style, so a title is
 *       redrawn only when it changes;</li>
 *   <li>a sprite: {@code studio-sprite:<id>}, a moving picture drawn at this moment ({@link #sprites});</li>
 *   <li>a video, overlay or main: {@code studio-video:<id>}, a moving picture, the source's frame at
 *       {@link #videos} (a main-track picture clip shows as that picture).</li>
 * </ul>
 */
public final class VisShow {

    /** The prefix of a title's picture reference. */
    public static final String TEXT = "studio-text:";
    /** The prefix of a sprite's picture reference (it moves). */
    public static final String SPRITE = "studio-sprite:";
    /** The prefix of a video's picture reference (it moves). */
    public static final String VIDEO = "studio-video:";

    /** Where a shown video is at this moment: its file and the time in it. */
    public static final class VideoAt {
        @NonNull public final Uri uri;
        public final long sourceMs;

        VideoAt(@NonNull Uri uri, long sourceMs) {
            this.uri = uri;
            this.sourceMs = sourceMs;
        }
    }

    /** {@code [layerIndex, ref]} pairs for the look shown now, or null. */
    @Nullable public final String[] pairs;
    /** The titles those references name, by reference. */
    @NonNull public final Map<String, TextOverlayItem> textItems;
    /** The sprites those references name, by reference. */
    @NonNull public final Map<String, SpriteOverlayItem> sprites;
    /** The videos those references name, by reference, with the time to show. */
    @NonNull public final Map<String, VideoAt> videos;
    /** The timeline time these were taken at (a sprite's frame is chosen by it). */
    public final long timelineMs;

    private VisShow(@Nullable String[] pairs, @NonNull Map<String, TextOverlayItem> textItems,
                    @NonNull Map<String, SpriteOverlayItem> sprites,
                    @NonNull Map<String, VideoAt> videos, long timelineMs) {
        this.pairs = pairs;
        this.textItems = textItems;
        this.sprites = sprites;
        this.videos = videos;
        this.timelineMs = timelineMs;
    }

    @NonNull
    public static VisShow at(@NonNull WaveformOverlayInstance o, long timelineMs) {
        Map<String, TextOverlayItem> texts = new HashMap<>();
        Map<String, SpriteOverlayItem> sprites = new HashMap<>();
        Map<String, VideoAt> videos = new HashMap<>();
        Map<String, Object> refs = o.getVisShowRefs();
        String look = refs.isEmpty() ? null : o.visLookAt(timelineMs);
        if (look == null) return new VisShow(null, texts, sprites, videos, timelineMs);
        String prefix = look + "/";
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : refs.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String index = e.getKey().substring(prefix.length());
            Object shown = e.getValue();
            String ref = null;
            if (shown instanceof TextOverlayItem) {
                TextOverlayItem item = (TextOverlayItem) shown;
                if (item.isImage()) {
                    ref = item.getImageUri();
                } else {
                    ref = TEXT + item.getId() + "#" + signature(item);
                    texts.put(ref, item);
                }
            } else if (shown instanceof SpriteOverlayItem) {
                SpriteOverlayItem s = (SpriteOverlayItem) shown;
                ref = SPRITE + s.getId();
                sprites.put(ref, s);
            } else if (shown instanceof Clip) {
                Clip c = (Clip) shown;
                if (c.getSourceUri() != null) {
                    ref = VIDEO + c.getId();
                    videos.put(ref, new VideoAt(c.getSourceUri(), overlaySourceMs(c, timelineMs)));
                }
            } else if (shown instanceof Timeline) {
                ref = mainVideo((Timeline) shown, timelineMs, videos);
            }
            if (ref == null) continue;
            out.add(index);
            out.add(ref);
        }
        return new VisShow(out.isEmpty() ? null : out.toArray(new String[0]), texts, sprites, videos,
                timelineMs);
    }

    /**
     * An overlay video's own time at {@code timelineMs}: as the export reads a picture-in-picture
     * (PipFrameOverlay), held at its first or last frame outside its stretch of the timeline.
     */
    static long overlaySourceMs(@NonNull Clip c, long timelineMs) {
        long into = Math.max(0, Math.min(Math.max(0, c.getTrimmedDurationMs()),
                timelineMs - c.getOverlayStartMs()));
        return Math.min(c.getInPointMs() + into, Math.max(c.getInPointMs(), c.getOutPointMs() - 1));
    }

    /**
     * The main video at {@code timelineMs}: the clip playing there, at its own time. A picture clip
     * shows as that picture (its file, a still reference); a gap shows nothing new (null).
     */
    @Nullable
    private static String mainVideo(@NonNull Timeline t, long timelineMs, @NonNull Map<String, VideoAt> videos) {
        long cursor = 0;
        for (Clip clip : t.getClips()) {
            long dur = clip.hasLoopExtension() ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs();
            if (timelineMs >= cursor && timelineMs < cursor + dur) {
                if (clip.getSourceUri() == null) return null;
                if (clip.isImageClip()) return clip.getSourceUri().toString();
                String ref = VIDEO + WaveformOverlayInstance.SHOW_MAIN_VIDEO + ":" + clip.getId();
                videos.put(ref, new VideoAt(clip.getSourceUri(), clip.mapToSourceMs(timelineMs - cursor)));
                return ref;
            }
            cursor += dur;
        }
        return null;
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
