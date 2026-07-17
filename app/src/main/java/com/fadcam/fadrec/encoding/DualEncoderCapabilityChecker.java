package com.fadcam.fadrec.encoding;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import androidx.annotation.Nullable;

import com.fadcam.FLog;

/**
 * Decides whether this device can sustain TWO simultaneous hardware AVC
 * encoders — the screen recording plus the raw webcam stream of the
 * dual-stream-recording feature (spec Decision 2/5: the opt-in toggle is
 * HIDDEN, not merely disabled, on devices that can't).
 *
 * <p>Concurrent hardware-encoder count is an OEM choice in
 * {@code media_codecs.xml}, surfaced via
 * {@link MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances()} — it
 * genuinely varies across SoCs and must never be assumed.</p>
 */
public final class DualEncoderCapabilityChecker {

    private static final String TAG = "DualEncoderCap";
    private static final String MIME_AVC = "video/avc";

    /** SharedPreferences key for the opt-in toggle (read by the recording path in Phase 1+). */
    public static final String PREF_DUAL_STREAM_WEBCAM = "fadrec_dual_stream_webcam";

    private static volatile int cachedMaxInstances = -1;

    private DualEncoderCapabilityChecker() { }

    /**
     * True when a hardware AVC encoder reports headroom for at least two
     * concurrent instances. Cached after first query (codec lists don't change
     * at runtime).
     */
    public static boolean supportsDualHardwareEncode() {
        return maxHardwareAvcEncoderInstances() >= 2;
    }

    /** Max concurrent instances of the best hardware AVC encoder, or 0 if none found. */
    public static int maxHardwareAvcEncoderInstances() {
        int cached = cachedMaxInstances;
        if (cached >= 0) return cached;
        int max = 0;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder() || !isHardware(info)) continue;
                String mime = supportedAvcMime(info);
                if (mime == null) continue;
                try {
                    int instances = info.getCapabilitiesForType(mime).getMaxSupportedInstances();
                    if (instances > max) max = instances;
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Encoder capability query failed", e);
        }
        FLog.i(TAG, "Hardware AVC encoder max concurrent instances: " + max);
        cachedMaxInstances = max;
        return max;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated();
        }
        // Pre-Q heuristic: software encoders are named OMX.google.* / c2.android.*.
        String name = info.getName().toLowerCase(java.util.Locale.US);
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.");
    }

    @Nullable
    private static String supportedAvcMime(MediaCodecInfo info) {
        for (String t : info.getSupportedTypes()) {
            if (MIME_AVC.equalsIgnoreCase(t)) return t;
        }
        return null;
    }
}
