package com.fadcam.ui.faditor.audio;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.StreamInformation;
import com.fadcam.FLog;

import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One background render at a time. Only completed project-owned files become clip sources. */
public final class StudioVoiceRender {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final String MODEL_HASH = "70bb6685eb0c2a1d18e2918dca3fbfbd39317010b1802eb1b6ea73a92f3fdec0";
    private static final String VERSION = "studio-voice-v1";
    private static File model;

    public interface Listener {
        void progress(int percent);
        /** A null file with no error means the original is already quiet. */
        void finished(File file, Exception error);
    }

    public static CancellationSignal start(Context context, File project, Uri source, long start, long end,
                                            Listener listener) {
        CancellationSignal cancel = new CancellationSignal();
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            File result;
            try {
                result = render(app, project, source, start, end, cancel, listener);
                cancel.throwIfCanceled();
            } catch (Exception error) {
                listener.finished(null, error);
                return;
            }
            listener.finished(result, null);
        });
        return cancel;
    }

    private static File render(Context app, File project, Uri source, long start, long end,
                               CancellationSignal cancel, Listener listener) throws Exception {
        cancel.throwIfCanceled();
        if (start < 0 || end <= start) throw new IOException("Invalid audio range");
        StreamInformation audio = firstAudio(probe(input(app, source)));
        String layout = audio.getChannelLayout();
        Long channels = audio.getNumberProperty("channels");
        if (Long.valueOf(1).equals(channels)) layout = "mono";
        if (Long.valueOf(2).equals(channels)) layout = "stereo";
        boolean surround = StudioVoiceRecipe.surround(layout);
        if (!surround && !"mono".equals(layout) && !"stereo".equals(layout)) {
            throw new UnsupportedOperationException("Unsupported dialogue layout: " + layout);
        }
        int probeChannels = "stereo".equals(layout) ? 2 : 1;
        int rate = surround ? Integer.parseInt(audio.getSampleRate()) : 48000;
        String fingerprint = identity(app, source);
        String key = VERSION + "|" + MODEL_HASH + "|" + source + "|" + fingerprint + "|" + start + "|" + end;
        File dir = new File(project, BakedAudioCache.BAKE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot create audio directory");
        File out = new File(dir, VERSION + "-" + hex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,32)
                + (surround ? ".flac" : ".m4a"));
        if (out.isFile() && valid(out, channels, end)) return out;
        long span = end - start;
        File sample = File.createTempFile("voice-sample-", ".pcm", app.getCacheDir());
        StudioVoiceRecipe.Strength strength = StudioVoiceRecipe.Strength.ORIGINAL;
        try {
            int count = span <= 12000 ? 1 : 3;
            long window = count == 1 ? span : 4000;
            for (int i = 0; i < count; i++) {
                long offset = count == 1 ? 0 : (span - window) * (1 + i * 4) / 10;
                run(new String[]{"-nostdin","-v","error","-y","-ss",StudioVoiceRecipe.seconds(start + offset),
                        "-i",input(app, source),"-map","0:a:0","-t",StudioVoiceRecipe.seconds(window),
                        "-af",surround ? "pan=mono|c0=FC" : "anull","-ac",Integer.toString(probeChannels),"-ar","8000",
                        "-f","s16le",sample.getPath()}, cancel, null, 0);
                if (sample.length() == 0 || sample.length() > 192000L * probeChannels) throw new IOException("Invalid audio sample");
                byte[] data = new byte[(int)sample.length()];
                try (DataInputStream in = new DataInputStream(new FileInputStream(sample))) { in.readFully(data); }
                StudioVoiceRecipe.Strength local = StudioVoiceRecipe.assess(data, probeChannels);
                if (local.ordinal() > strength.ordinal()) strength = local;
                if (strength == StudioVoiceRecipe.Strength.STRONG) break;
            }
            if (strength == StudioVoiceRecipe.Strength.ORIGINAL) {
                if (count > 1) strength = confirmQuiet(app, source, start, span, surround, cancel);
                if (strength == StudioVoiceRecipe.Strength.ORIGINAL) return null;
            }
        } finally { sample.delete(); }
        cancel.throwIfCanceled();
        File weights = installModel(app);
        File tmp = File.createTempFile("voice-render-", ".part", dir);
        long preroll = Math.min(start, 250);
        try {
            String graph = StudioVoiceRecipe.graph(layout, rate, strength, weights.getPath(), start, end, preroll);
            run(new String[]{"-nostdin","-v","error","-y","-ss",StudioVoiceRecipe.seconds(start-preroll),
                    "-i",input(app,source),"-filter_complex_threads","1","-filter_complex",graph,"-map","[out]",
                    "-ar",Integer.toString(rate),"-c:a",surround ? "flac" : "aac",
                    surround ? "-compression_level" : "-b:a",surround ? "2" : "192k",
                    "-f",surround ? "flac" : "mp4",tmp.getPath()},cancel,listener,end);
            cancel.throwIfCanceled();
            if (!valid(tmp, channels, end)) throw new IOException("Incomplete processed audio");
            try (RandomAccessFile file = new RandomAccessFile(tmp,"rw")) { file.getFD().sync(); }
            cancel.throwIfCanceled();
            if (!tmp.renameTo(out)) throw new IOException("Cannot publish processed audio");
            return out;
        } finally { tmp.delete(); }
    }

    private static StudioVoiceRecipe.Strength confirmQuiet(Context app, Uri source, long start, long span,
                                                            boolean surround, CancellationSignal cancel)
            throws IOException {
        File levels = File.createTempFile("voice-levels-", ".txt", app.getCacheDir());
        try {
            // One RMS value per 100 ms. Scan the whole span only when all short probes suggest
            // bypass. No long PCM file or clip-sized array, even for an hour of narration.
            String filter = (surround ? "pan=mono|c0=FC," : "")
                    + "aresample=8000,atrim=duration=" + StudioVoiceRecipe.seconds(span)
                    + ",asetnsamples=n=800:p=0,astats=metadata=1:reset=1:measure_perchannel=none:"
                    + "measure_overall=RMS_level,ametadata=mode=print:key=lavfi.astats.Overall.RMS_level:file="
                    + levels.getPath();
            run(new String[]{"-nostdin","-v","error","-ss",StudioVoiceRecipe.seconds(start),"-i",
                    input(app,source),"-map","0:a:0","-af",filter,"-f","null","-"},cancel,null,0);
            StudioVoiceRecipe.Strength decision = StudioVoiceRecipe.Strength.ORIGINAL;
            double[] window = new double[40];
            int count = 0, total = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(levels))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("lavfi.astats.Overall.RMS_level=")) continue;
                    String value = line.substring(line.indexOf('=') + 1);
                    window[count++] = "-inf".equals(value) ? -120 : Double.parseDouble(value);
                    total++;
                    if (count == window.length) {
                        cancel.throwIfCanceled();
                        StudioVoiceRecipe.Strength local = StudioVoiceRecipe.assessLevels(window);
                        if (local.ordinal() > decision.ordinal()) decision = local;
                        if (decision == StudioVoiceRecipe.Strength.STRONG) return decision;
                        count = 0;
                    }
                }
            }
            if (total == 0) throw new IOException("No audio levels");
            if (count >= 3) {
                StudioVoiceRecipe.Strength tail = StudioVoiceRecipe.assessLevels(java.util.Arrays.copyOf(window,count));
                if (tail.ordinal() > decision.ordinal()) decision = tail;
            }
            return decision;
        } finally { levels.delete(); }
    }

    private static void run(String[] args, CancellationSignal cancel, Listener listener, long end) throws IOException {
        cancel.throwIfCanceled();
        FFmpegSession session = FFmpegSession.create(args, null, null, stats -> {
            if (listener != null && end > 0) listener.progress((int)Math.min(99,100 * stats.getTime() / end));
            if (cancel.isCanceled()) FFmpegKit.cancel(stats.getSessionId());
        });
        cancel.setOnCancelListener(() -> FFmpegKit.cancel(session.getSessionId()));
        try {
            cancel.throwIfCanceled();
            FFmpegKitConfig.ffmpegExecute(session);
            cancel.throwIfCanceled();
            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                FLog.w("StudioVoice","FFmpeg returned " + session.getReturnCode() + ": " + session.getOutput());
                throw new IOException("Audio processing failed");
            }
        } finally { cancel.setOnCancelListener(null); }
    }

    private static String input(Context app, Uri uri) {
        // Each FFmpeg/FFprobe invocation consumes its SAF handle.
        return "file".equals(uri.getScheme()) ? uri.getPath() : FFmpegKitConfig.getSafParameterForRead(app,uri);
    }

    private static MediaInformation probe(String input) throws IOException {
        MediaInformation info = FFprobeKit.getMediaInformation(input).getMediaInformation();
        if (info == null) throw new IOException("Cannot read audio");
        return info;
    }

    private static StreamInformation firstAudio(MediaInformation info) throws IOException {
        for (StreamInformation stream : info.getStreams()) if ("audio".equals(stream.getType())) return stream;
        throw new IOException("No audio stream");
    }

    private static boolean valid(File f, Long channels, long end) {
        try {
            MediaInformation info = probe(f.getPath());
            return channels != null && channels.equals(firstAudio(info).getNumberProperty("channels"))
                    && Math.abs(Double.parseDouble(info.getDuration()) * 1000 - end) <= 50;
        } catch (Exception e) { return false; }
    }

    private static String identity(Context app, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            return f.length() + "/" + f.lastModified();
        }
        try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0) && !c.isNull(1) && c.getLong(1) > 0)
                return c.getLong(0) + "/" + c.getLong(1);
        } catch (RuntimeException ignored) { /* Some providers do not expose a revision. */ }
        return UUID.randomUUID().toString();
    }

    private static File installModel(Context app) throws Exception {
        if (model != null && model.isFile()) return model;
        File dir = app.getNoBackupFilesDir();
        File dest = new File(dir,"studio-voice-" + MODEL_HASH + ".rnnn");
        if (dest.isFile()) {
            MessageDigest existing = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(dest)) {
                byte[] buf = new byte[16384];
                int size;
                while ((size = in.read(buf)) != -1) existing.update(buf, 0, size);
            }
            if (hex(existing.digest()).equals(MODEL_HASH)) { model = dest; return model; }
        }
        File tmp = File.createTempFile("voice-model-", ".part",dir);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = app.getAssets().open("studio_voice/sh.rnnn");
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int size;
                while ((size=in.read(buf)) != -1) { out.write(buf,0,size); hash.update(buf,0,size); }
                out.getFD().sync();
            }
            if (!hex(hash.digest()).equals(MODEL_HASH) || !tmp.renameTo(dest)) throw new IOException("Invalid speech model");
            model = dest;
            return model;
        } finally { tmp.delete(); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format(Locale.ROOT,"%02x",b & 255));
        return s.toString();
    }

    private StudioVoiceRender() {}
}
