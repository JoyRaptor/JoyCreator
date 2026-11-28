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
    private CharacterLoader.Loaded loaded;
    private CharacterPoser poser;
    private final Context app;
    private String takeText;
    private Take take;
    private long lastMs = Long.MIN_VALUE;
    /** How far back the time may wobble before it counts as a jump back (a scrub). */
    static final long JITTER_MS = 100;

    private CharacterItemPuppet(Context app, CharacterLoader.Loaded loaded) {
        this.app = app;
        this.loaded = loaded;
        this.poser = CharacterPosers.get().forDoc(loaded.doc, new CharacterPictures(loaded.doc, loaded.beside));
    }

    /** The puppet for a character item, or null when its character cannot be found (the sprite picture shows). */
    public static CharacterItemPuppet forItem(Context c, SpriteOverlayItem item) {
        if (!item.isCharacter()) return null;
        CharacterLoader.Loaded l = CharacterLoader.load(c, item.getCharacterRef());
        if (l == null || l.doc.w <= 0 || l.doc.h <= 0) return null;
        return new CharacterItemPuppet(c.getApplicationContext(), l);
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
        CharacterLoader.Loaded n = CharacterLoader.newer(app, loaded);   // the room saved it again: draw it as it is now
        if (n != null && n.doc.w > 0 && n.doc.h > 0) {
            poser.release();
            loaded = n;
            poser = CharacterPosers.get().forDoc(n.doc, new CharacterPictures(n.doc, n.beside));
            lastMs = Long.MIN_VALUE;
        }
        long ms = Math.max(0, itemLocalMs) + item.getCharacterTakeOffsetMs();
        // back in time: a real jump (a scrub, a new export pass) starts the springs again; a few ms back (the player's
        // position wobbling, a frame drawn twice) keeps the picture it has, it never resets mid-play (X6 M8)
        if (ms < lastMs - JITTER_MS) poser.rewind();
        else if (ms < lastMs) ms = lastMs;
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
