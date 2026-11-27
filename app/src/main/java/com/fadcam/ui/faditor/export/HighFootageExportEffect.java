package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.GlUtil;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.PassthroughShaderProgram;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.lane.LaneCompositor;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.flow.DecodedFlowWork;
import com.fadcam.ui.faditor.motion.flow.FlowCache;
import com.fadcam.ui.faditor.motion.flow.FlowPairTexturesGl;
import com.fadcam.ui.faditor.motion.flow.HighFootageGatherGl;
import com.fadcam.ui.faditor.motion.flow.IndexedFootageMedia;
import com.fadcam.ui.faditor.motion.flow.PreviewIndexedFlow;
import com.fadcam.ui.faditor.motion.flow.VideoPresentationIndex;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Source-space High gather, before timeline retiming, crop, grade and compositing. */
record HighFootageExportEffect(Uri uri, long startUs, long endUs, double speed,
                               MotionShutter.Controls shutter,
                               com.fadcam.ui.faditor.motion.ShutterKeys keys) implements GlEffect {
    private static final String TAG = "HighFootageExport";

    @Override public GlShaderProgram toGlShaderProgram(Context context, boolean hdr) {
        return new PassthroughShaderProgram();
    }

    GlEffect forStream(int type, Format format, long offsetUs) {
        if (type != VideoFrameProcessor.INPUT_TYPE_SURFACE
                || Float.compare(format.pixelWidthHeightRatio, 1f) != 0) return this;
        return (context, hdr) -> hdr ? toGlShaderProgram(context, true)
                : new Program(context.getApplicationContext(), offsetUs);
    }

    private record Staged(GlTextureInfo texture, long graphUs, long sourceUs) {}
    private record Analysed(FlowCache.Pair before, FlowCache.Pair after) {}

    private final class Program implements GlShaderProgram {
        private final Context context;
        private final long offsetUs;
        private final MotionBreaks fences;
        private final LaneCompositor copy = new LaneCompositor();
        private final FlowPairTexturesGl previousFlow = new FlowPairTexturesGl();
        private final FlowPairTexturesGl nextFlow = new FlowPairTexturesGl();
        private final HighFootageGatherGl gather;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "Studio export footage");
            thread.setDaemon(true);
            return thread;
        });

        private InputListener input = new InputListener() {};
        private OutputListener output = new OutputListener() {};
        private Executor errorExecutor = Runnable::run;
        private ErrorListener errors = problem -> FLog.e(TAG, "Export footage program failed", problem);
        private GlTextureInfo destination;
        private Staged previous, centre;
        private boolean outputInUse, ending, ended, failed, reportedGather;
        private volatile boolean released;
        // Only the analysis worker touches these objects.
        private FlowCache cache;
        private PreviewIndexedFlow.Prepared media;
        private boolean attempted;
        private FlowCache.Pair last;

        Program(Context context, long offsetUs) {
            this.context = context;
            this.offsetUs = offsetUs;
            gather = new HighFootageGatherGl(context.getAssets());
            fences = new MotionBreaks.Builder().add(0)
                    .add(Math.round((endUs - startUs) / speed)).build();
        }

        @Override public void setInputListener(InputListener listener) {
            input = listener;
            input.onReadyToAcceptInputFrame();
        }

        @Override public void setOutputListener(OutputListener listener) { output = listener; }

        @Override public void setErrorListener(Executor executor, ErrorListener listener) {
            errorExecutor = executor;
            errors = listener;
        }

        @Override public void queueInputFrame(androidx.media3.common.GlObjectsProvider provider,
                GlTextureInfo texture, long timeUs) {
            try {
                if (destination == null) {
                    copy.configure(texture.width, texture.height);
                    destination = allocate(texture.width, texture.height);
                } else if (texture.width != destination.width || texture.height != destination.height) {
                    throw new IllegalStateException("Footage stream dimensions changed without registration");
                }
                long sourceUs = Math.addExact(startUs, Math.subtractExact(timeUs, offsetUs));
                Staged next = new Staged(texture, timeUs, sourceUs);
                Staged rendered = centre;
                if (rendered != null) render(next);
                GlTextureInfo donor = previous == null ? allocate(texture.width, texture.height) : previous.texture();
                try {
                    copy.drawCopy(texture.texId, donor.fboId);
                } catch (GlUtil.GlException | RuntimeException problem) {
                    if (previous == null) donor.release();
                    throw problem;
                }
                previous = centre;
                centre = new Staged(donor, timeUs, sourceUs);
                outputInUse = rendered != null;
                input.onInputFrameProcessed(texture);
                if (rendered == null) input.onReadyToAcceptInputFrame();
                else output.onOutputFrameAvailable(destination, rendered.graphUs());
            } catch (GlUtil.GlException | RuntimeException problem) {
                report(problem);
            }
        }

        private void render(Staged next) throws GlUtil.GlException {
            if (!failed && (previous != null || next != null)) {
                try {
                    if (renderHigh(next)) return;
                } catch (ExecutionException | IOException | GlUtil.GlException | RuntimeException problem) {
                    failed = true;
                    FLog.w(TAG, "Indexed export flow unavailable; using the sharp frame", problem);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failed = true;
                }
            }
            copy.drawCopy(centre.texture().texId, destination.fboId);
        }

        private boolean renderHigh(Staged next)
                throws IOException, GlUtil.GlException, ExecutionException, InterruptedException {
            Staged prior = previous;
            Staged current = centre;
            long periodUs = prior == null || current.sourceUs() <= prior.sourceUs()
                    ? 0 : Math.round((current.sourceUs() - prior.sourceUs()) / speed);
            if (periodUs <= 0 || current.sourceUs() - prior.sourceUs() > IndexedFootageMedia.MAX_GAP_US) {
                return false;
            }
            long editorUs = Math.round((current.sourceUs() - startUs) / speed);
            int width = destination.width, height = destination.height;
            Analysed fields = worker.submit(() -> analyse(prior, current, next, width, height)).get();
            if (fields.before() != null && fields.before().crossesCut()) {
                previous = null;
                prior.texture().release();
                previousFlow.set(null);
                return false;
            }
            MotionBreaks sourceCuts = fields.after() != null && fields.after().crossesCut()
                    ? new MotionBreaks.Builder().add(Math.round((next.sourceUs() - startUs) / speed)).build()
                    : MotionBreaks.none();
            var taps = keys.at(editorUs / 1000L, shutter).schedule(editorUs, periodUs, HighFootageGatherGl.MAX_TAPS,
                    fences, sourceCuts);
            if (taps == null) return false;
            var before = previousFlow.set(fields.before());
            var after = nextFlow.set(fields.after());
            if (before == null && after == null) return false;
            var dimensions = before != null ? fields.before().forward : fields.after().forward;
            var frame = new HighFootageGatherGl.Frame(prior.texture().texId, current.texture().texId,
                    next == null ? 0 : next.texture().texId, before, after, speed, 0, 0, 1, 1);
            boolean drawn = gather.draw(frame, destination.fboId, width, height,
                    dimensions.width, dimensions.height, editorUs, taps);
            if (drawn && !reportedGather) {
                reportedGather = true;
                FLog.i(TAG, "HIGH_FOOTAGE_EXPORT ptsUs=" + current.sourceUs());
            }
            return drawn;
        }

        private Analysed analyse(Staged prior, Staged current, Staged next, int width, int height)
                throws Exception {
            if (!attempted) {
                attempted = true;
                media = IndexedFootageMedia.open(context, uri, () -> released);
                if (media != null) cache = IndexedFootageMedia.cache(context);
            }
            if (media == null) return new Analysed(null, null);
            var geometry = media.geometry();
            int sourceWidth = geometry.right() - geometry.left();
            int sourceHeight = geometry.bottom() - geometry.top();
            boolean rotated = Math.floorMod(geometry.orientation(), 180) == 90;
            if (width != (rotated ? sourceHeight : sourceWidth)
                    || height != (rotated ? sourceWidth : sourceHeight)) return new Analysed(null, null);
            var before = pair(prior.sourceUs(), current.sourceUs());
            var after = next == null ? null : pair(current.sourceUs(), next.sourceUs());
            return new Analysed(before, after);
        }

        private FlowCache.Pair pair(long from, long to) throws Exception {
            VideoPresentationIndex.Pair adjacent = media.index().next(from, startUs, endUs,
                    IndexedFootageMedia.MAX_GAP_US);
            if (adjacent == null || adjacent.toPtsUs() != to) return null;
            if (last != null && last.forward.fromPtsUs == from && last.forward.toPtsUs == to) return last;
            var key = media.geometry().key(adjacent);
            var result = cache.get(key);
            if (result == null) {
                result = new DecodedFlowWork(key, media.decoder()).analyze(() -> released);
                if (result != null && !released) {
                    try { cache.put(key, result); } catch (IOException ignored) {
                        // The render can use the computed pair if the cache is unwritable.
                    }
                }
            }
            last = result;
            return result;
        }

        @Override public void releaseOutputFrame(GlTextureInfo texture) {
            if (texture != destination || !outputInUse) return;
            outputInUse = false;
            if (ending) emitEnd();
            else input.onReadyToAcceptInputFrame();
        }

        @Override public void signalEndOfCurrentInputStream() {
            ending = true;
            if (!outputInUse) emitEnd();
        }

        private void emitEnd() {
            if (ended) return;
            ended = true;
            try {
                if (centre != null) {
                    render(null);
                    outputInUse = true;
                    output.onOutputFrameAvailable(destination, centre.graphUs());
                }
                output.onCurrentOutputStreamEnded();
            } catch (GlUtil.GlException | RuntimeException problem) {
                report(problem);
            }
        }

        @Override public void flush() {
            try {
                releaseStaged();
                outputInUse = ending = ended = false;
                input.onFlush();
                input.onReadyToAcceptInputFrame();
            } catch (GlUtil.GlException problem) {
                report(problem);
            }
        }

        private void releaseStaged() throws GlUtil.GlException {
            if (previous != null) previous.texture().release();
            if (centre != null) centre.texture().release();
            previous = centre = null;
        }

        @Override public void release() throws VideoFrameProcessingException {
            if (released) return;
            released = true;
            worker.execute(() -> {
                if (media != null) {
                    try { media.close(); } catch (Exception failure) {
                        FLog.w(TAG, "Could not close footage analysis decoder", failure);
                    }
                }
            });
            worker.shutdown();
            previousFlow.release();
            nextFlow.release();
            try {
                releaseStaged();
                if (destination != null) destination.release();
                gather.release();
                copy.release();
            } catch (GlUtil.GlException failure) {
                throw VideoFrameProcessingException.from(failure);
            }
        }

        private void report(Exception problem) {
            errorExecutor.execute(() -> errors.onError(VideoFrameProcessingException.from(problem)));
        }
    }

    private static GlTextureInfo allocate(int width, int height) throws GlUtil.GlException {
        int texture = GlUtil.createTexture(width, height, false);
        try {
            return new GlTextureInfo(texture, GlUtil.createFboForTexture(texture),
                    androidx.media3.common.C.INDEX_UNSET, width, height);
        } catch (GlUtil.GlException failure) {
            GlUtil.deleteTexture(texture);
            throw failure;
        }
    }
}
