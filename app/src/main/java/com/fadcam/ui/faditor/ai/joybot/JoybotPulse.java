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
        return b != null && b.on;
    }

    /** Same look and timing as {@link JoybotView#setHasIdea}: studio green, ~4 s, low, no badge. */
    public static void breathe(@NonNull View v, boolean on) {
        Breath existing = BREATHS.get(v);
        if (existing != null) {
            existing.set(on);
            return;
        }
        if (!on) return;
        Breath b = new Breath(v);
        BREATHS.put(v, b);
        b.set(true);
    }

    private static final class Breath implements View.OnAttachStateChangeListener {
        /** Application context only: the map is weak on the view, so nothing here may hold it. */
        private final Context app;
        private final GradientDrawable glow;
        @Nullable private ValueAnimator anim;
        private boolean on;

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

        void set(boolean want) {
            on = want;
            if (!want) {
                if (anim != null) { anim.cancel(); anim = null; }
                glow.setAlpha(0);
                return;
            }
            if (anim != null) return;
            if (Motion.reduced(app)) { glow.setAlpha(110); return; }
            anim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            anim.setDuration(4000L);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(null);
            anim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                glow.setAlpha(Math.round(140 * 0.5f * (1f - (float) Math.cos(t))));
            });
            anim.start();
        }

        @Override public void onViewAttachedToWindow(@NonNull View v) { if (on) { anim = null; set(true); } }

        @Override public void onViewDetachedFromWindow(@NonNull View v) {
            if (anim != null) { anim.cancel(); anim = null; }
        }
    }
}
