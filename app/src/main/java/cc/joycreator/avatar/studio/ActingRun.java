package cc.joycreator.avatar.studio;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cc.joycreator.avatar.perform.Take;

import com.fadcam.ui.faditor.avatar.VideoActingReader;
import com.fadcam.ui.faditor.avatar.face.FaceSignals;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

/**
 * One read of a character's acting plan (slice 2b, item 3): every video in the plan read once, in turn, into the item's
 * take, for the things ticked on it. The caller makes the result one undo step and shows the progress.
 *
 * <p>The sources a character can act from: the main video ({@link #MAIN}), each picture-in-picture video (its clip id),
 * and each sound layer ({@code sound:<id>}, lips only: "Lips from a sound layer"). Lips come from a video's own sound
 * when it has one and it is not muted, else from the face's mouth.</p>
 */
public final class ActingRun {
    public static final String MAIN = "main";
    public static final String SOUND = "sound:";

    /** A source the drawer can offer. */
    public static final class Choice {
        public final String id, name;
        public final boolean sound;

        Choice(String id, String name, boolean sound) {
            this.id = id;
            this.name = name;
            this.sound = sound;
        }
    }

    public interface Progress {
        /** 0 to 1, over the whole read; on the main thread. */
        void at(float fraction, String sourceName);
    }

    private final Context c;
    private final Timeline tl;
    private final SpriteOverlayItem item;
    private final Acting.Plan plan;
    private final Progress progress;
    private final Consumer<String> done;
    private Take take;
    private int next;
    private volatile boolean stopped;
    @Nullable private VideoActingReader reader;

    private ActingRun(Context c, Timeline tl, SpriteOverlayItem item, Acting.Plan plan, Progress p, Consumer<String> done) {
        this.c = c.getApplicationContext();
        this.tl = tl;
        this.item = item;
        this.plan = plan;
        this.progress = p;
        this.done = done;
    }

    /** What the project offers to act from: the main video if it has any, each picture-in-picture video, each sound. */
    @NonNull
    public static List<Choice> choices(@NonNull Timeline tl, @NonNull String mainName) {
        List<Choice> out = new ArrayList<>();
        boolean video = false;
        for (Clip k : tl.getClips()) if (!k.isImageClip()) video = true;
        if (video) out.add(new Choice(MAIN, mainName, false));
        for (Clip k : tl.getOverlayClips()) {
            if (k.isImageClip()) continue;
            String n = k.getDisplayName();
            out.add(new Choice(k.getId(), n == null || n.isEmpty() ? "Video " + (out.size() + 1) : n, false));
        }
        for (AudioClip a : tl.getAudioClips()) {
            String n = a.getLabel();
            out.add(new Choice(SOUND + a.getId(), n == null || n.isEmpty() ? "Sound " + (out.size() + 1) : n, true));
        }
        return out;
    }

    /**
     * Reads the plan into a new take. {@code done} gets the new take's JSON text, or null when nothing could be read
     * (or the read was stopped); always on the main thread. Returns the run, to {@link #stop}.
     */
    public static ActingRun start(@NonNull Context c, @NonNull Timeline tl, @NonNull SpriteOverlayItem item,
                                  @NonNull Acting.Plan plan, @NonNull Progress p, @NonNull Consumer<String> done) {
        ActingRun r = new ActingRun(c, tl, item, plan, p, done);
        r.take = StudioCharacter.takeOf(item);
        r.step();
        return r;
    }

    public void stop() {
        stopped = true;
        if (reader != null) reader.cancel();
    }

    /** The item's length in ms (an open-ended item lasts as long as its take). */
    long itemMs() {
        long len = item.getEndMs() == Long.MAX_VALUE ? TakeClock.lengthMs(take) - item.getCharacterTakeOffsetMs()
                : item.getEndMs() - item.getStartMs();
        return Math.max(1, len);
    }

    private int offsetFrames() {
        return (int) Math.round(item.getCharacterTakeOffsetMs() * take.fps / 1000.0);
    }

    private void step() {
        if (stopped) {
            done.accept(null);
            return;
        }
        while (next < plan.sources.size() && plan.sources.get(next).listen.isEmpty()) next++;
        if (next >= plan.sources.size()) {
            done.accept(StudioCharacter.json(take));
            return;
        }
        Acting.Source s = plan.sources.get(next);
        final float base = next / (float) plan.sources.size(), span = 1f / plan.sources.size();
        next++;
        progress.at(base, s.clipId);
        long ms = itemMs();
        int n = offsetFrames() + TakeClock.framesFor(take, ms);

        if (s.clipId.startsWith(SOUND)) {
            AudioClip a = audio(s.clipId.substring(SOUND.length()));
            if (a == null || !s.listen.contains(Acting.Listen.LIPS)) {
                step();
                return;
            }
            final long start = item.getStartMs() + s.nudgeMs;
            LongUnaryOperator map = local -> {
                long t = start + local - a.getOffsetMs();
                return t < 0 || t >= a.getTrimmedDurationMs() ? -1 : a.getInPointMs() + t;
            };
            s.readAt = Acting.fingerprint(a.getOffsetMs(), a.getInPointMs(), a.getOutPointMs(), 1f, s.nudgeMs);
            SoundLips.read(c, a.getSourceUri(), map, TakeClock.framesFor(take, ms), lips -> {
                if (lips != null) take = Acting.writeLips(take, n, offsetFrames(), (float[]) lips[0], (String[]) lips[1]);
                step();
            });
            return;
        }

        final VideoActingReader.TimeMap map;
        final java.util.function.LongFunction<Uri> uriAt;
        final Clip only;
        if (MAIN.equals(s.clipId)) {
            map = VideoActingReader.spine(tl.getClips(), item.getStartMs(), s.nudgeMs);
            uriAt = local -> spineClipAt(item.getStartMs() + local + s.nudgeMs) == null ? null
                    : spineClipAt(item.getStartMs() + local + s.nudgeMs).getSourceUri();
            only = null;
            s.readAt = Acting.fingerprint(0, tl.getClips().size(), tl.getTotalDurationMs(), 1f, s.nudgeMs);
        } else {
            only = overlay(s.clipId);
            if (only == null) {
                step();
                return;
            }
            map = VideoActingReader.overlay(only, item.getStartMs(), s.nudgeMs);
            uriAt = local -> only.getSourceUri();
            s.readAt = Acting.fingerprint(only.getOverlayStartMs(), only.getInPointMs(), only.getOutPointMs(),
                    only.getSpeedMultiplier(), s.nudgeMs);
        }
        final Set<Acting.Listen> face = EnumSet.copyOf(s.listen);
        final Clip soundOf = only != null ? only : null;
        boolean lipsFromSound = face.contains(Acting.Listen.LIPS) && soundOf != null && !soundOf.isAudioMuted();
        Runnable faceRead = () -> {
            face.remove(Acting.Listen.SHOULDERS);   // the body tracker is not in this build: shoulders stay as they are
            if (face.isEmpty()) {
                step();
                return;
            }
            reader = new VideoActingReader(c);
            reader.read(uriAt, map, ms, new VideoActingReader.Listener() {
                @Override public void onProgress(int d, int total) {
                    progress.at(base + span * d / Math.max(1, total), s.clipId);
                }

                @Override public void onDone(@NonNull double[] times, @NonNull List<java.util.Map<String, Float>> sig) {
                    reader = null;
                    List<java.util.Map<String, Float>> frames = new ArrayList<>();
                    for (int i = 0; i < offsetFrames(); i++) frames.add(null);
                    frames.addAll(Acting.resample(times, sig, take.fps, TakeClock.framesFor(take, ms)));
                    take = Acting.write(take, n, frames, face, FaceSignals.SHAPES);
                    step();
                }

                @Override public void onCancelled() {
                    reader = null;
                    done.accept(null);
                }

                @Override public void onFailed(@NonNull String why) {
                    reader = null;
                    android.util.Log.w("AvatarStudio", "acting not read: " + why);
                    step();
                }
            });
        };
        if (lipsFromSound) {
            SoundLips.read(c, soundOf.getSourceUri(), local -> map.sourceMsAt(local), TakeClock.framesFor(take, ms), lips -> {
                if (lips != null) {
                    take = Acting.writeLips(take, n, offsetFrames(), (float[]) lips[0], (String[]) lips[1]);
                    face.remove(Acting.Listen.LIPS);
                }
                faceRead.run();
            });
        } else {
            faceRead.run();
        }
    }

    @Nullable
    private Clip spineClipAt(long t) {
        long at = 0;
        for (Clip k : tl.getClips()) {
            long span = k.hasLoopExtension() ? k.getVisualDurationMs() : k.getTrimmedDurationMs();
            if (t >= at && t < at + span) return k;
            at += span;
        }
        return null;
    }

    @Nullable
    private Clip overlay(String id) {
        for (Clip k : tl.getOverlayClips()) if (id.equals(k.getId())) return k;
        return null;
    }

    @Nullable
    private AudioClip audio(String id) {
        for (AudioClip a : tl.getAudioClips()) if (id.equals(a.getId())) return a;
        return null;
    }

    /** True when a video in a Keep as driver plan moved or was trimmed since it was read (read it again). */
    public static boolean stale(@NonNull Timeline tl, @NonNull Acting.Plan plan) {
        for (Acting.Source s : plan.sources) {
            if (s.listen.isEmpty()) continue;
            String now;
            if (MAIN.equals(s.clipId)) {
                now = Acting.fingerprint(0, tl.getClips().size(), tl.getTotalDurationMs(), 1f, s.nudgeMs);
            } else if (s.clipId.startsWith(SOUND)) {
                AudioClip a = null;
                for (AudioClip x : tl.getAudioClips()) if (s.clipId.substring(SOUND.length()).equals(x.getId())) a = x;
                if (a == null) continue;
                now = Acting.fingerprint(a.getOffsetMs(), a.getInPointMs(), a.getOutPointMs(), 1f, s.nudgeMs);
            } else {
                Clip k = null;
                for (Clip x : tl.getOverlayClips()) if (s.clipId.equals(x.getId())) k = x;
                if (k == null) continue;
                now = Acting.fingerprint(k.getOverlayStartMs(), k.getInPointMs(), k.getOutPointMs(), k.getSpeedMultiplier(), s.nudgeMs);
            }
            if (!now.equals(s.readAt)) return true;
        }
        return false;
    }
}
