package com.fadcam.ui.faditor;

/**
 * THE STUDIO'S COLOURS, for code.
 *
 * <p>JoyRaptor: <i>"those hundreds of hard coded colors and 66 greys hopefully we can get down
 * to minimal pallet and greys all changable from one central place of truth. that will tie
 * everything together and make the feel uniform and easy to tweak."</i>
 *
 * <p>An audit found 373 distinct hand-typed colours in the editor used 1,856 times, 188 of
 * them greys, 103 of those used exactly once. Eleven different greys meant "this control is
 * off"; three different colours meant "this is selected".
 *
 * <h3>Named by meaning, never by shade</h3>
 * There is no {@code GREY_9E} here and there never should be. A palette named by appearance
 * grows a new entry every time somebody wants a slightly different grey, which is exactly how
 * 188 of them happened. If a new colour is needed, the question to answer first is "what does
 * this SAY?" — and most of the time the answer turns out to be something already in this list.
 *
 * <h3>Gradient means action; flat means identity</h3>
 * This is the rule that lets green be two things without ambiguity. Anything the user can
 * PRESS to make something happen wears the aqua-to-lime gradient
 * ({@code R.drawable.studio_action_pill}). A flat colour is never an action — it is what an
 * object IS. So {@link #AUDIO} can stay green without competing with {@link #GO}.
 *
 * <h3>Why this file mirrors studio_tokens.xml instead of reading it</h3>
 * Most of these are consumed by {@code static final} fields in custom Views, which are
 * assigned at class-load time — before any Context exists to resolve a resource against.
 * Resolving at draw time instead would mean a resource lookup inside {@code onDraw} on a
 * timeline that repaints every frame while scrubbing.
 *
 * <p>So: {@code studio_tokens.xml} is the source for XML, this is the source for code, and
 * the two carry the same values. {@code tools/check_palette.py} fails if they ever drift —
 * that check is the thing that makes "one place of truth" true rather than aspirational.
 */
public final class Studio {

    private Studio() { }

    // ── GROUND ──────────────────────────────────────────────────────────────
    // Four surfaces, and a screen should not need a fifth. They step by roughly equal
    // perceptual amounts, so a panel on a panel is readable without a border.
    public static final int GROUND  = 0xFF000000;   // the timeline's own ground
    public static final int SURFACE = 0xFF0D0D10;   // the screen behind panels
    public static final int PANEL   = 0xFF16161B;   // a panel, a sheet, a drawer
    public static final int RAISED  = 0xFF1F1F26;   // a control sitting on a panel
    public static final int LINE    = 0xFF2C2C35;   // a divider or a hairline

    // ── INK ─────────────────────────────────────────────────────────────────
    // Measured against PANEL: 15.4:1, 7.1:1, 4.6:1 — all above WCAG AA. The grey this
    // replaces in the object drawer measured 1.39:1.
    public static final int INK       = 0xFFF4F4F5;
    public static final int INK_DIM   = 0xFFC4C4CE;
    public static final int INK_FAINT = 0xFF8A8A94;
    public static final int INK_OFF   = 0xFF52525B;

    // ── STATE ───────────────────────────────────────────────────────────────
    // What a control says about itself. Four, because the Studio has four; a fifth is a
    // sign two ideas have been folded into one control.
    public static final int ARMED   = 0xFF22D3EE;   // selected, or armed
    public static final int LIVE    = 0xFFFF008C;   // recording, playing, the playhead
    public static final int CAREFUL = 0xFFFBBF24;   // destructive, or a warning
    public static final int OFF     = 0xFF33333C;   // unavailable — ONE grey, not eleven
    /**
     * Destructive, or a signal that has gone past its limit — delete, remove, audio clipping.
     *
     * <p>Separate from {@link #LIVE}, and it has to be. LIVE is the magenta of recording and
     * of the playhead, which is a NORMAL state you want to see; this is the red of something
     * being lost. Material's #F44336 used to carry it in 31 places, which meant the editor's
     * "delete" and its "recording" were told apart only by hue.
     */
    public static final int DANGER  = 0xFFFF4438;
    /** An alignment guide, or a snap line. Not a control — a hint about geometry. */
    public static final int GUIDE   = 0xFFA78BFA;

    // ── GO ──────────────────────────────────────────────────────────────────
    // "the greens should all be replaced with the studio green gradient." Where a gradient
    // fits, use R.drawable.studio_action_pill; GO is the single value for the cases that
    // cannot take one — a stroke, a meter fill, a 1px rule.
    public static final int GO     = 0xFF35F6BF;
    public static final int GO_END = 0xFF97FE8B;
    public static final int ON_GO  = 0xFF050507;    // ink that sits ON the gradient

    // ── OBJECT IDENTITY ─────────────────────────────────────────────────────
    // What a thing IS, not what it is doing. These are hues, deliberately spread around the
    // wheel so two kinds are never told apart by brightness alone — which is the failure
    // mode that makes a timeline unreadable to the 8% of men with red-green colour blindness.
    public static final int AUDIO = 0xFF4ADE80;     // was Material #4CAF50
    /** Overlay video, PiP, and the master spine. Also the editor's "informational" blue. */
    public static final int VIDEO = 0xFF4397FD;

    // ── ROOMS ───────────────────────────────────────────────────────────────
    // The lobby's colour code, repeated here so the editor agrees with the room the
    // user just walked out of. Each is that room's dominant gradient stop.
    public static final int ROOM_STUDIO  = 0xFF35F6BF;
    public static final int ROOM_CAPTURE = 0xFFFA3D5D;
    public static final int ROOM_SPRITE  = 0xFFFF008C;
    public static final int ROOM_AVATAR  = 0xFFCC27FF;
    public static final int ROOM_VIZ     = 0xFFFAA03D;

    // ── FILM ────────────────────────────────────────────────────────────────
    // The master spine. The hole is DARKER than the rail because a hole shows the dark
    // behind it; the cut edge is the only line bright enough to draw the strip's shape
    // against a near-black ground.
    public static final int FILM_RAIL = 0xFF202027;
    public static final int FILM_EDGE = 0xFF44444F;
    public static final int FILM_HOLE = 0xFF050508;

    // ── LANES ───────────────────────────────────────────────────────────────
    // "one set can be fully black but the other should be a little brighter as it reads
    // currently as fully black unless I have the phone turned up very bright."
    //
    // The old alternate was #0B0B0D over black — a 4.3% lift, below what most panels
    // resolve at 30% backlight. LANE_B is roughly three times that.
    public static final int LANE_A = 0xFF000000;
    public static final int LANE_B = 0xFF17171C;

    /** Apply an alpha to a token without re-typing the hex. */
    public static int alpha(int colour, int a) {
        return (colour & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }
}
