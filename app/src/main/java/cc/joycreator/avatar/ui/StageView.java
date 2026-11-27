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
    private PoseRenderer pose;
    private java.util.function.Function<CharacterDoc, PoseRenderer> factory = RestPose::new;
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String hintText = "";
    private String selected;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StageView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        hint.setColor(0xFFA1A1AA);
        hint.setTextSize(12f * c.getResources().getDisplayMetrics().density);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(com.fadcam.ui.faditor.Studio.ARMED);
        line.setStrokeWidth(Math.max(1f, c.getResources().getDisplayMetrics().density));
    }

    /** What draws the character: {@link RestPose} until the solver's renderer is plugged in here. */
    public void setRendererFactory(java.util.function.Function<CharacterDoc, PoseRenderer> f) {
        factory = f == null ? RestPose::new : f;
        if (pose != null) pose.release();
        pose = doc == null ? null : factory.apply(doc);
        invalidate();
    }

    public void setCharacter(CharacterDoc d) {
        if (pose != null && d != null && pose.samePictures(d)) {
            pose.retarget(d);      // an edit of the tree: the decoded pictures stay
        } else {
            if (pose != null) pose.release();
            pose = d == null ? null : factory.apply(d);
        }
        doc = d;
        invalidate();
    }

    /** Look at one drawing of a set without changing the file; null puts the set back as it was. */
    public void tryDrawing(String setId, String kidId) {
        if (pose != null) pose.tryDrawing(setId, kidId);
        invalidate();
    }

    /** The live signals for this frame (the take's lane names); the stage draws them as far as its drawing can. */
    public void setSignals(java.util.Map<String, Object> signals) {
        if (pose != null) pose.setSignals(signals);
        invalidate();
    }

    /** The part in hand: a cyan hairline round where it is on the picture (the Spotlight look comes with the stage buttons). */
    public void setSelected(String id) {
        selected = id;
        invalidate();
    }

    // ── matching landmarks (spec A9): one drawing solid, its neighbour a tinted ghost, numbered dots to drag ──

    /** One landmark dot on the picture. */
    public static final class Dot {
        public float x, y;
        public String state = "here";       // here, behind, off
        public String label;                // the number, or null
        public int color;
        public boolean small;
        public Object tag;
    }

    /** What the landmarks screen shows and does on the stage; null puts the stage back to normal. */
    public interface LandmarkLayer {
        CharacterDoc.Node solid();

        CharacterDoc.Node ghost();

        int ghostTint();

        java.util.List<Dot> dots();

        /** A tap on empty picture, at character pixels. */
        void tap(float x, float y);

        void picked(Dot d);

        /** A dot dragged to x, y; {@code last} when the finger lifts (the edit is made then, as one undo step). */
        void moved(Dot d, float x, float y, boolean last);
    }

    private LandmarkLayer marks;
    private Dot dragging;
    private float dragX, dragY, fitS = 1, fitX, fitY;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG), dotText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setLandmarks(LandmarkLayer layer) {
        marks = layer;
        dragging = null;
        invalidate();
    }

    private void drawMarks(Canvas c, float dp) {
        if (marks.ghost() != null) pose.drawSubtree(c, marks.ghost(), marks.ghostTint(), 120);
        if (marks.solid() != null) pose.drawSubtree(c, marks.solid(), 0, 255);
        for (Dot d : marks.dots()) {
            float x = d == dragging ? dragX : d.x, y = d == dragging ? dragY : d.y;
            float r = (d.small ? 4.5f : 7f) * dp / fitS;
            dotPaint.setColor(d.color);
            if ("behind".equals(d.state)) {
                dotPaint.setStyle(Paint.Style.STROKE);
                dotPaint.setStrokeWidth(2 * dp / fitS);
                c.drawCircle(x, y, r, dotPaint);
            } else if ("off".equals(d.state)) {
                dotPaint.setColor(0xFF71717A);
                dotPaint.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y, r * 0.8f, dotPaint);
                dotPaint.setStyle(Paint.Style.STROKE);
                dotPaint.setStrokeWidth(1.5f * dp / fitS);
                dotPaint.setColor(0xFFFAFAFA);
                c.drawLine(x - r, y + r, x + r, y - r, dotPaint);
            } else {
                dotPaint.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y, r, dotPaint);
            }
            if (d.label != null) {
                dotText.setColor(0xFFFAFAFA);
                dotText.setTextSize(11 * dp / fitS);
                c.drawText(d.label, x + r * 1.1f, y - r * 1.1f, dotText);
            }
        }
    }

    private Dot hit(float cx, float cy) {
        if (marks == null) return null;
        float dp = getResources().getDisplayMetrics().density, reach = 24 * dp / fitS;
        Dot best = null;
        float bd = reach * reach;
        for (Dot d : marks.dots()) {
            float dx = d.x - cx, dy = d.y - cy, dd = dx * dx + dy * dy;
            if (dd <= bd) {
                bd = dd;
                best = d;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (marks == null) return super.onTouchEvent(e);
        float cx = (e.getX() - fitX) / fitS, cy = (e.getY() - fitY) / fitS;
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                dragging = hit(cx, cy);
                dragX = cx;
                dragY = cy;
                if (dragging != null) marks.picked(dragging);
                invalidate();
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (dragging != null) {
                    dragX = cx;
                    dragY = cy;
                    invalidate();
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
                if (dragging != null) {
                    boolean moved = Math.hypot(dragX - dragging.x, dragY - dragging.y) > 2 / fitS;
                    Dot d = dragging;
                    dragging = null;
                    if (moved) marks.moved(d, cx, cy, true);
                } else marks.tap(cx, cy);
                invalidate();
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                dragging = null;
                invalidate();
                return true;
            default:
                return true;
        }
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
            fitS = s;
            fitX = ox;
            fitY = oy;
            c.translate(ox, oy);
            c.scale(s, s);
            if (marks != null) drawMarks(c, dp);
            else pose.draw(c);
            if (selected != null && marks == null) {
                CharacterDoc.Node n = doc.find(selected);
                android.graphics.RectF r = n == null ? null : pose.bounds(n);
                if (r != null) {
                    line.setStrokeWidth(Math.max(1f, dp) / s);
                    c.drawRect(r, line);
                }
            }
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
