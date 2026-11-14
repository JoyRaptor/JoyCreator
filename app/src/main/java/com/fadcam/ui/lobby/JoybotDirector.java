package com.fadcam.ui.lobby;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.motion.Motion;

import java.util.List;
import java.util.Random;

/**
 * Chooses what Joybot does for a mood, so he is not the same every time.
 *
 * <p>Owner, 2026-11-13: "a richly animated character that's doing lots of stuff in just about any
 * situation. It'll feel like it's alive." A mood has several animations; he picks one he did not
 * just do, plays its way in, loops it, and plays its way out before the next. A job described in
 * words ({@link #hint}) picks the animation that fits it (deleting, trimming, colour...). Left
 * idle he starts to read, and later falls asleep.</p>
 *
 * <p>One director per {@link JoybotView}. Everything here runs on the main thread.</p>
 */
public final class JoybotDirector implements JoybotView.Director {

    private final JoybotView bot;
    private final JoybotActs acts;
    private final Random dice = new Random();

    @Nullable private JoybotActs.Act now;
    @Nullable private JoybotView.Mood nowMood;
    @Nullable private String lastName;
    /** What the next WORK should be, from the job's own words; used once. */
    @Nullable private JoybotActs.Act hinted;
    /** Which idle drift comes next (0 = reading, 1 = sleeping, …); -1 while not idle. */
    private int idleStep = -1;
    @Nullable private JoybotActs.Act drift;

    private final Runnable idleTick = this::driftOn;

    public JoybotDirector(@NonNull JoybotView bot, @NonNull JoybotActs acts) {
        this.bot = bot;
        this.acts = acts;
    }

    /** Tell this Joybot what the coming job is, in the words shown to the person. No director: no-op. */
    public static void hint(@NonNull JoybotView bot, @Nullable String task) {
        JoybotView.Director d = bot.getDirector();
        if (d instanceof JoybotDirector) ((JoybotDirector) d).hinted = ((JoybotDirector) d).acts.forTask(task);
    }

    /** The flourish he does when he first arrives, and now and then before cheering. */
    private static final String FLOURISH = "spin";
    private boolean greeted = false;
    /** While a flourish plays: what he settles into after it, and for which mood. */
    @Nullable private JoybotActs.Act chainTo;
    @Nullable private JoybotView.Mood chainMood;

    @Override
    public boolean keeps(@NonNull JoybotView.Mood m) {
        if (chainTo != null && chainMood == m) return true;
        if (now == null || nowMood != m) return false;
        if (m == JoybotView.Mood.IDLE) return drift == null || drift == now;
        return hinted == null || hinted == now;
    }

    @Nullable
    @Override
    public JoybotFilm leave() {
        if (now == null || Motion.reduced(bot.getContext())) return null;
        Bitmap p = acts.opened(now);
        return p == null ? null : acts.outFilm(now, p);
    }

    @Nullable
    @Override
    public JoybotFilm enter(@NonNull JoybotView.Mood m) {
        bot.removeCallbacks(idleTick);
        // An animation whose picture has just finished opening comes back through here: take it.
        JoybotActs.Act a = reopened;
        reopened = null;
        final boolean fresh = a == null;
        if (fresh) { chainTo = null; chainMood = null; }
        if (m == JoybotView.Mood.IDLE) {
            if (a == null) {
                if (drift == null) idleStep = 0;
                a = drift != null ? drift : pick("idle");
            }
            drift = null;
            scheduleDrift();
        } else {
            idleStep = -1;
            drift = null;
            if (a == null) a = m == JoybotView.Mood.WORK && hinted != null ? hinted : pick(m.sheetName);
            hinted = null;
        }
        if (a == null) { now = null; nowMood = null; return null; }
        if (fresh && !Motion.reduced(bot.getContext())) {
            // A flourish first: when he arrives, and one time in three when he is excited.
            boolean arrive = m == JoybotView.Mood.IDLE && !greeted && idleStep == 0;
            boolean cheer = m == JoybotView.Mood.EXCITED && dice.nextInt(3) == 0;
            JoybotActs.Act f = arrive || cheer ? acts.named(FLOURISH) : null;
            if (m == JoybotView.Mood.IDLE) greeted = true;
            if (f != null && f != a) { chainTo = a; chainMood = m; a = f; }
        }
        Bitmap p = acts.opened(a);
        if (p == null) {
            // Not open yet: the stills stand in for the moment it takes, then he comes back to it.
            final JoybotActs.Act want = a;
            now = null;
            nowMood = null;
            acts.open(a, bot, got -> {
                if (got == null || !bot.isAttachedToWindow() || bot.getMood() != m || bot.getDirector() != this) return;
                reopened = want;
                bot.setMood(m);
            });
            return null;
        }
        if (chainTo != null && chainTo != a) {
            final JoybotActs.Act next = chainTo;
            now = null;
            nowMood = null;
            return acts.onceFilm(a, p).thenRun(() -> {
                if (chainTo != next) return;
                chainTo = null;
                chainMood = null;
                if (!bot.isAttachedToWindow() || bot.getMood() != m || bot.getDirector() != this) return;
                reopened = next;
                bot.setMood(m);
            });
        }
        chainTo = null;
        chainMood = null;
        now = a;
        nowMood = m;
        lastName = a.name;
        return acts.film(a, p, Motion.reduced(bot.getContext()));
    }

    @Nullable private JoybotActs.Act reopened;

    @Nullable
    private JoybotActs.Act pick(@NonNull String mood) {
        List<JoybotActs.Act> pool = acts.generalFor(mood);
        if (pool.isEmpty()) return null;
        if (pool.size() == 1) return pool.get(0);
        JoybotActs.Act a;
        do { a = pool.get(dice.nextInt(pool.size())); } while (a.name.equals(lastName));
        return a;
    }

    private void scheduleDrift() {
        if (idleStep < 0 || idleStep >= acts.idleSteps()) return;
        long from = idleStep == 0 ? 0L : acts.idleSeconds(idleStep - 1);
        bot.postDelayed(idleTick, Math.max(1L, acts.idleSeconds(idleStep) - from) * 1000L);
    }

    private void driftOn() {
        if (!bot.isAttachedToWindow() || bot.getMood() != JoybotView.Mood.IDLE || bot.getDirector() != this) return;
        if (idleStep < 0 || idleStep >= acts.idleSteps()) return;
        drift = acts.named(acts.idleAct(idleStep));
        idleStep++;
        if (drift != null) bot.setMood(JoybotView.Mood.IDLE);
    }
}
