package com.fadcam.ui.faditor.avatar.face;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.Studio;

import java.util.HashMap;
import java.util.Map;

/**
 * FACE-1: a plain test face that shows every face signal at once, continuously, so the bench proves
 * the engine without anyone's character: head turn, nod, tilt, lean and distance; both eyes with
 * their own lids, squint, wide and gaze; both brows and the worried middle; the cheeks; the nose's
 * sneer; and a mouth built the way the owner described (11-22): the lips' opening takes its shape
 * from the mouth signals and slides with the mouth, the upper teeth stay with the head, the lower
 * teeth ride the jaw (open, sideways, forward) and both show only through the opening.
 *
 * <p>Sides: a character's left is on the screen's right (it faces you).</p>
 */
public final class FaceBenchFace extends View {

    private final Paint skin = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG), dark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p = new Path(), opening = new Path();
    private final RectF r = new RectF();
    private final float dp;
    @NonNull private Map<String, Float> s = new HashMap<>();
    private boolean dim;

    public FaceBenchFace(@NonNull Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        skin.setColor(Studio.RAISED);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2f * dp);
        edge.setColor(Studio.INK_DIM);
        edge.setStrokeCap(Paint.Cap.ROUND);
        edge.setStrokeJoin(Paint.Join.ROUND);
        white.setColor(Studio.INK);
        dark.setColor(Studio.GROUND);
        ink.setColor(Studio.ARMED);
        text.setColor(Studio.INK_FAINT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12 * dp);
    }

    /** The face to show (FaceSignals names). */
    public void setSignals(@NonNull Map<String, Float> signals) {
        s = signals;
        invalidate();
    }

    /** Greyed out while no face drives it and nothing plays (honest: it is resting, not tracking). */
    public void setDim(boolean d) {
        if (dim != d) { dim = d; invalidate(); }
    }

    private float g(String k) {
        Float v = s.get(k);
        return v == null || v.isNaN() ? 0f : v;
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        float w = getWidth(), h = getHeight(), R = Math.min(w, h) * 0.30f;
        int alpha = dim ? 110 : 255;
        for (Paint q : new Paint[]{skin, edge, white, dark, ink}) q.setAlpha(alpha);

        float tx = g(FaceSignals.TURN_X), ty = g(FaceSignals.TURN_Y);
        c.save();
        c.translate(w / 2f + g(FaceSignals.LEAN_X) * 0.45f * R, h / 2f + g(FaceSignals.LEAN_Y) * 0.35f * R);
        float scale = 1f + g(FaceSignals.NEAR) * 0.15f;
        c.scale(scale, scale);
        c.rotate(g(FaceSignals.ROLL) * 28f);

        // ── the head, a little narrower as it turns; the jaw drops the chin ──
        float jaw = g(FaceSignals.JAW), jawX = g(FaceSignals.JAW_X);
        float hw = R * (1f - 0.1f * Math.abs(tx));
        p.reset();
        p.moveTo(-hw, -0.15f * R);
        p.cubicTo(-hw, -1.25f * R, hw, -1.25f * R, hw, -0.15f * R);
        float chinY = 1.05f * R + jaw * 0.14f * R, chinX = jawX * 0.10f * R + tx * 0.12f * R;
        p.cubicTo(hw, 0.55f * R, chinX + 0.45f * R, chinY, chinX, chinY);
        p.cubicTo(chinX - 0.45f * R, chinY, -hw, 0.55f * R, -hw, -0.15f * R);
        c.drawPath(p, skin);
        c.drawPath(p, edge);

        // features ride the turn and the nod
        float fx = tx * 0.30f * R, fy = ty * 0.22f * R;

        // ── cheeks ──
        float puff = g(FaceSignals.CHEEK_PUFF);
        if (puff > 0.02f) {
            white.setAlpha((int) (alpha * 0.18f * puff));
            c.drawCircle(fx - 0.52f * R, fy + 0.25f * R, 0.22f * R * (0.6f + puff), white);
            c.drawCircle(fx + 0.52f * R, fy + 0.25f * R, 0.22f * R * (0.6f + puff), white);
            white.setAlpha(alpha);
        }

        // ── eyes (the character's left is on the screen's right) ──
        eye(c, fx + 0.38f * R, fy - 0.12f * R, R, g(FaceSignals.BLINK_L), g(FaceSignals.SQUINT_L), g(FaceSignals.WIDE_L));
        eye(c, fx - 0.38f * R, fy - 0.12f * R, R, g(FaceSignals.BLINK_R), g(FaceSignals.SQUINT_R), g(FaceSignals.WIDE_R));

        // ── brows ──
        float inner = g(FaceSignals.BROW_INNER);
        brow(c, fx + 0.38f * R, fy - 0.36f * R, R, g(FaceSignals.BROW_L), inner, 1f);
        brow(c, fx - 0.38f * R, fy - 0.36f * R, R, g(FaceSignals.BROW_R), inner, -1f);

        // ── nose and its sneer ──
        c.drawLine(fx, fy - 0.02f * R, fx + tx * 0.08f * R, fy + 0.16f * R, edge);
        float sneer = g(FaceSignals.SNEER);
        if (sneer > 0.05f) {
            edge.setAlpha((int) (alpha * sneer));
            c.drawLine(fx - 0.12f * R, fy - 0.02f * R, fx - 0.05f * R, fy + 0.04f * R, edge);
            c.drawLine(fx + 0.12f * R, fy - 0.02f * R, fx + 0.05f * R, fy + 0.04f * R, edge);
            edge.setAlpha(alpha);
        }

        mouth(c, fx, fy, R, alpha);
        c.restore();

        int vis = Math.round(g(FaceSignals.VIS));
        if (vis > 0 && vis < FaceSignals.SHAPES.length) {
            c.drawText("sheet mouth: " + FaceSignals.SHAPES[vis], w / 2f, h - 10 * dp, text);
        }
    }

    private void eye(Canvas c, float x, float y, float R, float blink, float squint, float wide) {
        float rx = 0.17f * R, ry = 0.11f * R * (1f + 0.35f * wide);
        r.set(x - rx, y - ry, x + rx, y + ry);
        c.save();
        p.reset();
        p.addOval(r, Path.Direction.CW);
        c.clipPath(p);
        c.drawOval(r, white);
        float lx = g(FaceSignals.LOOK_X), ly = g(FaceSignals.LOOK_Y);
        c.drawCircle(x + lx * 0.09f * R, y + ly * 0.05f * R, 0.06f * R, dark);
        // upper lid comes down with the blink; the lower lid rises with a squint
        float top = y - ry, span = 2 * ry;
        c.drawRect(x - rx, top - 1, x + rx, top + span * Math.min(1f, blink), skin);
        c.drawRect(x - rx, y + ry - span * 0.45f * squint, x + rx, y + ry + 1, skin);
        c.restore();
        c.drawOval(r, edge);
    }

    /** side +1 = the screen's right brow (its inner end is toward the middle, to the left). */
    private void brow(Canvas c, float x, float y, float R, float lift, float inner, float side) {
        float half = 0.17f * R;
        float outerY = y - lift * 0.10f * R;
        float innerY = y - lift * 0.06f * R - inner * 0.08f * R + Math.max(0f, -lift) * 0.05f * R;
        float ix = x - side * half, ox = x + side * half;
        edge.setStrokeWidth(4f * dp);
        c.drawLine(ix, innerY, ox, outerY, edge);
        edge.setStrokeWidth(2f * dp);
    }

    /** The lips' opening (shape from the mouth signals, sliding with the mouth), teeth behind it. */
    private void mouth(Canvas c, float fx, float fy, float R, int alpha) {
        float open = g(FaceSignals.MOUTH), wide = g(FaceSignals.WIDE), pucker = g(FaceSignals.PUCKER);
        float funnel = g(FaceSignals.FUNNEL), press = g(FaceSignals.PRESS);
        float cx = fx + g(FaceSignals.MOUTH_X) * 0.16f * R, cy = fy + 0.42f * R;
        float half = 0.21f * R * (1f + 0.3f * wide - 0.4f * pucker - 0.25f * funnel);
        // the corners: the screen-left corner is the character's right
        float lY = cy - (g(FaceSignals.SMILE_R) - g(FaceSignals.FROWN_R)) * 0.10f * R;
        float rY = cy - (g(FaceSignals.SMILE_L) - g(FaceSignals.FROWN_L)) * 0.10f * R;
        float round = Math.max(pucker, funnel);
        float up = open * 0.05f * R * (1f + round) + 0.002f * R;
        float upL = g(FaceSignals.LIP_UP_R) * 0.08f * R, upR = g(FaceSignals.LIP_UP_L) * 0.08f * R;
        float dn = open * 0.15f * R * (1f + 0.5f * round) * (1f - 0.6f * press) + 0.002f * R;
        float dnL = g(FaceSignals.LIP_DOWN_R) * 0.06f * R, dnR = g(FaceSignals.LIP_DOWN_L) * 0.06f * R;

        opening.reset();
        opening.moveTo(cx - half, lY);
        opening.cubicTo(cx - half * 0.5f, cy - up - upL, cx + half * 0.5f, cy - up - upR, cx + half, rY);
        opening.cubicTo(cx + half * 0.5f, cy + dn + dnR, cx - half * 0.5f, cy + dn + dnL, cx - half, lY);
        opening.close();
        c.drawPath(opening, dark);

        c.save();
        c.clipPath(opening);
        // upper teeth: fixed to the skull
        white.setAlpha((int) (alpha * 0.9f));
        c.drawRect(fx - 0.2f * R, cy - 0.12f * R, fx + 0.2f * R, cy - 0.005f * R, white);
        // lower teeth: on the jaw (down as it opens, sideways, forward)
        float jaw = g(FaceSignals.JAW), jx = g(FaceSignals.JAW_X) * 0.07f * R, fwd = 1f + g(FaceSignals.JAW_FWD) * 0.12f;
        float ty = cy + jaw * 0.15f * R;
        c.drawRect(fx + jx - 0.18f * R * fwd, ty + 0.005f * R, fx + jx + 0.18f * R * fwd, ty + 0.11f * R, white);
        white.setAlpha(alpha);
        c.restore();
        c.drawPath(opening, edge);
    }
}
