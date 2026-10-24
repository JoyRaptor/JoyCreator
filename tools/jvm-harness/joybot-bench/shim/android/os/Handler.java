package android.os;

/** Runtime shim: posts onto the shim {@link Looper}'s thread. */
public class Handler {

    private final Looper looper;

    public Handler() { this(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper()); }
    public Handler(Looper looper) { this.looper = looper; }

    public final Looper getLooper() { return looper; }

    public final boolean post(Runnable r) { return postDelayed(r, 0); }
    public final boolean postAtFrontOfQueue(Runnable r) { return postDelayed(r, 0); }

    public final boolean postDelayed(Runnable r, long delayMillis) {
        looper.queue.add(new Looper.Item(r, System.nanoTime() + Math.max(0, delayMillis) * 1_000_000L, null));
        return true;
    }

    public final boolean postDelayed(Runnable r, Object token, long delayMillis) {
        looper.queue.add(new Looper.Item(r, System.nanoTime() + Math.max(0, delayMillis) * 1_000_000L, token));
        return true;
    }

    public final void removeCallbacks(Runnable r) { looper.queue.removeIf(i -> i.r == r); }
    public final void removeCallbacks(Runnable r, Object token) { looper.queue.removeIf(i -> i.r == r); }

    public final void removeCallbacksAndMessages(Object token) {
        if (token == null) looper.queue.clear();
        else looper.queue.removeIf(i -> i.token == token);
    }

    public final boolean hasCallbacks(Runnable r) { return looper.queue.stream().anyMatch(i -> i.r == r); }
}
