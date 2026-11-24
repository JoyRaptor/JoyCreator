package com.fadcam.ui.faditor.export;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.RigidTitleRaster;
import com.fadcam.ui.faditor.compositor.lane.LaneCompositor;
import com.fadcam.ui.faditor.compositor.lane.LanePlan;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxRegistry;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.BlendModes;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionFrameCadence;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/** Media3 host for the ordered compositor. The spine has already been placed by the clip chain. */
final class LaneCompositeGlEffect implements GlEffect {
    @FunctionalInterface
    private interface ProgramFactory {
        BaseGlShaderProgram create(Context context) throws VideoFrameProcessingException;
    }

    private record Binding(String id, ProgramFactory program, LongPredicate activeAt) {}
    private record ExactScope(int boundary, AdjustmentLayer layer, MotionShutter.Controls controls,
                              MotionBreaks fences, long firstUs, long endUs) {
        boolean appliesAt(long presentationUs, long editorMs) {
            return presentationUs >= firstUs && presentationUs < endUs && layer.activeAt(editorMs);
        }
    }

    record Candidate(@Nullable LaneCompositeGlEffect effect, @Nullable String fallbackReason) {}

    private final List<Binding> bindings;
    private final long editorOffsetMs;
    @Nullable private final ExactScope exactScope;

    private LaneCompositeGlEffect(List<Binding> bindings, long editorOffsetMs, @Nullable ExactScope exactScope) {
        this.bindings = bindings;
        this.editorOffsetMs = editorOffsetMs;
        this.exactScope = exactScope;
    }

    static Candidate create(Context context, LanePlan plan, long totalDurationMs, long editorOffsetMs,
                            long pieceStartMs, long motionDurationMs, int frameHeight,
                            List<SpriteSheet> sheets) {
        var bindings = new ArrayList<Binding>();
        ExactScope exactScope = null;
        boolean replayablePrefix = true;
        boolean hasMovingProducer = false;
        long firstUs = pieceStartMs * 1000L;
        long endUs = firstUs + motionDurationMs * 1000L;
        var fences = new MotionBreaks.Builder().add(firstUs).add(endUs);
        for (var entry : plan.entries()) {
            switch (entry.kind) {
                case IMAGE -> {
                    TextOverlayItem picture = entry.item.getTextOverlay();
                    if (!LanePlan.supportsOrderedPicture(picture)) {
                        return new Candidate(null, "picture " + entry.id);
                    }
                    var effect = new GlImageOverlayEffect(context, List.of(picture), totalDurationMs,
                            editorOffsetMs, null, pieceStartMs, motionDurationMs, true);
                    bindings.add(new Binding(entry.kind.name() + ":" + entry.id, c -> effect.toGlShaderProgram(c, false),
                            picture::isVisibleAt));
                    replayablePrefix &= addExactFences(fences, picture, editorOffsetMs);
                    hasMovingProducer |= picture.isAnimated();
                }
                case TEXT -> {
                    var title = entry.item.getTextOverlay();
                    if (title == null || !RigidTitleRaster.supportsPlain(title)
                            || !RigidTitleRaster.measure(title, title.getStartMs(), totalDurationMs,
                                    frameHeight).fits()) {
                        return new Candidate(null, "title " + entry.id);
                    }
                    var effect = new GlImageOverlayEffect(context, List.of(title), totalDurationMs,
                            editorOffsetMs, null, pieceStartMs, motionDurationMs, true);
                    bindings.add(new Binding(entry.kind.name() + ":" + entry.id, c -> effect.toGlShaderProgram(c, false),
                            title::isVisibleAt));
                    replayablePrefix &= addExactFences(fences, title, editorOffsetMs);
                    hasMovingProducer |= title.isAnimated();
                }
                case SPRITE -> {
                    replayablePrefix = false;
                    var sprite = entry.item.getSprite();
                    if (!LanePlan.supportsOrderedSprite(sprite)) {
                        return new Candidate(null, "sprite " + entry.id);
                    }
                    var atlas = sheets.stream().filter(sheet -> sheet.getId().equals(sprite.getSheetId()))
                            .findFirst().orElse(null);
                    if (atlas == null || atlas.isSequence()
                            || atlas.getCellTransforms().values().stream().anyMatch(transform -> !transform.isIdentity())) {
                        return new Candidate(null, "sprite cell source " + entry.id);
                    }
                    var effect = new SpriteBlendGlEffect(context, sprite, List.of(atlas), List.of(),
                            totalDurationMs, editorOffsetMs, pieceStartMs, motionDurationMs, true);
                    bindings.add(new Binding(entry.kind.name() + ":" + entry.id, c -> effect.toGlShaderProgram(c, false),
                            sprite::isVisibleAt));
                }
                case PIP -> {
                    replayablePrefix = false;
                    var pip = entry.item.getClip();
                    if (pip == null || pip.isImageClip() || PipFrameStats.isMasked(pip)
                            || (pip.getCompositing() != null && pip.getCompositing().mattePeerId != null)) {
                        return new Candidate(null, "PiP " + entry.id);
                    }
                    long start = pip.getOverlayStartMs();
                    long end = start + Math.max(0L, pip.getTrimmedDurationMs());
                    var effect = new BlendModeGlEffect(context, pip, null, editorOffsetMs,
                            pieceStartMs, motionDurationMs);
                    bindings.add(new Binding(entry.kind.name() + ":" + entry.id, c -> effect.toGlShaderProgram(c, false),
                            editorMs -> editorMs >= start && editorMs <= end));
                }
                case ADJUSTMENT -> {
                    var adjustment = entry.item.getAdjustment();
                    if (adjustment == null || adjustment.getFx().hearsMusic()) {
                        return new Candidate(null, "adjustment " + entry.id);
                    }
                    var cards = adjustment.getFx().active();
                    if (cards.size() == 1 && FxRegistry.LANE_MOTION_ID.equals(cards.get(0).effectId)) {
                        if (exactScope == null && motionDurationMs > 0 && replayablePrefix && hasMovingProducer
                                && supportsExactLayer(adjustment, cards.get(0))) {
                            long start = (adjustment.getStartMs() - editorOffsetMs) * 1000L;
                            fences.add(start);
                            if (adjustment.getDurationMs() > 0) {
                                fences.add((adjustment.getEndMs() - editorOffsetMs) * 1000L);
                            }
                            exactScope = new ExactScope(bindings.size(), adjustment, controls(cards.get(0)),
                                    fences.build(), firstUs, endUs);
                        }
                        replayablePrefix = false;
                        continue;
                    }
                    // Adjustment stacks can hold history or use effect-local clock state.
                    if (adjustment.rendersAnything()) {
                        replayablePrefix = false;
                        var effect = new AdjustmentLayerGlEffect(context, adjustment, editorOffsetMs);
                        bindings.add(new Binding(entry.kind.name() + ":" + entry.id, c -> effect.toGlShaderProgram(c, false),
                                adjustment::activeAt));
                    }
                }
                default -> {
                    return new Candidate(null, "unported lane kind " + entry.kind);
                }
            }
        }
        return new Candidate(bindings.isEmpty() ? null
                : new LaneCompositeGlEffect(bindings, editorOffsetMs, exactScope), null);
    }

    private static boolean addExactFences(MotionBreaks.Builder fences, TextOverlayItem item, long editorOffsetMs) {
        if (item.isRigidMotionBlurEnabled()) return false;
        long startUs = (item.getStartMs() - editorOffsetMs) * 1000L;
        fences.add(startUs);
        if (item.getEndMs() != Long.MAX_VALUE) {
            fences.add((item.getEndMs() + 1L - editorOffsetMs) * 1000L);
        }
        for (var track : item.getKeyframes().tracks()) fences.addSteps(track, startUs);
        return true;
    }

    private static boolean supportsExactLayer(AdjustmentLayer layer, FxInstance card) {
        return layer.getCompositing().isEmpty() && BlendModes.NORMAL.equals(layer.getBlendMode())
                && (layer.getTransform() == null || layer.getTransform().isEmpty())
                && !layer.getFx().isAnimated() && card.opacity == 1f
                && BlendModes.NORMAL.equals(card.blendMode);
    }

    private static MotionShutter.Controls controls(FxInstance card) {
        var def = FxRegistry.require(FxRegistry.LANE_MOTION_ID);
        return new MotionShutter.Controls(Math.round(card.getScalar(def.param("angle"))),
                MotionShutter.Timing.values()[Math.round(card.getScalar(def.param("timing")))],
                Math.round(card.getScalar(def.param("phase"))), Math.round(card.getScalar(def.param("mix"))));
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context context, boolean hdr)
            throws VideoFrameProcessingException {
        if (hdr) throw new VideoFrameProcessingException("HDR lane compositor is unavailable");
        var programs = new ArrayList<BaseGlShaderProgram>();
        try {
            for (var binding : bindings) {
                programs.add(binding.program().create(context));
            }
        } catch (VideoFrameProcessingException error) {
            releasePrograms(programs, error);
            throw error;
        }
        return new Program(bindings, programs, editorOffsetMs, exactScope);
    }

    private static VideoFrameProcessingException releasePrograms(List<BaseGlShaderProgram> programs,
                                                                  VideoFrameProcessingException prior) {
        for (var program : programs) {
            try {
                program.release();
            } catch (VideoFrameProcessingException error) {
                if (prior == null) prior = error;
                else prior.addSuppressed(error);
            }
        }
        return prior;
    }

    private static final class Program extends BaseGlShaderProgram {
        private final List<Binding> bindings;
        private final List<BaseGlShaderProgram> programs;
        private final List<BaseGlShaderProgram> active = new ArrayList<>();
        private final LaneCompositor compositor = new LaneCompositor();
        private final long editorOffsetMs;
        @Nullable private final ExactScope exactScope;
        @Nullable private final LaneCompositor exactPrefix;
        private final List<BaseGlShaderProgram> activePrefix = new ArrayList<>();
        private final MotionFrameCadence cadence = new MotionFrameCadence();
        private boolean loggedExact;

        Program(List<Binding> bindings, List<BaseGlShaderProgram> programs, long editorOffsetMs,
                @Nullable ExactScope exactScope) {
            super(false, 2);
            this.bindings = bindings;
            this.programs = programs;
            this.editorOffsetMs = editorOffsetMs;
            this.exactScope = exactScope;
            exactPrefix = exactScope == null ? null : new LaneCompositor();
        }

        @Override
        public boolean shouldClearTextureBuffer() { return false; }

        @NonNull
        @Override
        public Size configure(int width, int height) throws VideoFrameProcessingException {
            try {
                compositor.configure(width, height);
                if (exactPrefix != null) exactPrefix.configure(width, height);
                for (var program : programs) {
                    Size output = program.configure(width, height);
                    if (output.getWidth() != width || output.getHeight() != height) {
                        throw new VideoFrameProcessingException("Lane producer changed canvas size");
                    }
                }
                FLog.i("LaneComposite", "LANE_EXPORT " + width + "x" + height
                        + " " + bindings.stream().map(Binding::id)
                                .collect(java.util.stream.Collectors.joining(",")));
                return new Size(width, height);
            } catch (GlUtil.GlException error) {
                throw new VideoFrameProcessingException(error);
            }
        }

        @Override
        public void drawFrame(int inputTexture, long presentationTimeUs)
                throws VideoFrameProcessingException {
            long editorMs = presentationTimeUs / 1000L + editorOffsetMs;
            long periodUs = exactScope == null ? 0L : cadence.observe(presentationTimeUs);
            boolean expose = periodUs > 0 && exactScope.appliesAt(presentationTimeUs, editorMs);
            int first = expose ? exactScope.boundary() : 0;
            active.clear();
            for (int i = first; i < bindings.size(); i++) {
                if (bindings.get(i).activeAt().test(editorMs)) active.add(programs.get(i));
            }
            try {
                int target = PipChainGl.boundFbo();
                if (expose) {
                    var schedule = exactScope.controls().schedule(presentationTimeUs, periodUs, 32,
                            exactScope.fences(), MotionBreaks.none());
                    compositor.drawScopeAt(inputTexture, target, active.size() + 1,
                            presentationTimeUs, (i, prefix, time) -> {
                                if (i == 0) drawPrefixExposure(inputTexture, schedule);
                                else active.get(i - 1).drawFrame(prefix, time);
                            });
                } else {
                    compositor.drawScopeAt(inputTexture, target, active.size(),
                            presentationTimeUs, (i, prefix, time) -> active.get(i).drawFrame(prefix, time));
                }
            } catch (GlUtil.GlException error) {
                throw new VideoFrameProcessingException(error);
            }
        }

        private void drawPrefixExposure(int inputTexture, MotionShutter.Schedule schedule)
                throws VideoFrameProcessingException {
            if (!loggedExact && schedule.timesUs().length > 1) {
                FLog.i("LaneComposite", "LANE_EXACT " + schedule.timesUs().length + " taps");
                loggedExact = true;
            }
            try {
                exactPrefix.drawExposure(PipChainGl.boundFbo(), schedule, (target, timeUs) -> {
                    activePrefix.clear();
                    long editorMs = Math.floorDiv(timeUs, 1000L) + editorOffsetMs;
                    for (int i = 0; i < exactScope.boundary(); i++) {
                        if (bindings.get(i).activeAt().test(editorMs)) activePrefix.add(programs.get(i));
                    }
                    exactPrefix.drawScopeAt(inputTexture, target, activePrefix.size(), timeUs,
                            (i, prefix, time) -> activePrefix.get(i).drawFrame(prefix, time));
                });
            } catch (GlUtil.GlException error) {
                throw new VideoFrameProcessingException(error);
            }
        }

        @Override
        public void flush() {
            cadence.reset();
            for (var program : programs) program.flush();
            super.flush();
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            VideoFrameProcessingException failure = null;
            try {
                super.release();
            } catch (VideoFrameProcessingException error) {
                failure = error;
            }
            failure = releasePrograms(programs, failure);
            try {
                try {
                    compositor.release();
                } finally {
                    if (exactPrefix != null) exactPrefix.release();
                }
            } catch (GlUtil.GlException error) {
                if (failure == null) failure = new VideoFrameProcessingException(error);
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
