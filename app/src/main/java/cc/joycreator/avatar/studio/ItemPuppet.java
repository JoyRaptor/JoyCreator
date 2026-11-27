package cc.joycreator.avatar.studio;

import android.graphics.Canvas;
import android.graphics.RectF;

import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

/**
 * Something that draws a performing sprite item in Studio's preview and export: the old rig's
 * {@code AvatarItemPuppet} or a new-format character's {@link CharacterItemPuppet}. Callers keep their rotate and flip
 * around {@link #draw}, as they do today.
 */
public interface ItemPuppet {
    void draw(Canvas canvas, RectF dest, SpriteOverlayItem item, long itemLocalMs, float alpha);

    void release();
}
