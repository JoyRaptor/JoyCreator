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
    // Sprite Lab's ramp, which the Studio mockup says to take untouched: "Panel · Control
    // · Pressed · Line — #111114 · #1C1C22 · #26262E · #2C2C35. Sprite Lab's ramp,
    // untouched. Only the ground moved." The ground is the part that moved: the timeline
    // draws on true black so a lane can BE the ground rather than a fill over it.
    public static final int GROUND  = 0xFF000000;   // the timeline's own ground
    public static final int SURFACE = 0xFF0D0D10;   // the screen behind panels
    public static final int PANEL   = 0xFF111114;   // a panel, a sheet, a drawer
    public static final int SUNK    = 0xFF16161B;   // a well cut INTO a panel
    public static final int RAISED  = 0xFF1C1C22;   // a control sitting on a panel
    /** A control being held down. The rung the first ladder skipped. */
    public static final int PRESSED = 0xFF26262E;
    public static final int LINE    = 0xFF2C2C35;   // a divider or a hairline

    // ══ INK — AND THERE ARE TWO RAMPS, NOT ONE ═════════════════════════════
    //
    // This block used to hold ONE ramp, taken from the line "Ink · Dim · Label — #F2F2F5 ·
    // #C9C9D3 · #C4C4CE". That triple is real, but it is record 06's —dink / —ddim /
    // —dlabel: the ramp for text sitting on the FROSTED DRAWER, over moving video. Record
    // 06's own :root defines a different, darker ramp for everything else, and record 04
    // (the lobby and first run) declares the identical one:
    //
    //     --ink #e4e4e7  --dim #a1a1aa  --dimmer #71717a  --dimmest #4b4b55  --off #33333c
    //
    // Two records, written three weeks apart, agreeing to the digit. One ramp had been
    // promoted over the other, so every piece of text in the app that is NOT over video was
    // wearing the brightness that exists to survive a blown-out outdoor frame. The lobby was
    // the clearest casualty: it had the correct ramp typed out privately, and a tokenising
    // pass of mine "fixed" it onto the wrong one.
    //
    // The raise the old comment describes is real and stays — #A6A6B2 measured 3.49:1 and
    // became #C4C4CE at 4.86:1 — but it was a finding about DRAWER SECTION LABELS, and it
    // belongs to the drawer ramp only.

    /** Screen ink. Records 04 and 06, {@code --ink}. */
    public static final int INK       = 0xFFE4E4E7;
    /** {@code --dim}: secondary text on an opaque surface. */
    public static final int INK_DIM   = 0xFFA1A1AA;
    /** {@code --dimmer}: tertiary. Was 0xFF8A8A94, which is in neither record. */
    public static final int INK_FAINT = 0xFF71717A;
    /** {@code --dimmest}: a label that is present but not available. */
    public static final int INK_OFF   = 0xFF4B4B55;
    /** A label on a control. {@code --dlabel}, and the one rung the two ramps share a use for. */
    public static final int LABEL     = 0xFFC4C4CE;

    // ── the drawer's own ink — ONLY for content over the scrim ──────────────────
    // Record 06, CRITICAL 01: blur softens but never darkens, so a drawer over a bright
    // frame gets LIGHTER. The scrim went dark and the text on it went up. Using these
    // anywhere else makes ordinary text brighter than the spec, which is how this whole
    // mistake started; using anything else INSIDE a drawer is the contrast bug the record
    // measured at 1.09:1.
    /** {@code --dink}. */
    public static final int DRAWER_INK   = 0xFFF2F2F5;
    /** {@code --ddim}. */
    public static final int DRAWER_DIM   = 0xFFC9C9D3;
    /** {@code --dlabel}: 4.86:1 over the dark scrim, measured, not estimated. */
    public static final int DRAWER_LABEL = 0xFFC4C4CE;

    // ── STATE ───────────────────────────────────────────────────────────────
    // What a control says about itself. Four, because the Studio has four; a fifth is a
    // sign two ideas have been folded into one control.
    public static final int ARMED   = 0xFF22D3EE;   // selected, or armed
    /**
     * Live — recording, playing, the playhead.
     *
     * <p>#F43F8E, which is what JoyRaptor's Swatch Room says and what BOTH design records
     * say: {@code --live:#f43f8e} in The Marquee and in Studio Final.
     *
     * <p>It held #FF008C, which is Capture's SECOND STOP — the same value as
     * {@link #ROOM_SPRITE}. So "live" was not a colour of its own at all; it was a room's
     * colour wearing a state's name, and the playhead gradient only looked correct because
     * ROOM_CAPTURE to LIVE happened to spell out the Capture gradient by accident. Found by
     * re-reading his own messages rather than my summary of them.
     */
    public static final int LIVE    = 0xFFF43F8E;
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
    /**
     * Destroys — #FA3D5D, from the Swatch Room, and {@code --danger} in both records.
     *
     * <p>It held #FF4438, a red from nowhere. Note that this is the same value as
     * {@link #ROOM_CAPTURE}: that is deliberate and is in both drawings, where Capture's
     * gradient RUNS FROM the destroys-red to neon pink. Two roles, one value, like
     * GO / ROOM_STUDIO.
     */
    public static final int DANGER  = 0xFFFA3D5D;
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
    /**
     * The Studio room's far stop. Equal to {@link #GO_END} by value and deliberately a
     * separate name: GO is an ACTION, the Studio room is a PLACE, and video tapes wear the
     * place. If the action colour is ever retuned, video must not move with it.
     */
    public static final int ROOM_STUDIO_END = 0xFF97FE8B;
    public static final int ROOM_CAPTURE = 0xFFFA3D5D;
    /**
     * WHAT SPLIT WILL CUT: the dashed marker on the playhead. A ROLE, not a room: its value is
     * the Finder room's golden yellow (#F9F462, Grand Design rFinA), the brightest yellow on
     * the wheel. It was CAREFUL amber, and JoyRaptor: "the yellow is not nearly a bright
     * enough yellow to contrast with the pink, it needs to pop more by being brighter."
     * Measured against the playhead's LIVE pink: about 3.4:1, where the amber was about 2.4:1.
     */
    public static final int SLICE = 0xFFF9F462;
    public static final int ROOM_SPRITE  = 0xFFFF008C;
    /**
     * Sprite Lab's far stop (Grand Design: rSprA #FF008C to rSprB #CC27FF). Equal to
     * {@link #ROOM_AVATAR} by value, because the two rooms are neighbours on the wheel and
     * hand over at that colour; a separate name so neither drags the other if retuned.
     */
    public static final int ROOM_SPRITE_END = 0xFFCC27FF;
    public static final int ROOM_AVATAR  = 0xFFCC27FF;
    /** The Avatar room's deeper stop, and the object table's TEXT hue. */
    public static final int ROOM_AVATAR_DEEP = 0xFF8C3DFA;
    public static final int ROOM_VIZ     = 0xFFFAA03D;
    /** Viz Lab's deeper stop. The lobby draws the pair; this is the far end. */
    public static final int ROOM_VIZ_DEEP = 0xFFFC6818;
    /**
     * The light end of the armed ramp.
     *
     * <p>Named ROOM_LIBRARY at first, which was wrong twice over. Measured, it is 1.2° from
     * {@link #ARMED} — and jakubkrehel's better-colors is blunt about that: <i>"treat hues
     * within 15° as the same color"</i>, and <i>"one color carries one meaning across the
     * interface."</i> A second name for the same cyan is a second place for it to drift.
     *
     * <p>It is also not a room. The library is not somewhere you make things, which is why it
     * has no hue of its own; it carries the cyan that means "this one" everywhere else, one
     * step brighter so a chip reads against a panel. That is a RAMP STEP, and it is named as
     * one now.
     */
    public static final int ARMED_LIGHT = 0xFF55E0F9;
    /**
     * The indigo Joybot's disc falls to.
     *
     * <p>Third stop of the Avatar ramp: {@link #ROOM_AVATAR} to
     * {@link #ROOM_AVATAR_DEEP} to this. He sits on it because white-on-indigo was the
     * only combination that stayed readable at 40dp — "turns out he looks harder to read
     * colored on black."
     */
    public static final int ORB_DEEP = 0xFF5C43FD;

    // ── FILM ────────────────────────────────────────────────────────────────
    // The master spine. The hole is DARKER than the rail because a hole shows the dark
    // behind it; the cut edge is the only line bright enough to draw the strip's shape
    // against a near-black ground.
    // These three were declared here and used NOWHERE: EditorTimelineView had its own
    // aliases pointing at RAISED, OFF and GROUND instead. So the spine shipped in one set of
    // colours while the tokens named after it held another, and nobody could have found that
    // by reading either file alone.
    //
    // Corrected to the values that actually ship and that JoyRaptor approved — "film spine
    // reads as film on near-black" — rather than repainting an approved surface to match
    // constants no one had looked at. FILM_RAIL now equals RAISED and FILM_EDGE equals OFF by
    // VALUE, which is fine and is the point: they are different ROLES, so a later change to
    // what a raised control looks like will not silently restyle the film.
    public static final int FILM_RAIL = 0xFF1C1C22;
    public static final int FILM_EDGE = 0xFF33333C;
    public static final int FILM_HOLE = 0xFF000000;

    // ── LANES ───────────────────────────────────────────────────────────────
    // "one set can be fully black but the other should be a little brighter as it reads
    // currently as fully black unless I have the phone turned up very bright."
    //
    // The old alternate was #0B0B0D over black — a 4.3% lift, below what most panels
    // resolve at 30% backlight. LANE_B is roughly three times that.
    /**
     * Lane A is the GROUND SHOWING THROUGH, so it is transparent, not black.
     *
     * <p>It was declared opaque and used nowhere — LayerRowRenderer painted 0x00000000
     * directly instead, which is the right thing and for the right reason: a lane drawn
     * opaque would cover whatever has already been composited beneath it. The token was
     * simply wrong about what lane A is.
     */
    public static final int LANE_A = 0x00000000;
    public static final int LANE_B = 0xFF17171C;

    /*
     * ══ MEASURED HUE COLLISIONS, AND WHY EACH ONE IS ALLOWED ════════════════════════
     *
     * jakubkrehel/skills better-colors: "treat hues within 15 degrees as the same color."
     * Run against this palette, four pairs land inside that window. Each was then checked for
     * whether the two ever have to be told apart AT A GLANCE, which is the thing the rule is
     * actually protecting. None of them do, and the reasons are recorded here so that the next
     * person to run the same measurement does not "fix" work that was done on purpose.
     *
     *   GO / ROOM_STUDIO          0.0 deg — the same value by design. The Studio room's
     *                             identity IS the go colour. Two roles, one value.
     *   ROOM_CAPTURE / DANGER     0.0 deg — the same value, and that is in BOTH design
     *                             records: Capture's gradient runs FROM the destroys-red to
     *                             neon pink. Two roles, one value.
     *
     *   LIVE / ROOM_SPRITE       10.2 deg — they USED to be the same value, because LIVE was
     *                             wrongly holding Capture's second stop. With LIVE corrected
     *                             to #F43F8E they are a live pink and a room pink, ten degrees
     *                             apart, and they co-occur only in the timeline where live is
     *                             a 1.5dp line and the room colour is a filled tape.
     *   ROOM_VIZ / CAREFUL       11.8 deg — co-occur on the lobby, where careful is a 10sp
     *                             mono stat label beside a clock glyph in the header and the
     *                             Viz Lab amber is a 3dp signature bar and a pill. Context
     *                             disambiguates before colour has to.
     *
     *   GUIDE / ROOM_AVATAR_DEEP  9.9 deg — these are TWO STEPS OF ONE VIOLET RAMP, not two
     *                             accents. A previous audit already caught them reading alike
     *                             and resolved it: see COLOR_SAME_ROW_OUTLINE in
     *                             LayerRowRenderer, where the same-row cue was moved to white
     *                             so that purple means cross-row and nothing else. Do not
     *                             "separate the hues" here; the separation is in the shapes.
     *   ARMED / ARMED_LIGHT       1.2 deg — named as a ramp for exactly this reason, after it
     *                             spent a while pretending to be a room.
     * ═════════════════════════════════════════════════════════════════════════════
     */

    /** Apply an alpha to a token without re-typing the hex. */
    public static int alpha(int colour, int a) {
        return (colour & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }
}
