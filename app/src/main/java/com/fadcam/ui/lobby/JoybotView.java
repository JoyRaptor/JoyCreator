package com.fadcam.ui.lobby;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.motion.Motion;

/**
 * JOYBOT.
 *
 * <p>Two frames of white line art on transparency — {@code joybot_neutral} and
 * {@code joybot_smile} — registered on his HANDS, so that swapping them squashes him
 * rather than moving or resizing him. The art carries no colour of its own: tint it, or
 * put it on a disc with {@link #setOrb}.
 *
 * <h3>What he does, and why each one earns its frames</h3>
 * <ul>
 *   <li><b>Idle hover.</b> A slow float, about 1.6% of his height, on a ~4 second cycle.
 *       He is the only thing on the lobby that is a CHARACTER rather than a control, and a
 *       character that is perfectly still is a picture of a character. The amplitude is
 *       deliberately below the threshold where you would describe it as "moving" — you
 *       notice it only if you look at him directly, which is exactly when you want to.</li>
 *   <li><b>Smile on push.</b> JoyRaptor's instruction. The smile comes with a downward dip of
 *       {@link #DIP_FRACTION}, which is his note that "his body moves down about 5% when
 *       smiling in the art" — applied here as motion rather than baked into the frames, so
 *       it eases instead of cutting.</li>
 *   <li><b>Smile on scroll.</b> Also his: "on scroll gesture in screens it looks like he's
 *       watching and reacting". He reacts to the content moving, then settles.</li>
 * </ul>
 *
 * <h3>The animation is not allowed to cost anything</h3>
 * Everything here moves {@code translationY} and {@code alpha}, which are compositor
 * properties — no layout pass, no redraw of the two bitmaps. The idle animator is torn down
 * whenever he is off-screen or detached, because an infinite animator that survives the
 * screen it belongs to is the classic way a "subtle" flourish turns into a battery
 * complaint nobody can trace. {@link Motion#reduced(Context)} switches all of it off.
 */
public final class JoybotView extends FrameLayout {

    /** How far he dips when he smiles, as a fraction of his own height. */
    private static final float DIP_FRACTION = 0.05f;
    /** Idle float amplitude, as a fraction of his height. */
    private static final float HOVER_FRACTION = 0.016f;
    private static final long HOVER_PERIOD_MS = 4000L;
    /** How long a smile lasts before he settles back. */
    private static final long SMILE_HOLD_MS = 1100L;
    /** Cross-fade between the two faces. Fast: an expression is a change, not a dissolve. */
    private static final long FACE_SWAP_MS = 130L;

    private final ImageView neutral;
    private final ImageView smile;

    @Nullable private ValueAnimator hover;
    private float hoverOffset = 0f;
    private float dipOffset = 0f;
    private boolean smiling = false;

    private final Runnable settle = this::stopSmiling;

    public JoybotView(Context c) { this(c, null); }
    public JoybotView(Context c, @Nullable AttributeSet a) { this(c, a, 0); }

    public JoybotView(Context c, @Nullable AttributeSet a, int def) {
        super(c, a, def);
        neutral = face(R.drawable.joybot_neutral, 1f);
        smile   = face(R.drawable.joybot_smile, 0f);
        addView(neutral);
        addView(smile);
        setClipChildren(false);
        setClipToPadding(false);
    }

    // ── the shared canvas ───────────────────────────────────────────────────
    // Both frames live on one 360x564 canvas, registered on the BOTTOM OF HIS HANDS.
    //
    // That anchor is JoyRaptor's, and it is the reason he squashes instead of resizing. The
    // two frames are scaled so their hand-to-hand spans match, which leaves the smiling
    // head 246x295 against the neutral's 231x319 -- wider and shorter at nearly the same
    // area. Pinning the hands then makes that compression happen DOWNWARD from the crown
    // rather than upward from the base: "blinking pulling his tummy up is weirder than
    // blinking pulling his crown down."
    //
    // The box below is the NEUTRAL body. Everything above it is headroom only the smile's
    // spark strokes ever enter. Fractions, not pixels, so re-exporting the art at another
    // resolution cannot silently move him.
    private static final float BODY_TOP = 0.2074f;
    private static final float BODY_BOT = 0.9663f;
    private static final float BODY_L   = 0.0417f;
    private static final float BODY_R   = 0.9611f;

    /**
     * How much of the box he fills when he is sitting on an orb.
     *
     * <p>Without an orb he fills it. On one he has to clear a CIRCLE, and a circle's
     * usable width shrinks toward its top and bottom — his hands are at his widest exactly
     * where the disc is still wide, but his base ring and his crown are not, so fitting him
     * edge to edge would push both outside the disc and make it look like a badge he is
     * falling out of.
     */
    private static final float ORB_FILL = 0.76f;

    private boolean onOrb = false;

    // ── THE ANIMATED HALF ───────────────────────────────────────────────────
    // JoyRaptor: "he will when large or in chat be an animated claymation style avatar ...
    // he greets you as a claymation and then he flies up into the corner and flattens into
    // the white icon."
    //
    // The stills and the film are two representations of ONE character, so they live in one
    // view rather than in two that call sites have to choose between. Everything public here
    // — setTint, setOrb, react, watch — behaves identically whichever is playing, which is
    // the point: the lobby chrome should not need to know that Joybot has learned to move.
    @Nullable private FilmView film;

    /**
     * Play a spritesheet instead of the stills.
     *
     * <p>Pass null to go back to the line art. The view keeps its size, its orb, its tint
     * and its idle hover across the swap, so a call site that set Joybot up once never has
     * to set him up again.
     */
    public void setFilm(@Nullable JoybotFilm f) {
        // The colour character has no orb under him: the orb is the icon's seat (owner, 09-18:
        // the claymation "flattens into the white icon").
        android.graphics.drawable.Drawable seat = getBackground();
        if (seat != null) seat.setAlpha(f != null && f.isActor() ? 0 : 255);
        if (f == null) {
            if (film != null) { removeView(film); film = null; }
            neutral.setVisibility(VISIBLE);
            smile.setVisibility(VISIBLE);
            return;
        }
        if (film == null) {
            film = new FilmView(getContext());
            film.setLayoutParams(new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
            addView(film);
            film.calm = calmThruster;
        }
        // The stills are HIDDEN rather than removed: a one-shot film finishing has to hand
        // the face back instantly, and re-inflating two ImageViews at that moment would
        // drop the frame the handover happens on.
        neutral.setVisibility(INVISIBLE);
        smile.setVisibility(INVISIBLE);
        film.onFinished = null;
        film.play(f);
    }

    /**
     * The entrance: he plays out, then flattens into the icon he is in the chrome.
     *
     * <p>The flatten is a scale toward the view's own bounds plus a cross-fade to the still,
     * which is what "flattens into the white icon" has to mean mechanically — the claymation
     * is a photographed object with depth and the icon is a flat line drawing, so the
     * transition is from one to the other, not a shrink of the first.
     *
     * <p>{@code onDone} fires after the still is fully in place, so a caller can chain the
     * fly-to-corner without guessing at a duration.
     */
    public void playThenFlatten(@NonNull JoybotFilm f, @Nullable Runnable onDone) {
        setFilm(f);
        if (film == null) { if (onDone != null) onDone.run(); return; }
        film.onFinished = () -> {
            neutral.setVisibility(VISIBLE);
            neutral.setAlpha(0f);
            neutral.animate().alpha(1f).setDuration(FACE_SWAP_MS * 2)
                    .setInterpolator(Motion.EASE_OUT).start();
            film.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                    .setDuration(FACE_SWAP_MS * 2)
                    .setInterpolator(Motion.EASE_IN_OUT)
                    .withEndAction(() -> {
                        setFilm(null);
                        if (onDone != null) onDone.run();
                    }).start();
        };
    }

    /**
     * One cell of a spritesheet, on a clock.
     *
     * <p>Drives itself with postInvalidateOnAnimation rather than a ValueAnimator: the film
     * already knows which cell belongs to a timestamp, so all this needs is "draw again next
     * vsync", and going through an animator would add a second source of timing that could
     * disagree with the first.
     */
    private static final class FilmView extends View {
        @Nullable JoybotFilm f;
        @Nullable Runnable onFinished;
        private final android.graphics.RectF box = new android.graphics.RectF();
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);

        /** The colour character is drawn as he is: a tint would flatten him to a silhouette. */
        private final android.graphics.Paint plain =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);

        // ── life on top of the drawings (owner, 11-13: "use the squash and stretch ... extra zest") ──
        // The frames change eight times a second; between them he would be a cut-out. So he sways
        // and bobs on his hover peg all the time, and every new pose lands with a small squash that
        // springs back. All of it pivots on the peg, so his feet never slide.
        private static final long SWAY_MS = 3600L;
        private static final float SWAY_DEG = 1.6f;
        private static final float BOB_FRACTION = 0.012f;
        private static final float POP_MS = 240f;
        private static final float POP_SMALL = 0.035f, POP_BIG = 0.09f;
        private int lastShown = -1;
        private long popAt = 0L;
        private boolean popBig = false;

        FilmView(android.content.Context c) { super(c); }

        // ── his calm thruster on his seat (owner, 14 Nov: "fade away to a very calm glow and calm low
        // emission once he's in place") ──
        // A faint plasma push ring every 1.1 s, drifting a little way off his hover peg as it grows and
        // fades; the same rings the arrival's GL stage puffs in its calm phase, drawn here so nothing
        // has to render on the GPU while the chat sits still. Only while he sits at home.
        boolean calm;
        private static final int RINGS = 3;
        private static final long RING_EVERY_MS = 1100L, RING_LIFE_MS = 1300L;
        private final long[] ringBorn = new long[RINGS];
        private final float[] ringSize = new float[RINGS];
        private int ringNext;
        private long lastRing;
        private final java.util.Random dice = new java.util.Random();
        private final android.graphics.Paint ringCore = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ringGlow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF ringBox = new android.graphics.RectF();

        // -- choreography on top of the frames (owner, 11-15: "squash and stretch, pacing, moving around to add extra
        // depth ... he can do a flip. He can have his particle effects and things going off ... when he's searching, he
        // can be kind of searching around"), from the animation's acts.json "moves" --
        private static final long FLIP_MS = 650L;

        /** Drift, hop (with a squash on landing), a backflip as it starts, and size; all about his peg or body. */
        private void choreograph(@NonNull Canvas canvas, @NonNull JoybotFilm f, float px, float py, float cy, long now) {
            JoybotFilm.Moves mv = f.moves();
            f.turnForDrift(false);
            if (mv == null) return;
            float dp = getResources().getDisplayMetrics().density;
            long t = Math.max(0L, now - f.startedAt());
            // A one-shot drifts out and comes back, so it hands over to the next pose where it began (no jump).
            long once = f.onceMs();
            float env = once > 0L ? (float) Math.sin(Math.PI * Math.min(1.0, t / (double) once)) : 1f;
            if (mv.driftDp > 0f && mv.driftMs > 0f) {
                double ph = t / (double) mv.driftMs * Math.PI * 2.0;
                canvas.translate((float) Math.sin(ph) * mv.driftDp * dp * env, 0f);
                f.turnForDrift(Math.cos(ph) < 0.0);          // he faces the way he is moving
            }
            if (mv.hopDp > 0f && mv.hopMs > 0f) {
                float ph = (t % (long) mv.hopMs) / mv.hopMs;
                canvas.translate(0f, -4f * ph * (1f - ph) * mv.hopDp * dp * env);
                float q = ph < .1f ? (1f - ph / .1f) * .08f : ph > .92f ? (ph - .92f) / .08f * .05f : 0f;
                canvas.scale(1f + q, 1f - q, px, py);
                if (mv.sparks && ph < .1f && t > 200L && now - lastSpark > 40L) {   // a few off each landing
                    lastSpark = now;
                    spark(px, py, 2, dp);
                }
            }
            if (mv.flip && t < FLIP_MS) flip(canvas, t / (float) FLIP_MS, px, cy, dp);
            if (mv.scale != 1f) canvas.scale(mv.scale, mv.scale, px, py);
            if (mv.sparks && t < 2600L && now - lastSpark > 70L) {
                lastSpark = now;
                spark(px, cy, 1, dp);
            }
        }

        /** A backflip at fraction {@code u}: up, over backwards about his body centre, and down again. */
        private static void flip(@NonNull Canvas canvas, float u, float px, float cy, float dp) {
            float e = u < .5f ? 4f * u * u * u : 1f - (float) Math.pow(-2f * u + 2f, 3) / 2f;
            canvas.translate(0f, -4f * u * (1f - u) * 34f * dp);
            canvas.rotate(-360f * e, px, cy);
        }

        // -- tricks on top of whatever plays (owner, 11-15 midday): a backflip when asked or tapped, and a word that pops
        // above his head and floats away ("oopsies!" when a tap wakes him, "wheee!" when he is flung) --
        /** When the backflip started (uptime ms), 0 for none. */
        long trickAt = 0L;
        private boolean trickSparked;
        @Nullable String word;
        long wordAt;
        private static final long WORD_MS = 1300L;
        @Nullable private android.graphics.Paint wordPaint;

        /** A squash that springs back, as if he had been bumped. */
        void pop() {
            popAt = android.os.SystemClock.uptimeMillis();
            popBig = true;
            invalidate();
        }

        private void drawWord(@NonNull Canvas canvas, long now, float headTop) {
            if (word == null) return;
            long age = now - wordAt;
            if (age >= WORD_MS) { word = null; return; }
            float dp = getResources().getDisplayMetrics().density;
            if (wordPaint == null) {
                wordPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                wordPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
                wordPaint.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
                android.graphics.Typeface tf = null;
                try { tf = androidx.core.content.res.ResourcesCompat.getFont(getContext(), R.font.archivo_variable); }
                catch (RuntimeException ignored) { /* the system bold */ }
                wordPaint.setTypeface(android.graphics.Typeface.create(tf != null ? tf : android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                wordPaint.setShadowLayer(6f * dp, 0f, 0f, 0xCCFF2FB8);
            }
            float u = age / (float) WORD_MS;
            float x = Math.min(1f, age / 170f) - 1f;
            float grow = 1f + 2.70158f * x * x * x + 1.70158f * x * x;          // overshoots a little, then settles
            float rise = 16f * dp * (1f - (1f - u) * (1f - u));
            float alpha = u < .7f ? 1f : 1f - (u - .7f) / .3f;
            wordPaint.setColor(android.graphics.Color.argb(Math.round(255 * alpha), 255, 244, 251));
            int s = canvas.save();
            canvas.translate(getWidth() / 2f, headTop - 4f * dp - rise);
            canvas.scale(grow, grow);
            canvas.rotate(-6f);
            canvas.drawText(word, 0f, 0f, wordPaint);
            canvas.restoreToCount(s);
        }

        // a few of his plasma sparks and glints: white-hot cooling to pink, made of the same stuff as his thruster
        private static final int SPARKS = 40;
        private final float[] sx = new float[SPARKS], sy = new float[SPARKS], svx = new float[SPARKS], svy = new float[SPARKS];
        private final long[] sBorn = new long[SPARKS];
        private final boolean[] sGlint = new boolean[SPARKS];
        private int sNext;
        private long lastSpark, lastSparkDraw;
        private final android.graphics.Paint sparkPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path glintPath = new android.graphics.Path();

        private void spark(float x, float y, int n, float dp) {
            long now = android.os.SystemClock.uptimeMillis();
            for (int i = 0; i < n; i++) {
                int k = sNext;
                sNext = (sNext + 1) % SPARKS;
                double a = dice.nextDouble() * Math.PI * 2.0;
                float r = (14f + dice.nextFloat() * 22f) * dp;
                sx[k] = x + (float) Math.cos(a) * r;
                sy[k] = y + (float) Math.sin(a) * r * .8f;
                float sp = (40f + dice.nextFloat() * 90f) * dp;
                svx[k] = (float) Math.cos(a) * sp;
                svy[k] = (float) Math.sin(a) * sp - 60f * dp;
                sBorn[k] = now;
                sGlint[k] = dice.nextInt(4) == 0;
            }
        }

        private void drawSparks(@NonNull Canvas canvas, long now) {
            float dt = lastSparkDraw == 0L ? 0f : Math.min(.05f, (now - lastSparkDraw) / 1000f);
            lastSparkDraw = now;
            float dp = getResources().getDisplayMetrics().density;
            for (int k = 0; k < SPARKS; k++) {
                if (sBorn[k] == 0L) continue;
                float age = (now - sBorn[k]) / 520f;
                if (age >= 1f) { sBorn[k] = 0L; continue; }
                svy[k] += 260f * dp * dt;
                svx[k] *= (float) Math.pow(.4, dt);
                sx[k] += svx[k] * dt;
                sy[k] += svy[k] * dt;
                float a = (1f - age) * (1f - age), core = Math.max(0f, 1f - age * 2.5f);
                if (sGlint[k]) {
                    float s1 = (float) Math.sin(Math.PI * age), L = 5f * dp * s1;
                    sparkPaint.setColor(android.graphics.Color.argb(Math.round(230 * s1), 255, 220, 245));
                    glintPath.reset();
                    glintPath.moveTo(sx[k], sy[k] - L * 1.6f); glintPath.lineTo(sx[k] + L * .22f, sy[k]);
                    glintPath.lineTo(sx[k], sy[k] + L * 1.6f); glintPath.lineTo(sx[k] - L * .22f, sy[k]); glintPath.close();
                    glintPath.moveTo(sx[k] - L * 1.6f, sy[k]); glintPath.lineTo(sx[k], sy[k] + L * .22f);
                    glintPath.lineTo(sx[k] + L * 1.6f, sy[k]); glintPath.lineTo(sx[k], sy[k] - L * .22f); glintPath.close();
                    canvas.drawPath(glintPath, sparkPaint);
                } else {
                    sparkPaint.setColor(android.graphics.Color.argb(Math.round(255 * a), 255, Math.round(80 + 170 * core), Math.round(200 + 50 * core)));
                    canvas.drawCircle(sx[k], sy[k], Math.max(.6f, 1.8f * (1f - age * .5f)) * dp, sparkPaint);
                }
            }
        }

        private void drawCalmRings(@NonNull Canvas canvas, float px, float py, long now) {
            View host = (View) getParent();
            boolean home = host != null && Math.abs(host.getTranslationX()) < 1f && Math.abs(host.getTranslationY()) < 1f
                    && Math.abs(host.getRotation()) < .5f;
            if (home && now - lastRing >= RING_EVERY_MS) {
                lastRing = now;
                ringBorn[ringNext] = now;
                ringSize[ringNext] = .25f + .75f * dice.nextFloat();
                ringNext = (ringNext + 1) % RINGS;
            }
            float dp = getResources().getDisplayMetrics().density;
            ringCore.setStyle(android.graphics.Paint.Style.STROKE);
            ringGlow.setStyle(android.graphics.Paint.Style.STROKE);
            for (int i = 0; i < RINGS; i++) {
                if (ringBorn[i] == 0L) continue;
                long age = now - ringBorn[i];
                if (age >= RING_LIFE_MS) continue;
                float f = age / (float) RING_LIFE_MS, f0 = ringSize[i];
                float rad = (3.64f + 12.6f * (1f - (1f - f) * (1f - f))) * f0 * dp;
                // drifts off the peg, slowing, and spreads on the seat's sill 4 dp below him
                float drop = Math.min(3.5f * dp, 13f * dp * (1f - (float) Math.pow(.3, age / 1000.0)));
                float alpha = (float) Math.pow(1f - f, 1.6) * .55f, hot = Math.max(0f, 1f - f * 2.2f);
                ringBox.set(px - rad, py + drop - rad * .3f, px + rad, py + drop + rad * .3f);
                ringGlow.setStrokeWidth((5f + 8f * f) * dp);
                ringGlow.setColor(android.graphics.Color.argb(Math.round(255 * alpha * .22f), 255, 40, 190));
                canvas.drawOval(ringBox, ringGlow);
                ringCore.setStrokeWidth((2f * (1f - f * .6f) + 2.4f * f) * dp);
                ringCore.setColor(android.graphics.Color.argb(Math.round(255 * alpha * (.55f + .4f * hot)),
                        255, Math.round(70 + 110 * hot), Math.round(200 + 45 * hot)));
                canvas.drawOval(ringBox, ringCore);
            }
        }

        void play(@NonNull JoybotFilm film) {
            this.f = film;
            film.start(android.os.SystemClock.uptimeMillis());
            lastShown = -1;
            popAt = android.os.SystemClock.uptimeMillis();
            popBig = true;
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
            postInvalidateOnAnimation();
        }

        void setFilmTint(int colour) {
            paint.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    colour, android.graphics.PorterDuff.Mode.SRC_ATOP));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (f == null || getWidth() == 0) return;
            // Fit the cell, preserving its aspect. A claymation cell is a photograph of a
            // physical object; stretching it is immediately visible in a way that stretching
            // line art is not.
            float cw = f.cellWidth(), ch = f.cellHeight();
            android.graphics.RectF body = f.focus();
            if (body != null && body.width() > 0f && body.height() > 0f) {
                // His BODY fills the view, as the stills' body does; what he holds may reach
                // outside it (clipChildren is off all the way up).
                float s = Math.min(getWidth() / (cw * body.width()), getHeight() / (ch * body.height()));
                float left = getWidth() / 2f - cw * s * body.centerX();
                float top = getHeight() / 2f - ch * s * body.centerY();
                box.set(left, top, left + cw * s, top + ch * s);
            } else {
                float s = Math.min(getWidth() / cw, getHeight() / ch);
                float w = cw * s, h = ch * s;
                box.set((getWidth() - w) / 2f, (getHeight() - h) / 2f,
                        (getWidth() + w) / 2f, (getHeight() + h) / 2f);
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (calm && f.isActor() && body != null) {
                drawCalmRings(canvas, getWidth() / 2f, box.top + box.height() * body.bottom, now);
            }
            int saved = canvas.save();
            if (f.isActor() && body != null) {
                float px = getWidth() / 2f;
                float py = box.top + box.height() * body.bottom;
                double t = (now % SWAY_MS) / (double) SWAY_MS * Math.PI * 2.0;
                float age = (now - popAt) / POP_MS;
                float k = 0f;
                if (age < 1f) {
                    float fade = (1f - age) * (1f - age);
                    k = fade * (popBig ? POP_BIG : POP_SMALL) * (float) Math.cos(age * Math.PI * 1.5);
                }
                canvas.translate(0f, (float) Math.sin(t * 2.0 + 0.6) * getHeight() * BOB_FRACTION);
                canvas.rotate((float) Math.sin(t) * SWAY_DEG, px, py);
                canvas.scale(1f + k, 1f - k, px, py);
                float cy = box.top + box.height() * body.centerY();
                choreograph(canvas, f, px, py, cy, now);
                long ft = now - trickAt;
                if (trickAt != 0L && ft < FLIP_MS) {
                    float dp = getResources().getDisplayMetrics().density;
                    flip(canvas, ft / (float) FLIP_MS, px, cy, dp);
                    if (!trickSparked && ft > FLIP_MS * .4f) { trickSparked = true; spark(px, cy, 10, dp); }
                } else {
                    trickAt = 0L;
                }
            }
            f.draw(canvas, box, f.isActor() ? plain : paint, now);
            canvas.restoreToCount(saved);
            if (f.isActor() && body != null) {
                drawSparks(canvas, now);
                drawWord(canvas, now, box.top + box.height() * body.top);
            }
            if (f.shownFrame() != lastShown) {
                if (lastShown != -1) { popAt = now; popBig = false; }
                lastShown = f.shownFrame();
            }
            if (f.isFinished()) {
                Runnable done = onFinished;
                onFinished = null;                 // once
                if (done != null) post(done);
                Runnable chain = f.takeOnEnd();
                if (chain != null) post(chain);
            } else {
                postInvalidateOnAnimation();
            }
        }
    }

    private ImageView face(int res, float alpha) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(res);
        // MATRIX, not FIT_CENTER.
        //
        // FIT_CENTER fits the whole 360x540 canvas, headroom included, so in a 40dp chrome
        // slot Joybot came out as a tiny distant figure whose head was about 16dp — the
        // headroom was eating a third of his box for something that is visible for one
        // second when he smiles.
        //
        // The matrix fits his BODY to the view and lets the sparks fall outside it. Both
        // frames get the SAME matrix, which is the whole point of registering them: they
        // stay locked to each other, and when he smiles the sparks pop above the view's own
        // bounds. clipChildren is off all the way up, so that reads as delight rather than
        // as clipping.
        v.setScaleType(ImageView.ScaleType.MATRIX);
        v.setAlpha(alpha);
        v.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return v;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit(neutral, w, h);
        fit(smile, w, h);
    }

    private void fit(ImageView v, int w, int h) {
        android.graphics.drawable.Drawable d = v.getDrawable();
        if (d == null || w == 0 || h == 0) return;
        float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return;

        float bodyW = iw * (BODY_R - BODY_L);
        float bodyH = ih * (BODY_BOT - BODY_TOP);
        float fill = onOrb ? ORB_FILL : 1f;
        float s = Math.min(w * fill / bodyW, h * fill / bodyH);

        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        // Centre the BODY box in the view, not the canvas.
        m.postTranslate((w - bodyW * s) / 2f - iw * BODY_L * s,
                        (h - bodyH * s) / 2f - ih * BODY_TOP * s);
        v.setImageMatrix(m);
    }

    /** Tint both faces. The art is white, so this is a straight multiply. */
    public void setTint(int colour) {
        neutral.setColorFilter(colour);
        smile.setColorFilter(colour);
        // The claymation is full-colour art, so a tint would normally be wrong on it — but
        // the chrome's white-on-disc treatment applies to whichever representation is
        // showing, and a half-tinted handover looks like a bug. Callers that want the film
        // in its own colours simply do not tint.
        if (film != null) film.setFilmTint(colour);
    }

    /**
     * Put him on a gradient disc.
     *
     * <p>JoyRaptor, after seeing him tinted violet on the lobby's black: "turns out he looks
     * harder to read coloured on black." He is right, and the reason is that this art is
     * LINE work — its shape is carried by thin strokes and by the holes between them, and
     * a mid-violet stroke on black has nothing like the separation a white one does.
     *
     * <p>The disc fixes both halves at once: it gives the violet back as a field instead
     * of as line colour, and it gives the white strokes a bright ground to read against.
     * It also makes him a distinct OBJECT in the chrome rather than a drawing floating in
     * the background, which is right for the one element on the screen that is a character.
     */
    public void setOrb(int from, int to) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{from, to});
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        setBackground(d);
        onOrb = true;
        // White strokes on the orb: studio_tokens.xml s_on_room, the ink for dark room gradients.
        setTint(androidx.core.content.ContextCompat.getColor(getContext(), com.fadcam.R.color.s_on_room));
        if (getWidth() > 0) { fit(neutral, getWidth(), getHeight()); fit(smile, getWidth(), getHeight()); }
    }

    // ── "he's got a good idea" ──────────────────────────────────────────────
    //
    // Owner, 2026-10-14: when Joybot has a suggestion you haven't seen, his orb BREATHES — the
    // studio green behind him, slow and calm, easy to ignore. No sound, no vibration, no badge.
    // A separate disc under the faces so it works with or without an orb, and so the owner's
    // sprite films can play over it later. Reduced motion: a still tint instead of a breath.

    private static final long IDEA_BREATH_MS = 4000L;
    /** Peak opacity of the breath. Low on purpose: noticed when you look, ignorable when you don't. */
    private static final float IDEA_PEAK = 0.55f;

    @Nullable private View ideaGlow;
    @Nullable private ValueAnimator ideaBreath;
    private boolean hasIdea = false;

    /** Breathe the studio green behind him while there is an idea you haven't seen. */
    public void setHasIdea(boolean has) {
        if (has == hasIdea) return;
        hasIdea = has;
        if (has) {
            if (ideaGlow == null) {
                ideaGlow = new View(getContext());
                android.graphics.drawable.GradientDrawable d =
                        new android.graphics.drawable.GradientDrawable(
                                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                                new int[]{com.fadcam.ui.faditor.Studio.GO,
                                        com.fadcam.ui.faditor.Studio.GO_END});
                d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                ideaGlow.setBackground(d);
                ideaGlow.setLayoutParams(new LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
                ideaGlow.setAlpha(0f);
                addView(ideaGlow, 0);    // under both faces and any film
            }
            startIdeaBreath();
        } else {
            stopIdeaBreath();
            if (ideaGlow != null) ideaGlow.animate().alpha(0f).setDuration(400L).start();
        }
    }

    public boolean hasIdea() { return hasIdea; }

    private void startIdeaBreath() {
        if (!hasIdea || ideaGlow == null || ideaBreath != null) return;
        if (Motion.reduced(getContext())) {
            ideaGlow.setAlpha(IDEA_PEAK * 0.8f);
            return;
        }
        ideaBreath = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        ideaBreath.setDuration(IDEA_BREATH_MS);
        ideaBreath.setRepeatCount(ValueAnimator.INFINITE);
        ideaBreath.setInterpolator(null);
        ideaBreath.addUpdateListener(a -> {
            if (ideaGlow == null) return;
            // 0 → peak → 0 on a cosine: it fades in from nothing, so it never "switches on".
            float t = (float) a.getAnimatedValue();
            ideaGlow.setAlpha(IDEA_PEAK * 0.5f * (1f - (float) Math.cos(t)));
        });
        ideaBreath.start();
    }

    private void stopIdeaBreath() {
        if (ideaBreath != null) { ideaBreath.cancel(); ideaBreath = null; }
    }

    // ── moods ───────────────────────────────────────────────────────────────
    //
    // Owner, 2026-10-14: he will load a LABELLED sprite sheet of Joybot (talking, thinking,
    // excited…). So everything that wants Joybot to look a certain way asks for a MOOD, never for
    // frames. Today a mood is played with the two stills and motion; once a film is registered
    // under the same name, that mood plays the film instead. No call site changes on the day the
    // art arrives.

    public enum Mood {
        IDLE("idle"), LISTEN("listen"), THINK("think"), WORK("work"),
        TALK("talk"), EXCITED("excited"), SHOW("show"), OOPS("oops"),
        /** Looking it up in the handbook (owner, 11-15: while he checks the help). */
        READ("read"),
        /** He can't help, or says he can't do something (owner, 11-15). */
        NERVOUS("nervous");

        /** The animation name to look for in his sprite sheet. */
        public final String sheetName;
        Mood(String n) { sheetName = n; }
    }

    private Mood mood = Mood.IDLE;
    private final java.util.Map<Mood, JoybotFilm> moodFilms = new java.util.EnumMap<>(Mood.class);
    @Nullable private ValueAnimator moodAnim;

    /** Register the film a mood plays (from his sprite sheet). Null removes it. */
    public void setMoodFilm(@NonNull Mood m, @Nullable JoybotFilm f) {
        if (f == null) moodFilms.remove(m); else moodFilms.put(m, f);
        if (m == mood) setMood(mood);
    }

    @NonNull public Mood getMood() { return mood; }

    /**
     * Someone who chooses WHICH animation a mood plays, so one mood can have several and each can
     * have a way in and a way out. Without one, a mood plays its single registered film or the stills.
     */
    public interface Director {
        /** True when he is already doing the right thing for this mood: leave him be. */
        boolean keeps(@NonNull Mood m);
        /** A short film that puts away what he is doing now, or null to cut straight over. */
        @Nullable JoybotFilm leave();
        /** The film for this mood, or null to fall back to the stills. */
        @Nullable JoybotFilm enter(@NonNull Mood m);
        /** He has just come into view (the chat hides him until he leaves his seat in the header). */
        default void shown() {}
        /** True while a short flourish (a bow, a salute, a point) is playing. */
        default boolean flourishing() { return false; }
    }

    @Nullable private Director director;
    private int moodTurn = 0;
    private boolean arrived;

    /** He has just flown in and landed on his seat (the chat's arrival): that was his entrance. */
    public void markArrived() { arrived = true; setCalmThruster(true); }

    private boolean calmThruster;

    /** On his seat his thruster idles: a faint ring now and then (see FilmView). */
    public void setCalmThruster(boolean on) {
        calmThruster = on;
        if (film != null) film.calm = on;
    }

    /** True once {@link #markArrived} has been called: his director skips its own entrance flourish. */
    public boolean arrived() { return arrived; }

    public void setDirector(@Nullable Director d) {
        director = d;
        if (d != null) setMood(mood);
    }

    @Nullable public Director getDirector() { return director; }

    @Nullable private String pendingMoment;
    private boolean forceMood;

    /**
     * Play a moment (greet, thanks, done, present, point-left...) once, then carry on with the current mood. Asked of
     * the director; see {@link JoybotDirector#moment}.
     */
    public void playMoment(@NonNull String moment) {
        if (director == null) return;
        pendingMoment = moment;
        if (director.flourishing()) return;                 // after the one playing now
        refreshMood();
    }

    public boolean hasMoment() { return pendingMoment != null; }

    /** Enter the current mood again even if the director would keep what is playing (a moment is waiting). */
    public void refreshMood() {
        forceMood = true;
        setMood(mood);
    }

    /** The moment asked for, once (the director takes it). */
    @Nullable
    public String takeMoment() {
        String m = pendingMoment;
        pendingMoment = null;
        return m;
    }

    public void setMood(@NonNull Mood m) {
        boolean force = forceMood;
        forceMood = false;
        if (!force && director != null && film != null && director.keeps(m)) { mood = m; return; }
        mood = m;
        if (moodAnim != null) { moodAnim.cancel(); moodAnim = null; }
        if (director != null) {
            final int turn = ++moodTurn;
            JoybotFilm out = film != null ? director.leave() : null;
            final JoybotFilm next = director.enter(m);
            if (next != null) {
                if (out != null) {
                    setFilm(out);
                    if (film != null) film.onFinished = () -> { if (turn == moodTurn) setFilm(next); };
                } else {
                    setFilm(next);
                }
                return;
            }
        }
        JoybotFilm f = moodFilms.get(m);
        if (f != null) { setFilm(f); return; }
        if (film != null) setFilm(null);
        boolean still = Motion.reduced(getContext());
        switch (m) {
            case LISTEN:
            case EXCITED:
                react();
                removeCallbacks(settle);            // hold the smile while the mood lasts
                if (m == Mood.EXCITED && !still) hop(2);
                break;
            case SHOW:
                react();
                removeCallbacks(settle);
                if (!still) hop(1);
                break;
            case TALK:
                // Two stills can still TALK: neutral and smile alternate like a mouth.
                stopSmiling();
                if (still) break;
                moodAnim = ValueAnimator.ofInt(0, 1);
                moodAnim.setDuration(190L);
                moodAnim.setRepeatCount(ValueAnimator.INFINITE);
                moodAnim.setRepeatMode(ValueAnimator.REVERSE);
                moodAnim.addUpdateListener(a -> {
                    boolean open = (int) a.getAnimatedValue() == 1;
                    neutral.setAlpha(open ? 0f : 1f);
                    smile.setAlpha(open ? 1f : 0f);
                });
                moodAnim.start();
                break;
            case THINK:
            case READ:
            case WORK:
                stopSmiling();
                if (still) break;
                // A slow tilt while he thinks, a quicker one while he works.
                moodAnim = ValueAnimator.ofFloat(-1f, 1f);
                moodAnim.setDuration(m == Mood.WORK ? 700L : 1400L);
                moodAnim.setRepeatCount(ValueAnimator.INFINITE);
                moodAnim.setRepeatMode(ValueAnimator.REVERSE);
                moodAnim.setInterpolator(Motion.EASE_IN_OUT);
                moodAnim.addUpdateListener(a -> {
                    float r = (float) a.getAnimatedValue() * (m == Mood.WORK ? 3f : 6f);
                    neutral.setRotation(r);
                    smile.setRotation(r);
                });
                moodAnim.start();
                break;
            case OOPS:
            case NERVOUS:
                stopSmiling();
                if (still) break;
                moodAnim = ValueAnimator.ofFloat(0f, 1f);
                moodAnim.setDuration(420L);
                moodAnim.addUpdateListener(a -> {
                    float t = a.getAnimatedFraction();
                    float x = (float) Math.sin(t * Math.PI * 6) * (1f - t) * getWidth() * 0.05f;
                    neutral.setTranslationX(x);
                    smile.setTranslationX(x);
                });
                moodAnim.start();
                break;
            case IDLE:
            default:
                stopSmiling();
                break;
        }
        if (m != Mood.THINK && m != Mood.READ && m != Mood.WORK) { neutral.setRotation(0f); smile.setRotation(0f); }
        if (m != Mood.OOPS && m != Mood.NERVOUS) { neutral.setTranslationX(0f); smile.setTranslationX(0f); }
        if (m != Mood.TALK && !smiling) { neutral.setAlpha(1f); smile.setAlpha(0f); }
    }

    /** A little jump of delight, {@code n} times. Rides the dip offset so hover keeps working. */
    private void hop(int n) {
        if (dipAnim != null) dipAnim.cancel();
        float h = -getHeight() * 0.12f;
        float[] keys = new float[n * 2 + 1];
        for (int i = 0; i < n; i++) { keys[i * 2] = 0f; keys[i * 2 + 1] = h; }
        keys[n * 2] = 0f;
        dipAnim = ValueAnimator.ofFloat(keys);
        dipAnim.setDuration(320L * n);
        dipAnim.setInterpolator(Motion.EASE_IN_OUT);
        dipAnim.addUpdateListener(a -> {
            dipOffset = (float) a.getAnimatedValue();
            applyOffsets();
        });
        dipAnim.start();
    }

    // ── places ──────────────────────────────────────────────────────────────

    /**
     * Fly so his centre lands on {@code x, y} (in his parent's coordinates), then run {@code then}.
     * An arc, not a straight slide: he is a character going somewhere, not a panel moving.
     */
    public void flyTo(float x, float y, @Nullable Runnable then) {
        final float sx = getTranslationX(), sy = getTranslationY();
        final float tx = sx + (x - (getLeft() + sx + getWidth() / 2f));
        final float ty = sy + (y - (getTop() + sy + getHeight() / 2f));
        final float lift = -Math.min(getHeight() * 1.2f, Math.abs(tx - sx) * 0.25f + getHeight() * 0.3f);
        // He leans into the flight and stretches along it, then rights himself as he lands.
        final float lean = Math.max(-14f, Math.min(14f, (tx - sx) / Math.max(1f, getWidth()) * 5f));
        final int turn = ++flight;
        final long start = android.os.SystemClock.uptimeMillis();
        // On the clock, not a system animator: he flies even with "remove animations" on (owner, 11-14).
        Runnable step = new Runnable() {
            @Override public void run() {
                if (turn != flight) return;
                float k = Math.min(1f, (android.os.SystemClock.uptimeMillis() - start) / 560f);
                float t = Motion.EASE_IN_OUT.getInterpolation(k);
                float arc = (float) Math.sin(Math.PI * t);
                setTranslationX(sx + (tx - sx) * t);
                setTranslationY(sy + (ty - sy) * t + lift * arc);
                setRotation(lean * arc);
                setScaleX(1f - 0.06f * arc);
                setScaleY(1f + 0.08f * arc);
                if (k < 1f) { postOnAnimation(this); return; }
                setRotation(0f);
                setScaleX(1f);
                setScaleY(1f);
                if (then != null) then.run();
            }
        };
        postOnAnimation(step);
    }

    /** Counts flights, so a new one replaces one still under way. */
    private int flight = 0;

    // ── tricks and touch (owner, 11-15 midday) ──────────────────────────────
    // "He can do a flip"; "asleep, tap: he wakes with a scramble or an 'oopsies'"; "a directional poke nudges him (he's
    // a little guy)"; "a flick flings him round the screen: he bounces off the edges, comes back and settles". All on
    // the clock like his flights, so they play with "remove animations" on.

    /** A backflip on top of whatever he is doing. */
    public void backflip() {
        if (film == null) return;
        film.trickAt = android.os.SystemClock.uptimeMillis();
        film.trickSparked = false;
        film.invalidate();
    }

    /** A word that pops above his head and floats away ("oopsies!", "wheee!"). */
    public void say(@NonNull String word) {
        if (film == null) return;
        film.word = word;
        film.wordAt = android.os.SystemClock.uptimeMillis();
        film.invalidate();
    }

    /** Pushed by a poke: a little way along it ({@code dx, dy} px, any length), leaning, then he springs back. */
    public void nudge(float dx, float dy) {
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;
        final float dp = getResources().getDisplayMetrics().density;
        final float push = Math.max(10f * dp, Math.min(26f * dp, len * .5f));
        final float ux = dx / len, uy = dy / len;
        final float sx = getTranslationX(), sy = getTranslationY();
        final int turn = ++flight;
        final long start = android.os.SystemClock.uptimeMillis();
        if (film != null) film.pop();
        postOnAnimation(new Runnable() {
            @Override public void run() {
                if (turn != flight) return;
                float t = android.os.SystemClock.uptimeMillis() - start;
                float k;
                if (t < 110f) {
                    float u = t / 110f;
                    k = 1f - (1f - u) * (1f - u) * (1f - u);
                } else {
                    float b = Math.min(1f, (t - 110f) / 520f);
                    k = (float) (Math.exp(-4.2 * b) * Math.cos(b * Math.PI * 2.6));   // springs back, a wobble past
                }
                if (t >= 630f) {
                    setTranslationX(sx);
                    setTranslationY(sy);
                    setRotation(0f);
                    return;
                }
                setTranslationX(sx + ux * push * k);
                setTranslationY(sy + uy * push * k);
                setRotation(ux * 12f * k);
                postOnAnimation(this);
            }
        });
    }

    /** True while he is being flung about (a flick), until he is back on his seat. */
    public boolean tossed() { return toss != null && toss.turn == flight; }

    @Nullable private Toss toss;

    /**
     * Flung by a flick ({@code vx, vy} px/s): he flies off, bounces off the edges of what he is in (the chat), slows, then
     * comes home and rights himself; {@code settled} runs once he is back on his seat.
     */
    public void fling(float vx, float vy, @Nullable Runnable settled) {
        if (!(getParent() instanceof View)) return;
        float dp = getResources().getDisplayMetrics().density;
        float sp = (float) Math.hypot(vx, vy), cap = 4200f * dp;
        if (sp > cap) { vx *= cap / sp; vy *= cap / sp; }
        toss = new Toss(++flight, (View) getParent(), vx, vy, settled);
        postOnAnimation(toss);
    }

    private final class Toss implements Runnable {
        final int turn;
        final View parent;
        @Nullable final Runnable settled;
        float x, y, vx, vy, rot, spin;
        final long start;
        long last, hitAt = -1L, homeAt = -1L;
        boolean hitSide;
        float hx, hy, hr;

        Toss(int turn, @NonNull View parent, float vx, float vy, @Nullable Runnable settled) {
            this.turn = turn;
            this.parent = parent;
            this.settled = settled;
            this.vx = vx;
            this.vy = vy;
            x = getTranslationX();
            y = getTranslationY();
            rot = getRotation();
            float dp = getResources().getDisplayMetrics().density;
            spin = Math.max(-720f, Math.min(720f, vx / dp * .35f));
            start = last = android.os.SystemClock.uptimeMillis();
        }

        @Override
        public void run() {
            if (turn != flight) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = Math.min(.034f, (now - last) / 1000f);
            last = now;
            float dp = getResources().getDisplayMetrics().density;
            if (homeAt < 0L) {
                vy += 340f * dp * dt;                                   // a little weight, so he arcs
                float drag = (float) Math.exp(-1.2 * dt);
                vx *= drag;
                vy *= drag;
                x += vx * dt;
                y += vy * dt;
                float minX = -getLeft(), maxX = parent.getWidth() - getLeft() - getWidth();
                float minY = -getTop(), maxY = parent.getHeight() - getTop() - getHeight();
                if (x < minX) { x = minX; hit(now, true, -1, dp, Math.abs(vx)); vx = Math.abs(vx) * .72f; }
                else if (x > maxX) { x = maxX; hit(now, true, 1, dp, Math.abs(vx)); vx = -Math.abs(vx) * .72f; }
                if (y < minY) { y = minY; hit(now, false, -1, dp, Math.abs(vy)); vy = Math.abs(vy) * .72f; }
                else if (y > maxY) { y = maxY; hit(now, false, 1, dp, Math.abs(vy)); vy = -Math.abs(vy) * .62f; vx *= .9f; }
                spin *= (float) Math.exp(-1.0 * dt);
                rot += spin * dt;
                float speed = (float) Math.hypot(vx, vy) / dp;
                if ((now - start > 700L && speed < 150f) || now - start > 3200L) {
                    homeAt = now;
                    hx = x;
                    hy = y;
                    hr = rot - Math.round(rot / 360f) * 360f;            // the short way upright
                }
                setTranslationX(x);
                setTranslationY(y);
                setRotation(rot);
                float a = hitAt < 0L ? 0f : .2f * Math.max(0f, 1f - (now - hitAt) / 220f);
                setScaleX(hitSide ? 1f - a : 1f + a * .7f);
                setScaleY(hitSide ? 1f + a * .7f : 1f - a);
                postOnAnimation(this);
                return;
            }
            // home: an arc back to his seat, righting himself on the way
            float k = Math.min(1f, (now - homeAt) / 560f);
            float e = Motion.EASE_IN_OUT.getInterpolation(k);
            float arc = (float) Math.sin(Math.PI * e);
            float lift = -Math.min(getHeight(), Math.abs(hx) * .2f + getHeight() * .3f);
            setTranslationX(hx * (1f - e));
            setTranslationY(hy * (1f - e) + lift * arc);
            setRotation(hr * (1f - e));
            setScaleX(1f);
            setScaleY(1f);
            if (k < 1f) { postOnAnimation(this); return; }
            setTranslationX(0f);
            setTranslationY(0f);
            setRotation(0f);
            toss = null;
            if (film != null) film.pop();
            if (settled != null) settled.run();
        }

        /** A bounce off an edge: a squash against it and a few sparks on that side. */
        private void hit(long now, boolean side, int dir, float dp, float impact) {
            if (now - hitAt < 60L || impact < 220f * dp) return;           // resting against an edge is not a bounce
            hitAt = now;
            hitSide = side;
            spin = -spin * .8f + (side ? 0f : vx / dp * .15f);
            if (film == null) return;
            float px = getWidth() / 2f + (side ? dir * getWidth() * .45f : 0f);
            float py = getHeight() / 2f + (side ? 0f : dir * getHeight() * .45f);
            film.spark(px, py, 5, dp);
        }
    }

    /** Back to where his layout put him. */
    public void flyHome(@Nullable Runnable then) {
        flyTo(getLeft() + getWidth() / 2f, getTop() + getHeight() / 2f, then);
    }

    // ── expressions ─────────────────────────────────────────────────────────

    /**
     * Smile, then settle on his own.
     *
     * <p>Re-arming rather than restarting: a second call while he is already smiling just
     * pushes the settle back, so a burst of scroll events reads as one sustained reaction
     * instead of a stutter of half-finished cross-fades.
     */
    public void react() {
        removeCallbacks(settle);
        postDelayed(settle, SMILE_HOLD_MS);
        if (smiling) return;
        smiling = true;
        if (Motion.reduced(getContext())) {
            neutral.setAlpha(0f);
            smile.setAlpha(1f);
            return;
        }
        neutral.animate().alpha(0f).setDuration(FACE_SWAP_MS)
                .setInterpolator(Motion.EASE_OUT).start();
        smile.animate().alpha(1f).setDuration(FACE_SWAP_MS)
                .setInterpolator(Motion.EASE_OUT).start();
        animateDip(getHeight() * DIP_FRACTION);
    }

    private void stopSmiling() {
        if (!smiling) return;
        smiling = false;
        if (Motion.reduced(getContext())) {
            neutral.setAlpha(1f);
            smile.setAlpha(0f);
            return;
        }
        neutral.animate().alpha(1f).setDuration(FACE_SWAP_MS * 2)
                .setInterpolator(Motion.EASE_OUT).start();
        smile.animate().alpha(0f).setDuration(FACE_SWAP_MS * 2)
                .setInterpolator(Motion.EASE_OUT).start();
        animateDip(0f);
    }

    @Nullable private ValueAnimator dipAnim;

    private void animateDip(float target) {
        if (dipAnim != null) dipAnim.cancel();
        // The dip and the hover both want to own translationY, so neither sets it directly;
        // each writes its own offset and applyOffsets() sums them. Letting two animators
        // drive one property is how a float like this ends up fighting itself and snapping.
        dipAnim = ValueAnimator.ofFloat(dipOffset, target);
        dipAnim.setDuration(target > 0f ? 260L : 420L);
        dipAnim.setInterpolator(target > 0f ? Motion.EASE_OUT : Motion.EASE_IN_OUT);
        dipAnim.addUpdateListener(a -> {
            dipOffset = (float) a.getAnimatedValue();
            applyOffsets();
        });
        dipAnim.start();
    }

    private void applyOffsets() {
        neutral.setTranslationY(hoverOffset + dipOffset);
        smile.setTranslationY(hoverOffset + dipOffset);
    }

    // ── idle ────────────────────────────────────────────────────────────────

    private void startHover() {
        if (hover != null || Motion.reduced(getContext())) return;
        hover = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        hover.setDuration(HOVER_PERIOD_MS);
        hover.setRepeatCount(ValueAnimator.INFINITE);
        hover.setInterpolator(null);   // a sine is already eased; an interpolator on top
                                       // of it would make the float lurch at the ends
        hover.addUpdateListener(a -> {
            float amp = getHeight() * HOVER_FRACTION;
            hoverOffset = (float) Math.sin((float) a.getAnimatedValue()) * amp;
            applyOffsets();
        });
        hover.start();
    }

    private void stopHover() {
        if (hover == null) return;
        hover.cancel();
        hover = null;
        hoverOffset = 0f;
        applyOffsets();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startHover();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(settle);
        if (dipAnim != null) dipAnim.cancel();
        if (moodAnim != null) { moodAnim.cancel(); moodAnim = null; }
        stopIdeaBreath();
        stopHover();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        // Covers the cases onDetachedFromWindow does not: the fragment behind another
        // fragment, the screen off, the app in the background. All of those leave the view
        // attached, and without this he would keep animating into a surface nobody sees.
        if (visible) { startHover(); startIdeaBreath(); } else { stopHover(); stopIdeaBreath(); }
        if (visible && director != null) director.shown();
    }

    private long lastReactMs = 0L;

    /**
     * Wire him to a scroll container so he reacts while it moves.
     *
     * <p>Uses {@link View#setOnScrollChangeListener}, which is scoped to the ONE view it is
     * set on. The obvious alternative — {@code getViewTreeObserver().addOnScrollChangedListener}
     * — is scoped to the whole WINDOW, so he would twitch at every scroll anywhere on the
     * screen, and it is registered against an observer that outlives this view, which leaks
     * it until the window goes away.
     *
     * <p>Throttled because a scroll fires this on every frame. Without the gate a flick
     * would post and cancel the settle runnable sixty times a second to no visible effect.
     */
    public void watch(View scrollable) {
        if (scrollable == null) return;
        scrollable.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastReactMs < 300L) return;
            lastReactMs = now;
            react();
        });
    }
}
