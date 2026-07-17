package com.fadcam.fadrec.encoding;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.media.FragmentedMp4MuxerWrapper;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Encodes the RAW webcam feed into a second, independent file
 * (<code>&lt;screenfile&gt;_webcam.mp4</code>) for the dual-stream recording
 * feature. Mirrors {@link ScreenRecordingPipeline}'s encoder/muxer shape:
 *
 * <ul>
 *   <li><b>Video:</b> a {@code video/avc} {@link MediaCodec} fed by an input
 *       {@link Surface}. That surface is added as a SECOND capture target on
 *       {@code FloatingWebcamService}'s existing camera session (spec
 *       architecture map) — the camera writes frames straight into the encoder,
 *       no GL/watermark pass (this is the raw feed, by design).</li>
 *   <li><b>Audio:</b> an AAC {@link MediaCodec} fed by the SAME PCM buffer the
 *       screen pipeline records, teed in via {@link #queueAudioData} (Decision 4).
 *       There is no {@code AudioRecord} here — the mic is owned by the screen
 *       pipeline.</li>
 *   <li><b>Muxer:</b> {@link FragmentedMp4MuxerWrapper}, same wrapper as every
 *       other FadCam recording (keeps Faditor's fMP4 remux behavior consistent).</li>
 * </ul>
 *
 * <p>All presentation timestamps flow through a {@link RecordingClock.Stream}
 * obtained from the shared clock, so this file rides the exact same pause
 * timeline as the screen recording (spec Decision 3) — it starts at PTS 0 and
 * subtracts the same accumulated pause duration, giving matching effective
 * duration and segment boundaries.</p>
 *
 * <p><b>v1 limitation:</b> segment rollover (auto-splitting) is intentionally
 * NOT implemented for the webcam file — it writes one continuous file. If the
 * screen recording rolls over, the webcam file simply keeps going.</p>
 */
public class WebcamEncoderPipeline {

    private static final String TAG = "WebcamEncPipeline";
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final int VIDEO_IFRAME_INTERVAL = 2;

    private final int width;
    private final int height;
    private final int framerate;
    private final int bitrate;
    private final boolean enableAudio;
    private final int audioSampleRate;
    private final int orientationHint;
    private final String outputFilePath;
    private final FileDescriptor outputFd;

    // Shared clock (pause state) + this file's own timeline baseline.
    private final RecordingClock clock;
    private final RecordingClock.Stream stream;

    private MediaCodec videoEncoder;
    private Surface encoderInputSurface;
    private MediaCodec audioEncoder;
    private FragmentedMp4MuxerWrapper muxer;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean muxerStarted = false;

    private HandlerThread videoEncodingThread;
    private Handler videoEncodingHandler;
    private HandlerThread audioEncodingThread;
    private Handler audioEncodingHandler;

    private volatile boolean isRecording = false;
    private volatile boolean isStopped = false;

    private final Object encoderDrainLock = new Object();

    public static class Builder {
        private int width;
        private int height;
        private int framerate = Constants.DEFAULT_SCREEN_RECORDING_FPS;
        private int bitrate = 6_000_000;
        private boolean enableAudio = false;
        private int audioSampleRate = Constants.DEFAULT_AUDIO_SAMPLING_RATE;
        private int orientationHint = 0;
        private String outputFilePath;
        private FileDescriptor outputFd;
        private RecordingClock clock;

        public Builder setSize(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder setVideoConfig(int framerate, int bitrate) {
            this.framerate = framerate;
            this.bitrate = bitrate;
            return this;
        }

        public Builder setEnableAudio(boolean enable, int sampleRate) {
            this.enableAudio = enable;
            this.audioSampleRate = sampleRate;
            return this;
        }

        public Builder setOrientationHint(int degrees) {
            this.orientationHint = degrees;
            return this;
        }

        public Builder setOutputFile(String path) {
            this.outputFilePath = path;
            return this;
        }

        public Builder setOutputFileDescriptor(FileDescriptor fd) {
            this.outputFd = fd;
            return this;
        }

        /** The SHARED clock the screen pipeline drives; a secondary stream is derived from it. */
        public Builder setRecordingClock(RecordingClock clock) {
            this.clock = clock;
            return this;
        }

        public WebcamEncoderPipeline build() throws IOException {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Webcam dimensions must be positive");
            }
            if (clock == null) {
                throw new IllegalArgumentException("RecordingClock is required (shared with the screen pipeline)");
            }
            if (outputFilePath == null && outputFd == null) {
                throw new IllegalArgumentException("Output file path or descriptor required");
            }
            return new WebcamEncoderPipeline(this);
        }
    }

    private WebcamEncoderPipeline(Builder b) throws IOException {
        // Dimensions come straight from the camera's chosen preview size, which the
        // encoder input surface must match exactly (Camera2 validates target surface
        // sizes against the supported output list). Camera sizes are already even, so
        // no rounding here — rounding could push us off a supported camera size and
        // fail session configuration.
        this.width = b.width;
        this.height = b.height;
        this.framerate = b.framerate;
        this.bitrate = b.bitrate;
        this.enableAudio = b.enableAudio;
        this.audioSampleRate = b.audioSampleRate;
        this.orientationHint = b.orientationHint;
        this.outputFilePath = b.outputFilePath;
        this.outputFd = b.outputFd;
        this.clock = b.clock;
        this.stream = b.clock.newStream();
        initialize();
    }

    private void initialize() throws IOException {
        videoEncodingThread = new HandlerThread("WebcamVideoEncoding");
        videoEncodingThread.start();
        videoEncodingHandler = new Handler(videoEncodingThread.getLooper());

        if (enableAudio) {
            audioEncodingThread = new HandlerThread("WebcamAudioEncoding");
            audioEncodingThread.start();
            audioEncodingHandler = new Handler(audioEncodingThread.getLooper());
        }

        initializeMuxer();
        initializeVideoEncoder();
        if (enableAudio) {
            initializeAudioEncoder();
        }
        FLog.d(TAG, "WebcamEncoderPipeline initialized: " + width + "x" + height
                + "@" + framerate + "fps audio=" + enableAudio);
    }

    private void initializeMuxer() throws IOException {
        muxer = outputFd != null
                ? new FragmentedMp4MuxerWrapper(outputFd)
                : new FragmentedMp4MuxerWrapper(outputFilePath);
        if (orientationHint != 0) {
            muxer.setOrientationHint(orientationHint);
        }
    }

    private void initializeVideoEncoder() throws IOException {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(500_000, bitrate));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

            videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = videoEncoder.createInputSurface();
            FLog.d(TAG, "Webcam video encoder initialized: " + width + "x" + height);
        } catch (Exception e) {
            throw new IOException("Failed to initialize webcam video encoder", e);
        }
    }

    private void initializeAudioEncoder() throws IOException {
        try {
            MediaFormat audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, Constants.DEFAULT_AUDIO_BITRATE);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            FLog.d(TAG, "Webcam audio encoder initialized: AAC " + audioSampleRate + "Hz");
        } catch (Exception e) {
            throw new IOException("Failed to initialize webcam audio encoder", e);
        }
    }

    /** The encoder's input surface — add this as a second target on the camera session. */
    public Surface getInputSurface() {
        return encoderInputSurface;
    }

    public void startRecording() {
        if (isRecording) {
            return;
        }
        videoEncoder.start();
        if (enableAudio && audioEncoder != null) {
            audioEncoder.start();
        }
        // Anchor this stream's wall-clock baseline. Pause state stays owned by the
        // screen pipeline through the shared clock — this pipeline never mutates it.
        stream.start();
        isRecording = true;
        isStopped = false;
        startVideoEncodingLoop();
        if (enableAudio) {
            startAudioEncodingLoop();
        }
        FLog.d(TAG, "Webcam recording started");
    }

    private void startVideoEncodingLoop() {
        videoEncodingHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isStopped || !isRecording) {
                    return;
                }
                drainVideoEncoder(false);
                if (isRecording && !isStopped) {
                    videoEncodingHandler.postDelayed(this, 10);
                }
            }
        });
    }

    private void drainVideoEncoder(boolean endOfStream) {
        synchronized (encoderDrainLock) {
            if (videoEncoder == null) {
                return;
            }
            if (endOfStream) {
                try {
                    videoEncoder.signalEndOfInputStream();
                } catch (Exception e) {
                    FLog.w(TAG, "signalEndOfInputStream failed", e);
                }
            }
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (true) {
                int idx = videoEncoder.dequeueOutputBuffer(bufferInfo, 0);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrackIndex = muxer.addTrack(videoEncoder.getOutputFormat());
                    tryStartMuxer();
                } else if (idx >= 0) {
                    ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(idx);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size > 0 && muxerStarted && !clock.isPaused()) {
                        bufferInfo.presentationTimeUs = stream.videoPtsUs(bufferInfo.presentationTimeUs);
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                    }
                    videoEncoder.releaseOutputBuffer(idx, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    /**
     * Feeds a copy of the screen pipeline's PCM into the webcam audio encoder
     * (Decision 4). Called synchronously from the screen audio loop's tap; the
     * buffer is consumed before this returns.
     */
    public void queueAudioData(ByteBuffer pcm, int size) {
        if (!enableAudio || audioEncoder == null || !isRecording || isStopped) {
            return;
        }
        try {
            int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                inputBuffer.put(pcm);
                long ptsUs = stream.audioPtsUs();
                audioEncoder.queueInputBuffer(inputBufferIndex, 0, size, ptsUs, 0);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Webcam queueAudioData failed", e);
        }
    }

    private void startAudioEncodingLoop() {
        audioEncodingHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isStopped || !isRecording) {
                    return;
                }
                drainAudioEncoder(false);
                if (isRecording && !isStopped) {
                    audioEncodingHandler.postDelayed(this, 10);
                }
            }
        });
    }

    private void drainAudioEncoder(boolean endOfStream) {
        if (audioEncoder == null) {
            return;
        }
        if (endOfStream) {
            try {
                int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
                if (inputBufferIndex >= 0) {
                    audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (Exception e) {
                FLog.w(TAG, "Audio EOS queue failed", e);
            }
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int idx = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                tryStartMuxer();
            } else if (idx >= 0) {
                ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(idx);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size > 0 && muxerStarted && !clock.isPaused()) {
                    // Audio PTS was already computed at queue time; keep the encoder's value.
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo);
                }
                audioEncoder.releaseOutputBuffer(idx, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private void tryStartMuxer() {
        boolean videoReady = videoTrackIndex >= 0;
        boolean audioReady = !enableAudio || audioTrackIndex >= 0;
        if (!muxerStarted && videoReady && audioReady) {
            muxer.start();
            muxerStarted = true;
            FLog.d(TAG, "Webcam muxer started");
        }
    }

    public void stopRecording() {
        if (isStopped) {
            return;
        }
        FLog.d(TAG, "Stopping webcam recording");
        isStopped = true;
        isRecording = false;

        drainVideoEncoder(true);
        if (enableAudio && audioEncoder != null) {
            drainAudioEncoder(true);
        }
        release();
        FLog.d(TAG, "Webcam recording stopped");
    }

    public void release() {
        if (videoEncodingThread != null) {
            videoEncodingThread.quitSafely();
            videoEncodingThread = null;
        }
        if (audioEncodingThread != null) {
            audioEncodingThread.quitSafely();
            audioEncodingThread = null;
        }
        if (encoderInputSurface != null) {
            try {
                encoderInputSurface.release();
            } catch (Exception e) {
                FLog.w(TAG, "Error releasing webcam input surface", e);
            }
            encoderInputSurface = null;
        }
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception e) {
                FLog.w(TAG, "Error releasing webcam video encoder", e);
            }
            videoEncoder = null;
        }
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                audioEncoder.release();
            } catch (Exception e) {
                FLog.w(TAG, "Error releasing webcam audio encoder", e);
            }
            audioEncoder = null;
        }
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
            } catch (Exception e) {
                FLog.w(TAG, "Error releasing webcam muxer", e);
            }
            muxer = null;
        }
    }
}
