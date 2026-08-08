package com.fadcam.ui.faditor.fx;

/**
 * The one piece of arithmetic that turns a drag on screen into a move in the stack.
 *
 * <p><b>SCREEN IS TOP-DOWN, THE MODEL IS BOTTOM-UP.</b> The last card in an {@link FxStack} is
 * applied last, so it belongs at the TOP of a vertical list — the way every layer stack in every
 * editor reads. Dragging a card DOWN one row therefore moves it EARLIER in the model.</p>
 *
 * <p><b>Why this is its own class.</b> Inverting that relationship is the most confusing bug this
 * panel could have: the user would watch a card move down while the picture changed as though it
 * had moved up, and both directions "look like something happened", so it would survive casual
 * testing. The gesture that produces it cannot be driven by adb at all — the drawer's hold has
 * defeated four injection strategies — so the arithmetic lives here, Android-free, where the
 * gson-only harness can reach it and pin it.</p>
 */
public final class FxReorder {

    private FxReorder() {}

    /**
     * @param screenPos the card's row on screen, 0 = topmost.
     * @param shift     rows moved, positive = downward on screen.
     * @param count     number of cards in the stack.
     * @return {@code {fromIndex, toIndex}} in MODEL order, the target clamped in range so an
     *         over-drag lands at the end rather than handing the model an illegal index.
     */
    public static int[] indices(int screenPos, int shift, int count) {
        int from = (count - 1) - screenPos;
        int to = (count - 1) - (screenPos + shift);
        to = Math.max(0, Math.min(count - 1, to));
        return new int[]{from, to};
    }
}
