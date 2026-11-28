package cc.joycreator.avatar.perform;

import java.util.ArrayList;
import java.util.List;

/**
 * One owner's delayed jobs on a shared handler: it may cancel its own and ONLY its own. (The joystick's double tap used to clear the
 * room's whole handler, taking the autosave and a save or open about to finish with it.)
 */
public final class OwnJobs implements Stick.Later {
    /** The handler's post and remove (the room's {@code Handler}). */
    public interface Poster {
        void post(Runnable r, long ms);

        void remove(Runnable r);
    }

    private final Poster poster;
    private final List<Runnable> mine = new ArrayList<>();

    public OwnJobs(Poster poster) {
        this.poster = poster;
    }

    @Override
    public void after(long ms, Runnable r) {
        final Runnable[] wrapped = new Runnable[1];
        wrapped[0] = () -> {
            mine.remove(wrapped[0]);
            r.run();
        };
        mine.add(wrapped[0]);
        poster.post(wrapped[0], ms);
    }

    @Override
    public void cancel(Object tag) {
        for (Runnable w : new ArrayList<>(mine)) poster.remove(w);
        mine.clear();
    }
}
