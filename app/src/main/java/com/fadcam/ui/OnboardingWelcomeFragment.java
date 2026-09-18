package com.fadcam.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.ui.lobby.JoybotView;
import com.fadcam.ui.motion.Motion;
import com.fadcam.ui.faditor.Studio;

/**
 * THE FIRST SCREEN.
 *
 * <p>Built to the mockup in {@code design/marquee.html} §03, not from memory of it. The
 * previous attempt was written after a compaction had taken the mockup out of context and
 * came out with a different architecture — a single cycling WORD over a saturated gradient,
 * where the design calls for a cycling PHRASE over a dark field with one soft glow in it.
 * JoyRaptor: "it doesn't look anything like the mockup you made!"
 *
 * <h3>The phrase and the light change together</h3>
 * One index, one timer. A slideshow running on its own clock beside a line of text running
 * on another produces pairings nobody chose, and the reader spends the screen working out
 * whether the mismatch means something.
 *
 * <h3>The timings are the mockup's, and they are deliberately slow</h3>
 * 3000ms per beat and a 560ms cross-fade. Long fades are usually a mistake — they make a
 * person wait — but this is the one place on the whole product where nobody is waiting on
 * anything, so the fade can take the time that makes it look like light changing rather
 * than like an image being swapped. The text swaps at 260ms, inside its own fade, so it is
 * never legible while it is in motion.
 */
public final class OnboardingWelcomeFragment extends Fragment {

    /** One beat: the line, and the light behind it. */
    private static final class Beat {
        final int line, glow, baseTop, baseBottom;
        final float atX, atY;
        Beat(int line, int glow, int baseTop, int baseBottom, float atX, float atY) {
            this.line = line; this.glow = glow;
            this.baseTop = baseTop; this.baseBottom = baseBottom;
            this.atX = atX; this.atY = atY;
        }
    }

    // The seven lines are the mockup's, verbatim, in its order. Each is a sentence a
    // stranger could repeat to someone else — which is the test a feature list fails and
    // this one has to pass, because it is the only description of the product most people
    // will ever read.
    //
    // The glow alphas (0x4D–0x57 ≈ .30–.34) and the off-centre placements come from the
    // mockup's CSS. They alternate left and right of centre so consecutive beats move the
    // light across the screen instead of pulsing it in place.
    /**
     * The seven beats.
     *
     * <p>The GLOW of each is that beat's room, at an alpha tuned per beat — named rather than
     * re-typed, so a room that changes hue changes here too.
     *
     * <p>The two base tones are NOT derivable and are deliberately left as numbers. I fitted
     * a mix of "neutral near-black plus a trace of the room hue" against all fourteen of
     * them: the best fit ranges from f=0.001 (Capture's beat is essentially neutral) to
     * f=0.121, over neutrals from #05 to #17. There is no single rule, because each pair was
     * tuned by eye against the text that sits on it. They are authored art in a table, not
     * scattered literals, and a later tidy-up that replaces them with a formula will flatten
     * seven distinct moods into one.
     */
    private static final Beat[] BEATS = {
            new Beat(R.string.intro_line_capture,    Studio.alpha(Studio.ROOM_SPRITE, 0x57), 0xFF17171F, 0xFF101016, 0.34f, 0.30f),
            new Beat(R.string.intro_line_record,     Studio.alpha(Studio.ROOM_CAPTURE, 0x52), 0xFF1C1418, 0xFF0F0B0D, 0.62f, 0.34f),
            new Beat(R.string.intro_line_editor,     Studio.alpha(Studio.GO, 0x52), 0xFF12201C, 0xFF0C1512, 0.40f, 0.28f),
            new Beat(R.string.intro_line_animation,  Studio.alpha(Studio.ROOM_AVATAR, 0x4D), 0xFF1A1024, 0xFF0D0912, 0.56f, 0.32f),
            new Beat(R.string.intro_line_transcribe, Studio.alpha(Studio.ROOM_VIZ, 0x4D), 0xFF241A10, 0xFF120D08, 0.40f, 0.30f),
            new Beat(R.string.intro_line_secondcam,  Studio.alpha(Studio.VIDEO, 0x52), 0xFF101822, 0xFF0A0D12, 0.66f, 0.34f),
            new Beat(R.string.intro_line_ai,         Studio.alpha(Studio.ORB_DEEP, 0x4D), 0xFF14142A, 0xFF0A0A14, 0.44f, 0.26f),
    };

    private static final long BEAT_MS = 3000L;
    private static final long CROSS_MS = 560L;
    private static final long TEXT_SWAP_MS = 260L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int index = 0;
    /** Which of the two slide layers is currently the visible one. */
    private boolean showingA = true;

    private View slideA, slideB, fade, hairline;
    private TextView line;
    @Nullable private JoybotView bot;
    @Nullable private Runnable tick;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        View v = inf.inflate(R.layout.onboarding_welcome, parent, false);

        slideA = v.findViewById(R.id.welcome_slide_a);
        slideB = v.findViewById(R.id.welcome_slide_b);
        fade = v.findViewById(R.id.welcome_fade);
        line = v.findViewById(R.id.welcome_line);
        hairline = v.findViewById(R.id.welcome_hairline);
        bot = v.findViewById(R.id.welcome_bot);

        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ Studio.alpha(Studio.GROUND, 0x00), Studio.GROUND }));

        hairline.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ Studio.GO, Studio.GO_END }));
        hairline.setPivotX(0f);
        hairline.setScaleX(1f / 3f);           // one of three screens

        if (bot != null) bot.setTint(Studio.INK);
        paintPromise(v.findViewById(R.id.welcome_promise));

        slideA.setBackground(slideFor(BEATS[0]));
        line.setText(getString(BEATS[0].line));

        View support = v.findViewById(R.id.welcome_support);
        View start = v.findViewById(R.id.welcome_start);
        Motion.press(start);
        Motion.press(support);
        // "Start creating" moves to the next screen; AppIntro owns the pager, so this asks
        // the host rather than trying to drive it from inside a page.
        start.setOnClickListener(b -> {
            if (getActivity() instanceof OnboardingActivity) {
                ((OnboardingActivity) getActivity()).advanceSlide();
            }
        });
        support.setOnClickListener(b -> openSupport());
        return v;
    }

    private GlowSlide slideFor(Beat b) {
        return new GlowSlide(b.glow, b.baseTop, b.baseBottom, b.atX, b.atY);
    }

    /**
     * The promise, with its last clause in the Studio accent.
     *
     * <p>The Marquee §03: {@code .ipromise s{color:var(--studio-a)}}. It is the only
     * coloured word on the whole screen, and it sits on the promise that costs the most to
     * keep — no export paywall. White, it was just one more line of the paragraph.
     */
    private void paintPromise(android.widget.TextView t) {
        if (t == null) return;
        // The joining space lives HERE, not in the string. Android trims trailing
        // whitespace out of a string resource unless it is quoted, so "No watermark. "
        // arrives as "No watermark." and the two clauses run together.
        String head = getString(R.string.intro_promise) + " ";
        String ever = getString(R.string.intro_promise_ever);
        android.text.SpannableString sp = new android.text.SpannableString(head + ever);
        sp.setSpan(new android.text.style.ForegroundColorSpan(Studio.GO),
                head.length(), head.length() + ever.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sp);
    }

    private void openSupport() {
        // An UNSET support URL opens nothing, on purpose. It used to hold the Ko-fi of the
        // developer of the app this one was forked from, sitting under the sentence "It's
        // how I support my family" — so the button quietly contradicted the paragraph above
        // it and sent the money elsewhere. Better to do nothing than to do that.
        String url = getString(R.string.support_url);
        if (url == null || url.trim().isEmpty()) return;
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception ignored) {
            // No browser. Silently doing nothing is right here: this is a donation link on
            // a welcome screen, and an error dialog about it would be the first thing the
            // product ever said to someone.
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (Motion.reduced(requireContext())) return;   // one beat, held
        tick = () -> {
            advance();
            ui.postDelayed(tick, BEAT_MS);
        };
        ui.postDelayed(tick, BEAT_MS);
        if (bot != null) ui.postDelayed(bot::react, 700L);
    }

    @Override
    public void onPause() {
        // A repeating timer left running behind another screen keeps waking the main
        // thread to animate something nobody is looking at.
        if (tick != null) ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (tick != null) ui.removeCallbacks(tick);
        super.onDestroyView();
    }

    private void advance() {
        if (slideA == null || !isAdded()) return;
        index = (index + 1) % BEATS.length;
        Beat b = BEATS[index];

        // Paint the HIDDEN layer, then fade it up and the visible one down. Painting the
        // visible one would show the new slide instantly at full strength, and the fade
        // would then be running on an image that had already arrived.
        View incoming = showingA ? slideB : slideA;
        View outgoing = showingA ? slideA : slideB;
        incoming.setBackground(slideFor(b));
        incoming.animate().alpha(1f).setDuration(CROSS_MS)
                .setInterpolator(Motion.EASE_OUT).start();
        outgoing.animate().alpha(0f).setDuration(CROSS_MS)
                .setInterpolator(Motion.EASE_OUT).start();
        showingA = !showingA;

        Motion.swap(line, TEXT_SWAP_MS, () -> line.setText(getString(b.line)));
    }
}
