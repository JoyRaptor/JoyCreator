package com.fadcam.ui.faditor.avatar.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.avatar.MediaPipeTrackingSource;

/**
 * FACE-1: the small preview of what the face camera sees. The picture is mirrored like a mirror and
 * small on purpose; it is never stored or recorded. With the picture off it shows only the outline
 * the tracker found (eyes, brows, lips, the face's edge), which is enough to frame yourself without
 * showing the room. The outline is cyan while a face is found and grey while it looks; the little
 * dot in the corner is on whenever the camera is.
 */
public final class FacePreviewView extends View implements MediaPipeTrackingSource.PreviewSink {

    private final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), clip = new Path();
    private final RectF box = new RectF();
    private final float dp;
    @Nullable private Bitmap picture;
    @Nullable private float[] outline;
    private boolean found, cameraOn;

    public FacePreviewView(@NonNull Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Analysis thread: hand the newest frame to the screen. */
    @Override
    public void onPreview(@Nullable Bitmap pictureIn, @Nullable float[] outlineIn, boolean foundIn) {
        post(() -> {
            picture = pictureIn;
            outline = outlineIn;
            found = foundIn;
            invalidate();
        });
    }

    /** The camera's on-dot (the host knows when it has started or stopped the camera). */
    public void setCameraOn(boolean on) {
        cameraOn = on;
        if (!on) {
            picture = null;
            outline = null;
            found = false;
        }
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        float r = 10 * dp;
        box.set(0, 0, getWidth(), getHeight());
        fill.setColor(Studio.SUNK);
        c.drawRoundRect(box, r, r, fill);
        clip.reset();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        // The picture's own box (upright, aspect kept, centred and cropped to fill).
        RectF frame = new RectF(box);
        Bitmap b = picture;
        if (b != null && !b.isRecycled()) {
            float s = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight());
            float w = b.getWidth() * s, h = b.getHeight() * s;
            frame.set((box.width() - w) / 2f, (box.height() - h) / 2f, (box.width() + w) / 2f, (box.height() + h) / 2f);
            c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), frame, pic);
        } else {
            // outline only: a 3:4 picture box, the same shape the picture would have
            float w = box.width(), h = w * 4f / 3f;
            if (h > box.height()) { h = box.height(); w = h * 3f / 4f; }
            frame.set((box.width() - w) / 2f, (box.height() - h) / 2f, (box.width() + w) / 2f, (box.height() + h) / 2f);
        }
        float[] o = outline;
        if (o != null) {
            path.reset();
            boolean pen = false;
            for (int i = 0; i + 1 < o.length; i += 2) {
                if (Float.isNaN(o[i])) { pen = false; continue; }
                float x = frame.left + o[i] * frame.width(), y = frame.top + o[i + 1] * frame.height();
                if (pen) path.lineTo(x, y); else path.moveTo(x, y);
                pen = true;
            }
            line.setColor(found ? Studio.ARMED : Studio.INK_FAINT);
            c.drawPath(path, line);
        }
        c.restore();
        line.setColor(found ? Studio.ARMED : Studio.LINE);
        c.drawRoundRect(box, r, r, line);
        if (cameraOn) {
            fill.setColor(Studio.LIVE);
            c.drawCircle(box.right - 8 * dp, box.top + 8 * dp, 3.5f * dp, fill);
        }
    }
}
