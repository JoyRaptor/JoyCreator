package com.fadcam.vislab.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.avatar.AvatarItemPuppet;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.TextOverlayRenderer;
import com.fadcam.ui.faditor.sprite.SpriteFrameResolver;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;
import com.fadcam.vislab.gl.VisRenderer;
import com.fadcam.vislab.room.Pictures;
import com.fadcam.vislab.room.Videos;

import java.util.HashMap;
import java.util.Map;

/**
 * Pictures for a look drawn in Studio: VisLab's own ({@link Pictures}: the Joy emblem, a picked
 * file or video, a Studio picture's file) plus the Studio items a picture layer shows (roadmap R9):
 * titles drawn by Studio's own title renderer, sprites (and performing avatars) drawn by Sprite
 * Lab's renderer at the frame's moment, and videos at the frame's time in their source. Called on
 * the GL thread.
 */
final class StudioPictures implements VisRenderer.ImageSource {

    /** Titles are drawn as for a 720-tall frame: sharp enough, and a small texture. */
    private static final int TEXT_FRAME_H = 720;
    /** Sprites are drawn this tall; their cell's shape sets the width. */
    private static final int SPRITE_H = 512;

    @NonNull private final Context app;
    @NonNull private final Pictures pictures;
    @NonNull private final Videos videos;
    @Nullable private volatile VisShow show;
    private final Map<String, SpriteSheetRenderer> renderers = new HashMap<>();
    private final Map<String, AvatarItemPuppet> puppets = new HashMap<>();
    private final Map<String, Bitmap> spriteFrames = new HashMap<>();
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    StudioPictures(@NonNull Context c) {
        app = c.getApplicationContext();
        pictures = new Pictures(app);
        videos = new Videos(app);
    }

    /** What this frame's picture layers show (VisShow), or null for their own pictures. */
    void setShow(@Nullable VisShow s) {
        show = s;
    }

    @Nullable
    @Override
    public Bitmap bitmap(@Nullable String ref) {
        if (ref != null && ref.startsWith(VisShow.TEXT)) {
            VisShow s = show;
            TextOverlayItem o = s != null ? s.textItems.get(ref) : null;
            if (o == null) return null;
            try {
                return TextOverlayRenderer.render(o, TEXT_FRAME_H * 16 / 9, TEXT_FRAME_H);
            } catch (RuntimeException e) {
                return null;   // a title that cannot draw shows nothing, never breaks the look
            }
        }
        return pictures.bitmap(ref);
    }

    @Override
    public boolean moves(@Nullable String ref) {
        if (ref == null) return false;
        return ref.startsWith(VisShow.SPRITE) || ref.startsWith(VisShow.VIDEO) || pictures.moves(ref);
    }

    @Nullable
    @Override
    public Bitmap frame(@NonNull String ref, double seconds) {
        VisShow s = show;
        if (ref.startsWith(VisShow.SPRITE)) {
            SpriteOverlayItem o = s != null ? s.sprites.get(ref) : null;
            return o != null ? sprite(ref, o, s.timelineMs) : null;
        }
        if (ref.startsWith(VisShow.VIDEO)) {
            VisShow.VideoAt v = s != null ? s.videos.get(ref) : null;
            // A Studio video is shown at its own time, held at its ends (never looped).
            return v != null ? videos.frame(v.uri.toString(), v.sourceMs / 1000.0, false) : null;
        }
        return pictures.frame(ref, seconds);   // a video picked in the look plays on the look's clock
    }

    /** A sprite (or a performing avatar) as it is at {@code timelineMs}, into its own picture. */
    @Nullable
    private Bitmap sprite(@NonNull String ref, @NonNull SpriteOverlayItem o, long timelineMs) {
        SpriteSheet sheet = StudioArt.sheet(o.getSheetId());
        SpriteSheetRenderer r = sheet != null ? rendererFor(o.getSheetId()) : null;
        if (sheet == null || r == null) return null;
        // Outside its stretch of the timeline the sprite holds its first or last frame.
        long t = Math.max(o.getStartMs(), Math.min(o.getEndMs(), timelineMs));
        AvatarItemPuppet puppet = puppetFor(o);
        int cell = SpriteFrameResolver.resolveCellAt(sheet, o, t);
        if (puppet == null && cell == SpriteFrameResolver.NO_CELL) cell = 0;
        float aspect = r.cellAspect();
        int h = SPRITE_H;
        int w = Math.max(1, Math.round(h * (aspect > 0 ? aspect : 1f)));
        Bitmap b = spriteFrames.get(ref);
        if (b == null || b.isRecycled() || b.getWidth() != w || b.getHeight() != h) {
            if (b != null && !b.isRecycled()) b.recycle();
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            spriteFrames.put(ref, b);
        }
        b.eraseColor(0);
        Canvas c = new Canvas(b);
        if (o.isFlipH() || o.isFlipV()) c.scale(o.isFlipH() ? -1f : 1f, o.isFlipV() ? -1f : 1f, w / 2f, h / 2f);
        RectF dest = new RectF(0, 0, w, h);
        try {
            if (puppet != null && o.getAvatarTrack() != null) {
                puppet.draw(c, dest, o.getAvatarTrack(), Math.max(0, o.toLocalMs(t)), 1f);
            } else {
                r.drawCell(c, cell, dest, spritePaint);
            }
        } catch (RuntimeException e) {
            return null;   // a sprite that cannot draw shows nothing, never breaks the look
        }
        return b;
    }

    @Nullable
    private SpriteSheetRenderer rendererFor(@Nullable String sheetId) {
        if (sheetId == null) return null;
        if (renderers.containsKey(sheetId)) return renderers.get(sheetId);
        SpriteSheet sheet = StudioArt.sheet(sheetId);
        SpriteSheetRenderer r = sheet != null ? SpriteSheetRenderer.load(app, sheet, SPRITE_H * 2) : null;
        renderers.put(sheetId, r);
        return r;
    }

    @Nullable
    private AvatarItemPuppet puppetFor(@NonNull SpriteOverlayItem o) {
        if (!o.hasAvatarPerformance()) return null;
        if (puppets.containsKey(o.getId())) return puppets.get(o.getId());
        AvatarItemPuppet p = AvatarItemPuppet.forItem(app, o, StudioArt.rig(o.getAvatarRigId()),
                this::rendererFor, StudioArt::sheet);
        puppets.put(o.getId(), p);
        return p;
    }

    /** Let go of decoders and drawn frames (the renderer is being let go). */
    void release() {
        pictures.release();
        videos.release();
        for (Bitmap b : spriteFrames.values()) if (b != null && !b.isRecycled()) b.recycle();
        spriteFrames.clear();
        for (SpriteSheetRenderer r : renderers.values()) if (r != null) r.recycle();
        renderers.clear();
        puppets.clear();
    }
}
