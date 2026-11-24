package cc.joycreator.avatar;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Joybot's hands in the Avatar Studio room (spec A12): read the character, put a part in hand, switch stage, show or
 * hide a layer, move it in front or behind, save. Every call runs on the room's thread and waits for it; every change
 * is one named undo step through the same checked edit a tap makes (rule 6: never raw JSON). When the room is not
 * open, a call says so in words.
 */
public final class AvatarRoom {

    /** What Joybot is told when it cannot do the thing (words for the person, not an error code). */
    public static final class Refused extends Exception {
        public Refused(String why) {
            super(why);
        }
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private AvatarRoom() {}

    public static boolean isOpen() {
        AvatarRoomActivity a = AvatarRoomActivity.current();
        return a != null && !a.isFinishing();
    }

    @NonNull
    public static String describe() throws Refused {
        return onUi(AvatarRoomActivity::joybotDescribe);
    }

    public static boolean choosePart(@NonNull String what) throws Refused {
        return onUi(a -> a.joybotChoose(what));
    }

    public static boolean stage(@NonNull String name) throws Refused {
        return onUi(a -> a.joybotStage(name));
    }

    /** A sentence for what was done, or null when no layer is called that. */
    @Nullable
    public static String shown(@NonNull String what, boolean shown) throws Refused {
        return onUi(a -> a.joybotShown(what, shown));
    }

    @Nullable
    public static String stack(@NonNull String what, @NonNull String how) throws Refused {
        return onUi(a -> a.joybotStack(what, how));
    }

    public static void save() throws Refused {
        onUi(a -> {
            a.joybotSave();
            return Boolean.TRUE;
        });
    }

    private static <T> T onUi(@NonNull Function<AvatarRoomActivity, T> job) throws Refused {
        AvatarRoomActivity a = AvatarRoomActivity.current();
        if (a == null || a.isFinishing()) throw new Refused("The Avatar Studio room isn't open (open_room avatar opens it).");
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<RuntimeException> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Runnable run = () -> {
            try {
                out.set(job.apply(a));
            } catch (RuntimeException e) {
                err.set(e);
            } finally {
                done.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) run.run();
        else {
            UI.post(run);
            try {
                if (!done.await(5, TimeUnit.SECONDS)) throw new Refused("Avatar Studio is busy; try again in a moment.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Refused("Interrupted.");
            }
        }
        if (err.get() != null) throw new Refused("That didn't work: " + err.get().getMessage());
        return out.get();
    }
}
