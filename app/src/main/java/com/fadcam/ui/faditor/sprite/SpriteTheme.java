package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;

/**
 * The sprite surfaces' colour vocabulary, in one place.
 *
 * <p>Joy Creator's editor hardcodes ~1,092 {@code 0xFF…} literals and has never joined the
 * theming system the recorder already uses. This class is the sprite package's half of the
 * fix and, deliberately, a worked example for the rest: every colour the Lab and the drawer
 * draw comes from here, so re-theming the section is editing one file rather than hunting
 * literals across five.</p>
 *
 * <h3>The two rules that must survive the eventual app-wide unification</h3>
 * <ol>
 *   <li><b>STATE colours are global and constant.</b> {@link #SELECTED} always means "this is
 *       the one you are pointing at"; {@link #LIVE} always means "this is what the preview is
 *       showing right now". A section accent may never be a colour that already means a state,
 *       or pink means "playing" in one room and "you are in Sprite Lab" in another.</li>
 *   <li><b>Drawer surfaces are their own token, WITH ALPHA.</b> {@link #DRAWER_SCRIM} is
 *       translucent on purpose so the video keeps playing underneath while you noodle. Folding
 *       it into the opaque panel colour would destroy a compromise this feature-dense editor
 *       actually needs.</li>
 * </ol>
 *
 * <p>{@link #resolve} is here for the day the app-wide attrs land: each constant becomes a
 * fallback for a theme attribute rather than an answer, and nothing else has to change.</p>
 */
public final class SpriteTheme {

    private SpriteTheme() {}

    // ── ground and structure ────────────────────────────────────────────
    /** True black, so the user's own art is the brightest thing on screen. */
    public static final int BG        = 0xFF050507;
    public static final int PANEL     = 0xFF111114;
    public static final int CONTROL   = 0xFF1C1C22;
    public static final int CONTROL_HI= 0xFF262630;
    public static final int LINE      = 0xFF2C2C35;
    /** The drawer floats over live video; see rule 2. */
    public static final int DRAWER_SCRIM = 0xE0111114;

    // ── ink ─────────────────────────────────────────────────────────────
    public static final int INK       = 0xFFE4E4E7;
    public static final int DIM       = 0xFFA1A1AA;
    public static final int DIMMER    = 0xFF71717A;
    /** Ink to place ON a saturated accent. */
    public static final int ON_ACCENT = 0xFF09090B;

    // ── state (rule 1 — never reused as a section accent) ───────────────
    public static final int SELECTED  = 0xFF22D3EE;   // cyan
    public static final int LIVE      = 0xFFF43F8E;   // pink

    // ── section accents ─────────────────────────────────────────────────
    public static final int ACCENT_GRID  = 0xFFFBBF24;   // amber  — grid & slicing
    public static final int ACCENT_CELL  = 0xFFA78BFA;   // violet — a single cell's identity
    public static final int ACCENT_SEQ   = 0xFFF43F8E;   // pink   — the sequence you are building
    public static final int ACCENT_ALIGN = 0xFF22D3EE;   // cyan   — alignment
    public static final int ACCENT_VIEW  = 0xFF60A5FA;   // blue   — onion and other viewing aids
    public static final int ACCENT_CLIPS = 0xFFA78BFA;   // violet — saved animations
    public static final int ACCENT_OUT   = 0xFF34D399;   // green  — export
    public static final int WARN         = 0xFFFBBF24;

    // ── geometry ────────────────────────────────────────────────────────
    /** Controls are pills, matching the rest of Joy Creator's direction. */
    public static final float RADIUS_PILL = 999f;
    public static final float RADIUS_CARD = 14f;
    public static final float RADIUS_CHIP = 8f;

    /**
     * Resolve a theme attribute, falling back to one of the constants above.
     *
     * <p>Nothing calls this yet — the app-wide attrs do not exist. It is here so that when
     * they land, this file becomes the fallback table instead of the source of truth, and no
     * call site has to be touched.</p>
     */
    public static int resolve(@NonNull Context ctx, @AttrRes int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(attr, tv, true) && tv.data != 0) return tv.data;
        } catch (RuntimeException ignored) {
            // A missing attribute is the expected case today, not an error.
        }
        return fallback;
    }
}
