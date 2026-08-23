package com.fadcam.ui.faditor.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.FLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * B5.E — punch-in voiceover capture against playback.
 *
 * <p>Mirrors the capture infra on the recording side of this app ({@code fadrec} —
 * {@link com.fadcam.audio.NoiseMonitor} and {@code ScreenRecordingPipeline}): open
 * {@link AudioRecord} at 44.1k mono 16-bit with {@link AudioRecord#getMinBufferSize},
 * guard {@code STATE_INITIALIZED}, drain in a dedicated thread, release on stop.
 * The file is a plain WAV (PCM 16-bit) in {@code getFilesDir()/faditor_audio} —
 * the durable location road_map already mandates (not cache), so a voiceover
 * survives reinstall and is playable by every MediaPlayer path that already
 * handles extracted audio.
 *
 * <p><b>Monitoring / feedback decision (spec §B5):</b> this engine mutes
 * playback output while armed. If the user is on speakers, recording while the
 * timeline plays would capture its own output as an audible echo/feedback loop
 * — a take ruined only after the fact. The activity checks
 * {@link AudioManager#isWiredHeadsetOn()} / {@code isBluetoothA2dpOn()}:
 * on speakers it <i>mutes</i> the live players and shows a toast
 * "Playback muted to prevent feedback — use headphones to hear the timeline
 * while recording". On headphones it leaves playback audible. This is the
 * "mute output while armed" half of the spec's "warn, or mute" — warning
 * alone would still let the loop be recorded; muting is the only guaranteed
 * prevention, and warning without muting would be theatre.
 *
 * <p>Threading: {@link #start} creates the file and starts the drain thread;
 * {@link #stop} joins it, patches the WAV header, and returns the file.
 * No-op and idempotent.
 */
public class VoiceoverRecorder {

    private static final String TAG = "VoiceoverRecorder";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHANNEL_COUNT = 1;
    private static final int BITS_PER_SAMPLE = 16;

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;
    private File outputFile;
    private FileOutputStream outputStream;
    private long totalSamples = 0; // mono samples written
    private long startPlayheadMs = 0;

    public synchronized boolean start(@NonNull Context context, long playheadMs) {
        if (isRecording) return true;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            FLog.w(TAG, "RECORD_AUDIO not granted");
            return false;
        }
        File audioDir = new File(context.getFilesDir(), "faditor_audio");
        if (!audioDir.exists() && !audioDir.mkdirs()) {
            FLog.e(TAG, "Failed to create faditor_audio dir");
            return false;
        }
        long ts = System.currentTimeMillis();
        outputFile = new File(audioDir, "voiceover_" + ts + ".wav");
        startPlayheadMs = playheadMs;
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            FLog.e(TAG, "Invalid buffer size");
            return false;
        }
        bufferSize = Math.max(bufferSize, SAMPLE_RATE / 10);
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                FLog.e(TAG, "AudioRecord not initialized");
                release();
                return false;
            }
            outputStream = new FileOutputStream(outputFile);
            // placeholder header (44 bytes, to be patched on stop)
            writeWavHeaderPlaceholder(outputStream);
            audioRecord.startRecording();
            isRecording = true;
            totalSamples = 0;
            final int localBufferSize = bufferSize;
            recordingThread = new Thread(() -> {
                // Buffer in shorts, write as bytes (little-endian PCM)
                short[] shortBuf = new short[localBufferSize / 2];
                byte[] byteBuf = new byte[shortBuf.length * 2];
                while (isRecording) {
                    int read = audioRecord.read(shortBuf, 0, shortBuf.length);
                    if (read > 0) {
                        // Convert shorts to little-endian bytes
                        for (int i = 0; i < read; i++) {
                            byteBuf[i * 2] = (byte) (shortBuf[i] & 0xFF);
                            byteBuf[i * 2 + 1] = (byte) ((shortBuf[i] >> 8) & 0xFF);
                        }
                        try {
                            synchronized (VoiceoverRecorder.this) {
                                if (outputStream != null) {
                                    outputStream.write(byteBuf, 0, read * 2);
                                }
                            }
                            totalSamples += read;
                        } catch (IOException e) {
                            FLog.e(TAG, "Write failed", e);
                            break;
                        }
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION
                            || read == AudioRecord.ERROR_BAD_VALUE) {
                        FLog.e(TAG, "AudioRecord read error: " + read);
                        break;
                    }
                }
            }, "VoiceoverRecorderThread");
            recordingThread.start();
            FLog.d(TAG, "Voiceover started at playhead " + playheadMs + "ms -> " + outputFile.getName()
                    + " buffer=" + localBufferSize);
            return true;
        } catch (Exception e) {
            FLog.e(TAG, "Error starting VoiceoverRecorder", e);
            release();
            return false;
        }
    }

    /**
     * Stop recording, patch the WAV header, and return the file.
     * Returns null if nothing was recorded or the file is too short (<100ms).
     */
    @Nullable
    public synchronized File stop() {
        if (!isRecording) return null;
        isRecording = false;
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        // Close stream before patching header
        if (outputStream != null) {
            try {
                outputStream.flush();
                outputStream.close();
            } catch (IOException e) {
                FLog.e(TAG, "Error closing stream", e);
            }
            outputStream = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping AudioRecord", e);
            }
            try {
                audioRecord.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }
        if (outputFile == null || totalSamples == 0) {
            if (outputFile != null) outputFile.delete();
            outputFile = null;
            return null;
        }
        long durationMs = (totalSamples * 1000) / SAMPLE_RATE;
        if (durationMs < 100) { // too short — discard
            outputFile.delete();
            outputFile = null;
            FLog.w(TAG, "Voiceover too short (" + durationMs + "ms), discarded");
            return null;
        }
        try {
            patchWavHeader(outputFile, totalSamples);
        } catch (IOException e) {
            FLog.e(TAG, "Failed to patch WAV header", e);
            outputFile.delete();
            outputFile = null;
            return null;
        }
        File result = outputFile;
        outputFile = null;
        FLog.i(TAG, "Voiceover stopped: " + result.getName() + " " + durationMs + "ms");
        return result;
    }

    public synchronized boolean isRecording() {
        return isRecording;
    }

    public synchronized long getStartPlayheadMs() {
        return startPlayheadMs;
    }

    public synchronized long getDurationMs() {
        return (totalSamples * 1000) / SAMPLE_RATE;
    }

    /** Whether playback should be audible while recording (headphones connected). */
    public static boolean shouldKeepPlaybackAudible(@NonNull Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        // Wired headset or bluetooth A2DP/SCO
        if (am.isWiredHeadsetOn()) return true;
        if (am.isBluetoothA2dpOn()) return true;
        // isBluetoothScoOn is deprecated but still useful for headset profile
        try {
            if (am.isBluetoothScoOn()) return true;
        } catch (Exception ignore) {}
        return false;
    }

    private void release() {
        isRecording = false;
        if (outputStream != null) {
            try { outputStream.close(); } catch (Exception ignored) {}
            outputStream = null;
        }
        if (audioRecord != null) {
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (outputFile != null && outputFile.exists() && totalSamples == 0) {
            outputFile.delete();
        }
    }

    private static void writeWavHeaderPlaceholder(@NonNull FileOutputStream out) throws IOException {
        // 44-byte placeholder, patched later
        out.write(new byte[44]);
    }

    private static void patchWavHeader(@NonNull File wavFile, long totalSamples) throws IOException {
        long byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8;
        long dataSize = totalSamples * CHANNEL_COUNT * BITS_PER_SAMPLE / 8;
        long chunkSize = 36 + dataSize;
        RandomAccessFile raf = new RandomAccessFile(wavFile, "rw");
        try {
            raf.seek(0);
            raf.writeBytes("RIFF");
            writeLeInt(raf, (int) chunkSize);
            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");
            writeLeInt(raf, 16); // subchunk1 size
            writeLeShort(raf, (short) 1); // PCM
            writeLeShort(raf, (short) CHANNEL_COUNT);
            writeLeInt(raf, SAMPLE_RATE);
            writeLeInt(raf, (int) byteRate);
            writeLeShort(raf, (short) (CHANNEL_COUNT * BITS_PER_SAMPLE / 8)); // block align
            writeLeShort(raf, (short) BITS_PER_SAMPLE);
            raf.writeBytes("data");
            writeLeInt(raf, (int) dataSize);
        } finally {
            raf.close();
        }
    }

    private static void writeLeInt(@NonNull RandomAccessFile raf, int v) throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
        raf.write((v >> 16) & 0xFF);
        raf.write((v >> 24) & 0xFF);
    }

    private static void writeLeShort(@NonNull RandomAccessFile raf, short v) throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
    }
}
