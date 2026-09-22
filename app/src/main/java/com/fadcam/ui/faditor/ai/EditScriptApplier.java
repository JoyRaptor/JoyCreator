package com.fadcam.ui.faditor.ai;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.model.GeneratedSource;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.slides.SlideCache;
import com.fadcam.ui.faditor.slides.SlideContract;
import com.fadcam.ui.faditor.slides.SlideFiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.ExportSettings;

import java.util.List;

/**
 * Validates and applies an {@link EditScript} to a live {@link FaditorProject}.
 *
 * <p>Validation runs first for ALL operations. If any operation is invalid,
 * nothing is applied (atomic batch). This prevents half-applied AI edits.</p>
 *
 * <p>Supported operations:
 * <ul>
 *   <li><b>REMOVE_SPAN</b> — add a non-destructive cut to a clip. Params: clipId, startMs, endMs.</li>
 *   <li><b>ADD_TEXT_OVERLAY</b> — add a text overlay. Params: text, centerX, centerY, sizeFraction, startMs, endMs.</li>
 *   <li><b>REMOVE_TEXT_OVERLAY</b> — remove an overlay by id. Params: overlayId.</li>
 *   <li><b>SET_OVERLAY_RANGE</b> — set overlay visible time range. Params: overlayId, startMs, endMs.</li>
 *   <li><b>SET_OVERLAY_POSITION</b> — set overlay center. Params: overlayId, centerX, centerY.</li>
 *   <li><b>SET_OVERLAY_TEXT</b> — set overlay text. Params: overlayId, text.</li>
 *   <li><b>SET_CLIP_SPEED</b> — set clip speed. Params: clipId, speed.</li>
 *   <li><b>SET_CLIP_VOLUME</b> — set clip volume. Params: clipId, volume.</li>
 *   <li><b>SET_CLIP_MUTED</b> — mute/unmute clip. Params: clipId, muted.</li>
 *   <li><b>SET_CAPTIONS_ENABLED</b> — toggle captions on a clip. Params: clipId, enabled.</li>
 *   <li><b>SET_CAPTION_STYLE</b> — set caption style. Params: clipId, styleId.</li>
 *   <li><b>SET_CANVAS_PRESET</b> — set project canvas. Params: preset.</li>
 *   <li><b>SET_EXPORT_SETTING</b> — set an export setting. Params: key, value.</li>
 *   <li><b>MOVE_KEYFRAME</b> — move a keyframe on an overlay. Params: overlayId, oldLocalMs, newLocalMs.</li>
 *   <li><b>ADD_KEYFRAME</b> — add a keyframe at a timeline time. Params: overlayId, timelineMs.</li>
 *   <li><b>CLEAR_KEYFRAMES</b> — clear all keyframes on an overlay. Params: overlayId.</li>
 * </ul></p>
 */
public class EditScriptApplier {

    /**
     * App context, required for generated-slide operations (resolves the project
     * directory where slide HTML and the render cache live). Null is fine for
     * scripts that contain no slide operations.
     */
    @Nullable
    private Context context;

    public void setContext(@Nullable Context ctx) {
        this.context = ctx != null ? ctx.getApplicationContext() : null;
    }

    /** Id of the last clip created by an ADD/REGENERATE slide op (for tool callers). */
    @Nullable
    private String lastGeneratedClipId;

    @Nullable
    public String getLastGeneratedClipId() {
        return lastGeneratedClipId;
    }

    /**
     * Result of applying an edit script.
     */
    public static class Result {
        public final boolean success;
        public final int appliedCount;
        @Nullable public final String error;

        private Result(boolean success, int appliedCount, @Nullable String error) {
            this.success = success;
            this.appliedCount = appliedCount;
            this.error = error;
        }

        public static Result ok(int count) { return new Result(true, count, null); }
        public static Result fail(@NonNull String error) { return new Result(false, 0, error); }
    }

    /**
     * Validate all operations without applying any.
     *
     * @return null if all valid, or an error message for the first invalid op.
     */
    @Nullable
    public String validate(@NonNull FaditorProject project,
                           @NonNull EditScript script) {
        for (int i = 0; i < script.getOperations().size(); i++) {
            EditScript.EditOp op = script.getOperations().get(i);
            String err = validateOp(project, op);
            if (err != null) {
                return "Op " + i + " (" + op.type + "): " + err;
            }
        }
        return null;
    }

    /**
     * Validate and apply all operations atomically.
     * If validation fails, no operations are applied.
     */
    @NonNull
    public Result apply(@NonNull FaditorProject project,
                        @NonNull EditScript script) {
        // Scripts with structural ops (SPLIT/REORDER) have cross-op id dependencies — a later op
        // references a clip a previous op creates — so the all-at-once static validator can't see
        // them. Validate those by SIMULATING the ops on a deep copy first. Non-structural scripts keep
        // the original static path unchanged (zero behavior change for normal AI edits).
        if (hasStructuralOps(script)) {
            FaditorProject sim = deepCopy(project);
            if (sim != null) {
                int idx = 0;
                for (EditScript.EditOp op : script.getOperations()) {
                    String err = validateOp(sim, op);
                    if (err != null) {
                        return Result.fail("Op " + idx + " (" + op.type + "): " + err);
                    }
                    try {
                        applyOp(sim, op);
                    } catch (Exception e) {
                        return Result.fail("Op " + idx + " (" + op.type
                                + ") would fail: " + e.getMessage());
                    }
                    idx++;
                }
                // Simulation passed end-to-end → replay on the live project.
                int count = 0;
                java.util.Map<String, Long> before = project.getTimeline().beginStructural();
                try {
                    for (EditScript.EditOp op : script.getOperations()) {
                        try {
                            applyOp(project, op);
                            count++;
                        } catch (Exception e) {
                            return Result.fail("Failed applying op " + count
                                    + " (" + op.type + "): " + e.getMessage());
                        }
                    }
                } finally {
                    // ONE bracket around the whole script, not one per op: a script is a single
                    // user action, and a split is remove+add+add — bracketing per op would shift
                    // a rider two or three times. In a finally because a failed op returns from
                    // inside the loop, and a bracket left open makes every LATER edit on this
                    // timeline look nested, which would silently switch rippling off.
                    project.getTimeline().endStructural(before);
                }
                project.touch();
                return Result.ok(count);
            }
            // deepCopy unavailable (no context) → fall through to static path.
        }

        String validationError = validate(project, script);
        if (validationError != null) {
            return Result.fail(validationError);
        }

        int count = 0;
        java.util.Map<String, Long> before = project.getTimeline().beginStructural();
        try {
            for (EditScript.EditOp op : script.getOperations()) {
                try {
                    applyOp(project, op);
                    count++;
                } catch (Exception e) {
                    return Result.fail("Failed applying op " + count
                            + " (" + op.type + "): " + e.getMessage());
                }
            }
        } finally {
            project.getTimeline().endStructural(before);   // see the structural path above
        }
        project.touch();
        return Result.ok(count);
    }

    private boolean hasStructuralOps(@NonNull EditScript script) {
        for (EditScript.EditOp op : script.getOperations()) {
            if (op.type == EditScript.OpType.SPLIT_CLIP_AT_TIME
                    || op.type == EditScript.OpType.REORDER_CLIPS
                    || op.type == EditScript.OpType.INSERT_BROLL_CUTAWAY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deep-copy a project via the canonical JSON serializer (same path as undo snapshots) so a script
     * can be simulated without touching the live project. Returns null if no context is available.
     */
    @Nullable
    private FaditorProject deepCopy(@NonNull FaditorProject project) {
        if (context == null) return null;
        try {
            ProjectStorage storage = new ProjectStorage(context);
            return storage.fromJson(storage.toJson(project));
        } catch (Exception e) {
            return null;
        }
    }

    // ── Per-op validation ───────────────────────────────────────────

    @Nullable
    private String validateOp(@NonNull FaditorProject project,
                              @NonNull EditScript.EditOp op) {
        switch (op.type) {
            case REMOVE_SPAN: return validateRemoveSpan(project, op);
            case ADD_TEXT_OVERLAY: return validateAddTextOverlay(op);
            case REMOVE_TEXT_OVERLAY: return validateOverlayExists(project, op);
            case SET_OVERLAY_RANGE: return validateOverlayExists(project, op);
            case SET_OVERLAY_POSITION: return validateOverlayExists(project, op);
            case SET_OVERLAY_TEXT: return validateOverlayExists(project, op);
            case SET_CLIP_SPEED: return validateClipExists(project, op);
            case SET_CLIP_VOLUME: return validateClipExists(project, op);
            case SET_CLIP_MUTED: return validateClipExists(project, op);
            case SET_CAPTIONS_ENABLED: return validateClipExists(project, op);
            case SET_CAPTION_STYLE: return validateClipExists(project, op);
            case SET_CANVAS_PRESET: return null;
            case SET_EXPORT_SETTING: return null;
            case MOVE_KEYFRAME: return validateOverlayExists(project, op);
            case ADD_KEYFRAME: return validateOverlayExists(project, op);
            case ADD_OPACITY_KEYFRAME: return validateOverlayExists(project, op);
            case CLEAR_KEYFRAMES: return validateOverlayExists(project, op);
            case ADD_GENERATED_SLIDE: return validateAddGeneratedSlide(op);
            case REGENERATE_SLIDE: return validateRegenerateSlide(project, op);
            case SPLIT_CLIP_AT_TIME: return validateSplitClipAtTime(project, op);
            case REORDER_CLIPS: return validateReorderClips(project, op);
            case INSERT_BROLL_CUTAWAY: return validateInsertBrollCutaway(project, op);
            case ADD_VISUALIZER: return validateAddVisualizer(op);
            default: return "Unknown operation type: " + op.type;
        }
    }

    @Nullable
    private String validateRemoveSpan(@NonNull FaditorProject project,
                                      @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return "clip not found: " + getParamString(op, "clipId");
        long startMs = getParamLong(op, "startMs", -1);
        long endMs = getParamLong(op, "endMs", -1);
        if (startMs < 0 || endMs < 0 || endMs <= startMs) {
            return "invalid span: startMs=" + startMs + " endMs=" + endMs;
        }
        if (startMs < clip.getInPointMs() || endMs > clip.getOutPointMs()) {
            return "span outside clip trim range";
        }
        return null;
    }

    @Nullable
    private String validateAddTextOverlay(@NonNull EditScript.EditOp op) {
        String text = getParamString(op, "text");
        if (text == null || text.isEmpty()) return "text is required";
        float cx = getParamFloat(op, "centerX", 0.5f);
        float cy = getParamFloat(op, "centerY", 0.5f);
        if (cx < 0 || cx > 1 || cy < 0 || cy > 1) return "centerX/Y must be 0..1";
        float size = getParamFloat(op, "sizeFraction", 0.1f);
        if (size < 0.02f || size > 0.6f) return "sizeFraction must be 0.02..0.6";
        return null;
    }

    @Nullable
    private String validateOverlayExists(@NonNull FaditorProject project,
                                         @NonNull EditScript.EditOp op) {
        String id = getParamString(op, "overlayId");
        if (id == null) return "overlayId is required";
        if (findOverlay(project, id) == null) return "overlay not found: " + id;
        return null;
    }

    @Nullable
    private String validateClipExists(@NonNull FaditorProject project,
                                      @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return "clip not found: " + getParamString(op, "clipId");
        return null;
    }

    // ── Per-op application ──────────────────────────────────────────

    private void applyOp(@NonNull FaditorProject project,
                         @NonNull EditScript.EditOp op) {
        switch (op.type) {
            case REMOVE_SPAN: applyRemoveSpan(project, op); break;
            case ADD_TEXT_OVERLAY: applyAddTextOverlay(project, op); break;
            case REMOVE_TEXT_OVERLAY: applyRemoveOverlay(project, op); break;
            case SET_OVERLAY_RANGE: applySetOverlayRange(project, op); break;
            case SET_OVERLAY_POSITION: applySetOverlayPosition(project, op); break;
            case SET_OVERLAY_TEXT: applySetOverlayText(project, op); break;
            case SET_CLIP_SPEED: applySetClipSpeed(project, op); break;
            case SET_CLIP_VOLUME: applySetClipVolume(project, op); break;
            case SET_CLIP_MUTED: applySetClipMuted(project, op); break;
            case SET_CAPTIONS_ENABLED: applySetCaptionsEnabled(project, op); break;
            case SET_CAPTION_STYLE: applySetCaptionStyle(project, op); break;
            case SET_CANVAS_PRESET: applySetCanvasPreset(project, op); break;
            case SET_EXPORT_SETTING: applySetExportSetting(project, op); break;
            case MOVE_KEYFRAME: applyMoveKeyframe(project, op); break;
            case ADD_KEYFRAME: applyAddKeyframe(project, op); break;
            case ADD_OPACITY_KEYFRAME: applyAddOpacityKeyframe(project, op); break;
            case CLEAR_KEYFRAMES: applyClearKeyframes(project, op); break;
            case ADD_GENERATED_SLIDE: applyAddGeneratedSlide(project, op); break;
            case REGENERATE_SLIDE: applyRegenerateSlide(project, op); break;
            case SPLIT_CLIP_AT_TIME: applySplitClipAtTime(project, op); break;
            case REORDER_CLIPS: applyReorderClips(project, op); break;
            case INSERT_BROLL_CUTAWAY: applyInsertBrollCutaway(project, op); break;
            case ADD_VISUALIZER: applyAddVisualizer(project, op); break;
        }
    }

    private void applyRemoveSpan(@NonNull FaditorProject project,
                                 @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return;
        long startMs = getParamLong(op, "startMs", -1);
        long endMs = getParamLong(op, "endMs", -1);
        clip.getRemovedSpans().add(new long[]{startMs, endMs});
    }

    private void applyAddTextOverlay(@NonNull FaditorProject project,
                                     @NonNull EditScript.EditOp op) {
        String text = getParamString(op, "text");
        int color = getParamInt(op, "colorInt", Studio.INK);
        float cx = getParamFloat(op, "centerX", 0.5f);
        float cy = getParamFloat(op, "centerY", 0.5f);
        float size = getParamFloat(op, "sizeFraction", 0.1f);
        float rot = getParamFloat(op, "rotationDeg", 0f);
        TextOverlayItem item = new TextOverlayItem(text, color, cx, cy, size, rot);
        long startMs = getParamLong(op, "startMs", 0);
        long endMs = getParamLong(op, "endMs", Long.MAX_VALUE);
        item.setTimeRange(startMs, endMs);
        project.getTimeline().addTextOverlay(item);
    }

    private void applyRemoveOverlay(@NonNull FaditorProject project,
                                    @NonNull EditScript.EditOp op) {
        String id = getParamString(op, "overlayId");
        TextOverlayItem o = findOverlay(project, id);
        if (o != null) project.getTimeline().removeTextOverlay(o);
    }

    private void applySetOverlayRange(@NonNull FaditorProject project,
                                      @NonNull EditScript.EditOp op) {
        TextOverlayItem o = findOverlay(project, getParamString(op, "overlayId"));
        if (o == null) return;
        long startMs = getParamLong(op, "startMs", o.getStartMs());
        long endMs = getParamLong(op, "endMs", o.getEndMs());
        o.setTimeRange(startMs, endMs);
    }

    private void applySetOverlayPosition(@NonNull FaditorProject project,
                                         @NonNull EditScript.EditOp op) {
        TextOverlayItem o = findOverlay(project, getParamString(op, "overlayId"));
        if (o == null) return;
        float cx = getParamFloat(op, "centerX", o.getCenterX());
        float cy = getParamFloat(op, "centerY", o.getCenterY());
        o.setCenter(cx, cy);
    }

    private void applySetOverlayText(@NonNull FaditorProject project,
                                     @NonNull EditScript.EditOp op) {
        TextOverlayItem o = findOverlay(project, getParamString(op, "overlayId"));
        if (o == null) return;
        String text = getParamString(op, "text");
        if (text != null) o.setText(text);
    }

    private void applySetClipSpeed(@NonNull FaditorProject project,
                                   @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return;
        float speed = getParamFloat(op, "speed", 1f);
        clip.setSpeedMultiplier(Math.max(0.25f, Math.min(4f, speed)));
    }

    private void applySetClipVolume(@NonNull FaditorProject project,
                                    @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return;
        float vol = getParamFloat(op, "volume", 1f);
        clip.setVolumeLevel(Math.max(0f, Math.min(2f, vol)));
    }

    private void applySetClipMuted(@NonNull FaditorProject project,
                                   @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return;
        boolean muted = getParamBoolean(op, "muted", false);
        clip.setAudioMuted(muted);
    }

    private void applySetCaptionsEnabled(@NonNull FaditorProject project,
                                         @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return;
        boolean enabled = getParamBoolean(op, "enabled", false);
        clip.setCaptionsEnabled(enabled);
    }

    private void applySetCaptionStyle(@NonNull FaditorProject project,
                                      @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return;
        String styleId = getParamString(op, "styleId");
        if (styleId != null) clip.setCaptionStyleId(styleId);
    }

    private void applySetCanvasPreset(@NonNull FaditorProject project,
                                      @NonNull EditScript.EditOp op) {
        String preset = getParamString(op, "preset");
        if (preset != null) project.setCanvasPreset(preset);
    }

    private void applySetExportSetting(@NonNull FaditorProject project,
                                       @NonNull EditScript.EditOp op) {
        String key = getParamString(op, "key");
        String value = getParamString(op, "value");
        if (key == null || value == null) return;
        ExportSettings settings = project.getExportSettings();
        switch (key) {
            case "resolution":
                try { settings.setResolution(ExportSettings.Resolution.valueOf(value)); }
                catch (IllegalArgumentException ignored) { }
                break;
            case "quality":
                try { settings.setQuality(ExportSettings.Quality.valueOf(value)); }
                catch (IllegalArgumentException ignored) { }
                break;
            case "format":
                try { settings.setFormat(ExportSettings.Format.valueOf(value)); }
                catch (IllegalArgumentException ignored) { }
                break;
            case "cleanAudio":
                settings.setCleanAudio(Boolean.parseBoolean(value));
                break;
        }
    }

    private void applyMoveKeyframe(@NonNull FaditorProject project,
                                   @NonNull EditScript.EditOp op) {
        TextOverlayItem o = findOverlay(project, getParamString(op, "overlayId"));
        if (o == null) return;
        long oldMs = getParamLong(op, "oldLocalMs", -1);
        long newMs = getParamLong(op, "newLocalMs", -1);
        if (oldMs >= 0 && newMs >= 0) {
            o.moveKeyframeLocalTime(oldMs, newMs);
        }
    }

    private void applyAddKeyframe(@NonNull FaditorProject project,
                                  @NonNull EditScript.EditOp op) {
        TextOverlayItem o = findOverlay(project, getParamString(op, "overlayId"));
        if (o == null) return;
        long timelineMs = getParamLong(op, "timelineMs", 0);
        o.addKeyframeAt(timelineMs);
    }

    private void applyAddOpacityKeyframe(@NonNull FaditorProject project,
                                         @NonNull EditScript.EditOp op) {
        TextOverlayItem o = findOverlay(project, getParamString(op, "overlayId"));
        if (o == null) return;
        long timelineMs = getParamLong(op, "timelineMs", 0);
        float opacity = getParamFloat(op, "opacity", 1f);
        o.addOpacityKeyframeAt(timelineMs, opacity);
    }

    private void applyClearKeyframes(@NonNull FaditorProject project,
                                     @NonNull EditScript.EditOp op) {
        TextOverlayItem o = findOverlay(project, getParamString(op, "overlayId"));
        if (o == null) return;
        o.clearKeyframes();
    }

    // ── Generated slides ────────────────────────────────────────────

    @Nullable
    private String validateAddGeneratedSlide(@NonNull EditScript.EditOp op) {
        String mode = getParamString(op, "mode");
        if (mode == null) mode = SlideContract.MODE_FULLSCREEN;
        if (!SlideContract.MODE_FULLSCREEN.equals(mode)
                && !SlideContract.MODE_OVERLAY.equals(mode)) {
            return "mode must be fullscreen or overlay";
        }
        if (SlideContract.MODE_OVERLAY.equals(mode)
                && getParamLong(op, "startMs", -1) < 0) {
            return "overlay slides need startMs";
        }
        String text = slideText(op);
        if (text == null || text.trim().isEmpty()) return "title_or_text is required";
        if (context == null) return "slide context unavailable (setContext not called)";
        return null;
    }

    @Nullable
    private String validateRegenerateSlide(@NonNull FaditorProject project,
                                           @NonNull EditScript.EditOp op) {
        if (context == null) return "slide context unavailable (setContext not called)";
        Clip clip = findClip(project, op);
        if (clip == null) return "clip not found: " + getParamString(op, "clipId");
        if (!clip.isGeneratedSlide()) return "clip is not a generated slide: " + clip.getId();
        return null;
    }

    private void applyAddGeneratedSlide(@NonNull FaditorProject project,
                                        @NonNull EditScript.EditOp op) {
        String mode = getParamString(op, "mode");
        if (mode == null) mode = SlideContract.MODE_FULLSCREEN;
        String text = slideText(op);
        long durationMs = clampDuration(getParamLong(op, "durationMsHint", 3000));
        String styleHint = getParamString(op, "styleHint");
        String sourceModel = getParamString(op, "sourceModel");
        String clipId = getParamString(op, "clipId");
        if (clipId == null) clipId = UUID.randomUUID().toString();
        String htmlUriParam = getParamString(op, "htmlUri");
        String hashParam = getParamString(op, "contentHash");

        if (SlideContract.MODE_OVERLAY.equals(mode)) {
            applyAddGeneratedOverlay(project, op, clipId, text, durationMs,
                    htmlUriParam, hashParam, styleHint, sourceModel);
            return;
        }

        Clip clip = buildSlideClip(project, clipId, mode, text, durationMs,
                htmlUriParam, hashParam, styleHint, sourceModel);
        if (clip == null) return;

        int count = project.getTimeline().getClipCount();
        int index = (int) getParamLong(op, "insertAtClipIndex", count);
        if (index < 0) index = 0;
        if (index > count) index = count;
        project.getTimeline().addClip(index, clip);
        project.getTimeline().shiftTransitionsAfterInsert(index);
        lastGeneratedClipId = clip.getId();
    }

    private void applyRegenerateSlide(@NonNull FaditorProject project,
                                      @NonNull EditScript.EditOp op) {
        Clip old = findClip(project, op);
        if (old == null || !old.isGeneratedSlide()) return;

        // Locate the clip's index so the replacement lands in the same spot.
        int index = -1;
        for (int i = 0; i < project.getTimeline().getClipCount(); i++) {
            if (project.getTimeline().getClip(i).getId().equals(old.getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) return;

        GeneratedSource oldGs = old.getGeneratedSource();
        String mode = oldGs != null ? oldGs.mode : SlideContract.MODE_FULLSCREEN;
        long durationMs = clampDuration(getParamLong(op, "durationMsHint",
                oldGs != null ? oldGs.authoredDurationMs : 3000));
        String styleHint = getParamString(op, "newStyleHint");
        if (styleHint == null && oldGs != null) styleHint = oldGs.styleHint;
        String sourceModel = getParamString(op, "sourceModel");
        String htmlUriParam = getParamString(op, "htmlUri");
        String hashParam = getParamString(op, "contentHash");
        String text = slideText(op);
        if (text == null) text = "Slide";

        // Keep the same clip id so undo/history stays coherent.
        Clip fresh = buildSlideClip(project, old.getId(), mode, text, durationMs,
                htmlUriParam, hashParam, styleHint, sourceModel);
        if (fresh == null) return;
        project.getTimeline().removeClip(index);
        project.getTimeline().addClip(index, fresh);
        lastGeneratedClipId = fresh.getId();
    }

    /**
     * Overlay-mode ADD_GENERATED_SLIDE (spec Phase 4): provisions the HTML and
     * adds a transparent TextOverlay spanning startMs..startMs+duration whose
     * generatedSource points at the PNG-sequence render path. The sequence
     * itself is produced lazily (background render / export pre-pass).
     */
    private void applyAddGeneratedOverlay(@NonNull FaditorProject project,
                                          @NonNull EditScript.EditOp op,
                                          @NonNull String id, @NonNull String text,
                                          long durationMs, @Nullable String htmlUriParam,
                                          @Nullable String hashParam,
                                          @Nullable String styleHint,
                                          @Nullable String sourceModel) {
        if (context == null) return;
        try {
            File projectDir = new ProjectStorage(context).projectDir(project.getId());
            int[] dims = SlideFiles.dimensionsFor(project);
            int w = dims[0], h = dims[1];

            String html;
            File htmlFile;
            if (htmlUriParam != null && !htmlUriParam.isEmpty()) {
                htmlFile = fileFromUri(htmlUriParam);
                html = readFileUtf8(htmlFile);
            } else {
                html = SlideContract.buildFallbackHtml(w, h,
                        SlideContract.MODE_OVERLAY, text, durationMs);
                htmlFile = SlideFiles.writeHtml(projectDir, id, html);
            }
            String hash = (hashParam != null && !hashParam.isEmpty())
                    ? hashParam : SlideFiles.contentHash(html, w, h, durationMs);

            long startMs = getParamLong(op, "startMs", 0);
            // White = untinted, as a literal: a slide overlay's colour multiplies what it draws,
            // so a UI token here tinted every AI-made slide grey once INK moved to #E4E4E7.
            com.fadcam.ui.faditor.model.TextOverlayItem item =
                    new com.fadcam.ui.faditor.model.TextOverlayItem(id, "", 0xFFFFFFFF,
                            0.5f, 0.5f, 0.3f, 0f);
            item.setTimeRange(startMs, startMs + durationMs);
            GeneratedSource gs = new GeneratedSource(SlideContract.MODE_OVERLAY,
                    Uri.fromFile(htmlFile).toString(), hash, durationMs, w, h);
            gs.styleHint = styleHint;
            gs.sourceModel = sourceModel;
            item.setGeneratedSource(gs);
            project.getTimeline().addTextOverlay(item);
            lastGeneratedClipId = item.getId();
        } catch (Exception ignored) { }
    }

    /**
     * Provisions the slide HTML (writing the built-in fallback when no htmlUri is
     * supplied), computes the content hash, and returns a Clip whose source points
     * at the deterministic, content-addressed render path under the project's
     * slide cache. The render itself is produced lazily by the export pre-pass.
     */
    @Nullable
    private Clip buildSlideClip(@NonNull FaditorProject project, @NonNull String clipId,
                                @NonNull String mode, @NonNull String text, long durationMs,
                                @Nullable String htmlUriParam, @Nullable String hashParam,
                                @Nullable String styleHint, @Nullable String sourceModel) {
        if (context == null) return null;
        try {
            File projectDir = new ProjectStorage(context).projectDir(project.getId());
            int[] dims = SlideFiles.dimensionsFor(project);
            int w = dims[0], h = dims[1];

            String html;
            File htmlFile;
            if (htmlUriParam != null && !htmlUriParam.isEmpty()) {
                htmlFile = fileFromUri(htmlUriParam);
                html = readFileUtf8(htmlFile);
            } else {
                html = SlideContract.buildFallbackHtml(w, h, mode, text, durationMs);
                htmlFile = SlideFiles.writeHtml(projectDir, clipId, html);
            }

            String hash = (hashParam != null && !hashParam.isEmpty())
                    ? hashParam : SlideFiles.contentHash(html, w, h, durationMs);

            SlideCache cache = new SlideCache(projectDir);
            File renderMp4 = cache.mp4ForState(hash,
                    com.fadcam.ui.faditor.slides.SlideRenderer
                            .initialRenderStateHash(hash, durationMs));
            Uri sourceUri = Uri.fromFile(renderMp4);

            Clip clip = new Clip(clipId, sourceUri, 0, durationMs,
                    com.fadcam.ui.faditor.slides.SlideRenderer.SLIDE_MAX_DURATION_MS,
                    1.0f, false, 1.0f, 0, false, false, "none", 0f, 0f, 1f, 1f);

            GeneratedSource gs = new GeneratedSource(mode,
                    Uri.fromFile(htmlFile).toString(), hash, durationMs, w, h);
            gs.renderCacheUri = sourceUri.toString();
            gs.styleHint = styleHint;
            gs.sourceModel = sourceModel;
            clip.setGeneratedSource(gs);
            return clip;
        } catch (Exception e) {
            return null;
        }
    }

    @NonNull
    private static String readFileUtf8(@NonNull File file) throws Exception {
        try (InputStream is = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private static File fileFromUri(@NonNull String uri) {
        if (uri.startsWith("file://")) {
            return new File(Uri.parse(uri).getPath());
        }
        return new File(uri);
    }

    @Nullable
    private String slideText(@NonNull EditScript.EditOp op) {
        String t = getParamString(op, "title_or_text");
        if (t == null) t = getParamString(op, "title");
        if (t == null) t = getParamString(op, "text");
        return t;
    }

    private static long clampDuration(long ms) {
        return Math.max(800, Math.min(20000, ms));
    }

    // ── Split / reorder / b-roll (narrative + b-roll specs) ──────────

    @Nullable
    private String validateSplitClipAtTime(@NonNull FaditorProject project,
                                           @NonNull EditScript.EditOp op) {
        Clip clip = findClip(project, op);
        if (clip == null) return "clip not found: " + getParamString(op, "clipId");
        if (clip.isGeneratedSlide()) return "cannot split a generated slide";
        if (clip.isImageClip()) return "cannot split an image clip";
        long at = getParamLong(op, "atSourceMs", -1);
        if (at < 0) return "atSourceMs is required";
        if (at <= clip.getInPointMs() + 100 || at >= clip.getOutPointMs() - 100) {
            return "atSourceMs " + at + " not strictly inside trim ["
                    + clip.getInPointMs() + "," + clip.getOutPointMs() + "] (100ms margin)";
        }
        return null;
    }

    private void applySplitClipAtTime(@NonNull FaditorProject project,
                                      @NonNull EditScript.EditOp op) {
        Clip original = findClip(project, op);
        if (original == null) return;
        Timeline tl = project.getTimeline();
        int index = indexOfClip(tl, original.getId());
        if (index < 0) return;
        long at = getParamLong(op, "atSourceMs", -1);

        String idA = getParamString(op, "firstClipId");
        if (idA == null) idA = original.getId();
        String idB = getParamString(op, "secondClipId");
        if (idB == null) idB = UUID.randomUUID().toString();

        String originalId = original.getId();
        Clip[] parts = splitClip(original, at, idA, idB);
        tl.removeClip(index);
        tl.addClip(index, parts[1]);
        tl.addClip(index, parts[0]);
        // The seam that sat after the original now sits after part B — push it (and
        // any later seam) right by one. The new A|B seam carries no transition.
        tl.shiftTransitionsAfterSplit(index);
        // Both halves carry FRESH ids, so every layer object anchored to the original would be
        // orphaned. Timeline.splitAt guards this internally; this hand-rolled split has to ask.
        tl.reanchorAfterManualSplit(originalId, index);
        lastGeneratedClipId = parts[0].getId();
    }

    /**
     * Split one clip into two at a source-time point, partitioning removed spans
     * and transcript words by time so each child only carries what falls in its
     * range. Children keep all other edits (speed, crop, effects, …).
     */
    @NonNull
    private Clip[] splitClip(@NonNull Clip original, long atSourceMs,
                             @NonNull String idA, @NonNull String idB) {
        Clip a = new Clip(original, idA);
        Clip b = new Clip(original, idB);
        a.setOutPointMs(atSourceMs);
        b.setInPointMs(atSourceMs);
        a.setRemovedSpans(clampSpans(original.getRemovedSpans(), original.getInPointMs(), atSourceMs));
        b.setRemovedSpans(clampSpans(original.getRemovedSpans(), atSourceMs, original.getOutPointMs()));
        // NOTE: Transcript is NOT partitioned on split — non-destructive. See Timeline.partitionAfterSplit.
        return new Clip[]{a, b};
    }

    @NonNull
    private java.util.List<long[]> clampSpans(@NonNull java.util.List<long[]> spans,
                                              long lo, long hi) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (long[] s : spans) {
            long a = Math.max(lo, s[0]);
            long b = Math.min(hi, s[1]);
            if (b > a) out.add(new long[]{a, b});
        }
        return out;
    }

    /** Drop words on the wrong side of the split (by word start time). */
    private void partitionTranscripts(@NonNull Clip clip, long splitMs, boolean keepBefore) {
        for (com.fadcam.ui.faditor.transcript.NamedTranscript nt : clip.getTranscripts()) {
            java.util.Iterator<com.fadcam.ui.faditor.transcript.TranscriptWord> it =
                    nt.transcript.words.iterator();
            while (it.hasNext()) {
                com.fadcam.ui.faditor.transcript.TranscriptWord w = it.next();
                boolean before = w.startMs < splitMs;
                if (before != keepBefore) it.remove();
            }
        }
    }

    @Nullable
    private String validateReorderClips(@NonNull FaditorProject project,
                                        @NonNull EditScript.EditOp op) {
        if (!op.params.has("newOrder") || !op.params.get("newOrder").isJsonArray()) {
            return "newOrder array is required";
        }
        com.google.gson.JsonArray arr = op.params.getAsJsonArray("newOrder");
        if (arr.size() == 0) return "newOrder must not be empty";
        Timeline tl = project.getTimeline();
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (int i = 0; i < tl.getClipCount(); i++) existing.add(tl.getClip(i).getId());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (com.google.gson.JsonElement e : arr) {
            String id = e.getAsString();
            if (!existing.contains(id)) return "newOrder references unknown clipId: " + id;
            if (!seen.add(id)) return "newOrder has duplicate clipId: " + id;
        }
        // COMPLETENESS (user decision 2026-07-27). The three checks above reject unknown and
        // duplicate ids but never required the list to be COMPLETE, and applyReorderClips drops
        // whatever is missing. For a well-formed script that is fine; for a truncated or
        // partially-hallucinated newOrder it silently DELETED footage, with no error raised and
        // -- per audit 1.5 -- no undo entry that reliably restores it. Reordering is not a
        // deletion tool, so a partial list is now a refused edit rather than a quiet data loss.
        if (seen.size() != existing.size()) {
            java.util.Set<String> missing = new java.util.LinkedHashSet<>();
            for (int i = 0; i < tl.getClipCount(); i++) {
                String id = tl.getClip(i).getId();
                if (!seen.contains(id)) missing.add(id);
            }
            return "newOrder must list every clip (reorder cannot delete). Missing "
                    + missing.size() + " of " + existing.size() + ": " + missing;
        }
        return null;
    }

    private void applyReorderClips(@NonNull FaditorProject project,
                                   @NonNull EditScript.EditOp op) {
        Timeline tl = project.getTimeline();
        com.google.gson.JsonArray arr = op.params.getAsJsonArray("newOrder");

        // Capture the clip-id pair flanking each transition seam BEFORE reordering.
        java.util.List<com.fadcam.ui.faditor.model.Transition> trans =
                new java.util.ArrayList<>(tl.getTransitions());
        java.util.List<String[]> seamPairs = new java.util.ArrayList<>();
        for (com.fadcam.ui.faditor.model.Transition t : trans) {
            String left = (t.clipIndex >= 0 && t.clipIndex < tl.getClipCount())
                    ? tl.getClip(t.clipIndex).getId() : null;
            String right = (t.clipIndex + 1 < tl.getClipCount())
                    ? tl.getClip(t.clipIndex + 1).getId() : null;
            seamPairs.add(new String[]{left, right});
        }

        java.util.Map<String, Clip> byId = new java.util.HashMap<>();
        for (int i = 0; i < tl.getClipCount(); i++) byId.put(tl.getClip(i).getId(), tl.getClip(i));
        java.util.List<String> newOrder = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement e : arr) newOrder.add(e.getAsString());

        // ── Rollback pre-state (audit 1.5, the mechanical half) ──────────────────────────
        // `trans` above is a SHALLOW copy: it duplicates the LIST but holds the very same
        // Transition objects the timeline does, and the loop below writes t.clipIndex on them.
        // Combined with clearTransitions() that left a part-way failure unrollbackable — the
        // clips were already removed, the transition list already emptied, and the surviving
        // Transition objects already carried indices for an order that was never finished.
        // Capture a true pre-state (clip order + each transition's ORIGINAL clipIndex) so any
        // throw mid-rebuild restores exactly what was there.
        java.util.List<Clip> clipsBefore = new java.util.ArrayList<>();
        for (int i = 0; i < tl.getClipCount(); i++) clipsBefore.add(tl.getClip(i));
        int[] transIndexBefore = new int[trans.size()];
        for (int ti = 0; ti < trans.size(); ti++) transIndexBefore[ti] = trans.get(ti).clipIndex;

        try {
            // Rebuild the clip list in the requested order. validateReorderClips now guarantees
            // newOrder is a complete permutation of the existing clip ids, so the byId lookup
            // below cannot drop a clip; the null guard is kept as a belt-and-braces.
            for (int i = tl.getClipCount() - 1; i >= 0; i--) tl.removeClip(i);
            for (String id : newOrder) {
                Clip c = byId.get(id);
                if (c != null) tl.addClip(c);
            }

            // Keep a transition only if its two clips are still adjacent in the new order.
            tl.clearTransitions();
            for (int ti = 0; ti < trans.size(); ti++) {
                String[] pair = seamPairs.get(ti);
                if (pair[0] == null || pair[1] == null) continue;
                int li = newOrder.indexOf(pair[0]);
                int ri = newOrder.indexOf(pair[1]);
                if (li >= 0 && ri == li + 1) {
                    com.fadcam.ui.faditor.model.Transition t = trans.get(ti);
                    t.clipIndex = li;
                    tl.addTransition(t);
                }
            }
        } catch (RuntimeException e) {
            // Restore the pre-state, then rethrow so the caller still reports the failure —
            // a half-reordered timeline is worse than a refused edit.
            for (int i = tl.getClipCount() - 1; i >= 0; i--) tl.removeClip(i);
            for (Clip c : clipsBefore) tl.addClip(c);
            tl.clearTransitions();
            for (int ti = 0; ti < trans.size(); ti++) {
                com.fadcam.ui.faditor.model.Transition t = trans.get(ti);
                t.clipIndex = transIndexBefore[ti];
                tl.addTransition(t);
            }
            throw e;
        }
    }

    @Nullable
    private String validateInsertBrollCutaway(@NonNull FaditorProject project,
                                              @NonNull EditScript.EditOp op) {
        long atMs = getParamLong(op, "atMs", -1);
        long durationMs = getParamLong(op, "durationMs", -1);
        String assetUri = getParamString(op, "assetUri");
        if (atMs < 0) return "atMs is required";
        if (durationMs < 1500 || durationMs > 8000) return "durationMs must be 1500..8000";
        if (assetUri == null || assetUri.isEmpty()) return "assetUri is required";
        Timeline tl = project.getTimeline();
        long total = tl.getVideoTrackDurationMs();
        if (atMs < 2000 || atMs + durationMs > total - 2000) {
            return "cutaway must not fall in the first/last 2s of the recording";
        }
        int[] loc = locateClipAtTimeline(tl, atMs);
        int[] loc2 = locateClipAtTimeline(tl, atMs + durationMs);
        if (loc == null || loc2 == null) return "cutaway span is outside the timeline";
        if (loc[0] != loc2[0]) return "cutaway span crosses a clip boundary (unsupported in v1)";
        Clip c = tl.getClip(loc[0]);
        if (c.isGeneratedSlide() || c.isImageClip()) return "target clip is not a video clip";
        if (c.getSpeedMultiplier() != 1.0f) return "cutaway target must be at 1x speed (v1)";
        if (c.hasRemovedSpans()) return "cutaway target has removed spans (unsupported in v1)";
        return null;
    }

    private void applyInsertBrollCutaway(@NonNull FaditorProject project,
                                         @NonNull EditScript.EditOp op) {
        long atMs = getParamLong(op, "atMs", -1);
        long durationMs = getParamLong(op, "durationMs", -1);
        String assetUri = getParamString(op, "assetUri");
        Timeline tl = project.getTimeline();

        int[] loc = locateClipAtTimeline(tl, atMs);
        if (loc == null) return;
        int clipIndex = loc[0];
        Clip target = tl.getClip(clipIndex);

        // Target is 1x, no removed spans (validated): timeline-local == source offset.
        long spanSourceStart = target.getInPointMs() + loc[1];
        long spanSourceEnd = spanSourceStart + durationMs;
        if (spanSourceEnd >= target.getOutPointMs()) spanSourceEnd = target.getOutPointMs() - 1;

        // 1. Keep the original narration audio of the span playing underneath.
        Uri originalUri = target.getSourceUri();
        AudioClip narration = new AudioClip(originalUri, target.getSourceDurationMs());
        narration.setInPointMs(spanSourceStart);
        narration.setOutPointMs(spanSourceEnd);
        narration.setOffsetMs(atMs);
        narration.setLabel("Narration (cutaway)");

        // 2. Split target into [before][span][after].
        Clip[] firstSplit = splitClip(target, spanSourceStart,
                target.getId(), UUID.randomUUID().toString());
        Clip[] secondSplit = splitClip(firstSplit[1], spanSourceEnd,
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        Clip before = firstSplit[0];
        Clip span = secondSplit[0];
        Clip after = secondSplit[1];

        // 3. Replace the span's video with the b-roll (muted, trimmed to fit).
        Uri broll = Uri.parse(assetUri);
        long brollSourceDur = probeDurationMs(broll, durationMs);
        long brollOut = Math.max(1, Math.min(brollSourceDur, spanSourceEnd - spanSourceStart));
        Clip brollClip = new Clip(span.getId(), broll, 0, brollOut, brollSourceDur,
                1.0f, true, 1.0f, 0, false, false, "none", 0f, 0f, 1f, 1f);

        String cutawayOriginalId = tl.getClip(clipIndex).getId();
        tl.removeClip(clipIndex);
        tl.addClip(clipIndex, after);
        tl.addClip(clipIndex, brollClip);
        tl.addClip(clipIndex, before);
        // Two clips were inserted after the original's position → push later seams by 2.
        tl.shiftTransitionsAfterSplit(clipIndex);
        tl.shiftTransitionsAfterSplit(clipIndex);
        // Same fresh-id orphaning as the plain split — but this one produced THREE clips
        // (before / b-roll / after), and re-homing it as a two-way split dumped every rider from
        // the last part onto the b-roll. Pass the real part count.
        tl.reanchorAfterManualSplit(cutawayOriginalId, clipIndex, 3);

        tl.addAudioClip(narration);
        lastGeneratedClipId = brollClip.getId();
    }

    /**
     * Map a timeline position (ms, effective/post-edit) to its clip index and the
     * offset within that clip. Returns {clipIndex, localMs} or null if past the end.
     */
    @Nullable
    private int[] locateClipAtTimeline(@NonNull Timeline tl, long timelineMs) {
        long cumul = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            long dur = tl.getClip(i).getEffectiveDurationMs();
            if (timelineMs <= cumul + dur) {
                return new int[]{i, (int) (timelineMs - cumul)};
            }
            cumul += dur;
        }
        return null;
    }

    private int indexOfClip(@NonNull Timeline tl, @NonNull String id) {
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (tl.getClip(i).getId().equals(id)) return i;
        }
        return -1;
    }

    private long probeDurationMs(@NonNull Uri uri, long fallback) {
        if (context == null) return fallback;
        android.media.MediaMetadataRetriever r = null;
        try {
            r = new android.media.MediaMetadataRetriever();
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                r.setDataSource(uri.getPath());
            } else {
                r.setDataSource(context, uri);
            }
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Math.max(1, Long.parseLong(d));
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.release(); } catch (Exception ignored) { }
        }
        return fallback;
    }

    // ── Waveform visualizer ─────────────────────────────────────────

    @Nullable
    private String validateAddVisualizer(@NonNull EditScript.EditOp op) {
        String styleId = getParamString(op, "presetId");
        if (styleId == null) styleId = getParamString(op, "styleId");
        if (styleId == null || styleId.isEmpty()) return "presetId (or styleId) is required";
        if (getParamLong(op, "durationMs", -1) <= 0) return "durationMs must be > 0";
        return null;
    }

    private void applyAddVisualizer(@NonNull FaditorProject project,
                                    @NonNull EditScript.EditOp op) {
        String styleId = getParamString(op, "presetId");
        if (styleId == null) styleId = getParamString(op, "styleId");
        if (styleId == null) return;
        com.fadcam.ui.faditor.model.WaveformOverlayInstance wo =
                new com.fadcam.ui.faditor.model.WaveformOverlayInstance(styleId);
        String audioRef = getParamString(op, "audioSourceRef");
        if (audioRef == null) audioRef = getParamString(op, "clipId");
        wo.setAudioSourceRef(audioRef);
        long startMs = getParamLong(op, "startMs", 0);
        long durationMs = getParamLong(op, "durationMs", 5000);
        wo.setTimeRange(startMs, startMs + durationMs);
        if ("fullscreen".equals(getParamString(op, "mode"))) {
            wo.setCenter(0.5f, 0.5f);
            wo.setSize(1.0f, 0.5f);
        } else {
            wo.setCenter(0.5f, 0.78f);
            wo.setSize(0.85f, 0.22f);
        }
        project.getTimeline().addWaveformOverlay(wo);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    @Nullable
    private Clip findClip(@NonNull FaditorProject project,
                          @NonNull EditScript.EditOp op) {
        String id = getParamString(op, "clipId");
        if (id == null) return null;
        Timeline tl = project.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (tl.getClip(i).getId().equals(id)) return tl.getClip(i);
        }
        return null;
    }

    @Nullable
    private TextOverlayItem findOverlay(@NonNull FaditorProject project,
                                        @Nullable String id) {
        if (id == null) return null;
        for (TextOverlayItem o : project.getTimeline().getTextOverlays()) {
            if (o.getId().equals(id)) return o;
        }
        return null;
    }

    @Nullable
    private String getParamString(@NonNull EditScript.EditOp op, @NonNull String key) {
        return op.params.has(key) ? op.params.get(key).getAsString() : null;
    }

    private long getParamLong(@NonNull EditScript.EditOp op, @NonNull String key, long def) {
        return op.params.has(key) ? op.params.get(key).getAsLong() : def;
    }

    private int getParamInt(@NonNull EditScript.EditOp op, @NonNull String key, int def) {
        return op.params.has(key) ? op.params.get(key).getAsInt() : def;
    }

    private float getParamFloat(@NonNull EditScript.EditOp op, @NonNull String key, float def) {
        return op.params.has(key) ? op.params.get(key).getAsFloat() : def;
    }

    private boolean getParamBoolean(@NonNull EditScript.EditOp op, @NonNull String key, boolean def) {
        return op.params.has(key) ? op.params.get(key).getAsBoolean() : def;
    }
}
