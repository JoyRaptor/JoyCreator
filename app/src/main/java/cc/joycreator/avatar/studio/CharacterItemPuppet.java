package cc.joycreator.avatar.studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

import cc.joycreator.avatar.perform.Take;

import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import org.json.JSONObject;

import java.util.Map;

/**
 * Draws a new-format character item in Studio: its take sampled at the item's time, posed by the installed
 * {@link CharacterPoser} (the solver once X2 / X3 land, the standing stand-in before), fitted into the item's box the way
 * its standing picture is (the box has the character's shape, so the two line up exactly).
 *
 * <p>Deterministic in the item's time, as {@code AvatarItemPuppet} is: going back in time rewinds the poser, so a
 * replay is the same however the playhead got there, and preview and export draw the same frame.</p>
 */
public final class CharacterItemPuppet implements ItemPuppet {
    private final CharacterLoader.Loaded loaded;
    private final CharacterPoser poser;
    private String takeText;
    private Take take;
    private long lastMs = Long.MIN_VALUE;

    private CharacterItemPuppet(CharacterLoader.Loaded loaded) {
        this.loaded = loaded;
        this.poser = CharacterPosers.get().forDoc(loaded.doc, new CharacterPictures(loaded.doc, loaded.beside));
    }

    /** The puppet for a character item, or null when its character cannot be found (the sprite picture shows). */
    public static CharacterItemPuppet forItem(Context c, SpriteOverlayItem item) {
        if (!item.isCharacter()) return null;
        CharacterLoader.Loaded l = CharacterLoader.load(c, item.getCharacterRef());
        if (l == null || l.doc.w <= 0 || l.doc.h <= 0) return null;
        return new CharacterItemPuppet(l);
    }

    /** The item's take, read again only when its text changed (a pass recorded, acting read). */
    Take takeOf(SpriteOverlayItem item) {
        String t = item.getCharacterTake();
        if (t == null) return null;
        if (!t.equals(takeText)) {
            takeText = t;
            try {
                take = Take.fromJson(new JSONObject(t));
            } catch (Exception e) {
                take = null;
            }
        }
        return take;
    }

    @Override
    public void draw(Canvas canvas, RectF dest, SpriteOverlayItem item, long itemLocalMs, float alpha) {
        long ms = Math.max(0, itemLocalMs) + item.getCharacterTakeOffsetMs();
        if (ms < lastMs) poser.rewind();
        lastMs = ms;
        Map<String, Object> signals = TakeClock.sample(takeOf(item), ms);
        float[] box = fit(loaded.doc.w, loaded.doc.h, dest.width(), dest.height());
        int save = alpha >= 0.999f ? canvas.save()
                : canvas.saveLayerAlpha(dest.left, dest.top, dest.right, dest.bottom, Math.round(Math.max(0f, alpha) * 255));
        try {
            canvas.translate(dest.left + box[1], dest.top + box[2]);
            canvas.scale(box[0], box[0]);
            canvas.clipRect(0, 0, loaded.doc.w, loaded.doc.h);
            poser.draw(canvas, signals, ms / 1000.0);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** {scale, x, y}: the character's w x h fitted whole into a box, centred. */
    static float[] fit(float w, float h, float bw, float bh) {
        float s = Math.min(bw / w, bh / h);
        return new float[]{s, (bw - w * s) / 2f, (bh - h * s) / 2f};
    }

    @Override
    public void release() {
        poser.release();
    }
}
