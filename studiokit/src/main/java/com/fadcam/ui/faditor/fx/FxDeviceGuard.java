package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which effects THIS phone's GPU driver refused to compile or link. A shader that fails must
 * never crash Studio, break an export or show a black picture, so the failure is remembered here
 * and the effect is simply not offered: the picker shows it as "not available on this phone"
 * and will not add it.
 *
 * <p>The live preview and the export already fall back on their own (preview: previews ungraded
 * and says so; export: passthrough). This class is the front door: it keeps a card that can never
 * work on this device from being added in the first place. It is fed by the offscreen swatch
 * renderer, the first thing to compile each effect, and by {@link #probe} for tests.</p>
 *
 * <p>Android-free so the harness can pin it. Process-wide on purpose: a driver does not change
 * its mind while the app runs.</p>
 */
public final class FxDeviceGuard {

    private FxDeviceGuard() {}

    /** Compiles one effect for real. Throws when the driver refuses it. */
    public interface Compiler {
        void compile(@NonNull FxEffectDef def) throws Exception;
    }

    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());

    /** Record that {@code effectId} did not compile or link here. */
    public static void markFailed(@NonNull String effectId) { FAILED.add(effectId); }

    /** True when this device has refused the effect: do not offer it, do not add it. */
    public static boolean isUnavailable(@NonNull String effectId) { return FAILED.contains(effectId); }

    /** Forget everything (tests). */
    public static void reset() { FAILED.clear(); }

    /**
     * Try each effect; any that throws is marked unavailable and the rest carry on. Never
     * throws. Returns how many were refused.
     */
    public static int probe(@NonNull List<FxEffectDef> defs, @NonNull Compiler compiler) {
        int refused = 0;
        for (FxEffectDef def : defs) {
            try {
                compiler.compile(def);
            } catch (Throwable t) {
                markFailed(def.id);
                refused++;
            }
        }
        return refused;
    }

    /**
     * The cards of {@code stack} that this device cannot run: a saved project from another phone
     * can hold one, and it must be skipped rather than allowed to break the whole stack.
     */
    @NonNull
    public static List<FxInstance> unavailableIn(@NonNull FxStack stack) {
        java.util.ArrayList<FxInstance> out = new java.util.ArrayList<>();
        for (FxInstance c : stack.cards()) if (isUnavailable(c.effectId)) out.add(c);
        return out;
    }
}
