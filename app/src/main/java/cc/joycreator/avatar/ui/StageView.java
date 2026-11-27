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
        scale = new android.view.ScaleGestureDetector(c, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                lastFx = d.getFocusX();
                lastFy = d.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(android.view.ScaleGestureDetector d) {
                float nz = Math.max(1f, Math.min(6f, zoom * d.getScaleFactor()));
                float fx = d.getFocusX(), fy = d.getFocusY();
                float cx = (fx - baseX - panX) / (baseS * zoom), cy = (fy - baseY - panY) / (baseS * zoom);   // the spot under the fingers stays there
                zoom = nz;
                panX = fx - baseX - cx * baseS * zoom + 0f;
                panY = fy - baseY - cy * baseS * zoom + 0f;
                panX += 0f;
                lastFx = fx;
                lastFy = fy;
                if (zoom <= 1.001f) {
                    panX = 0;
                    panY = 0;
                }
                invalidate();
                return true;
            }
        });
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

        /** A finger went down on empty picture inside the thing being moved (the box): true to take the drag. */
        default boolean beginMove(float x, float y) {
            return false;
        }

        /** The finger that {@link #beginMove} took has moved ({@code last}: lifted). */
        default void moveBy(float x, float y, boolean last) {
        }

        /** True while dragging should show the loupe (pins). */
        default boolean loupe() {
            return false;
        }

        java.util.List<Dot> dots();

        /** A tap on empty picture, at character pixels. */
        void tap(float x, float y);

        void picked(Dot d);

        /** A dot dragged to x, y; {@code last} when the finger lifts (the edit is made then, as one undo step). */
        void moved(Dot d, float x, float y, boolean last);
    }

    private LandmarkLayer marks;
    private Dot dragging;
    private boolean moving;
    private boolean glBacked;
    // the view: zoom and pan (two fingers; Fit puts them back), "pick a piece", and how the part in hand is shown
    private float zoom = 1f, panX, panY, baseS = 1f, baseX, baseY, lastFx, lastFy;
    private boolean picking;
    private int lookIndex;
    private final android.view.ScaleGestureDetector scale;
    private final java.util.function.Consumer<String>[] pickListener = new java.util.function.Consumer[1];
    public static final int LOOKS = 5;
    private float dragX, dragY, fitS = 1, fitX, fitY;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG), dotText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setLandmarks(LandmarkLayer layer) {
        marks = layer;
        dragging = null;
        invalidate();
    }

    /** The part being drawn: the pose as it is, so a layer with no solid or ghost (the pins) shows the whole character. */
    public PoseRenderer renderer() {
        return pose;
    }

    private void drawMarks(Canvas c, float dp) {
        if (marks.solid() == null && marks.ghost() == null && !glBacked) pose.draw(c);
        if (marks.ghost() != null) pose.drawSubtree(c, marks.ghost(), marks.ghostTint(), 120);
        if (marks.solid() != null) pose.drawSubtree(c, marks.solid(), 0, 255);
        drawDots(c, dp, false);
    }

    /** The dots; {@code zoomed} in the loupe, where they are drawn at the same size as on the stage. */
    private void drawDots(Canvas c, float dp, boolean zoomed) {
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

    /** While a dot is dragged: a round loupe above the finger, three times the size, that follows it (the pin-placing ruling). */
    private void drawLoupe(Canvas c, float dp) {
        float r = 46 * dp, lx = Math.max(r, Math.min(getWidth() - r, fitX + dragX * fitS)), ly = fitY + dragY * fitS - 84 * dp;
        if (ly < r) ly = fitY + dragY * fitS + 84 * dp;
        int save = c.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addCircle(lx, ly, r, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        c.drawColor(0xFF09090B);
        float z = fitS * 3f;
        c.translate(lx - dragX * z, ly - dragY * z);
        c.scale(z, z);
        pose.draw(c);
        float oldFit = fitS;
        fitS = z;
        drawDots(c, dp, true);
        fitS = oldFit;
        c.restoreToCount(save);
        dotPaint.setStyle(Paint.Style.STROKE);
        dotPaint.setStrokeWidth(2 * dp);
        dotPaint.setColor(0xFFFAFAFA);
        c.drawCircle(lx, ly, r, dotPaint);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(com.fadcam.ui.faditor.Studio.ARMED);
        c.drawCircle(lx, ly, 2.5f * dp, dotPaint);
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

    /** A finger on the body while performing: it takes the character with it (walking). Coordinates are the character's own. */
    public interface BodyDrag {
        /** Does a finger going down here take the body? (Only when the character can travel.) */
        boolean grab(float x, float y);

        void drag(float x, float y);

        void release();
    }

    private BodyDrag body;
    private boolean bodyHeld;

    public void setBodyDrag(BodyDrag b) {
        body = b;
        bodyHeld = false;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (body != null && marks == null && !picking && e.getPointerCount() == 1) {
            float bx = (e.getX() - fitX) / fitS, by = (e.getY() - fitY) / fitS;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    bodyHeld = body.grab(bx, by);
                    if (bodyHeld) return true;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (bodyHeld) {
                        body.drag(bx, by);
                        return true;
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (bodyHeld) {
                        bodyHeld = false;
                        body.release();
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        if (bodyHeld) {                                                  // a second finger came down: let go of the body
            bodyHeld = false;
            body.release();
        }
        scale.onTouchEvent(e);
        if (scale.isInProgress() || e.getPointerCount() > 1) {          // two fingers: zoom and pan, never a drag of a dot
            dragging = null;
            moving = false;
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && e.getPointerCount() == 2 && zoom > 1.001f) {
                float fx = (e.getX(0) + e.getX(1)) / 2f, fy = (e.getY(0) + e.getY(1)) / 2f;
                panX += fx - lastFx;
                panY += fy - lastFy;
                lastFx = fx;
                lastFy = fy;
            }
            invalidate();
            return true;
        }
        if (marks == null) {
            if (picking && pose != null && doc != null && e.getActionMasked() == android.view.MotionEvent.ACTION_UP && pickListener[0] != null) {
                CharacterDoc.Node n = pose.hit((e.getX() - fitX) / fitS, (e.getY() - fitY) / fitS);
                if (n != null) pickListener[0].accept(n.id);
            }
            return picking || super.onTouchEvent(e);
        }
        float cx = (e.getX() - fitX) / fitS, cy = (e.getY() - fitY) / fitS;
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                dragging = hit(cx, cy);
                dragX = cx;
                dragY = cy;
                moving = false;
                if (dragging != null) marks.picked(dragging);
                else moving = marks.beginMove(cx, cy);
                invalidate();
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (dragging != null) {
                    dragX = cx;
                    dragY = cy;
                    marks.moved(dragging, cx, cy, false);
                    invalidate();
                } else if (moving) {
                    marks.moveBy(cx, cy, false);
                    invalidate();
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
                if (moving) {
                    moving = false;
                    marks.moveBy(cx, cy, true);
                } else if (dragging != null) {
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

    /**
     * True when the real character is drawn by the GPU surface behind this view: this view then draws only what goes over it
     * (dots, boxes, the selection) and is see-through. False: this view draws the stand-in itself.
     */
    public void setGlBacked(boolean on) {
        glBacked = on;
        setBackgroundColor(on ? 0x00000000 : 0xFF000000);
        invalidate();
    }

    /** The view's scale and offset (character pixels to this view's pixels), as the GPU drawing needs them. */
    public float viewScale() {
        return fitS;
    }

    public float viewX() {
        return fitX;
    }

    public float viewY() {
        return fitY;
    }

    /** The next tap on the picture takes the piece under the finger (the Pick a piece button). */
    public void setPicking(boolean on, java.util.function.Consumer<String> then) {
        picking = on;
        pickListener[0] = then;
    }

    public boolean picking() {
        return picking;
    }

    /** Frames the part in hand (or the whole character when nothing is in hand). */
    public void fitToHand() {
        zoom = 1f;
        panX = panY = 0;
        android.graphics.RectF r = selected == null || pose == null || doc == null ? null : pose.bounds(doc.find(selected));
        if (r != null && r.width() > 1 && r.height() > 1) {
            float viewW = getWidth() - 60 * getResources().getDisplayMetrics().density, viewH = getHeight() - 36 * getResources().getDisplayMetrics().density;
            zoom = Math.max(1f, Math.min(6f, 0.8f * Math.min(viewW / (r.width() * baseS), viewH / (r.height() * baseS))));
            float cx = r.centerX(), cy = r.centerY();
            panX = viewW / 2f - baseX - cx * baseS * zoom;
            panY = viewH / 2f - baseY - cy * baseS * zoom;
        }
        invalidate();
    }

    /** Cycles how the part in hand is shown: Spotlight, dashes, glow, corners, wash. Returns the new index. */
    public int cycleLook() {
        lookIndex = (lookIndex + 1) % LOOKS;
        invalidate();
        return lookIndex;
    }

    public int look() {
        return lookIndex;
    }

    private void drawSelection(Canvas c, android.graphics.RectF r, float dp, float s) {
        float w = Math.max(1f, dp) / s;
        line.setStrokeWidth(w);
        line.setPathEffect(null);
        line.setStyle(Paint.Style.STROKE);
        switch (lookIndex) {
            case 0: {   // Spotlight: everything else steps back
                int save = c.save();
                c.clipRect(r, android.graphics.Region.Op.DIFFERENCE);
                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setColor(0x99000000);
                c.drawRect(-10000, -10000, 20000, 20000, dotPaint);
                c.restoreToCount(save);
                c.drawRect(r, line);
                break;
            }
            case 1:     // dashes
                line.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * dp / s, 4 * dp / s}, 0));
                c.drawRect(r, line);
                line.setPathEffect(null);
                break;
            case 2:     // glow
                line.setStrokeWidth(w * 6);
                line.setAlpha(60);
                c.drawRect(r, line);
                line.setAlpha(255);
                line.setStrokeWidth(w);
                c.drawRect(r, line);
                break;
            case 3: {   // corners
                float k = Math.min(r.width(), r.height()) * 0.2f;
                line.setStrokeWidth(w * 2.5f);
                c.drawLine(r.left, r.top, r.left + k, r.top, line);
                c.drawLine(r.left, r.top, r.left, r.top + k, line);
                c.drawLine(r.right, r.top, r.right - k, r.top, line);
                c.drawLine(r.right, r.top, r.right, r.top + k, line);
                c.drawLine(r.left, r.bottom, r.left + k, r.bottom, line);
                c.drawLine(r.left, r.bottom, r.left, r.bottom - k, line);
                c.drawLine(r.right, r.bottom, r.right - k, r.bottom, line);
                c.drawLine(r.right, r.bottom, r.right, r.bottom - k, line);
                line.setStrokeWidth(w);
                break;
            }
            default:    // wash
                line.setStyle(Paint.Style.FILL);
                line.setAlpha(50);
                c.drawRect(r, line);
                line.setAlpha(255);
                line.setStyle(Paint.Style.STROKE);
                c.drawRect(r, line);
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
            baseS = s;
            baseX = ox;
            baseY = oy;
            fitS = s * zoom;
            fitX = ox + panX;
            fitY = oy + panY;
            s = s * zoom;
            c.translate(ox + panX, oy + panY);
            c.scale(s, s);
            if (marks != null) drawMarks(c, dp);
            else if (!glBacked) pose.draw(c);
            if (selected != null && marks == null) {
                CharacterDoc.Node n = doc.find(selected);
                android.graphics.RectF r = n == null ? null : pose.bounds(n);
                if (r != null) drawSelection(c, r, dp, s);
            }
            c.restoreToCount(save);
        }
        if (marks != null && dragging != null && doc != null && pose != null && marks.loupe()) drawLoupe(c, dp);
        if (!hintText.isEmpty()) c.drawText(hintText, 12 * dp, getHeight() - 10 * dp, hint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pose != null) pose.release();
        pose = null;
    }
}
