package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import cc.joycreator.avatar.file.CharacterDoc;

/**
 * The stage: old-black, the character fitted into the room left between the hint line and the stage buttons
 * (spec A2). One view; fingers (pins, the box, Perform) are added here as the stages are built.
 */
public final class StageView extends View {
    private CharacterDoc doc;
    private RestPose pose;
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String hintText = "";

    public StageView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        hint.setColor(0xFFA1A1AA);
        hint.setTextSize(12f * c.getResources().getDisplayMetrics().density);
    }

    public void setCharacter(CharacterDoc d) {
        if (pose != null) pose.release();
        doc = d;
        pose = d == null ? null : new RestPose(d);
        invalidate();
    }

    public void setHint(String s) {
        hintText = s == null ? "" : s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float dp = getResources().getDisplayMetrics().density;
        float bottom = getHeight() - 28 * dp;     // the hint line's room
        float right = getWidth() - 52 * dp;       // the stage buttons' column
        if (doc != null && pose != null && doc.w > 0 && doc.h > 0) {
            float pad = 8 * dp;
            float s = Math.min((right - 2 * pad) / doc.w, (bottom - 2 * pad) / doc.h);
            float ox = pad + ((right - 2 * pad) - doc.w * s) / 2f;
            float oy = pad + ((bottom - 2 * pad) - doc.h * s) / 2f;
            int save = c.save();
            c.translate(ox, oy);
            c.scale(s, s);
            pose.draw(c);
            c.restoreToCount(save);
        }
        if (!hintText.isEmpty()) c.drawText(hintText, 12 * dp, getHeight() - 10 * dp, hint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pose != null) pose.release();
        pose = null;
    }
}
