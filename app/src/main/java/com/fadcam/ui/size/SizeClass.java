package com.fadcam.ui.size;

/**
 * How much room a window has, sorted into three classes per side (TAB-1, 2026-11-14).
 *
 * <p>One rule app-wide: decide by the WINDOW's width and height, never by "is this a tablet".
 * A phone turned sideways, a tablet in split-screen and a folded phone then all get the same
 * treatment as any other window of the same size. The breakpoints are AndroidX's
 * {@code WindowSizeClass} ones (Material's compact / medium / expanded), so a later move to that
 * library changes nothing on screen; the library itself is not added just for three numbers.
 *
 * <p>The XML side uses the matching resource folders: {@code values-w600dp} (medium and up) and
 * {@code values-w840dp} (expanded). Never {@code sw600dp}: that one asks about the device, not
 * the window, and stays "tablet" in a half-width split-screen.
 *
 * <p>Pure Java (no Android imports) so the JVM harness can test it: see
 * {@code tools/jvm-harness/SizeClassTest.java}. The Android side is {@link WindowSize} and
 * {@link ContentColumn}.
 */
public final class SizeClass {

    private SizeClass() {}

    /** Width at or above this is MEDIUM (a phone sideways, half a tablet, a small tablet upright). */
    public static final int MEDIUM_WIDTH_DP = 600;
    /** Width at or above this is EXPANDED (a tablet sideways, a big tablet upright). */
    public static final int EXPANDED_WIDTH_DP = 840;
    /** Height at or above this is MEDIUM; below it is a phone sideways. */
    public static final int MEDIUM_HEIGHT_DP = 480;
    /** Height at or above this is EXPANDED (a tablet upright). */
    public static final int EXPANDED_HEIGHT_DP = 900;

    /** Simple screens (Settings, onboarding, What's New) keep their words and rows inside this. */
    public static final int READING_COLUMN_DP = 640;

    public enum Width { COMPACT, MEDIUM, EXPANDED }

    public enum Height { COMPACT, MEDIUM, EXPANDED }

    public static Width width(int widthDp) {
        if (widthDp >= EXPANDED_WIDTH_DP) return Width.EXPANDED;
        if (widthDp >= MEDIUM_WIDTH_DP) return Width.MEDIUM;
        return Width.COMPACT;
    }

    public static Height height(int heightDp) {
        if (heightDp >= EXPANDED_HEIGHT_DP) return Height.EXPANDED;
        if (heightDp >= MEDIUM_HEIGHT_DP) return Height.MEDIUM;
        return Height.COMPACT;
    }

    /**
     * How many cells of at least {@code minCellDp} fit across {@code availableDp}, never fewer than
     * {@code atLeast}. Cells keep their phone size and a wide window gets MORE of them, not bigger
     * ones (owner's rule: dp values stay as drawn, use the room for content).
     *
     * <p>Anything narrower than {@link #MEDIUM_WIDTH_DP} is a phone held upright and gets exactly
     * {@code atLeast}: the phone layout as drawn. (The owner's Note 9 runs at a dense 548 dp, where
     * a plain division would have added a third card.)
     */
    public static int columns(int availableDp, int minCellDp, int atLeast) {
        int phone = Math.max(1, atLeast);
        if (minCellDp <= 0 || availableDp < MEDIUM_WIDTH_DP) return phone;
        return Math.max(phone, availableDp / minCellDp);
    }

    /**
     * The extra padding each side needs so the content between {@code baseStart} and
     * {@code baseEnd} padding is at most {@code maxContent} wide and centred. Zero when it
     * already fits, so a phone in portrait is never touched. All values in the same unit (px).
     */
    public static int sideInset(int width, int baseStart, int baseEnd, int maxContent) {
        int room = width - baseStart - baseEnd - maxContent;
        return room > 0 ? room / 2 : 0;
    }

    /**
     * Bottom padding for a block centred in a scrolling viewport whose bottom {@code gap} is covered
     * (a bar of page dots drawn over it), so the block's last line never sits under that bar.
     *
     * <p>Returns {@code padBottom} unchanged whenever the block already clears the bar: every page
     * on an upright phone. Otherwise, if the content can fit above the bar, just enough extra to
     * lift it clear while staying centred; if it cannot fit at all, enough that scrolling to the end
     * brings the last line above the bar. All values in the same unit (px).
     */
    public static int clearBottomPadding(int padTop, int content, int padBottom, int viewport, int gap) {
        if (gap <= 0) return padBottom;
        int h = padTop + content + padBottom;
        if (h <= viewport && (viewport - h) / 2 + padTop + content <= viewport - gap) return padBottom;
        if (padTop + content <= viewport - gap) {
            return Math.max(padBottom, padTop + content - viewport + 2 * gap);
        }
        return Math.max(padBottom, gap);
    }
}
