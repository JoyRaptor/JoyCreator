package com.fadcam.ui.faditor.export;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.Presentation;

import com.fadcam.ui.faditor.compositor.RecordedAvatarMotion;

import java.util.ArrayList;
import java.util.List;

/** Bind the motion effects to the decoder's registered stream before configuration. */
final class FootageFrameProcessor implements VideoFrameProcessor {
    record AvatarCanvas(Presentation presentation) implements MatrixTransformation {
        @Override public Size configure(int width, int height) {
            return presentation.configure(width, height);
        }

        @Override public Matrix getMatrix(long timeUs) {
            return presentation.getMatrix(timeUs);
        }

        @Override public boolean isNoOp(int width, int height) {
            return presentation.isNoOp(width, height);
        }
    }

    private final VideoFrameProcessor delegate;

    private FootageFrameProcessor(VideoFrameProcessor delegate) {
        this.delegate = delegate;
    }

    static Factory factory(DefaultVideoFrameProcessor.Factory delegate) {
        return (context, debug, color, automatic, executor, listener) ->
                new FootageFrameProcessor(delegate.create(context, debug, color,
                        automatic, executor, listener));
    }

    @Override public void registerInputStream(int type, Format format, List<Effect> effects,
                                              long offsetUs) {
        List<Effect> bound = new ArrayList<>(effects.size());
        for (Effect effect : effects) {
            if (effect instanceof HighFootageExportEffect footage) {
                bound.add(footage.forStream(type, format, offsetUs));
            } else if (effect instanceof AvatarCanvas canvas) {
                Presentation presentation = canvas.presentation();
                boolean identity = bound.isEmpty() && type == INPUT_TYPE_SURFACE
                        && RecordedAvatarMotion.canStageNativeVideo(format)
                        && presentation.isNoOp(format.width, format.height);
                if (!identity) bound.add(presentation);
            } else {
                bound.add(effect);
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
