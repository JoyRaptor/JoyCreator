package com.fadcam.ui.faditor.slides;

import androidx.annotation.NonNull;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.fadcam.FLog;

import java.io.File;

/**
 * Encodes a captured PNG frame sequence into an MP4 (fullscreen slide mode).
 *
 * <p>Overlay mode needs no encode step — the PNG sequence is itself the cached
 * artifact, composited per-frame at export time.</p>
 *
 * <p>Uses the same FFmpegKit invocation path already proven by Clean Audio v2 in
 * {@code ExportManager}.</p>
 */
public class SlideEncoder {

    private static final String TAG = "SlideEncoder";

    /**
     * Encode {@code frameDir}/frame%04d.png into {@code outMp4} at {@code fps}.
     * H.264 has no alpha, so a fullscreen slide is expected to render an opaque
     * background; transparent regions are flattened to black.
     *
     * <p>The bundled {@code ffmpeg-kit-full} variant is non-GPL and therefore has
     * no {@code libx264} (that encoder is GPL-only). It ships OpenH264 instead, so
     * we encode with {@code libopenh264} and fall back to the always-present
     * {@code mpeg4} encoder if that is somehow unavailable. The slide MP4 is only
     * an intermediate that Media3 re-encodes during composition, so either codec
     * just needs to be decodable.</p>
     *
     * @return true on success.
     */
    public boolean encodePngSequenceToMp4(@NonNull File frameDir, @NonNull File outMp4,
                                          int fps) {
        File first = new File(frameDir, "frame0000.png");
        if (!first.exists()) {
            FLog.e(TAG, "No frames to encode in " + frameDir);
            return false;
        }
        if (outMp4.getParentFile() != null) outMp4.getParentFile().mkdirs();

        String input = quote(new File(frameDir, "frame%04d.png").getAbsolutePath());
        String out = quote(outMp4.getAbsolutePath());
        String pad = quote("pad=ceil(iw/2)*2:ceil(ih/2)*2");
        // A silent audio track so the slide is a normal A/V clip — Media3 rejects a
        // video-only item that precedes items WITH audio in a sequence. Generated
        // via lavfi anullsrc (input 1), truncated to the video length by -shortest.
        String silentAudio = "-f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100";

        // OpenH264 (BSD, in ffmpeg-kit-full): bitrate rate-control, no CRF.
        String openh264 = "-y -framerate " + fps + " -i " + input + " " + silentAudio
                + " -c:v libopenh264 -pix_fmt yuv420p -b:v 12M -c:a aac -b:a 128k -shortest"
                + " -vf " + pad + " -r " + fps + " -movflags +faststart " + out;
        // Universal fallback (MPEG-4 Part 2) — always built in.
        String mpeg4 = "-y -framerate " + fps + " -i " + input + " " + silentAudio
                + " -c:v mpeg4 -q:v 3 -pix_fmt yuv420p -c:a aac -b:a 128k -shortest"
                + " -vf " + pad + " -r " + fps + " -movflags +faststart " + out;

        if (runEncode(openh264, outMp4, "libopenh264")) return true;
        if (runEncode(mpeg4, outMp4, "mpeg4")) return true;
        // Don't leave a partial file behind — the content-addressed cache check
        // treats any non-empty file as a valid hit.
        if (outMp4.exists()) outMp4.delete();
        return false;
    }

    private boolean runEncode(@NonNull String cmd, @NonNull File outMp4,
                              @NonNull String codecLabel) {
        if (outMp4.exists()) outMp4.delete();
        FFmpegSession session = FFmpegKit.execute(cmd);
        boolean ok = ReturnCode.isSuccess(session.getReturnCode())
                && outMp4.exists() && outMp4.length() > 0;
        if (ok) {
            FLog.i(TAG, "Encoded slide MP4 (" + codecLabel + "): " + outMp4.getName()
                    + " " + outMp4.length() + " bytes");
        } else {
            String logs = session.getOutput();
            if (logs != null && logs.length() > 600) {
                logs = logs.substring(logs.length() - 600);
            }
            FLog.e(TAG, "Slide encode failed (" + codecLabel + "): rc="
                    + session.getReturnCode() + " tail=" + logs);
        }
        return ok;
    }

    @NonNull
    private static String quote(@NonNull String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
