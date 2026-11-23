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
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

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
    record Candidate(@Nullable LaneCompositeGlEffect effect, @Nullable String fallbackReason) {}

    private final List<Binding> bindings;
    private final long editorOffsetMs;

    private LaneCompositeGlEffect(List<Binding> bindings, long editorOffsetMs) {
        this.bindings = bindings;
        this.editorOffsetMs = editorOffsetMs;
    }

    static Candidate create(Context context, LanePlan plan, long totalDurationMs, long editorOffsetMs,
                            long pieceStartMs, long motionDurationMs, int frameHeight,
                            List<SpriteSheet> sheets) {
        var bindings = new ArrayList<Binding>();
        for (var entry : plan.entries()) {
            switch (entry.kind) {
                case IMAGE -> {
                    TextOverlayItem picture = entry.item.getTextOverlay();
                    if (picture == null || picture.hasMesh() || picture.isLinked()
                            || picture.wantsGlExport()
                            || CaptionAnimator.parsePreset(picture.getTextAnimPreset())
                                    != CaptionAnimator.Preset.NONE) {
                        return new Candidate(null, "picture " + entry.id);
                    }
                    var effect = new GlImageOverlayEffect(context, List.of(picture), totalDurationMs,
                            editorOffsetMs, null, pieceStartMs, motionDurationMs);
                    bindings.add(new Binding(entry.id, c -> effect.toGlShaderProgram(c, false),
                            picture::isVisibleAt));
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
                    bindings.add(new Binding(entry.id, c -> effect.toGlShaderProgram(c, false),
                            title::isVisibleAt));
                }
                case SPRITE -> {
                    var sprite = entry.item.getSprite();
                    if (sprite == null || sprite.isLinked() || sprite.hasAvatarPerformance()
                            || sprite.wantsGl() || sprite.hasCornerPin()
                            || sprite.getFrameTrack().keys().stream().anyMatch(key -> key.presetId != null)) {
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
                    bindings.add(new Binding(entry.id, c -> effect.toGlShaderProgram(c, false),
                            sprite::isVisibleAt));
                }
                case PIP -> {
                    var pip = entry.item.getClip();
                    if (pip == null || pip.isImageClip() || PipFrameStats.isMasked(pip)
                            || (pip.getCompositing() != null && pip.getCompositing().mattePeerId != null)) {
                        return new Candidate(null, "PiP " + entry.id);
                    }
                    long start = pip.getOverlayStartMs();
                    long end = start + Math.max(0L, pip.getTrimmedDurationMs());
                    var effect = new BlendModeGlEffect(context, pip, null, editorOffsetMs,
                            pieceStartMs, motionDurationMs);
                    bindings.add(new Binding(entry.id, c -> effect.toGlShaderProgram(c, false),
                            editorMs -> editorMs >= start && editorMs <= end));
                }
                case ADJUSTMENT -> {
                    var adjustment = entry.item.getAdjustment();
                    if (adjustment == null || adjustment.getFx().hearsMusic()) {
                        return new Candidate(null, "adjustment " + entry.id);
                    }
                    if (adjustment.rendersAnything()) {
                        var effect = new AdjustmentLayerGlEffect(context, adjustment, editorOffsetMs);
                        bindings.add(new Binding(entry.id, c -> effect.toGlShaderProgram(c, false),
                                adjustment::activeAt));
                    }
                }
                default -> {
                    return new Candidate(null, "unported lane kind " + entry.kind);
                }
            }
        }
        return new Candidate(bindings.isEmpty() ? null
                : new LaneCompositeGlEffect(bindings, editorOffsetMs), null);
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
        return new Program(bindings, programs, editorOffsetMs);
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

        Program(List<Binding> bindings, List<BaseGlShaderProgram> programs, long editorOffsetMs) {
            super(false, 2);
            this.bindings = bindings;
            this.programs = programs;
            this.editorOffsetMs = editorOffsetMs;
        }

        @Override
        public boolean shouldClearTextureBuffer() { return false; }

        @NonNull
        @Override
        public Size configure(int width, int height) throws VideoFrameProcessingException {
            try {
                compositor.configure(width, height);
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
            active.clear();
            for (int i = 0; i < bindings.size(); i++) {
                if (bindings.get(i).activeAt().test(editorMs)) active.add(programs.get(i));
            }
            try {
                compositor.drawScopeAt(inputTexture, PipChainGl.boundFbo(), active.size(),
                        presentationTimeUs, (i, prefix, time) -> active.get(i).drawFrame(prefix, time));
            } catch (GlUtil.GlException error) {
                throw new VideoFrameProcessingException(error);
            }
        }

        @Override
        public void flush() {
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
                compositor.release();
            } catch (GlUtil.GlException error) {
                if (failure == null) failure = new VideoFrameProcessingException(error);
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
