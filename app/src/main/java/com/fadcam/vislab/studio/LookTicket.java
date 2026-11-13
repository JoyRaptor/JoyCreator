package com.fadcam.vislab.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One export frame's picture, handed from VisLab's GL thread to the export that waits for it.
 * A wait that gives up (too slow, or the export was cancelled) abandons the ticket: work not
 * begun is skipped, so a slow thread never collects a queue of frames nobody wants, and a picture
 * that arrives late is thrown away here instead of being left for no one to recycle.
 * Pure Java: the JVM harness tests it.
 */
public final class LookTicket<T> {

    public enum Outcome {
        /** The GL thread answered. {@link #take} holds the picture, or null when it cannot draw. */
        READY,
        /** The look was drawing and stopped: the export must not go on without it. */
        FAILED,
        TIMED_OUT,
        CANCELLED
    }

    /** How often a waiter looks up to see whether the export was cancelled. */
    private static final long SLICE_MS = 100;

    private final Consumer<T> discard;
    private boolean finished, failed, abandoned;
    @Nullable private T result;

    public LookTicket(@NonNull Consumer<T> discard) {
        this.discard = discard;
    }

    /** GL thread, before drawing: false when nobody is waiting any more. */
    public synchronized boolean begin() {
        return !abandoned;
    }

    /** GL thread: the frame's picture (null = VisLab cannot draw it). */
    public void finish(@Nullable T picture, boolean brokeDrawing) {
        synchronized (this) {
            if (!abandoned) {
                result = picture;
                failed = brokeDrawing;
                finished = true;
                notifyAll();
                return;
            }
        }
        if (picture != null) discard.accept(picture);
    }

    /** Waits up to {@code timeoutMs}; gives up at once when {@code cancelled} turns true. */
    @NonNull
    public Outcome await(long timeoutMs, @Nullable BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        boolean interrupted = false;
        Outcome out = null;
        while (out == null) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                out = Outcome.CANCELLED;
                break;
            }
            synchronized (this) {
                if (finished) {
                    return settled(interrupted);
                }
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    out = Outcome.TIMED_OUT;
                    break;
                }
                try {
                    wait(Math.min(left, SLICE_MS));
                } catch (InterruptedException e) {
                    interrupted = true;   // the export's thread is going away
                    out = Outcome.CANCELLED;
                }
            }
        }
        abandon();
        if (interrupted) Thread.currentThread().interrupt();
        return out;
    }

    private Outcome settled(boolean interrupted) {
        if (interrupted) Thread.currentThread().interrupt();
        return failed ? Outcome.FAILED : Outcome.READY;
    }

    /** After {@link Outcome#READY}: the picture, now the caller's. */
    @Nullable
    public synchronized T take() {
        T r = result;
        result = null;
        return r;
    }

    private void abandon() {
        T left;
        synchronized (this) {
            abandoned = true;
            left = result;
            result = null;
        }
        if (left != null) discard.accept(left);
    }
}
