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
    private final Map<String, DecodedSheet> renderers = new HashMap<>();
    private final Map<String, AvatarItemPuppet> puppets = new HashMap<>();
    private final Map<String, Bitmap> spriteFrames = new HashMap<>();
    private final Map<String, HeldCell> heldCells = new HashMap<>();
    private long generation;

    private static final class DecodedSheet {
        final SpriteSheet sheet;
        final long revision;
        final SpriteSheetRenderer renderer;

        DecodedSheet(SpriteSheet sheet, SpriteSheetRenderer renderer) {
            this.sheet = sheet;
            this.revision = sheet.decodeRevision();
            this.renderer = renderer;
        }
    }

    /** A grid or sequence cell may be reused only when its source, crop and alignment still match. */
    private static final class HeldCell {
        final SpriteSheetRenderer renderer;
        final int cell, source, w, h;
        final long geometry, generation;
        final boolean flipH, flipV;
        final float dx, dy, scale, rotation;

        HeldCell(SpriteSheetRenderer r, SpriteSheet s, SpriteOverlayItem item, int cell, int w, int h, long gen) {
            renderer = r; this.cell = cell; source = s.sourceCell(cell); geometry = s.geometryRevision();
            this.w = w; this.h = h; generation = gen; flipH = item.isFlipH(); flipV = item.isFlipV();
            SpriteSheet.CellXf xf = s.cellTransform(cell);
            dx = xf == null ? 0f : xf.dx; dy = xf == null ? 0f : xf.dy;
            scale = xf == null ? 1f : xf.scale; rotation = xf == null ? 0f : xf.rot;
        }

        boolean matches(SpriteSheetRenderer r, SpriteSheet s, SpriteOverlayItem item, int c, int width, int height) {
            if (renderer != r || cell != c || source != s.sourceCell(c) || geometry != s.geometryRevision()
                    || w != width || h != height || flipH != item.isFlipH() || flipV != item.isFlipV()) return false;
            SpriteSheet.CellXf xf = s.cellTransform(c);
            return xf == null ? dx == 0 && dy == 0 && scale == 1 && rotation == 0
                    : dx == xf.dx && dy == xf.dy && scale == xf.scale && rotation == xf.rot;
        }
    }

    @Override public long frameGeneration(@NonNull String ref) {
        HeldCell held = heldCells.get(ref);
        return held == null ? pictures.frameGeneration(ref) : held.generation;
    }

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
            if (o.hasTextLook()) return lookTitle(ref, o, s.timelineMs);
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
        return ref.startsWith(VisShow.SPRITE) || ref.startsWith(VisShow.VIDEO) || pictures.moves(ref)
                || lookTitleRef(ref);   // a title wearing a TextLab look moves (fire, particles, its light)
    }

    private boolean lookTitleRef(@NonNull String ref) {
        if (!ref.startsWith(VisShow.TEXT)) return false;
        VisShow s = show;
        TextOverlayItem o = s != null ? s.textItems.get(ref) : null;
        return o != null && o.hasTextLook();
    }

    private final Map<String, Bitmap> lookFrames = new HashMap<>();

    /**
     * A title wearing a TextLab look (TEXT-1), as it is at {@code timelineMs}: TextLab draws it on its own thread
     * (its own renderer for VisLab, apart from the preview's and an export's) at the size a plain title would have
     * here, into one reused picture per title (as sprites are).
     */
    @Nullable
    private Bitmap lookTitle(@NonNull String ref, @NonNull TextOverlayItem o, long timelineMs) {
        float fontPx = Math.max(1f, o.getSizeFraction() * TEXT_FRAME_H);
        cc.joycreator.textlab.StudioTextLab.Frame f = cc.joycreator.textlab.StudioTextLab.get(app).renderBlocking(
                "vislab:" + o.getId(), o.getTextLook(), o.getText(), o.lookTime(timelineMs), fontPx);
        if (f == null) return null;
        try {
            float k = (float) (fontPx / f.em);
            int w = Math.max(1, Math.round(f.bitmap.getWidth() * k)), h = Math.max(1, Math.round(f.bitmap.getHeight() * k));
            Bitmap b = lookFrames.get(ref);
            if (b == null || b.isRecycled() || b.getWidth() != w || b.getHeight() != h) {
                if (b != null && !b.isRecycled()) b.recycle();
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                lookFrames.put(ref, b);
            }
            b.eraseColor(0);
            new Canvas(b).drawBitmap(f.bitmap, null, new android.graphics.Rect(0, 0, w, h), spritePaint);
            return b;
        } finally {
            f.bitmap.recycle();
        }
    }

    @Nullable
    @Override
    public Bitmap frame(@NonNull String ref, double seconds) {
        VisShow s = show;
        if (ref.startsWith(VisShow.TEXT)) {
            TextOverlayItem o = s != null ? s.textItems.get(ref) : null;
            return o != null && o.hasTextLook() ? lookTitle(ref, o, s.timelineMs) : bitmap(ref);
        }
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
        HeldCell held = heldCells.get(ref);
        if (!o.hasAvatarPerformance() && b != null && !b.isRecycled() && held != null
                && held.matches(r, sheet, o, cell, w, h)) return b;
        heldCells.remove(ref);   // Failed draws and animated poses can never claim the previous pixels.
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
        if (!o.hasAvatarPerformance()) heldCells.put(ref, new HeldCell(r, sheet, o, cell, w, h, ++generation));
        return b;
    }

    @Nullable
    private SpriteSheetRenderer rendererFor(@Nullable String sheetId) {
        if (sheetId == null) return null;
        SpriteSheet sheet = StudioArt.sheet(sheetId);
        if (sheet == null) return null;
        DecodedSheet cached = renderers.get(sheetId);
        if (cached != null && cached.sheet == sheet && cached.revision == sheet.decodeRevision()) return cached.renderer;
        if (cached != null && cached.renderer != null) {
            cached.renderer.recycle();
            puppets.clear();
        }
        SpriteSheetRenderer r = SpriteSheetRenderer.load(app, sheet, SPRITE_H * 2);
        renderers.put(sheetId, new DecodedSheet(sheet, r));
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
        heldCells.clear();
        for (DecodedSheet cached : renderers.values()) if (cached.renderer != null) cached.renderer.recycle();
        renderers.clear();
        puppets.clear();
    }
}
