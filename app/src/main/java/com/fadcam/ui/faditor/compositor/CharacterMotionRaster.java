package com.fadcam.ui.faditor.compositor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

/**
 * An Avatar Studio character as the picture Studio's motion blur carries (STU-7): its performance
 * at one moment, drawn by its own puppet into a bitmap shaped like the box it occupies. The blur
 * then smears that picture along the character's placement, as it does a sprite's cell, so a
 * character moving across the frame blurs instead of showing its standing picture.
 *
 * <p>Movement inside the character (an arm, a turn of the head) is drawn sharp at each frame: the
 * same as the older avatar items, and what the live room can afford.</p>
 */
public final class CharacterMotionRaster {

    private CharacterMotionRaster() {}

    /**
     * Draw {@code puppet} for {@code item} at timeline {@code timelineMs} into a bitmap whose
     * longer side is {@code maxSide} and whose shape is {@code aspect} (width / height). Reuses
     * {@code reuse} when it has that size (the export draws then uploads at once); returns null
     * when the character cannot draw.
     */
    @Nullable
    public static Bitmap draw(@NonNull cc.joycreator.avatar.studio.ItemPuppet puppet,
                              @NonNull SpriteOverlayItem item, long timelineMs, float aspect,
                              int maxSide, @Nullable Bitmap reuse) {
        if (!(aspect > 0f) || maxSide <= 0) return null;
        int w = aspect >= 1f ? maxSide : Math.max(1, Math.round(maxSide * aspect));
        int h = aspect >= 1f ? Math.max(1, Math.round(maxSide / aspect)) : maxSide;
        Bitmap b = reuse;
        try {
            if (b == null || b.isRecycled() || b.getWidth() != w || b.getHeight() != h) {
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } else {
                b.eraseColor(Color.TRANSPARENT);
            }
            puppet.draw(new Canvas(b), new RectF(0, 0, w, h), item,
                    Math.max(0, item.toLocalMs(timelineMs)), 1f);
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
