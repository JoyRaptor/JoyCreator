package com.fadcam.ui.faditor.sprite;

/** SpriteLab's long-press lift, sideways insertion and upward removal, shared by board chrome.
 * Bounds are supplied by the host: Android children or the board's pure Kotlin layout. */
public final class RollDragController {
    public interface Bounds {
        int size();
        float centerX(int index);
        float centerY(int index);
        float height(int index);
        float viewportLeft();
        float viewportRight();
        void scrollBy(int pixels);
    }
    public interface Listener {
        void onLift(int index, float dx, float dy, boolean removing);
        void onGap(int gap);
        void onHint(boolean active, boolean removing);
        void onDrop(int from, int gap, boolean remove);
    }
    private final Bounds bounds;
    private final Listener listener;
    private final float density;
    private int from = -1, gap = -1;
    private float startX, startY, height;
    private boolean removing;

    public RollDragController(Bounds bounds, Listener listener, float density) {
        if (!(density > 0) || !Float.isFinite(density)) throw new IllegalArgumentException("density");
        this.bounds = bounds;
        this.listener = listener;
        this.density = density;
    }
    public boolean isDragging(int index) { return from >= 0 && from == index; }
    public void begin(int index) {
        if (index < 0 || index >= bounds.size()) return;
        if (from >= 0) end(false);
        from = gap = index;
        startX = bounds.centerX(index);
        startY = bounds.centerY(index);
        height = bounds.height(index);
        removing = false;
        listener.onLift(index, 0, 0, false);
        listener.onGap(gap);
        listener.onHint(true, false);
    }
    public void move(float rawX, float rawY) {
        if (from < 0) return;
        removing = rawY < startY - height;
        gap = -1;
        if (!removing) {
            gap = bounds.size();
            for (int i = 0; i < bounds.size(); i++) {
                if (rawX < bounds.centerX(i)) { gap = i; break; }
            }
        }
        listener.onLift(from, rawX - startX, rawY - startY, removing);
        listener.onGap(gap);
        float edge = 44 * density;
        if (rawX < bounds.viewportLeft() + edge) bounds.scrollBy((int) (-12 * density));
        else if (rawX > bounds.viewportRight() - edge) bounds.scrollBy((int) (12 * density));
        listener.onHint(true, removing);
    }
    public void end(boolean commit) {
        int oldFrom = from, oldGap = gap;
        boolean oldRemove = removing;
        from = gap = -1;
        removing = false;
        listener.onGap(-1);
        listener.onHint(false, false);
        if (oldFrom >= 0) listener.onLift(oldFrom, 0, 0, false);
        if (commit && oldFrom >= 0 && oldFrom < bounds.size() && (oldRemove || oldGap >= 0))
            listener.onDrop(oldFrom, oldGap, oldRemove);
    }
}
