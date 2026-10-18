package com.fadcam.ui.faditor.ai.joybot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.ai.AiKey;
import com.fadcam.ui.faditor.ai.AiService;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.lobby.JoybotView;
import com.fadcam.ui.motion.Motion;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "He's got a good idea": works out, off the main thread, whether Joybot has a suggestion for a
 * project that the person hasn't seen, and makes him breathe if so.
 *
 * <p>Two kinds of Joybot to light: the lobby's {@link JoybotView} (which owns its own glow), and
 * plain buttons such as the editor's top-bar Joybot, which get the same green breath layered over
 * their existing background.</p>
 */
public final class JoybotPulse {

    private JoybotPulse() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The guided flows this build ships; ideas for anything else are never shown. */
    @NonNull
    public static Set<String> builtFlows() {
        Set<String> s = new HashSet<>();
        s.add(IdeaEngine.FLOW_SLIDE);
        s.add(IdeaEngine.FLOW_PICTURE);
        return s;
    }

    public interface Result {
        /** On the main thread. {@code offer} is null when the project could not be read. */
        void onOffer(@Nullable IdeaEngine.Offer offer, boolean fresh);
    }

    /** Compute what Joybot would suggest for this project. Blocking: call off the main thread. */
    @Nullable
    public static IdeaEngine.Offer offerBlocking(@NonNull Context context, @NonNull String projectId,
                                                 @NonNull IdeaEngine.Room room) {
        try {
            Context app = context.getApplicationContext();
            FaditorProject p = new ProjectStorage(app).load(projectId);
            if (p == null) return null;
            boolean online = AiService.ready(app, AiKey.get(app));
            ProjectFacts f = ProjectFactsReader.read(app, p, online);
            return IdeaEngine.offer(f, room, builtFlows(), new IdeaMemory(app).quiet(projectId));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Work out the offer off the main thread, then report it (and whether it is new to them). */
    public static void check(@NonNull Context context, @Nullable String projectId,
                             @NonNull IdeaEngine.Room room, @NonNull Result result) {
        if (projectId == null || projectId.isEmpty()) { result.onOffer(null, false); return; }
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            IdeaEngine.Offer o = offerBlocking(app, projectId, room);
            boolean fresh = o != null && new IdeaMemory(app).hasFreshIdea(projectId, o.best());
            MAIN.post(() -> result.onOffer(o, fresh));
        });
    }

    /** Light the lobby Joybot when there is a fresh idea for this project. */
    public static void light(@NonNull JoybotView bot, @Nullable String projectId, @NonNull IdeaEngine.Room room) {
        check(bot.getContext(), projectId, room, (o, fresh) -> {
            if (bot.isAttachedToWindow()) bot.setHasIdea(fresh);
        });
        dressWhenReady(bot);
    }

    /** Give this Joybot the owner's animated sheet, if there is one (read once per process). */
    public static void dressWhenReady(@NonNull JoybotView bot) {
        final Context app = bot.getContext().getApplicationContext();
        IO.execute(() -> {
            java.util.Map<JoybotView.Mood, com.fadcam.ui.lobby.JoybotFilm> films = JoybotSheet.load(app);
            if (films.isEmpty()) return;
            MAIN.post(() -> JoybotSheet.dress(bot, films));
        });
    }

    /** Light a plain button (the editor's Joybot) the same way. */
    public static void lightButton(@NonNull View button, @Nullable String projectId, @NonNull IdeaEngine.Room room) {
        check(button.getContext(), projectId, room, (o, fresh) -> {
            if (button.isAttachedToWindow()) breathe(button, fresh);
        });
    }

    // ── the breath on a plain view ─────────────────────────────────────────

    /** Weak: a closed editor's button must not be kept alive by its glow. */
    private static final java.util.Map<View, Breath> BREATHS = new java.util.WeakHashMap<>();

    /** True while this button is glowing with an idea. */
    public static boolean isBreathing(@NonNull View v) {
        Breath b = BREATHS.get(v);
        return b != null && b.idea;
    }

    /** Same look and timing as {@link JoybotView#setHasIdea}: studio green, ~4 s, low, no badge. */
    public static void breathe(@NonNull View v, boolean on) {
        Breath existing = BREATHS.get(v);
        if (existing != null) {
            existing.idea = on;
            existing.apply();
            return;
        }
        if (!on) return;
        Breath b = new Breath(v);
        BREATHS.put(v, b);
        b.idea = true;
        b.apply();
    }

    /**
     * The same breath in amber: something needs the person's attention (files outside the Joy
     * Creator folder). Owner, 2026-10-16: no separate yellow dot cluttering the screen — Joybot
     * glows, and tapping him says what and offers to fix it. A warning wins over a green idea
     * while it lasts; the idea's green comes back when the warning clears.
     */
    public static void warn(@NonNull View v, boolean on) {
        Breath b = BREATHS.get(v);
        if (b == null) {
            if (!on) return;
            b = new Breath(v);
            BREATHS.put(v, b);
        }
        b.warning = on;
        b.apply();
    }

    /** True while this button glows amber with a warning. */
    public static boolean isWarning(@NonNull View v) {
        Breath b = BREATHS.get(v);
        return b != null && b.warning;
    }

    /** The amber end of the warning gradient (CAREFUL runs into a deeper amber, as GO into GO_END). */
    private static final int CAREFUL_END = 0xFFF59E0B;

    private static final class Breath implements View.OnAttachStateChangeListener {
        /** Application context only: the map is weak on the view, so nothing here may hold it. */
        private final Context app;
        private final GradientDrawable glow;
        @Nullable private ValueAnimator anim;
        private boolean on;
        /** Why it glows: an idea (green) and/or a warning (amber, wins). */
        boolean idea, warning;

        Breath(View v) {
            app = v.getContext().getApplicationContext();
            glow = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Studio.GO, Studio.GO_END});
            glow.setShape(GradientDrawable.OVAL);
            glow.setAlpha(0);
            Drawable base = v.getBackground();
            v.setBackground(base == null ? glow : new LayerDrawable(new Drawable[]{base, glow}));
            v.addOnAttachStateChangeListener(this);
        }

        int stillAlpha() { return warning ? 225 : 110; }

        void apply() {
            glow.setColors(warning ? new int[]{Studio.CAREFUL, CAREFUL_END} : new int[]{Studio.GO, Studio.GO_END});
            set(idea || warning);
        }

        void set(boolean want) {
            on = want;
            if (!want) {
                if (anim != null) { anim.cancel(); anim = null; }
                glow.setAlpha(0);
                return;
            }
            if (anim != null) { if (Motion.reduced(app)) glow.setAlpha(stillAlpha()); return; }
            // Still (reduced motion — the Note 9 has animations off): a warning must READ as amber.
            // At the idea's 110 the amber only turned the purple orb a muddy pink (10-16).
            if (Motion.reduced(app)) { glow.setAlpha(stillAlpha()); return; }
            anim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            anim.setDuration(4000L);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(null);
            anim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                float b = 0.5f * (1f - (float) Math.cos(t));
                // An idea fades in and out to nothing; a warning breathes but never disappears.
                glow.setAlpha(Math.round(warning ? 130 + 110 * b : 140 * b));
            });
            anim.start();
        }

        @Override public void onViewAttachedToWindow(@NonNull View v) { if (on) { anim = null; set(true); } }

        @Override public void onViewDetachedFromWindow(@NonNull View v) {
            if (anim != null) { anim.cancel(); anim = null; }
        }
    }
}
