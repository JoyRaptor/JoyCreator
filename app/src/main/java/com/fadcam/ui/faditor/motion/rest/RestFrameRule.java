package com.fadcam.ui.faditor.motion.rest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.motion.MotionBlurItem;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.function.Predicate;

/**
 * STU-7 §K.3 rule 4: when the playhead rests, does the editor owe the person the exact frame?
 *
 * <p>Yes when an object visible at the playhead has live-mode blur (on, not export only) that
 * changes the picture there, and the live preview did not already draw it. The caller says
 * which objects the live path drew ({@code liveDrawn}); anything it is unsure of counts as not
 * drawn, so the frame is rendered. Pure model logic, so it runs on the PC.</p>
 */
public final class RestFrameRule {

    private RestFrameRule() {}

    public static boolean needsFrame(@NonNull Timeline timeline, long ms,
                                     @NonNull Predicate<MotionBlurItem> liveDrawn) {
        for (int i = 0; i < timeline.getClipCount(); i++) {
            Clip clip = timeline.getClip(i);
            long start = timeline.getClipStartMs(i);
            if (ms >= start && ms < start + timeline.getClipSpanMs(i) && owes(clip, ms, liveDrawn)) return true;
        }
        for (Clip pip : timeline.getOverlayClips()) {
            long start = pip.getOverlayStartMs();
            if (ms >= start && ms <= start + pip.getTrimmedDurationMs() && owes(pip, ms, liveDrawn)) return true;
        }
        for (TextOverlayItem item : timeline.getTextOverlays()) {
            if (item.isVisibleAt(ms) && owes(item, ms, liveDrawn)) return true;
        }
        for (SpriteOverlayItem item : timeline.getSpriteOverlays()) {
            if (item.isVisibleAt(ms) && owes(item, ms, liveDrawn)) return true;
        }
        return false;
    }

    private static boolean owes(@NonNull MotionBlurItem item, long ms,
                                @NonNull Predicate<MotionBlurItem> liveDrawn) {
        return item.isMotionBlurInPreview() && blursAt(item, ms) && !liveDrawn.test(item);
    }

    /** The shutter is open and mixed in at {@code ms}: the blur can change the picture. */
    public static boolean blursAt(@NonNull MotionBlurItem item, long ms) {
        MotionShutter.Controls c = item.rigidMotionShutterAt(ms);
        return c.angle() > 0 && c.mixPercent() > 0;
    }

    /**
     * Turn a project COPY into what the editor shows: Export only objects stay sharp (they are
     * never drawn while editing). {@code peekId}, when set, is the object being held: drawn
     * with its blur on, whatever its state.
     */
    public static void forEditor(@NonNull Timeline copy, @Nullable String peekId) {
        for (Clip clip : copy.getClips()) prepare(clip, peekId);
        for (Clip pip : copy.getOverlayClips()) prepare(pip, peekId);
        for (TextOverlayItem item : copy.getTextOverlays()) prepare(item, peekId);
        for (SpriteOverlayItem item : copy.getSpriteOverlays()) prepare(item, peekId);
    }

    private static void prepare(@NonNull MotionBlurItem item, @Nullable String peekId) {
        if (item.getId().equals(peekId)) {
            item.setMotionBlurOff(false);
            item.setMotionBlurExportOnly(false);
            item.setRigidMotionBlurEnabled(true);
        } else if (item.isMotionBlurExportOnly()) {
            item.setRigidMotionBlurEnabled(false);
        }
    }
}
