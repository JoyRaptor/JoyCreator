package cc.joycreator.avatar.ui;

import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.perform.TakeRecorder;
import cc.joycreator.avatar.scene.Scene;
import cc.joycreator.avatar.scene.SceneClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the scene's clock asks about each performer (the pilot's {@code scene.cast()}): the one in hand is the room's own performer
 * (its recorder runs the Record and Play buttons); every other character has a take of its own that a headless recorder plays at the
 * clock's moment. Nothing here draws: {@link #signalsFor} gives the signals a following character takes this frame.
 */
final class ScenePlayers implements SceneClock.Cast {

    /** What the room holds that the players need. */
    interface Env {
        Scene scene();

        Map<String, CharacterDoc> docs();

        Map<String, Take> takes();

        String hand();

        TakeRecorder handRecorder();

        double[] boxOf(String id);
    }

    private final Env env;
    private final Map<String, TakeRecorder> rec = new HashMap<>();
    private final Set<String> following = new HashSet<>();

    ScenePlayers(Env env) {
        this.env = env;
    }

    private boolean isHand(String id) {
        return id != null && id.equals(env.hand());
    }

    private TakeRecorder playerOf(String id) {
        TakeRecorder r = rec.get(id);
        Take t = env.takes().get(id);
        if (r == null) {
            r = new TakeRecorder();
            rec.put(id, r);
        }
        if (r.take() != t) r.setTake(t);
        return r;
    }

    @Override
    public List<String> ids() {
        List<String> l = new ArrayList<>();
        for (JSONObject it : env.scene().cast()) if (env.docs().containsKey(it.optString("id"))) l.add(it.optString("id"));
        return l;
    }

    @Override
    public double duration(String id) {
        if (isHand(id)) return env.handRecorder().duration();
        Take t = env.takes().get(id);
        return t == null ? 0 : t.n / (double) t.fps;
    }

    @Override
    public boolean hasTake(String id) {
        if (isHand(id)) return env.handRecorder().take() != null && env.handRecorder().take().n > 0;
        Take t = env.takes().get(id);
        return t != null && t.n > 0;
    }

    @Override
    public boolean recording(String id) {
        return isHand(id) && env.handRecorder().recording();
    }

    @Override
    public boolean playing(String id) {
        return isHand(id) && env.handRecorder().playing();
    }

    @Override
    public boolean scrubbing(String id) {
        return false;
    }

    @Override
    public double phase(String id) {
        return isHand(id) ? env.handRecorder().clock() : 0;
    }

    @Override
    public void follow(String id, double ph) {
        following.add(id);
        if (isHand(id)) env.handRecorder().scrubTo(ph);
        else playerOf(id).scrubTo(ph);
    }

    @Override
    public void unfollow(String id) {
        following.remove(id);
        if (isHand(id)) env.handRecorder().stop();
        else playerOf(id).stop();
    }

    @Override
    public void sceneLength(String id, double len) {
    }

    @Override
    public double[] box(String id) {
        return env.boxOf(id);
    }

    @Override
    public double[] headBox(String id) {
        return null;
    }

    /** What a following character's take supplies this frame, or null when it is alive by itself. */
    Map<String, Object> signalsFor(String id) {
        if (isHand(id) || !following.contains(id)) return null;
        Take t = env.takes().get(id);
        if (t == null) return null;
        TakeRecorder r = playerOf(id);
        List<String> names = new ArrayList<>(t.ch.keySet());
        return r.step(0, Collections.<String, Object>emptyMap(), names, Collections.<String>emptySet());
    }

    /** A take was replaced (recorded, taken back): its player starts from the new one. */
    void forget(String id) {
        rec.remove(id);
    }
}
