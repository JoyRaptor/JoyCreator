package com.fadcam.ui.faditor.ai.bridge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/**
 * Who may use the bridge (BOT-6 safety): a fresh six-digit code each time it's switched on, only the
 * first computer that uses it, and a lock-out after a few wrong codes (so the code can't be guessed
 * from the same Wi-Fi). Short enough to say aloud or type; the bridge is off unless the person turned
 * it on, and Stop ends it. Pure Java (BridgeTest).
 */
public final class BridgeAuth {

    /** Wrong codes allowed before the bridge refuses everyone until it's switched on again. */
    public static final int MAX_WRONG = 8;

    public enum Verdict { OK, WRONG_CODE, OTHER_COMPUTER, LOCKED_OUT, BROWSER }

    @NonNull private final String code;
    @Nullable private String pairedAddress;
    private int wrong;

    public BridgeAuth(@NonNull String code) {
        this.code = code;
    }

    /** A fresh random six-digit code. */
    @NonNull
    public static String newCode(@NonNull SecureRandom rnd) {
        return String.format(java.util.Locale.US, "%06d", rnd.nextInt(1_000_000));
    }

    /** "482913" → "482 913", as the screen shows it. */
    @NonNull
    public static String spaced(@NonNull String code) {
        return code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
    }

    @NonNull
    public String code() {
        return code;
    }

    @Nullable
    public synchronized String pairedAddress() {
        return pairedAddress;
    }

    /**
     * May this request in? {@code authorization}: its Authorization header; {@code origin}: its Origin
     * header (only browsers send one: a web page must never reach the bridge); {@code from}: the
     * computer's address.
     */
    @NonNull
    public synchronized Verdict check(@Nullable String authorization, @Nullable String origin, @NonNull String from) {
        if (origin != null && !origin.isEmpty()) return Verdict.BROWSER;
        if (wrong >= MAX_WRONG) return Verdict.LOCKED_OUT;
        String given = bearer(authorization);
        if (given == null || !same(given.replace(" ", "").replace("-", ""), code)) {
            wrong++;
            return wrong >= MAX_WRONG ? Verdict.LOCKED_OUT : Verdict.WRONG_CODE;
        }
        if (pairedAddress == null) pairedAddress = from;
        if (!pairedAddress.equals(from)) return Verdict.OTHER_COMPUTER;
        // The right code from the paired computer: earlier typos no longer count toward the lock-out
        // (8 wrong codes in a row still lock it; a guesser never gets here).
        wrong = 0;
        return Verdict.OK;
    }

    /** The token in "Bearer 482913", or null. */
    @Nullable
    static String bearer(@Nullable String header) {
        if (header == null) return null;
        String h = header.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) return h.substring(7).trim();
        return null;
    }

    /** Constant-time compare: the answer's timing says nothing about how close a guess was. */
    private static boolean same(@NonNull String a, @NonNull String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
