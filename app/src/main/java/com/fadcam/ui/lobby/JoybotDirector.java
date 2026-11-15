package com.fadcam.ui.lobby;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
 * <p>He ALWAYS animates, whatever the phone's "remove animations" setting says (owner, 11-14: "he's
 * a character that guides you. He needs to be animated"). His frames run on the clock, not on
 * system animators, so that setting cannot stop them.</p>
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

    /** The flourish he does when he first arrives with no flight in, if no greeting is packed. */
    private static final String FLOURISH = "spin";
    private boolean greeted = false;
    /** While a flourish plays: what he settles into after it, and for which mood. */
    @Nullable private JoybotActs.Act chainTo;
    @Nullable private JoybotView.Mood chainMood;
    /** This play of {@link #now} faces the other way (owner, 11-15: mirror them "so he's facing different directions"). */
    private boolean nowMirror;
    /** The flourish about to play is mirrored (kept across a picture that has to open first). */
    private boolean flourishMirror;
    /** When he last saluted before a job, so back-to-back jobs do not get a salute each. */
    private long lastOnIt = 0L;
    @Nullable private String lastMomentAct;

    /**
     * A moment (owner, 11-15): greet, on-it, thanks, done, you-did-it, cheer, present, tip, point-left, point-right,
     * point-down. He plays a fitting animation once, then carries on with what he was doing. False when nothing fits.
     */
    public static boolean moment(@NonNull JoybotView bot, @NonNull String what) {
        JoybotView.Director d = bot.getDirector();
        if (!(d instanceof JoybotDirector) || !((JoybotDirector) d).hasMoment(what)) return false;
        bot.playMoment(what);
        return true;
    }

    private boolean hasMoment(@NonNull String what) {
        return acts.forMoment(what, null, dice) != null || acts.forMoment(opposite(what), null, dice) != null;
    }

    /** "point-left" can also be a point to the right played mirrored, and the other way round. */
    @NonNull
    private static String opposite(@NonNull String what) {
        if (what.endsWith("-left")) return what.substring(0, what.length() - 5) + "-right";
        if (what.endsWith("-right")) return what.substring(0, what.length() - 6) + "-left";
        return what;
    }

    /** The animation for a moment and whether to mirror it: {act, Boolean}. */
    @Nullable
    private Object[] resolve(@NonNull String what) {
        JoybotActs.Act straight = acts.forMoment(what, lastMomentAct, dice);
        String other = opposite(what);
        JoybotActs.Act turned = other.equals(what) ? null : acts.forMoment(other, lastMomentAct, dice);
        if (straight == null && turned == null) return null;
        boolean useTurned = straight == null || (turned != null && dice.nextInt(3) == 0);
        JoybotActs.Act a = useTurned ? turned : straight;
        lastMomentAct = a.name;
        return new Object[]{a, useTurned};
    }

    @Override
    public boolean flourishing() { return chainTo != null; }

    // ── touch and tricks (owner, 11-15 midday) ──────────────────────────────
    // "Asleep, tap or double-tap: he wakes with a scramble or an 'oopsies'. Awake, tap: one of many different actions,
    // and he moves around while doing it. Asked 'do a backflip': a backflip, then confetti or a bow. A directional poke
    // nudges him. A flick flings him round the screen: he bounces off the edges, comes back and settles."

    /** The flourish about to play was asked for by a tap: he moves about while he does it. */
    private boolean flourishLively;
    private long wokeAt = -10_000L, lastTap = -10_000L;
    private static final String[] TAP_WORDS = {"boop!", "hehe!", "hi!", "wheee!", "ta-da!"};

    @Nullable
    private static JoybotDirector of(@NonNull JoybotView bot) {
        JoybotView.Director d = bot.getDirector();
        return d instanceof JoybotDirector ? (JoybotDirector) d : null;
    }

    /** True while he is asleep (the last of his idle drifts). */
    public static boolean asleep(@NonNull JoybotView bot) {
        JoybotDirector d = of(bot);
        return d != null && d.now != null && "sleeping".equals(d.now.name);
    }

    /** A tap on him: asleep, he wakes up with a start; awake, one of many actions (now and then with a backflip). */
    public static void tapped(@NonNull JoybotView bot) {
        JoybotDirector d = of(bot);
        if (d == null) { bot.react(); return; }
        long t = android.os.SystemClock.uptimeMillis();
        // the second tap of the double-tap that woke him, or a tap on a tap
        if (t - d.wokeAt < 900L || t - d.lastTap < 380L) return;
        d.lastTap = t;
        if (asleep(bot)) {
            d.wokeAt = t;
            bot.say(d.dice.nextInt(3) == 0 ? "huh?!" : "oopsies!");
            moment(bot, "wake");
            return;
        }
        if (d.dice.nextInt(5) == 0) bot.backflip();
        if (d.dice.nextInt(3) == 0) bot.say(TAP_WORDS[d.dice.nextInt(TAP_WORDS.length)]);
        moment(bot, "tap");
    }

    /** A poke ({@code dx, dy} px, the way the finger went): he is nudged that way and springs back. */
    public static void poked(@NonNull JoybotView bot, float dx, float dy) {
        bot.nudge(dx, dy);
        JoybotDirector d = of(bot);
        if (d == null) return;
        if (asleep(bot)) { tapped(bot); return; }
        if (d.dice.nextInt(4) == 0) bot.say(d.dice.nextBoolean() ? "hey!" : "oof!");
    }

    /** A flick ({@code vx, vy} px/s): flung round the chat in a scramble, then dizzy or a bow once he is back. */
    public static void flung(@NonNull JoybotView bot, float vx, float vy) {
        JoybotDirector d = of(bot);
        if (d != null) d.wokeAt = android.os.SystemClock.uptimeMillis();
        bot.say("wheee!");
        moment(bot, "flung");
        bot.fling(vx, vy, () -> {
            if (d == null || !bot.isAttachedToWindow()) return;
            int r = d.dice.nextInt(4);
            if (r < 2) moment(bot, "dizzy");
            else if (r == 2) moment(bot, "bow");
        });
    }

    /** "Do a backflip" (asked in the chat): a backflip, then confetti or a bow. */
    public static void backflip(@NonNull JoybotView bot) {
        JoybotDirector d = of(bot);
        bot.backflip();
        bot.say("hup!");
        boolean confetti = d == null || d.dice.nextBoolean();
        bot.postDelayed(() -> { if (bot.isAttachedToWindow()) moment(bot, confetti ? "confetti" : "bow"); }, 560L);
    }

    // ── tricks on command (owner, 11-15) ────────────────────────────────────
    // "Go to sleep; hop on one foot (hopping around, moving up and down with springy squash and stretch); backflip; beg
    // (built from the frames we have); roll over; shake, with little electric zaps on him for a joke." Each is a named
    // action, so the Joybot brain (BOT-7) can trigger one from words as well as the chat.

    /** Every trick he knows, by the name {@link #trick} takes. */
    public static final String[] TRICKS = {"sleep", "hop", "backflip", "beg", "roll-over", "shake"};

    /** Do a trick by name (one of {@link #TRICKS}). False for a name he doesn't know. */
    public static boolean trick(@NonNull JoybotView bot, @NonNull String name) {
        JoybotDirector d = of(bot);
        if (!"sleep".equals(name) && asleep(bot)) moment(bot, "wake");          // woken up to do it
        switch (name) {
            case "sleep":
                if (d != null) d.sleepNow();
                bot.say("zzz…");
                return true;
            case "hop":
                bot.trick(JoybotView.TRICK_HOP);
                bot.say("hop!");
                return true;
            case "backflip":
                backflip(bot);
                return true;
            case "beg":
                if (d != null) d.beg();
                bot.trick(JoybotView.TRICK_BEG);
                bot.say("pleeease?");
                return true;
            case "roll-over":
                moment(bot, "roll");
                bot.trick(JoybotView.TRICK_ROLL);
                return true;
            case "shake":
                moment(bot, "shake");
                bot.trick(JoybotView.TRICK_SHAKE);
                bot.say("bzzzt!");
                return true;
            default:
                return false;
        }
    }

    /**
     * The trick a short message asks for ("go to sleep", "can you hop on one foot?", "roll over please"), or null. Only
     * the whole message as a command counts, so "trim the sleep scene" is not a trick.
     */
    @Nullable
    public static String trickFor(@Nullable String text) {
        if (text == null) return null;
        String t = text.toLowerCase(java.util.Locale.ROOT).replace('’', '\'').replaceAll("[^a-z' ]", " ")
                .replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("^(hey )?(joybot )?(please )?(can you |could you |will you |would you |now )?(please )?", "");
        t = t.replaceFirst("( for me)?( please)?( joybot)?( now)?$", "").trim();
        if (t.matches("(go to sleep|go to bed|take a nap|have a nap|nap|nap time|sleep|bedtime|bed time|go night night)")) return "sleep";
        if (t.matches("(do a |do the )?(hop|hop on one foot|hop around|hop hop|bunny hop|jump|jump around)")) return "hop";
        if (t.matches("(do a |do another |do the )?(backflip|back flip|flip|somersault)")) return "backflip";
        if (t.matches("(beg|do the beg|say please|puppy eyes|do puppy eyes)")) return "beg";
        if (t.matches("(roll over|roll|do a roll|barrel roll|do a barrel roll)")) return "roll-over";
        if (t.matches("(shake|shake it|shake it off|shake yourself|do a shake|shake shake)")) return "shake";
        return null;
    }

    /** "Do a backflip", "can you do a flip?": asked for one, in a short message. */
    public static boolean asksForBackflip(@Nullable String text) {
        return "backflip".equals(trickFor(text));
    }

    /** Asleep now, until tapped or asked to do something (his last idle drift, played on command). */
    private void sleepNow() {
        JoybotActs.Act s = acts.named("sleeping");
        if (s == null) return;
        drift = s;
        idleStep = acts.idleSteps();                     // nothing comes after sleep
        bot.setMood(JoybotView.Mood.IDLE);
    }

    /** Begging: his excited clasp held (hands together under his chin), the bounces are the trick on top. */
    private void beg() {
        final JoybotActs.Act a = acts.named("excited");
        if (a == null) return;
        Bitmap p = acts.opened(a);
        if (p != null) { playOnce(acts.framesFilm(a, p, new int[]{0, 1, 0}, new int[]{1, 15, 1})); return; }
        acts.open(a, bot, got -> {
            if (got != null && bot.isAttachedToWindow() && bot.getDirector() == this) {
                playOnce(acts.framesFilm(a, got, new int[]{0, 1, 0}, new int[]{1, 15, 1}));
            }
        });
    }

    /** A film played once as a flourish, then back to what he was doing. */
    private void playOnce(@NonNull JoybotFilm film) {
        final JoybotView.Mood m = bot.getMood();
        final JoybotActs.Act next = now != null ? now : pick(m.sheetName);
        if (next == null) return;
        chainTo = next;
        chainMood = m;
        now = null;
        nowMood = null;
        bot.setFilm(film.thenRun(() -> {
            if (chainTo != next) return;
            chainTo = null;
            chainMood = null;
            if (!bot.isAttachedToWindow() || bot.getDirector() != this) return;
            if (bot.getMood() != m || bot.hasMoment()) { bot.refreshMood(); return; }
            reopened = next;
            bot.setMood(m);
        }));
    }

    /** "Thanks", "great job", "love it": the person thanked or praised him (he takes a bow). */
    public static boolean isThanks(@Nullable String text) {
        if (text == null) return false;
        String t = " " + text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z' ]", " ") + " ";
        String[] words = {" thank", " thanks", " thx", " ty ", " cheers ", " great job", " good job", " nice work", " nice job",
                " well done", " awesome", " amazing", " perfect", " love it", " love this", " you rock", " brilliant",
                " fantastic", " excellent", " good boy", " good bot", " nailed it", " bravo"};
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    @Override
    public void shown() {
        if (bot.arrived()) greeted = true;      // his flight in was the entrance (ChatArrival)
        if (greeted || bot.getMood() != JoybotView.Mood.IDLE) return;
        now = null;
        nowMood = null;
        bot.setMood(JoybotView.Mood.IDLE);   // now that he can be seen: the arrival spin
    }

    @Override
    public boolean keeps(@NonNull JoybotView.Mood m) {
        // A flourish (a bow, a salute, a point) plays to its end; the chat's next mood follows it.
        if (chainTo != null) return true;
        if (now == null || nowMood != m) return false;
        if (m == JoybotView.Mood.IDLE) return drift == null || drift == now;
        return hinted == null || hinted == now;
    }

    @Nullable
    @Override
    public JoybotFilm leave() {
        if (now == null) return null;
        Bitmap p = acts.opened(now);
        return p == null ? null : acts.outFilm(now, p, nowMirror);
    }

    @Nullable
    @Override
    public JoybotFilm enter(@NonNull JoybotView.Mood m) {
        bot.removeCallbacks(idleTick);
        // An animation whose picture has just finished opening comes back through here: take it.
        JoybotActs.Act a = reopened;
        reopened = null;
        final boolean fresh = a == null;
        if (fresh) { chainTo = null; chainMood = null; flourishMirror = false; flourishLively = false; }
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
        if (fresh) {
            // A flourish first: a moment someone asked for (a thank-you, a job done, pointing at something);
            // a greeting when he arrives; a salute before he starts a job ("understood, on it"); a cheer one time in
            // two when he is excited. Only while he can be seen: a flourish nobody saw is a flourish wasted (11-14).
            if (bot.arrived()) greeted = true;
            String asked = bot.takeMoment();
            boolean arrive = m == JoybotView.Mood.IDLE && !greeted && bot.isShown();
            boolean cheer = m == JoybotView.Mood.EXCITED && dice.nextInt(2) == 0;
            long t = android.os.SystemClock.uptimeMillis();
            boolean onIt = m == JoybotView.Mood.WORK && nowMood != JoybotView.Mood.WORK && t - lastOnIt > 20_000L;
            Object[] f = asked != null ? resolve(asked)
                    : arrive ? resolve("greet")
                    : cheer ? resolve("cheer")
                    : onIt ? resolve("on-it")
                    : null;
            if (f == null && arrive) f = acts.named(FLOURISH) != null ? new Object[]{acts.named(FLOURISH), false} : null;
            if (f != null && f[0] != a) {
                if (onIt) lastOnIt = t;
                chainTo = a;
                chainMood = m;
                a = (JoybotActs.Act) f[0];
                flourishMirror = (Boolean) f[1];
                flourishLively = "tap".equals(asked) || "wake".equals(asked);
            }
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
            // Only now has he really done it: the chat changes his mood several times as it opens,
            // and each of those used to count as the greeting before a frame of it had played.
            if (m == JoybotView.Mood.IDLE) greeted = true;
            boolean mir = flourishMirror ^ (a.mirrorable && dice.nextInt(3) == 0);
            return acts.onceFilm(a, p, mir, dice.nextFloat() < a.flipChance, flourishLively).thenRun(() -> {
                if (chainTo != next) return;
                chainTo = null;
                chainMood = null;
                if (!bot.isAttachedToWindow() || bot.getDirector() != this) return;
                // The mood moved on while he did it, or another moment is waiting: go to that.
                if (bot.getMood() != m || bot.hasMoment()) { bot.refreshMood(); return; }
                reopened = next;
                bot.setMood(m);
            });
        }
        chainTo = null;
        chainMood = null;
        now = a;
        nowMood = m;
        lastName = a.name;
        nowMirror = a.mirrorable && dice.nextInt(3) == 0;
        return acts.film(a, p, nowMirror, dice.nextFloat() < a.flipChance);
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
