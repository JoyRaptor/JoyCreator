package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Joybot's eyes: a picture of something IN the project, for a model that can see.
 *
 * <p>Without this, a vision model could only see what the person attached by hand — so "label my
 * sprite sheet" meant the person exporting the sheet and attaching it, and "find B-roll that
 * matches what I say" had nothing to match against. The model now asks to {@code look} at an id
 * and the next request carries the picture (see {@link JoybotAgent}).</p>
 *
 * <p>A sprite sheet comes back with its grid drawn on and every cell NUMBERED with the index the
 * sprite tools use (label_sprite_cells, author_sprite_animation), so "cell 7 is the angry face"
 * means the same cell to the model, the tools and the person.</p>
 */
public final class JoybotEyes {

    private JoybotEyes() {}

    private static final String TAG = "JoybotEyes";
    /** Long edge sent. Enough to read a 64-cell sheet's faces; a fraction of a raw photo. */
    private static final int LONG_EDGE = 1600;
    /** Frames shown for an image sequence's contact sheet. */
    private static final int SEQUENCE_FRAMES = 20;

    /** One picture to send. */
    public static final class Sight {
        @NonNull public final String dataUrl;
        public final int width, height;
        /** What it is, in words the model reads with it. */
        @NonNull public final String caption;

        Sight(@NonNull String dataUrl, int width, int height, @NonNull String caption) {
            this.dataUrl = dataUrl;
            this.width = width;
            this.height = height;
            this.caption = caption;
        }
    }

    /** Why the last {@link #look} came back empty, for the model. */
    @NonNull public static String lastProblem = "";

    /**
     * A picture of the item with this id: a sprite sheet (numbered grid), a sprite layer (its
     * sheet), a picture layer, a video clip or layer (a frame — {@code atMs} into it, or its
     * middle when negative). "playhead" means the main video's frame at {@code playheadMs}.
     */
    @Nullable
    public static Sight look(@NonNull Context app, @NonNull FaditorProject p, @NonNull String id,
                             long atMs, long playheadMs) {
        lastProblem = "";
        try {
            if ("playhead".equalsIgnoreCase(id)) return playhead(app, p, playheadMs);
            SpriteSheet sheet = p.spriteSheetById(id);
            if (sheet != null) return sheet(app, sheet);
            for (SpriteOverlayItem s : p.getTimeline().getSpriteOverlays()) {
                if (id.equals(s.getId())) {
                    SpriteSheet sh = p.spriteSheetById(s.getSheetId());
                    if (sh != null) return sheet(app, sh);
                }
            }
            for (TextOverlayItem o : p.getTimeline().getTextOverlays()) {
                if (id.equals(o.getId()) && o.isImage()) {
                    Bitmap b = decode(app, o.getImageUri());
                    return b == null ? null : encode(b, "the picture layer " + quoted(o.getText()));
                }
            }
            Clip c = p.getTimeline().findClipById(id);
            if (c != null) return clipFrame(app, c, atMs);
            lastProblem = "Nothing with id '" + id + "' that I can look at (sprite sheets, sprite"
                    + " layers, picture layers, video clips, or \"playhead\").";
            return null;
        } catch (Throwable t) {
            FLog.w(TAG, "look failed for " + id, t);
            lastProblem = "I couldn't open that picture: " + t.getMessage();
            return null;
        }
    }

    // ── sprite sheets ────────────────────────────────────────────────────────

    @Nullable
    private static Sight sheet(@NonNull Context app, @NonNull SpriteSheet s) {
        if (s.isSequence()) return sequence(app, s);
        int[] size = bounds(app, s.getSheetUri());
        if (size == null) {
            lastProblem = "The sheet's picture file is missing.";
            return null;
        }
        Bitmap src = decode(app, s.getSheetUri());
        if (src == null) return null;
        float k = Math.min(1f, LONG_EDGE / (float) Math.max(size[0], size[1]));
        int w = Math.max(1, Math.round(size[0] * k));
        int h = Math.max(1, Math.round(size[1] * k));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        // Transparent pixels would turn black in a JPEG; a neutral grey keeps dark outlines readable.
        cv.drawColor(0xFF8C8C8C);
        cv.drawBitmap(src, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();

        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1f, w / 800f));
        grid.setColor(0xFFFF2BD6);
        int n = s.cellCount();
        float cellH = h / (float) Math.max(1, s.getRows());
        Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        badge.setColor(0xE6000000);
        Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        num.setColor(Color.WHITE);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextSize(Math.max(11f, Math.min(28f, cellH * 0.22f)));
        for (int i = 0; i < n; i++) {
            Rect r = SpriteSheetRenderer.cellRectSource(s, i, size[0], size[1]);
            RectF d = new RectF(r.left * k, r.top * k, r.right * k, r.bottom * k);
            cv.drawRect(d, grid);
            String label = String.valueOf(i);
            float tw = num.measureText(label);
            float pad = num.getTextSize() * 0.25f;
            RectF b = new RectF(d.left + 2, d.top + 2, d.left + 2 + tw + 2 * pad,
                    d.top + 2 + num.getTextSize() + pad);
            cv.drawRoundRect(b, pad, pad, badge);
            cv.drawText(label, b.left + pad, b.bottom - pad * 1.3f, num);
        }
        StringBuilder cap = new StringBuilder("the sprite sheet ").append(quoted(s.getName()))
                .append(" (id=").append(s.getId()).append("), ").append(s.getCols()).append(" columns x ")
                .append(s.getRows()).append(" rows = ").append(n).append(" cells. Each cell has its")
                .append(" INDEX in a black badge at its top-left (the number label_sprite_cells and")
                .append(" author_sprite_animation use), counted left to right, top to bottom; the pink")
                .append(" lines are the grid. If the grid doesn't sit on the drawings, fix it with")
                .append(" set_sprite_grid first.");
        if (!s.getCellNames().isEmpty()) cap.append(" Cells already named: ").append(s.getCellNames()).append('.');
        return encode(out, cap.toString());
    }

    /** An image sequence as a contact sheet of numbered frames. */
    @Nullable
    private static Sight sequence(@NonNull Context app, @NonNull SpriteSheet s) {
        List<String> frames = s.getFrameUris();
        if (frames.isEmpty()) { lastProblem = "That sequence has no frames."; return null; }
        int count = Math.min(SEQUENCE_FRAMES, frames.size());
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil(count / (double) cols);
        int cell = LONG_EDGE / Math.max(cols, rows);
        Bitmap out = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(0xFF8C8C8C);
        Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        num.setColor(Color.WHITE);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextSize(Math.max(12f, cell * 0.1f));
        Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        badge.setColor(0xE6000000);
        StringBuilder shown = new StringBuilder();
        for (int k = 0; k < count; k++) {
            int index = count == frames.size() ? k : Math.round(k * (frames.size() - 1) / (float) (count - 1));
            Bitmap f = decode(app, frames.get(index));
            int x = (k % cols) * cell, y = (k / cols) * cell;
            if (f != null) {
                float sc = Math.min(cell / (float) f.getWidth(), cell / (float) f.getHeight());
                int fw = Math.round(f.getWidth() * sc), fh = Math.round(f.getHeight() * sc);
                cv.drawBitmap(f, null, new Rect(x + (cell - fw) / 2, y + (cell - fh) / 2,
                        x + (cell + fw) / 2, y + (cell + fh) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
                f.recycle();
            }
            String label = String.valueOf(index);
            float pad = num.getTextSize() * 0.25f;
            cv.drawRoundRect(new RectF(x + 2, y + 2, x + 2 + num.measureText(label) + 2 * pad,
                    y + 2 + num.getTextSize() + pad), pad, pad, badge);
            cv.drawText(label, x + 2 + pad, y + 2 + num.getTextSize() - pad * 0.3f, num);
            shown.append(index).append(k + 1 < count ? "," : "");
        }
        return encode(out, "the image sequence " + quoted(s.getName()) + " (id=" + s.getId() + "), "
                + frames.size() + " frames; this contact sheet shows frames " + shown
                + ", each numbered with its frame index.");
    }

    // ── video ────────────────────────────────────────────────────────────────

    @Nullable
    private static Sight clipFrame(@NonNull Context app, @NonNull Clip c, long atMs) {
        Uri uri = c.getSourceUri();
        if (uri == null) { lastProblem = "That clip has no file."; return null; }
        long dur = Math.max(1, c.getVisualDurationMs());
        long local = atMs >= 0 ? Math.min(atMs, dur - 1) : dur / 2;
        if (c.isImageClip()) {
            Bitmap b = decode(app, uri.toString());
            return b == null ? null : encode(b, "the picture clip " + quoted(ProjectFactsReader.nameOf(c)));
        }
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(app, uri);
            Bitmap f = mmr.getFrameAtTime(c.mapToSourceMs(local) * 1000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (f == null) { lastProblem = "I couldn't read a frame from that clip."; return null; }
            return encode(f, "a frame " + clock(local) + " into the clip "
                    + quoted(ProjectFactsReader.nameOf(c)) + " (id=" + c.getId() + ")");
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
    }

    @Nullable
    private static Sight playhead(@NonNull Context app, @NonNull FaditorProject p, long playheadMs) {
        if (playheadMs < 0) { lastProblem = "I don't know where the playhead is (open me from the editor)."; return null; }
        long at = 0;
        for (int i = 0; i < p.getTimeline().getClipCount(); i++) {
            Clip c = p.getTimeline().getClip(i);
            long d = c.getVisualDurationMs();
            if (playheadMs < at + d) return clipFrame(app, c, playheadMs - at);
            at += d;
        }
        lastProblem = "The playhead is past the end of the video.";
        return null;
    }

    // ── pixels ───────────────────────────────────────────────────────────────

    @Nullable
    private static int[] bounds(@NonNull Context app, @Nullable String uri) {
        if (uri == null || uri.isEmpty()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(uri))) {
            BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
        return o.outWidth > 0 && o.outHeight > 0 ? new int[]{o.outWidth, o.outHeight} : null;
    }

    /** Decoded with a sample size that keeps the long edge near {@link #LONG_EDGE}. */
    @Nullable
    private static Bitmap decode(@NonNull Context app, @Nullable String uri) {
        int[] size = bounds(app, uri);
        if (size == null) { lastProblem = "The picture file is missing."; return null; }
        BitmapFactory.Options o = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(size[0], size[1]) / (sample * 2) >= LONG_EDGE) sample *= 2;
        o.inSampleSize = sample;
        try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(uri))) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            lastProblem = "I couldn't open the picture file.";
            return null;
        }
    }

    @NonNull
    private static Sight encode(@NonNull Bitmap b, @NonNull String caption) {
        Bitmap send = b;
        int longEdge = Math.max(b.getWidth(), b.getHeight());
        if (longEdge > LONG_EDGE) {
            float k = LONG_EDGE / (float) longEdge;
            send = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                    Math.max(1, Math.round(b.getHeight() * k)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        send.compress(Bitmap.CompressFormat.JPEG, 85, out);
        Sight s = new Sight("data:image/jpeg;base64,"
                + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP),
                send.getWidth(), send.getHeight(), caption);
        if (send != b) send.recycle();
        b.recycle();
        return s;
    }

    @NonNull
    private static String quoted(@Nullable String s) {
        return s == null || s.isEmpty() ? "(unnamed)" : "\"" + s + "\"";
    }

    @NonNull
    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
