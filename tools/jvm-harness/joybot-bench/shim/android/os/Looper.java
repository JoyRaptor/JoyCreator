package android.os;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime shim: one real "main" thread that runs posted work in time order, so the agent's
 * main-thread hand-offs (Handler.post, JoybotStorage.onMain) behave as on the phone.
 */
public final class Looper {

    private static final ThreadLocal<Looper> MINE = new ThreadLocal<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final Looper MAIN = new Looper("main");

    final DelayQueue<Item> queue = new DelayQueue<>();
    private final Thread thread;

    static final class Item implements Delayed {
        final Runnable r;
        final long at;
        final long seq = SEQ.incrementAndGet();
        final Object token;

        Item(Runnable r, long at, Object token) { this.r = r; this.at = at; this.token = token; }

        @Override public long getDelay(TimeUnit u) { return u.convert(at - System.nanoTime(), TimeUnit.NANOSECONDS); }

        @Override public int compareTo(Delayed o) {
            Item b = (Item) o;
            int c = Long.compare(at, b.at);
            return c != 0 ? c : Long.compare(seq, b.seq);
        }
    }

    private Looper(String name) {
        thread = new Thread(() -> {
            MINE.set(this);
            while (true) {
                try {
                    queue.take().r.run();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    System.err.println("[main thread] uncaught: " + t);
                    t.printStackTrace();
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return MINE.get(); }
    public static void prepare() { }
    public static void loop() { }
    public Thread getThread() { return thread; }
    public boolean isCurrentThread() { return Thread.currentThread() == thread; }
    public void quit() { }
    public void quitSafely() { }
}
