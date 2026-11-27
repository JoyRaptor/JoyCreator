package cc.joycreator.avatar.studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

import com.fadcam.ui.faditor.avatar.AvatarItemPuppet;
import com.fadcam.ui.faditor.avatar.AvatarRig;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import java.util.function.Function;

/**
 * The one choice of puppet for a sprite item, for Studio's three draw sites (preview, Canvas export, GL export):
 * a new-format character gets {@link CharacterItemPuppet}; an old avatar with a recorded track gets exactly today's
 * {@link AvatarItemPuppet}; anything else gets null (the plain sprite draw).
 */
public final class ItemPuppets {
    private ItemPuppets() {}

    /** True when {@link #forItem} would give a puppet (the sites use it for their "needs a puppet" check). */
    public static boolean performs(SpriteOverlayItem item) {
        return item.isCharacter() || item.hasAvatarPerformance();
    }

    public static ItemPuppet forItem(Context context, SpriteOverlayItem item, AvatarRig rig,
                                     Function<String, SpriteSheetRenderer> rendererLookup,
                                     Function<String, SpriteSheet> sheetLookup) {
        if (item.isCharacter()) return CharacterItemPuppet.forItem(context, item);
        final AvatarItemPuppet old = AvatarItemPuppet.forItem(context, item, rig, rendererLookup, sheetLookup);
        if (old == null) return null;
        return new ItemPuppet() {
            @Override
            public void draw(Canvas canvas, RectF dest, SpriteOverlayItem it, long itemLocalMs, float alpha) {
                if (it.getAvatarTrack() != null) old.draw(canvas, dest, it.getAvatarTrack(), itemLocalMs, alpha);
            }

            @Override
            public void release() {
                old.release();
            }
        };
    }
}
