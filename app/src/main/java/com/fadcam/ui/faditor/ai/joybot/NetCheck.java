package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Is the phone online, in the words Joybot needs. A phone with no internet waited a minute and was
 * told "The AI service went quiet", blaming the service for its own suspended line (Note 9,
 * 2026-10-28, BOT-1). Android's own check ("validated") says whether a connection really reaches
 * the internet.
 */
public final class NetCheck {
    private NetCheck() { }

    public enum State {
        /** No Wi-Fi and no mobile data at all. */
        NONE,
        /** Connected, but Android found it doesn't reach the internet (mobile data paused, Wi-Fi sign-in). */
        NOT_REACHING,
        /** Online, or Android can't tell (then we don't get in the way). */
        OK
    }

    @NonNull
    public static State of(@NonNull Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return State.OK;
            Network n = cm.getActiveNetwork();
            if (n == null) return State.NONE;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) return State.NONE;
            return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? State.OK : State.NOT_REACHING;
        } catch (Throwable t) {
            // Never block or break Joybot on a check that failed (the PC bench's stand-in Android
            // has no connection service at all, 10-28).
            return State.OK;
        }
    }

    /** What to tell the person, or null when the connection isn't the problem. Pure: tested off-device. */
    @Nullable
    public static String words(@NonNull State s) {
        switch (s) {
            case NONE:
                return "Your phone isn't online (no Wi-Fi or mobile data), so I can't reach the AI. "
                        + "The buttons that work offline still do.";
            case NOT_REACHING:
                return "Your phone's connection isn't reaching the internet right now (your mobile data may be "
                        + "paused, or the Wi-Fi needs a sign-in), so I couldn't reach the AI. Try again once it's "
                        + "back; the buttons that work offline still do.";
            default:
                return null;
        }
    }
}
