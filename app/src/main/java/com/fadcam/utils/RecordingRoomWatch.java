package com.fadcam.utils;

import android.os.Handler;

import com.fadcam.R;

/**
 * Watches a running recording for room (work-loss audit F5, SHELL-31): every few seconds it looks at
 * the free space where the file is being written and asks for a stop while there is still room to
 * finish the file; a write that fails anyway asks for a stop too. Each ask happens once, on the
 * handler's thread. The camera recorder has the same logic inline ({@code RecordingService}); screen
 * and webcam recording use this class.
 */
public final class RecordingRoomWatch {

    /** Where the free space is read from; -1 when it cannot be told (that never stops anything). */
    public interface FreeSpace {
        long freeBytes();
    }

    /** Stops the recording, keeping the file, after telling the person this message. */
    public interface Stopper {
        void stopForStorage(int messageRes);
    }

    private final Handler handler;
    private final FreeSpace freeSpace;
    private final Stopper stopper;
    private volatile boolean running;
    private volatile boolean fired;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || fired) return;
            long free;
            try {
                free = freeSpace.freeBytes();
            } catch (Throwable t) {
                free = -1;
            }
            if (RecordingRoom.mustStop(free)) {
                fire(R.string.record_stopped_low_space);
                return;
            }
            handler.postDelayed(this, RecordingRoom.CHECK_EVERY_MS);
        }
    };

    public RecordingRoomWatch(Handler handler, FreeSpace freeSpace, Stopper stopper) {
        this.handler = handler;
        this.freeSpace = freeSpace;
        this.stopper = stopper;
    }

    public void start() {
        running = true;
        fired = false;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, RecordingRoom.CHECK_EVERY_MS);
    }

    public void cancel() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** A write to the recording file failed (any thread): stop and say so, once. */
    public void writeFailed() {
        handler.post(() -> fire(R.string.record_stopped_write_problem));
    }

    private void fire(int messageRes) {
        if (!running || fired) return;
        fired = true;
        handler.removeCallbacks(tick);
        stopper.stopForStorage(messageRes);
    }
}
