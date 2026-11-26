package com.fadcam.ui.faditor.export;

import android.graphics.Bitmap;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.effect.DefaultVideoFrameProcessor;

import java.util.ArrayList;
import java.util.List;

/** Pass the exact stream registration offset to the frame effect before configuration. */
final class FootageFrameProcessor implements VideoFrameProcessor {
    private final VideoFrameProcessor delegate;

    private FootageFrameProcessor(VideoFrameProcessor delegate) {
        this.delegate = delegate;
    }

    static Factory factory() {
        var delegate = new DefaultVideoFrameProcessor.Factory.Builder().build();
        return (context, debug, color, automatic, executor, listener) ->
                new FootageFrameProcessor(delegate.create(context, debug, color,
                        automatic, executor, listener));
    }

    @Override public void registerInputStream(int type, Format format, List<Effect> effects,
                                              long offsetUs) {
        List<Effect> bound = effects;
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i) instanceof HighFootageExportEffect footage) {
                if (bound == effects) bound = new ArrayList<>(effects);
                bound.set(i, footage.forStream(type, format, offsetUs));
            }
        }
        delegate.registerInputStream(type, format, bound, offsetUs);
    }

    @Override public boolean queueInputBitmap(Bitmap bitmap, TimestampIterator timestamps) {
        return delegate.queueInputBitmap(bitmap, timestamps);
    }

    @Override public boolean queueInputTexture(int textureId, long timeUs) {
        return delegate.queueInputTexture(textureId, timeUs);
    }

    @Override public void setOnInputFrameProcessedListener(OnInputFrameProcessedListener listener) {
        delegate.setOnInputFrameProcessedListener(listener);
    }

    @Override public void setOnInputSurfaceReadyListener(Runnable listener) {
        delegate.setOnInputSurfaceReadyListener(listener);
    }

    @Override public Surface getInputSurface() { return delegate.getInputSurface(); }
    @Override public void redraw() { delegate.redraw(); }
    @Override public boolean registerInputFrame() { return delegate.registerInputFrame(); }
    @Override public int getPendingInputFrameCount() { return delegate.getPendingInputFrameCount(); }
    @Override public void setOutputSurfaceInfo(@Nullable SurfaceInfo surfaceInfo) {
        delegate.setOutputSurfaceInfo(surfaceInfo);
    }
    @Override public void renderOutputFrame(long renderTimeNs) { delegate.renderOutputFrame(renderTimeNs); }
    @Override public void signalEndOfInput() { delegate.signalEndOfInput(); }
    @Override public void flush() { delegate.flush(); }
    @Override public void release() { delegate.release(); }
}
