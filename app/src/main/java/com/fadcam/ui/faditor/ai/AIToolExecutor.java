package com.fadcam.ui.faditor.ai;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.slides.SlideContract;
import com.fadcam.ui.faditor.slides.SlideFiles;
import com.fadcam.ui.faditor.transcript.NamedTranscript;
import com.fadcam.ui.faditor.transcript.TranscriptionEngine;
import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.util.SilenceDetector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Executes AI-requested tool calls against the live project.
 *
 * <p>This is the "hands" of the AI assistant. The AI emits tool-call JSON,
 * this class validates and executes it against the project, and returns a
 * result string the AI can use in its next turn.</p>
 *
 * <p>Each tool is synchronous from the caller's perspective (uses a latch to
 * wait for async callbacks). Long-running tools (transcription) have a timeout.</p>
 */
public class AIToolExecutor {

    private static final String TAG = "AIToolExecutor";
    private static final long TOOL_TIMEOUT_MS = 120_000; // 2 min for transcription

    public interface ProgressListener {
        void onProgress(@NonNull String task, int percent);
    }

    @Nullable private final ProgressListener progressListener;

    @NonNull private final Context context;
    @NonNull private final ProjectStorage storage;
    @NonNull private final String projectId;

    public AIToolExecutor(@NonNull Context context, @NonNull String projectId) {
        this(context, projectId, null);
    }

    public AIToolExecutor(@NonNull Context context, @NonNull String projectId,
                          @Nullable ProgressListener progressListener) {
        this.context = context.getApplicationContext();
        this.storage = new ProjectStorage(this.context);
        this.projectId = projectId;
        this.progressListener = progressListener;
    }

    /**
     * Execute a tool call and return a result string.
     *
     * @param toolName  the tool to call
     * @param args      JSON arguments for the tool
     * @return result string (success or error message)
     */
    @NonNull
    public String executeTool(@NonNull String toolName, @NonNull JSONObject args) {
        try {
            switch (toolName) {
                case "get_project_state": return toolGetProjectState();
                case "generate_transcript": return toolGenerateTranscript(args);
                case "detect_silence": return toolDetectSilence(args);
                case "apply_edit_script": return toolApplyEditScript(args);
                case "split_clip": return toolSplitClip(args);
                case "delete_clip": return toolDeleteClip(args);
                case "set_clip_speed": return toolSetClipSpeed(args);
                case "set_clip_muted": return toolSetClipMuted(args);
                case "toggle_captions": return toolToggleCaptions(args);
                case "set_caption_style": return toolSetCaptionStyle(args);
                case "set_canvas_preset": return toolSetCanvasPreset(args);
                case "add_text_overlay": return toolAddTextOverlay(args);
                case "remove_text_overlay": return toolRemoveTextOverlay(args);
                case "remove_span": return toolRemoveSpan(args);
                case "add_opacity_keyframe": return toolAddOpacityKeyframe(args);
                case "add_transition": return toolAddTransition(args);
                case "list_broll": return toolListBRoll();
                case "add_broll_overlay": return toolAddBRollOverlay(args);
                case "export_project": return toolExportProject(args);
                case "health_check": return toolHealthCheck();
                case "auto_chapters": return toolAutoChapters(args);
                case "get_transcript": return toolGetTranscript(args);
                case "correct_transcript": return toolCorrectTranscript(args);
                case "retime_words": return toolRetimeWords(args);
                case "synthesize_transcript": return toolSynthesizeTranscript(args);
                case "cut_all_fillers": return toolCutAllFillers(args);
                case "ai_merge_transcript": return toolAIMergeTranscript(args);
                case "ai_enhance": return toolAIEnhance(args);
                // set_clip_duck is DELIBERATELY NOT REGISTERED. duckAmount is read by nothing —
                // zero references in ExportManager and zero in the whole player package — so the
                // tool set the field, reported "Audio ducking set to N%", and changed nothing the
                // user could ever hear. The human-facing slider was already hidden behind
                // `if (false)` for exactly this reason (VolumeControlBottomSheet ~:338); the AI
                // copy of it was missed. An assistant that claims an edit it did not make is
                // worse than one that says it cannot. Re-register when a duck processor exists.
                // See tasks/LEDGER.md §3f.
                case "set_clip_zoom": return toolSetClipZoom(args);
                case "auto_zoom": return toolAutoZoom(args);
                case "generate_slide": return toolGenerateSlide(args);
                case "move_clip_to": return toolMoveClipTo(args);
                case "reorder_clips_by_name": return toolReorderClipsByName(args);
                case "resize_overlay": return toolResizeOverlay(args);
                case "analyze_narrative_structure": return toolAnalyzeNarrativeStructure(args);
                case "suggest_broll_placements": return toolSuggestBrollPlacements(args);
                case "apply_narrative_proposal": return toolApplyNarrativeProposal(args);
                case "apply_broll_proposal": return toolApplyBrollProposal(args);
                case "rename_clip": return toolRenameClip(args);
                case "rename_asset": return toolRenameAsset(args);
                case "describe_clip": return toolDescribeClip(args);
                case "tag_broll_assets": return toolTagBrollAssets(args);
                case "describe_sprite_sheet": return toolDescribeSpriteSheet(args);
                case "describe_sequence": return toolDescribeSequence(args);
                case "edit_sequence": return toolEditSequence(args);
                case "set_sprite_grid": return toolSetSpriteGrid(args);
                case "author_sprite_animation": return toolAuthorSpriteAnimation(args);
                case "author_avatar_rig": return toolAuthorAvatarRig(args);
                case "apply_avatar_rig": return toolApplyAvatarRig(args);
                default: return "Error: unknown tool '" + toolName + "'";
            }
        } catch (Exception e) {
            FLog.e(TAG, "Tool execution failed: " + toolName, e);
            return "Error: " + e.getMessage();
        }
    }

    private void notifyProgress(@NonNull String task, int percent) {
        if (progressListener != null) {
            progressListener.onProgress(task, Math.max(0, Math.min(100, percent)));
        }
    }

    /** Get a JSON description of all available tools (for the AI system prompt). */
    @NonNull
    public static String getToolDescriptions() {
        return """
            Available tools (emit as JSON: {"tool":"name","args":{...}}):

            1. get_project_state — Get full project info (clips, transcripts, overlays)
               args: {}
            2. generate_transcript — Transcribe a clip's audio (Vosk or Whisper)
               args: {"clipId":"...", "engine":"vosk|whisper"}
            3. detect_silence — Find silent spans in a clip
               args: {"clipId":"...", "sensitivity":0.5}
            4. apply_edit_script — Apply a batch of validated edit operations
               args: {"script":"<EditScript JSON string>"}
            5. split_clip — Split a clip at a source-time position
               args: {"clipId":"...", "splitMs":12345}
            6. delete_clip — Delete a clip from the timeline
               args: {"clipId":"..."}
            7. set_clip_speed — Set clip playback speed (0.25–4.0)
               args: {"clipId":"...", "speed":1.5}
            8. set_clip_muted — Mute or unmute a clip
               args: {"clipId":"...", "muted":true}
            9. toggle_captions — Enable/disable captions on a clip
               args: {"clipId":"...", "enabled":true}
            10. set_caption_style — Set caption style
                args: {"clipId":"...", "style":"pop|zoom|bounce|boxed|hot"}
            11. set_canvas_preset — Set project canvas aspect ratio
                args: {"preset":"original|16:9|9:16|1:1|4:5"}
            12. add_text_overlay — Add a text overlay
                args: {"text":"...", "centerX":0.5, "centerY":0.5, "sizeFraction":0.1, "startMs":0, "endMs":999999}
            13. remove_text_overlay — Remove an overlay by id
                args: {"overlayId":"..."}
            14. remove_span — Add a non-destructive cut to a clip
                args: {"clipId":"...", "startMs":0, "endMs":0}
            15. add_opacity_keyframe — Add a fade in/out keyframe (opacity 0=invisible, 1=fully visible)
                args: {"overlayId":"...", "timelineMs":0, "opacity":0.0}
            16. add_transition — Add a transition between/at clips
                args: {"type":"FADE_IN_FROM_BLACK", "durationMs":500, "clipIndex":0, "fuzziness":0.0}
                Valid types: FADE_IN_FROM_BLACK, FADE_OUT_TO_BLACK, FADE_IN_FROM_WHITE, FADE_OUT_TO_WHITE,
                CROSS_DISSOLVE, WIPE_LEFT, WIPE_RIGHT, WIPE_UP, WIPE_DOWN,
                PUSH_LEFT, PUSH_RIGHT, PUSH_UP, PUSH_DOWN
                fuzziness: 0=hard edge, 1=very blurry
            17. list_broll — List available B-roll assets in the shared bucket
                args: {}
            18. add_broll_overlay — Add an image from the B-roll bucket as an overlay
                args: {"assetName":"photo.jpg", "centerX":0.5, "centerY":0.5, "sizeFraction":0.3, "startMs":0, "endMs":5000}
            19. get_transcript — Get the full transcript text for a clip
                args: {"clipId":"..."}
            19b. correct_transcript — Fix a transcription/caption error: replace a run of words (matched
                by text, case/punctuation-insensitive) with corrected text, keeping the timing. Use this
                when the user points out wrong/missing caption words. Call get_transcript first to see the
                exact words. `replace` empty deletes the run.
                args: {"clipId":"...", "find":"the to eat", "replace":"In the day you eat"}
            20. retime_words — Anchor one or more words to exact timeline times and
                 interpolate the words between them. Use when the user wants specific
                 words to land at specific times (e.g. "set 'in' at 6s, space
                 'the','day' evenly to 'ye'"). Each anchor identifies a word by
                 `index` (0-based) or `word` (case-insensitive text match).
                 Consecutive anchors define a span; words between them are
                 redistributed. distribute: "even" = equal gaps, "by-length" =
                 proportional to character length (default).
                 args: {"clipId":"...", "anchors":[{"word":"in","timeMs":6000},{"index":5,"timeMs":15000}], "distribute":"even"}
            21. synthesize_transcript — Merge Vosk timing + Whisper words + silence data into a "best of both" transcript. Filler words (um/uh) are detected via silence cross-checking and pre-marked as [CUT]. Requires both Vosk and Whisper transcripts to exist.
                args: {"clipId":"...", "runSilence":true, "sensitivity":0.5}
            22. cut_all_fillers — Cut all pre-marked filler words (struck) from a clip's transcript. Run synthesize_transcript first.
                args: {"clipId":"..."}
            23. ai_merge_transcript — Merge Vosk + Whisper transcripts algorithmically (LCS alignment, picks better word per position)
                args: {"clipId":"..."}
            24. ai_enhance — One-button enhance: generates missing transcripts, detects silence, synthesizes, and cuts fillers for ALL clips
                args: {}
            25. set_clip_zoom — Set punch-in zoom on a clip
                args: {"clipId":"...", "zoom":2.0, "centerX":0.5, "centerY":0.5}
            26. auto_zoom — Detect important words from transcript and apply punch-in zoom
                args: {"clipId":"...", "zoom":2.0}
            27. generate_slide — Design a fully custom ANIMATED slide (chapter card,
                stylized title, animated lower-third) authored as HTML/CSS/GSAP and
                rasterized to real video frames. Use this — NOT add_text_overlay —
                whenever the user asks for a designed, animated, or stylized title /
                chapter card / intro card. add_text_overlay is only plain static text.
                args: {"mode":"fullscreen", "title_or_text":"Introduction",
                       "style_hint":"bold cinematic, dark background, gold accents",
                       "duration_ms_hint":3000,
                       "placement":{"insertAtClipIndex":0}}
                mode "overlay" composites the slide TRANSPARENTLY over the video
                (animated lower-third); it needs placement.startMs instead of
                insertAtClipIndex:
                args: {"mode":"overlay", "title_or_text":"Dr. Jane Smith",
                       "style_hint":"lower-third, slide in from left",
                       "duration_ms_hint":4000, "placement":{"startMs":12000}}

            28. analyze_narrative_structure — Read-only. Propose a reordered/trimmed
                sequence for one long recording: segment the transcript into chunks,
                summarize each, mark KEEP/DROP, and rank kept chunks. Returns a
                proposal for the user to confirm; applies NOTHING. To apply after
                the user confirms, call apply_narrative_proposal (preferred — it builds
                the splits+reorder reliably) rather than hand-writing apply_edit_script.
                args: {"clipId":"..."}
            29. apply_narrative_proposal — Apply a CONFIRMED narrative proposal. Pass the
                same chunk list analyze_narrative_structure returned (optionally edited by
                the user's KEEP/DROP/order changes). Builds and atomically applies the
                SPLIT×N + REORDER_CLIPS script with matching ids; other clips on the
                timeline are preserved. Only call AFTER the user confirms.
                args: {"clipId":"...","chunks":[{"startMs":0,"endMs":47000,"keep":true,
                "order":1}, ...]}
            30. apply_broll_proposal — Apply CONFIRMED b-roll cutaways. Pass the accepted
                items from suggest_broll_placements (each must include assetUri). Inserts
                each cutaway atomically (narration audio keeps playing underneath). Only
                call AFTER the user confirms.
                args: {"cutaways":[{"atMs":64000,"durationMs":3500,
                "assetUri":"content://.../clip.mp4"}, ...]}
            31. move_clip_to — Move a clip to a specific position in the timeline.
                Use when the user says e.g. "move Intro to the end", "make the selected clip last", "move this to third place".
                The clip is identified by its id or by matching part of its name/label.
                args: {"clipId":"...", "position":"end|start|3"} or {"namePattern":"Intro", "position":"end"}
                Position can be "start", "end", or a 1-based number ("1", "3", etc.)

            32. reorder_clips_by_name — Reorder clips by matching their names/labels.
                Use when the user says e.g. "make the order: Intro, Scene1, Scene2, Outro".
                args: {"nameOrder":["Intro","Scene1","Scene2","Outro"]}
                Each entry is matched against clip names (case-insensitive contains match).
                Clips not matched stay in their original relative order at the end.
                If clips are on different layers, the tool asks the user to confirm.

            33. rename_clip — Rename a single clip by id.
                Use when the user says e.g. "rename the first clip to Intro".
                args: {"clipId":"...", "newName":"Intro"}

            34. rename_asset — Batch rename clips whose names/labels contain a pattern.
                Use when the user says e.g. "rename all IMG_ clips to Vacation".
                args: {"namePattern":"IMG_", "newName":"Vacation", "startIndex":1}
                startIndex is the starting counter for numbered suffixes (default 1).
                Result: "Vacation 1", "Vacation 2", ...

            35. describe_clip — Show detailed info about a clip (name, duration, source, overlays).
                Use when the user wants to know about a specific clip.
                args: {"clipId":"..."}

            36. resize_overlay — Resize a text/image overlay by percentage.
                Use when the user says e.g. "make the title bigger", "shrink the logo by 50%".
                args: {"overlayId":"...", "percentChange":50} (positive = bigger, negative = smaller)
                Or: {"overlayId":"...", "sizeFraction":0.15} (absolute size as fraction of screen height)

            37. suggest_broll_placements — Read-only. Suggest documentary-style b-roll
                cutaways (b-roll replaces the visible frame while the original narration
                keeps playing). Returns guardrail-checked candidates with reasons;
                applies NOTHING. To apply after confirmation, emit apply_edit_script
                with INSERT_BROLL_CUTAWAY per accepted suggestion.
                args: {"clipId":"..."}

            38. tag_broll_assets — Vision-tag the b-roll bucket: extracts a thumbnail
                from each untagged image/video asset, sends it to the configured
                multimodal model, and caches searchable tags + a one-line description.
                Cached tags automatically improve list_broll and
                suggest_broll_placements matching. Run this when b-roll suggestions
                seem to be matching only on filenames.
                args: {"maxAssets":10 (optional, cap per call), "force":false (retag all)}

            EditScript ops for the above (use with apply_edit_script):
              SPLIT_CLIP_AT_TIME {"type":"SPLIT_CLIP_AT_TIME","clipId":"...","atSourceMs":184500,
                "firstClipId":"...","secondClipId":"..."}  (ids optional)
              REORDER_CLIPS {"type":"REORDER_CLIPS","newOrder":["id7","id2","id9"]}
                (clip ids omitted from newOrder are DELETED)
              INSERT_BROLL_CUTAWAY {"type":"INSERT_BROLL_CUTAWAY","atMs":64000,
                "durationMs":3500,"assetUri":"content://.../clip.mp4"}

            33b. describe_sprite_sheet — Read-only. Inspect a sprite sheet: image
                dimensions, its grid (cols/rows/margins/spacing — the sheet's stored
                grid, or an auto-detected suggestion for a raw image), and per-cell
                bounding boxes + occupancy (fraction of non-transparent pixels) + any
                existing cell names. Use before author_avatar_rig to understand what art
                is on the sheet. Deterministic, no network. Omit both args to list the
                project's sheets.
                args: {"sheetId":"..."}  OR  {"imageUri":"file://…|content://…"}

            33c. set_sprite_grid — Set a sheet's grid/margins/spacing/fps. The companion to
                describe_sprite_sheet, which suggests a grid but could not apply one. Every
                field is optional; absent means leave it alone. REFUSES to change cols/rows
                on a sheet that already has saved animations, because frames are cell
                INDICES and reshaping the grid renumbers every cell.
                args: {"sheetId":"...","cols":8,"rows":4,"marginX":0,"marginY":0,
                       "spacingX":0,"spacingY":0,"fps":12}

            33d. author_sprite_animation — Create a NAMED animation on a sheet: an ordered
                run of cell indices with a cadence. This is the one people give up on doing
                by hand. Frames are validated against the sheet's cell count before anything
                is saved. "weights" is optional and parallel to "frames" — how many ticks
                each frame is HELD (1 = normal, 2 = "on twos"); omit it for uniform timing.
                "fps" 0 or absent = inherit the sheet's fps.
                args: {"sheetId":"...","name":"walk","frames":[0,1,2,3],
                       "type":"loop|pingpong|once","fps":12,"weights":[2,1,1,2]}

            33c. describe_sequence — Read-only. An IMAGE SEQUENCE's timing: frameCount,
                the WEIGHT array, fps, run length, loop mode, resize mode, and whether a
                neighbour is cutting it short. Omit objectId to list the project's
                sequences. Timing model: every frame has a weight (default 1) and
                frame_duration = total x weight / sum(weights); fps = sum(weights) /
                totalSeconds. Frame indices are 0-based. Speak in FRAMES and WEIGHTS,
                never pixels or per-frame ms.
                args: {"objectId":"..."}   (or {} to list)

            33d. edit_sequence — Change an image sequence's timing. One op per call.
                Reports the state it finds AFTER the edit, so trust the returned numbers
                rather than assuming your arguments applied.
                args: {"objectId":"...","op":"<one of below>", …}
                  setWeights       {"weights":[1,1,5,1,…]}  — must be EXACTLY frameCount
                                   long; a mismatch is refused, not padded.
                  applyStride      {"start":0,"every":6,"weight":5}
                                   "every 6th frame holds for five". "On twos" is
                                   {"start":0,"every":1,"weight":2}.
                  applyRamp        {"fromIdx":0,"toIdx":40,"w0":4,"w1":1,
                                   "ease":"EASE_IN_OUT"} — "gradually getting faster".
                  setTotalDuration {"ms":8000}  (or {"duration":"2m30s"})
                  setFrameRate     {"fps":12}
                  setLoop          {"mode":"NONE|LOOP|PING_PONG"}  — ping-pong preserves
                                   each frame's weight when it mirrors.
                  setResizeMode    {"mode":"RELATIVE|ABSOLUTE"} — what dragging the
                                   object's edge MEANS: retime everything (RELATIVE) or
                                   add/remove frames (ABSOLUTE).
                  reorder          {"order":"REVERSE|SHUFFLE"} — weights travel with
                                   their frames.
                Adding holds LENGTHENS the object; it does not speed the other frames up.

            39. author_avatar_rig — Propose an AVATAR PUPPET RIG (Avatar Studio) for the
                user to confirm. Emit rig JSON against the BUILT-IN BIPED TEMPLATE:
                canonical part ids head/body/armL/armR/handL/handR/mouth (body is the
                root; head/armL/armR parent to body; handL/handR parent to their arm;
                mouth parents to head), each part referencing a project sheetId (call
                describe_sprite_sheet / get_project_state to get sheet ids). Domains:
                a 3×3 head grid (driverX yaw, driverY pitch) and 1-D 5-cell limb strips
                (driverX angle). Leave pose-cell EXTREMES unauthored — the human arms
                them in Avatar Studio (AI does structure, human does taste). The tool
                VALIDATES (unknown part names, missing sheets, malformed domains → it
                returns the reasons for you to fix and re-emit) then shows the user a
                confirm card. WAIT for them to Apply; on a chat go-ahead call
                apply_avatar_rig with the same rig. Rig JSON shape:
                {"name":"Dino","parts":[{"id":"body","sheetId":"<id>","anchorX":0.5,
                "anchorY":0.85},{"id":"head","sheetId":"<id>","parentId":"body",
                "anchorX":0.5,"anchorY":0.9}, …],"domains":[{"id":"head","driverX":"yaw",
                "driverY":"pitch","cols":3,"rows":3},{"id":"armL","driverX":"angle",
                "cols":5,"rows":1}]}
                args: {"rig":{ …rig JSON object… }}
            40. apply_avatar_rig — Apply a CONFIRMED avatar rig: registers it in the
                project. Only call AFTER the user confirms (or use the Apply card).
                args: {"rig":{ …the same rig JSON… }}

            To call a tool, respond with ONLY a JSON object:
            {"tool":"generate_transcript","args":{"clipId":"abc123","engine":"vosk"}}
            """;
    }

    // ── Tool implementations ───────────────────────────────────────

    private String toolGetProjectState() {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        return buildProjectSummary(proj);
    }

    private String toolGenerateTranscript(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        String engine = args.optString("engine", "vosk");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;

        TranscriptionEngine te = new TranscriptionEngine(context);
        TranscriptionEngine.ModelType modelType = null;
        for (TranscriptionEngine.ModelType mt : TranscriptionEngine.ModelType.values()) {
            if (mt.engine.name().equalsIgnoreCase(engine) && te.isModelReady(mt)) {
                modelType = mt;
                break;
            }
        }
        if (modelType == null) {
            modelType = te.anyReadyModel();
        }
        if (modelType == null) {
            return "Error: No transcription model is downloaded. "
                    + "Please open the Transcript tool in the editor first to download a model.";
        }
        final TranscriptionEngine.ModelType finalModelType = modelType;

        AtomicReference<String> resultRef = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        notifyProgress("Generating " + finalModelType.engine + " transcript", 0);
        te.transcribe(clip.getSourceUri(), clip.getInPointMs(), clip.getOutPointMs(),
                finalModelType, new TranscriptionEngine.Callback() {
                    @Override
                    public void onProgress(@NonNull String message, float progress) {
                        notifyProgress(message, Math.round(progress * 100f));
                    }

                    @Override
                    public void onResult(@NonNull Transcript transcript) {
                        NamedTranscript nt = new NamedTranscript(
                                finalModelType.label, finalModelType.engine.name().toLowerCase(), transcript);
                        clip.addTranscript(nt);
                        // Re-running the same model REPLACES its previous run instead of
                        // stacking another near-identical copy in project.json. Only
                        // un-edited, non-active older runs of the same engine+label are
                        // dropped (see TranscriptDedup's conservative rule); guarded on a
                        // non-empty result so an empty run can never displace real words.
                        if (!transcript.words.isEmpty()) {
                            com.fadcam.ui.faditor.transcript.TranscriptDedup.dedupClip(clip, true);
                        }
                        storage.save(proj);
                        AIChatState.signalModified(projectId);
                        resultRef.set("Transcript generated successfully. "
                                + transcript.words.size() + " words. Engine: "
                                + finalModelType.engine + ", Model: " + finalModelType.label);
                        notifyProgress("Transcript saved", 100);
                        latch.countDown();
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        resultRef.set("Error transcribing: " + message);
                        latch.countDown();
                    }
                });

        try {
            if (!latch.await(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "Error: transcription timed out after 2 minutes";
            }
        } catch (InterruptedException e) {
            return "Error: transcription interrupted";
        }
        te.shutdown();
        return resultRef.get();
    }

    private String toolDetectSilence(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        float sensitivity = (float) args.optDouble("sensitivity", 0.5);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;

        AtomicReference<String> resultRef = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        notifyProgress("Detecting silence", 0);
        SilenceDetector sd = new SilenceDetector(context);
        sd.detect(clip.getSourceUri(), clip.getInPointMs(), clip.getOutPointMs(),
                sensitivity, new SilenceDetector.Callback() {
                    @Override
                    public void onResult(@NonNull List<long[]> keepRangesMs,
                                         int gapsRemoved, long msSaved) {
                        // Convert keep ranges to silence gaps (the parts NOT in keep ranges)
                        List<long[]> silenceSpans = new ArrayList<>();
                        long cursor = clip.getInPointMs();
                        for (long[] keep : keepRangesMs) {
                            if (keep[0] > cursor) {
                                silenceSpans.add(new long[]{cursor, keep[0]});
                            }
                            cursor = Math.max(cursor, keep[1]);
                        }
                        if (cursor < clip.getOutPointMs()) {
                            silenceSpans.add(new long[]{cursor, clip.getOutPointMs()});
                        }

                        clip.setSilenceCandidates(silenceSpans);
                        storage.save(proj);
                        AIChatState.signalModified(projectId);

                        StringBuilder sb = new StringBuilder();
                        sb.append("Silence detection complete. Found ")
                                .append(silenceSpans.size())
                                .append(" silent spans (").append(msSaved)
                                .append("ms total):\n");
                        for (int i = 0; i < silenceSpans.size(); i++) {
                            long[] s = silenceSpans.get(i);
                            sb.append("  ").append(i + 1).append(": ")
                                    .append(s[0]).append("-").append(s[1])
                                    .append("ms (").append(s[1] - s[0]).append("ms)\n");
                        }
                        resultRef.set(sb.toString());
                        notifyProgress("Silence saved", 100);
                        latch.countDown();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        resultRef.set("Error: " + e.getMessage());
                        latch.countDown();
                    }
                });

        try {
            if (!latch.await(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "Error: silence detection timed out";
            }
        } catch (InterruptedException e) {
            return "Error: interrupted";
        }
        sd.shutdown();
        return resultRef.get();
    }

    private String toolApplyEditScript(@NonNull JSONObject args) {
        String scriptJson = args.optString("script", "");
        if (scriptJson.isEmpty()) return "Error: 'script' is required";
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        try {
            EditScript script = EditScript.fromJson(scriptJson);
            EditScriptApplier applier = new EditScriptApplier();
            applier.setContext(context);
            // apply() validates internally (statically for normal scripts, by simulation for
            // structural SPLIT/REORDER scripts whose ops depend on each other), so no separate
            // validate() call here — a standalone validate can't see cross-op id dependencies.
            EditScriptApplier.Result result = applier.apply(proj, script);
            if (result.success) {
                storage.save(proj);
                // The script's description becomes the undo-history label for this step.
                AIChatState.signalModified(projectId, script.getDescription());
                return "Applied " + result.appliedCount + " operations successfully.";
            } else {
                return "Apply failed: " + result.error;
            }
        } catch (EditScript.EditScriptException e) {
            return "Error parsing edit script: " + e.getMessage();
        }
    }

    // ── Generated slides (AI-authored animated HTML) ─────────────────

    private String toolGenerateSlide(@NonNull JSONObject args) {
        String mode = args.optString("mode", SlideContract.MODE_FULLSCREEN);
        if (!SlideContract.MODE_FULLSCREEN.equals(mode)
                && !SlideContract.MODE_OVERLAY.equals(mode)) {
            mode = SlideContract.MODE_FULLSCREEN;
        }
        String text = args.optString("title_or_text", args.optString("text", "")).trim();
        if (text.isEmpty()) return "Error: 'title_or_text' is required";
        String styleHint = args.optString("style_hint", "clean, modern, high-contrast");
        long durationMs = Math.max(800, Math.min(20000,
                args.optLong("duration_ms_hint", 3000)));

        int insertIndex = -1;
        long startMs = 0;
        JSONObject placement = args.optJSONObject("placement");
        if (placement != null && placement.has("insertAtClipIndex")) {
            insertIndex = placement.optInt("insertAtClipIndex", -1);
        }
        if (placement != null && placement.has("startMs")) {
            startMs = Math.max(0, placement.optLong("startMs", 0));
        }

        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        int[] dims = SlideFiles.dimensionsFor(proj);
        int w = dims[0], h = dims[1];

        // Author the HTML (model → validate → retry once → built-in fallback).
        String[] authored = authorSlideHtml(mode, text, styleHint, durationMs, w, h);
        String html = authored[0];
        String usedModel = authored[1];

        try {
            File projectDir = storage.projectDir(projectId);
            String clipId = UUID.randomUUID().toString();
            File htmlFile = SlideFiles.writeHtml(projectDir, clipId, html);
            String hash = SlideFiles.contentHash(html, w, h, durationMs);

            JSONObject opObj = new JSONObject();
            opObj.put("type", "ADD_GENERATED_SLIDE");
            opObj.put("mode", mode);
            opObj.put("title_or_text", text);
            opObj.put("styleHint", styleHint);
            opObj.put("durationMsHint", durationMs);
            opObj.put("clipId", clipId);
            opObj.put("htmlUri", Uri.fromFile(htmlFile).toString());
            opObj.put("contentHash", hash);
            if (usedModel != null) opObj.put("sourceModel", usedModel);
            if (insertIndex >= 0) opObj.put("insertAtClipIndex", insertIndex);
            if (SlideContract.MODE_OVERLAY.equals(mode)) opObj.put("startMs", startMs);

            JSONObject scriptObj = new JSONObject();
            scriptObj.put("version", 1);
            scriptObj.put("description", "Add generated slide: " + text);
            scriptObj.put("operations", new JSONArray().put(opObj));

            EditScript script = EditScript.fromJson(scriptObj.toString());
            EditScriptApplier applier = new EditScriptApplier();
            applier.setContext(context);
            String validationError = applier.validate(proj, script);
            if (validationError != null) return "Validation failed: " + validationError;
            EditScriptApplier.Result result = applier.apply(proj, script);
            if (!result.success) return "Apply failed: " + result.error;

            storage.save(proj);
            AIChatState.signalModified(projectId);
            return "Created animated slide \"" + text + "\" (clip " + clipId + ", "
                    + w + "x" + h + ", " + durationMs + "ms, "
                    + (usedModel != null ? "model-authored" : "built-in template")
                    + "). It will render to video on export.";
        } catch (Exception e) {
            FLog.e(TAG, "generate_slide failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * @return [html, usedModelOrNull] — model is null when the built-in fallback
     *         template was used (no API key, or two failed validations).
     */
    @NonNull
    private String[] authorSlideHtml(@NonNull String mode, @NonNull String text,
                                     @NonNull String styleHint, long durationMs,
                                     int w, int h) {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        String apiKey = prefs.sharedPreferences.getString("ai_api_key", "");
        String model = prefs.sharedPreferences.getString("ai_model", "openrouter/auto");

        if (apiKey == null || apiKey.isEmpty()) {
            return new String[]{SlideContract.buildFallbackHtml(w, h, mode, text, durationMs), null};
        }

        String system = SlideContract.buildSystemPrompt(w, h, mode, text, styleHint, durationMs);
        String reply = callOpenRouterForHtml(apiKey, model, system, text);
        if (reply != null) {
            String html = SlideContract.stripFences(reply);
            if (SlideContract.validate(html) == null) {
                return new String[]{html, model};
            }
            String reason = SlideContract.validate(html);
            String correction = system + "\n\nYour previous output violated: " + reason
                    + ". Fix and resend, raw HTML only.";
            String reply2 = callOpenRouterForHtml(apiKey, model, correction, text);
            if (reply2 != null) {
                String html2 = SlideContract.stripFences(reply2);
                if (SlideContract.validate(html2) == null) {
                    return new String[]{html2, model};
                }
            }
        }
        // Never let a bad model response break the user's project.
        return new String[]{SlideContract.buildFallbackHtml(w, h, mode, text, durationMs), null};
    }

    @Nullable
    private String callOpenRouterForHtml(@NonNull String apiKey, @NonNull String model,
                                         @NonNull String system, @NonNull String user) {
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", user));
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", 4096);
            body.put("temperature", 0.6);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json")))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    FLog.e(TAG, "Slide author API error " + response.code() + ": "
                            + respBody.substring(0, Math.min(200, respBody.length())));
                    return null;
                }
                JSONObject json = new JSONObject(respBody);
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) return null;
                return choices.getJSONObject(0).getJSONObject("message").getString("content");
            }
        } catch (Exception e) {
            FLog.e(TAG, "callOpenRouterForHtml failed", e);
            return null;
        }
    }

    // ── AI narrative + b-roll suggestions (read-only; propose then confirm) ──

    private static final String NARRATIVE_PROMPT = """
        You are an editorial assistant restructuring a single continuous recording
        into the clearest, most persuasive sequence of segments.
        INPUT: a transcript as words with [startMs-endMs] in source time, excluding
        already-removed spans.
        TASK:
        1. Segment into coherent chunks (one complete thought each). Prefer natural
           pauses; never split mid-sentence.
        2. One-sentence summary per chunk.
        3. KEEP or DROP each chunk. Drop only chunks you're confident weaken the
           argument (redundant restatements, tangents, false starts). When unsure, keep.
        4. Order KEPT chunks for the strongest case (may differ from recording order).
        OUTPUT: ONLY a JSON array, no commentary:
        [ { "startMs":12000, "endMs":47000, "summary":"...", "keep":true, "order":1 } ]
        "order" is 1-based among kept chunks; dropped chunks get null.
        """;

    private static final String BROLL_PROMPT = """
        You are a documentary editor choosing supplementary b-roll footage.
        INPUT: the narration transcript with timestamps, and a catalog of available
        b-roll assets (filename, type).
        TASK: Identify moments that would benefit from a visual cutaway (the narrator
        describes something visually demonstrable, references a concrete object/place/
        process, or a long static stretch needs a visual break). For each, choose the
        single best-matching available asset by filename. Do not use the same asset
        more than twice. Never suggest a cutaway shorter than 1.5s or longer than 8s,
        nor inside the first or last 2s of the recording.
        OUTPUT: ONLY a JSON array, no commentary:
        [ { "atMs":64000, "durationMs":3500, "assetName":"sunset.mp4", "reason":"..." } ]
        """;

    private String toolAnalyzeNarrativeStructure(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = clipId.isEmpty()
                ? (proj.getTimeline().getClipCount() > 0 ? proj.getTimeline().getClip(0) : null)
                : findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        NamedTranscript nt = clip.getActiveNamedTranscript();
        if (nt == null || nt.transcript.words.isEmpty())
            return "Error: clip has no transcript. Run generate_transcript first.";
        com.fadcam.ui.faditor.transcript.Transcript windowed =
                nt.transcript.windowed(clip.getInPointMs(), clip.getOutPointMs());
        if (windowed.words.isEmpty())
            return "Error: no transcript words in clip's visible region. Run generate_transcript first.";

        StringBuilder words = new StringBuilder();
        for (var w : windowed.words) {
            if (w.struck) continue;
            words.append("[").append(w.startMs).append("-").append(w.endMs).append("] ")
                    .append(w.text).append("\n");
        }
        String[] km = apiKeyModel();
        if (km == null) return "Error: no AI API key configured (Settings → AI).";

        String reply = callOpenRouterForHtml(km[0], km[1], NARRATIVE_PROMPT, words.toString());
        if (reply == null) return "Error: model call failed.";
        String json = SlideContract.stripFences(reply).trim();
        // Machine-parseable sentinel so the chat layer can render a visual KEEP/DROP card; the
        // human text below keeps the transcript readable and the conversational apply path working.
        String payload = "{\"clipId\":\"" + clip.getId() + "\",\"chunks\":" + json + "}";
        return "@@PROPOSAL:narrative@@" + payload + "\n"
                + "NARRATIVE PROPOSAL for clip " + clip.getId()
                + " (read-only — confirm before applying):\n" + json
                + "\n\nA confirmation card is shown to the user — WAIT for them to review and tap Apply."
                + " If they instead tell you to apply in chat, call apply_narrative_proposal with"
                + " {\"clipId\":\"" + clip.getId() + "\",\"chunks\":<this array>} (it builds the"
                + " splits+reorder with matching ids). Drop a chunk by setting keep:false.";
    }

    /**
     * Apply a CONFIRMED narrative proposal: split one clip into chunks at the proposed boundaries and
     * reorder/drop them. Builds a deterministic {@code [SPLIT_CLIP_AT_TIME×(N-1), REORDER_CLIPS]}
     * script with matching ids (the second-half id of each split is pinned, so REORDER can reference
     * it) and applies it atomically via {@link EditScriptApplier} (which validates structural scripts
     * by simulation). Clips other than the target are preserved in their original positions.
     */
    /**
     * Decision 5 (narrative spec): snap a proposed chunk boundary to the middle
     * of the nearest detected silence gap within 300ms — cutting mid-silence is
     * the cleanest possible cut. Returns the original boundary when no gap is
     * near or the gap's midpoint would leave the clip trim.
     */
    private static long snapBoundaryToSilence(long boundary,
                                              @Nullable java.util.List<long[]> gaps,
                                              long inPoint, long outPoint) {
        if (gaps == null || gaps.isEmpty()) return boundary;
        long best = boundary;
        long bestDist = 301;
        for (long[] g : gaps) {
            long nearest = Math.max(g[0], Math.min(g[1], boundary));
            long dist = Math.abs(nearest - boundary);
            if (dist < bestDist) {
                long mid = (g[0] + g[1]) / 2;
                if (mid > inPoint + 100 && mid < outPoint - 100) {
                    best = mid;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    private String toolApplyNarrativeProposal(@NonNull JSONObject args) {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        String clipId = args.optString("clipId", "");
        Timeline tl = proj.getTimeline();
        Clip clip = clipId.isEmpty()
                ? (tl.getClipCount() > 0 ? tl.getClip(0) : null)
                : findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        clipId = clip.getId();

        JSONArray chunks = args.optJSONArray("chunks");
        if (chunks == null || chunks.length() == 0) return "Error: 'chunks' array is required";

        // Parse + sort chunks by source start time.
        java.util.List<long[]> spans = new java.util.ArrayList<>(); // [startMs, endMs]
        java.util.List<Boolean> keep = new java.util.ArrayList<>();
        java.util.List<Integer> order = new java.util.ArrayList<>();
        java.util.List<Integer> idxOrder = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.length(); i++) {
            JSONObject c = chunks.optJSONObject(i);
            if (c == null) continue;
            spans.add(new long[]{c.optLong("startMs", -1), c.optLong("endMs", -1)});
            keep.add(c.optBoolean("keep", true));
            order.add(c.has("order") && !c.isNull("order") ? c.optInt("order", Integer.MAX_VALUE)
                    : Integer.MAX_VALUE);
            idxOrder.add(spans.size() - 1);
        }
        int n = spans.size();
        if (n == 0) return "Error: no usable chunks";
        // Sort chunk indices by start time so piece i ↔ chunk i.
        idxOrder.sort((a, b) -> Long.compare(spans.get(a)[0], spans.get(b)[0]));

        int keptCount = 0;
        for (boolean k : keep) if (k) keptCount++;
        if (keptCount == 0) return "Error: proposal drops every chunk — refusing to empty the clip.";
        if (n == 1) {
            return keep.get(0)
                    ? "Proposal keeps the whole clip unchanged — nothing to apply."
                    : "Error: cannot drop the only chunk of a single-chunk clip.";
        }

        // Deterministic piece ids (sorted order) + intermediate "rest" ids.
        String[] pieceId = new String[n];
        for (int i = 0; i < n; i++) pieceId[i] = clipId + "__nr" + i;

        long inPoint = clip.getInPointMs();
        long outPoint = clip.getOutPointMs();
        try {
            JSONArray ops = new JSONArray();
            String restId = clipId;
            java.util.List<long[]> silenceGaps = clip.getSilenceCandidates();
            long prevBoundary = inPoint;
            for (int i = 0; i < n - 1; i++) {
                long boundary = spans.get(idxOrder.get(i))[1]; // this chunk's end == next chunk's start
                // Decision 5: word-timestamp boundaries are rarely clean cut points —
                // snap to the middle of the nearest detected silence gap within 300ms
                // (skipping any snap that would break boundary monotonicity).
                long snapped = snapBoundaryToSilence(boundary, silenceGaps, inPoint, outPoint);
                if (snapped > prevBoundary + 100) boundary = snapped;
                prevBoundary = boundary;
                if (boundary <= inPoint + 100 || boundary >= outPoint - 100) {
                    return "Error: chunk boundary " + boundary + " is not strictly inside the clip "
                            + "trim [" + inPoint + "," + outPoint + "]. Re-run analyze_narrative_structure.";
                }
                String firstId = pieceId[i];
                String secondId = (i == n - 2) ? pieceId[n - 1] : (clipId + "__rest" + (i + 1));
                JSONObject op = new JSONObject();
                op.put("type", "SPLIT_CLIP_AT_TIME");
                op.put("clipId", restId);
                op.put("atSourceMs", boundary);
                op.put("firstClipId", firstId);
                op.put("secondClipId", secondId);
                ops.put(op);
                restId = secondId;
            }

            // Build newOrder: clips before the target, then kept pieces (by 'order'), then clips after.
            int oIndex = indexOfClipId(tl, clipId);
            JSONArray newOrder = new JSONArray();
            for (int i = 0; i < tl.getClipCount(); i++) {
                if (i != oIndex) {
                    if (i < oIndex) newOrder.put(tl.getClip(i).getId());
                } // after-clips appended below
            }
            // kept piece indices sorted by their 'order' value
            java.util.List<Integer> keptPieces = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                int chunkIdx = idxOrder.get(i);
                if (keep.get(chunkIdx)) keptPieces.add(i);
            }
            keptPieces.sort((a, b) -> Integer.compare(order.get(idxOrder.get(a)),
                    order.get(idxOrder.get(b))));
            for (int pi : keptPieces) newOrder.put(pieceId[pi]);
            for (int i = oIndex + 1; i < tl.getClipCount(); i++) newOrder.put(tl.getClip(i).getId());

            JSONObject reorder = new JSONObject();
            reorder.put("type", "REORDER_CLIPS");
            reorder.put("newOrder", newOrder);
            ops.put(reorder);

            JSONObject scriptObj = new JSONObject();
            scriptObj.put("version", 1);
            scriptObj.put("description", "Apply narrative proposal (" + keptCount + " of " + n
                    + " chunks kept)");
            scriptObj.put("operations", ops);

            EditScript script = EditScript.fromJson(scriptObj.toString());
            EditScriptApplier applier = new EditScriptApplier();
            applier.setContext(context);
            EditScriptApplier.Result result = applier.apply(proj, script);
            if (!result.success) return "Apply failed: " + result.error;
            storage.save(proj);
            AIChatState.signalModified(projectId);
            return "Restructured the recording: split into " + n + " chunks, kept " + keptCount
                    + " in the proposed order (" + result.appliedCount + " ops applied).";
        } catch (Exception e) {
            return "Error building narrative script: " + e.getMessage();
        }
    }

    private static int indexOfClipId(@NonNull Timeline tl, @NonNull String id) {
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (id.equals(tl.getClip(i).getId())) return i;
        }
        return -1;
    }

    /**
     * Apply CONFIRMED b-roll cutaways: build one {@code INSERT_BROLL_CUTAWAY} op per accepted item
     * and apply atomically. Cutaways preserve total timeline duration (split before/broll/after +
     * narration audio underneath), so multiple inserts don't shift each other's {@code atMs}; the
     * structural-simulation apply path validates them sequentially.
     */
    private String toolApplyBrollProposal(@NonNull JSONObject args) {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        JSONArray cutaways = args.optJSONArray("cutaways");
        if (cutaways == null || cutaways.length() == 0) return "Error: 'cutaways' array is required";
        try {
            JSONArray ops = new JSONArray();
            int n = 0;
            for (int i = 0; i < cutaways.length(); i++) {
                JSONObject c = cutaways.optJSONObject(i);
                if (c == null) continue;
                long atMs = c.optLong("atMs", -1);
                long dur = c.optLong("durationMs", -1);
                String uri = c.optString("assetUri", "");
                if (atMs < 0 || dur <= 0 || uri.isEmpty()) continue;
                JSONObject op = new JSONObject();
                op.put("type", "INSERT_BROLL_CUTAWAY");
                op.put("atMs", atMs);
                op.put("durationMs", dur);
                op.put("assetUri", uri);
                ops.put(op);
                n++;
            }
            if (n == 0) return "Error: no usable cutaways (each needs atMs, durationMs, assetUri).";
            JSONObject scriptObj = new JSONObject();
            scriptObj.put("version", 1);
            scriptObj.put("description", "Apply " + n + " b-roll cutaway(s)");
            scriptObj.put("operations", ops);

            EditScript script = EditScript.fromJson(scriptObj.toString());
            EditScriptApplier applier = new EditScriptApplier();
            applier.setContext(context);
            EditScriptApplier.Result result = applier.apply(proj, script);
            if (!result.success) return "Apply failed: " + result.error;
            storage.save(proj);
            AIChatState.signalModified(projectId);
            return "Inserted " + result.appliedCount + " b-roll cutaway(s) (narration kept underneath).";
        } catch (Exception e) {
            return "Error building b-roll script: " + e.getMessage();
        }
    }

    private String toolSuggestBrollPlacements(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = clipId.isEmpty()
                ? (proj.getTimeline().getClipCount() > 0 ? proj.getTimeline().getClip(0) : null)
                : findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        NamedTranscript nt = clip.getActiveNamedTranscript();
        if (nt == null || nt.transcript.words.isEmpty())
            return "Error: clip has no transcript. Run generate_transcript first.";
        com.fadcam.ui.faditor.transcript.Transcript windowed =
                nt.transcript.windowed(clip.getInPointMs(), clip.getOutPointMs());
        if (windowed.words.isEmpty())
            return "Error: no transcript words in clip's visible region. Run generate_transcript first.";

        BRollBucket bucket = new BRollBucket(context);
        java.util.List<BRollBucket.AssetEntry> assets = bucket.listAssets();
        java.util.Map<String, BRollBucket.AssetEntry> byName = new java.util.HashMap<>();
        // Phase 3: cached vision tags ride along so the model matches on CONTENT,
        // not just filenames (tag_broll_assets builds/refreshes the cache).
        org.json.JSONObject tagIndex = bucket.loadTagIndex();
        StringBuilder catalog = new StringBuilder("AVAILABLE B-ROLL:\n");
        for (BRollBucket.AssetEntry a : assets) {
            if (a.isFont) continue;
            byName.put(a.name, a);
            catalog.append("  ").append(a.name).append(" (").append(a.type).append(")");
            String tags = bucket.tagsSummaryFor(tagIndex, a);
            if (tags != null) catalog.append(" — ").append(tags);
            catalog.append('\n');
        }
        if (byName.isEmpty()) return "Error: no b-roll assets. " + bucket.getAssetsSummary();

        StringBuilder words = new StringBuilder();
        for (var w : windowed.words) {
            if (w.struck) continue;
            words.append("[").append(w.startMs).append("] ").append(w.text).append(" ");
        }
        String[] km = apiKeyModel();
        if (km == null) return "Error: no AI API key configured (Settings → AI).";

        String user = catalog + "\nTRANSCRIPT:\n" + words;
        String reply = callOpenRouterForHtml(km[0], km[1], BROLL_PROMPT, user);
        if (reply == null) return "Error: model call failed.";

        long total = clip.getTrimmedDurationMs();
        java.util.Map<String, Integer> useCount = new java.util.HashMap<>();
        JSONArray accepted = new JSONArray();
        try {
            JSONArray arr = new JSONArray(SlideContract.stripFences(reply).trim());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                long atMs = s.optLong("atMs", -1);
                long dur = s.optLong("durationMs", -1);
                String name = s.optString("assetName", "");
                if (dur < 1500 || dur > 8000) continue;            // guardrail: duration
                if (atMs < 2000 || atMs + dur > total - 2000) continue; // guardrail: edges
                BRollBucket.AssetEntry a = byName.get(name);
                if (a == null) continue;                            // guardrail: real asset
                int c = useCount.getOrDefault(name, 0);
                if (c >= 2) continue;                               // guardrail: ≤2 uses
                useCount.put(name, c + 1);
                JSONObject out = new JSONObject();
                out.put("atMs", atMs);
                out.put("durationMs", dur);
                out.put("assetName", name);
                out.put("assetUri", a.uri);
                out.put("reason", s.optString("reason", ""));
                accepted.put(out);
            }
        } catch (Exception e) {
            return "Error: could not parse model suggestions: " + e.getMessage();
        }
        if (accepted.length() == 0)
            return "No guardrail-compliant b-roll suggestions for this clip.";
        String payload = "{\"cutaways\":" + accepted + "}";
        return "@@PROPOSAL:broll@@" + payload + "\n"
                + "B-ROLL SUGGESTIONS for clip " + clip.getId()
                + " (read-only — confirm before applying):\n" + accepted
                + "\n\nA confirmation card is shown to the user — WAIT for them to review and tap Apply."
                + " If they instead tell you to apply in chat, call apply_broll_proposal with"
                + " {\"cutaways\":<the accepted array>} (each item already has assetUri).";
    }

    @Nullable
    private String[] apiKeyModel() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        String apiKey = prefs.sharedPreferences.getString("ai_api_key", "");
        String model = prefs.sharedPreferences.getString("ai_model", "openrouter/auto");
        if (apiKey == null || apiKey.isEmpty()) return null;
        return new String[]{apiKey, model};
    }

    // ── B-roll Phase 3: vision tagging of the asset bucket ──────────

    private static final String VISION_TAG_PROMPT = """
        You are indexing b-roll footage for a video editor's search catalog.
        Look at the supplied frame and output ONLY a JSON object, no commentary:
        { "tags": ["5-12 short lowercase tags: subjects, setting, mood, colors, motion"],
          "description": "one sentence a documentary editor would search by" }
        """;

    /**
     * Vision-tag untagged bucket assets via the configured multimodal model; results are
     * cached in the bucket's sidecar index ({@link BRollBucket#loadTagIndex()}) keyed by
     * filename + size, and automatically enrich list_broll / suggest_broll_placements.
     */
    private String toolTagBrollAssets(@NonNull JSONObject args) {
        boolean force = args.optBoolean("force", false);
        int maxAssets = Math.max(1, Math.min(20, args.optInt("maxAssets", 10)));
        String[] km = apiKeyModel();
        if (km == null) return "Error: no AI API key configured (Settings → AI).";
        BRollBucket bucket = new BRollBucket(context);
        org.json.JSONObject index = bucket.loadTagIndex();
        java.util.List<BRollBucket.AssetEntry> assets = bucket.listAssets();
        int tagged = 0, failed = 0, cached = 0, attempted = 0;
        StringBuilder report = new StringBuilder();
        for (BRollBucket.AssetEntry a : assets) {
            if (a.isFont) continue;
            if (!force && bucket.tagsSummaryFor(index, a) != null) {
                cached++;
                continue;
            }
            if (attempted >= maxAssets) break;
            attempted++;
            notifyProgress("Tagging " + a.name, (attempted * 100) / maxAssets);
            String b64 = assetThumbnailBase64(a);
            if (b64 == null) {
                failed++;
                report.append("  ").append(a.name).append(": could not extract a frame\n");
                continue;
            }
            String reply = callOpenRouterVision(km[0], km[1], VISION_TAG_PROMPT,
                    "Filename: " + a.name + (a.isVideo ? " (a frame from a video)" : " (an image)"),
                    b64);
            if (reply == null) {
                failed++;
                report.append("  ").append(a.name)
                        .append(": model call failed (is the configured model multimodal?)\n");
                continue;
            }
            try {
                JSONObject parsed = new JSONObject(SlideContract.stripFences(reply).trim());
                JSONArray tagsArr = parsed.optJSONArray("tags");
                StringBuilder tags = new StringBuilder();
                if (tagsArr != null) {
                    for (int i = 0; i < tagsArr.length(); i++) {
                        String t = tagsArr.optString(i, "").trim();
                        if (t.isEmpty()) continue;
                        if (tags.length() > 0) tags.append(", ");
                        tags.append(t);
                    }
                }
                JSONObject entry = new JSONObject();
                entry.put("tags", tags.toString());
                entry.put("description", parsed.optString("description", ""));
                entry.put("sizeBytes", a.sizeBytes);
                entry.put("taggedAtMs", System.currentTimeMillis());
                index.put(a.name, entry);
                tagged++;
                report.append("  ").append(a.name).append(" → ").append(tags).append('\n');
            } catch (Exception e) {
                failed++;
                report.append("  ").append(a.name).append(": unparseable model reply\n");
            }
        }
        bucket.saveTagIndex(index);
        boolean more = attempted >= maxAssets;
        return "B-roll vision tagging: " + tagged + " newly tagged, " + cached
                + " already cached, " + failed + " failed.\n" + report
                + (more ? "(Batch cap reached — call tag_broll_assets again for the rest.)" : "");
    }

    /**
     * A small JPEG thumbnail of the asset as base64 (image decode or a video frame at
     * ~1s), longest edge ≤512px. Null when nothing decodable.
     */
    @Nullable
    private String assetThumbnailBase64(@NonNull BRollBucket.AssetEntry a) {
        android.graphics.Bitmap bmp = null;
        try {
            android.net.Uri uri = android.net.Uri.parse(a.uri);
            if (a.isVideo) {
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                try {
                    mmr.setDataSource(context, uri);
                    bmp = mmr.getFrameAtTime(1_000_000L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (bmp == null) bmp = mmr.getFrameAtTime();
                } finally {
                    try { mmr.release(); } catch (Exception ignored) {}
                }
            } else {
                try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                    android.graphics.BitmapFactory.Options opts =
                            new android.graphics.BitmapFactory.Options();
                    opts.inSampleSize = 4; // bucket assets can be large; a rough decode is plenty
                    bmp = android.graphics.BitmapFactory.decodeStream(in, null, opts);
                }
            }
            if (bmp == null) return null;
            int longEdge = Math.max(bmp.getWidth(), bmp.getHeight());
            if (longEdge > 512) {
                float s = 512f / longEdge;
                bmp = android.graphics.Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(bmp.getWidth() * s)),
                        Math.max(1, Math.round(bmp.getHeight() * s)), true);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            FLog.w(TAG, "assetThumbnailBase64 failed for " + a.name, e);
            return null;
        }
    }

    /** Chat-completions call with an image part (OpenRouter multimodal convention). */
    @Nullable
    private String callOpenRouterVision(@NonNull String apiKey, @NonNull String model,
                                        @NonNull String system, @NonNull String userText,
                                        @NonNull String base64Jpeg) {
        try {
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", userText));
            content.put(new JSONObject().put("type", "image_url").put("image_url",
                    new JSONObject().put("url", "data:image/jpeg;base64," + base64Jpeg)));
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", content));
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", 600);
            body.put("temperature", 0.2);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json")))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    FLog.e(TAG, "Vision tag API error " + response.code() + ": "
                            + respBody.substring(0, Math.min(200, respBody.length())));
                    return null;
                }
                JSONObject json = new JSONObject(respBody);
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) return null;
                return choices.getJSONObject(0).getJSONObject("message").getString("content");
            }
        } catch (Exception e) {
            FLog.e(TAG, "callOpenRouterVision failed", e);
            return null;
        }
    }

    // ── Sprite / avatar AI authoring (FF-B + A5) ─────────────────────

    /**
     * FF-B describe_sprite_sheet: a deterministic, no-network structured read of a
     * sprite sheet (project sheet by id, or a raw image by uri) for the model —
     * dimensions, grid, and per-cell bounding boxes + alpha occupancy + names. Grid
     * geometry is the single authority ({@link com.fadcam.ui.faditor.sprite.SpriteSheetRenderer#cellRectSource});
     * a raw image gets an auto-detected grid ({@link com.fadcam.ui.faditor.sprite.SpriteGridDetector}).
     */
    /**
     * SPEC_IMAGE_SEQUENCE §7a — read a sequence's timing. Deterministic, no network.
     * Omit {@code objectId} to list the project's sequences.
     */
    private String toolDescribeSequence(@NonNull JSONObject args) {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        String id = args.optString("objectId", "");
        if (id.isEmpty()) return SequenceAiOps.listSequences(proj);
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem item = SequenceAiOps.itemById(proj, id);
        if (item == null) {
            return "Error: no object with id '" + id + "'.\n" + SequenceAiOps.listSequences(proj);
        }
        try {
            return SequenceAiOps.describe(proj, item);
        } catch (Exception e) {
            FLog.e(TAG, "describe_sequence failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * SPEC_IMAGE_SEQUENCE §7b — edit a sequence's timing.
     *
     * <p>Saves and signals exactly like the other mutating tools, which is this app's ONE-undo-
     * step granularity for AI edits: the editor reloads the whole project on
     * {@code signalModified}, so an AI edit reverses in a single press with the history behind
     * it intact (LEDGER §4). The audit re-verified in 2026-08-05 that AI edits already undo from
     * whole-project snapshots, so routing these through a bespoke EditScript operation would add
     * a second snapshot discipline without adding safety.</p>
     */
    private String toolEditSequence(@NonNull JSONObject args) {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        String id = args.optString("objectId", "");
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem item = SequenceAiOps.itemById(proj, id);
        if (item == null) {
            return "Error: no object with id '" + id + "'.\n" + SequenceAiOps.listSequences(proj);
        }
        try {
            String result = SequenceAiOps.edit(proj, item, args);
            // Only persist when the op actually succeeded. Saving after an error string would
            // write whatever half-state the failed branch left — and then report the failure,
            // which is the worst of both.
            if (result.startsWith("Error:")) return result;
            storage.save(proj);
            AIChatState.signalModified(projectId);
            return result;
        } catch (Exception e) {
            FLog.e(TAG, "edit_sequence failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private String toolDescribeSpriteSheet(@NonNull JSONObject args) {
        String sheetId = args.optString("sheetId", "");
        String imageUri = args.optString("imageUri", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        com.fadcam.ui.faditor.sprite.SpriteSheet sheet = null;
        String decodeUri;
        if (!sheetId.isEmpty()) {
            sheet = proj.spriteSheetById(sheetId);
            if (sheet == null) {
                return "Error: no sprite sheet with id '" + sheetId + "'. "
                        + availableSheetsLine(proj);
            }
            decodeUri = sheet.getSheetUri();
        } else if (!imageUri.isEmpty()) {
            decodeUri = imageUri;
        } else {
            // No target — list the project's sheets so the model can pick one.
            return availableSheetsLine(proj);
        }

        try {
            android.net.Uri uri = android.net.Uri.parse(decodeUri);
            // Bounds decode → true source dimensions.
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            int trueW = bounds.outWidth, trueH = bounds.outHeight;
            if (trueW <= 0 || trueH <= 0) return "Error: could not decode image at " + decodeUri;

            int maxEdge = 1024;
            int sample = 1;
            while (Math.max(trueW, trueH) / (sample * 2) >= maxEdge) sample *= 2;
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            android.graphics.Bitmap bmp;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                bmp = android.graphics.BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return "Error: could not decode image at " + decodeUri;

            boolean detected = false;
            if (sheet == null) {
                // Raw image: auto-detect a grid (in decoded space) → scale to source.
                sheet = com.fadcam.ui.faditor.sprite.SpriteSheet.create("scan", decodeUri);
                com.fadcam.ui.faditor.sprite.SpriteGridDetector.Result r =
                        com.fadcam.ui.faditor.sprite.SpriteGridDetector.detect(bmp, 0);
                if (r != null) {
                    sheet.setGrid(r.cols, r.rows);
                    sheet.setMargins(r.marginX * sample, r.marginY * sample);
                    sheet.setSpacing(r.spacingX * sample, r.spacingY * sample);
                    detected = true;
                } else {
                    sheet.setGrid(1, 1); // no confident grid → whole image is one cell
                }
            }

            JSONObject out = new JSONObject();
            out.put("source", sheetId.isEmpty() ? "image" : ("sheet:" + sheetId));
            out.put("imageWidth", trueW);
            out.put("imageHeight", trueH);
            JSONObject grid = new JSONObject();
            grid.put("cols", sheet.getCols());
            grid.put("rows", sheet.getRows());
            grid.put("marginX", sheet.getMarginX());
            grid.put("marginY", sheet.getMarginY());
            grid.put("spacingX", sheet.getSpacingX());
            grid.put("spacingY", sheet.getSpacingY());
            grid.put("detected", detected);
            out.put("grid", grid);

            int cellCount = sheet.cellCount();
            if (cellCount > 256) {
                out.put("note", "grid has " + cellCount
                        + " cells — per-cell detail omitted (over 256).");
            } else {
                JSONArray cells = new JSONArray();
                int bw = bmp.getWidth(), bh = bmp.getHeight();
                for (int i = 0; i < cellCount; i++) {
                    android.graphics.Rect src =
                            com.fadcam.ui.faditor.sprite.SpriteSheetRenderer
                                    .cellRectSource(sheet, i, trueW, trueH);
                    JSONObject cj = new JSONObject();
                    cj.put("index", i);
                    com.fadcam.ui.faditor.sprite.SpriteSheet.Cell named = sheet.cellAt(i);
                    if (named != null && !named.name.isEmpty()) cj.put("name", named.name);
                    cj.put("x", src.left);
                    cj.put("y", src.top);
                    cj.put("w", src.width());
                    cj.put("h", src.height());
                    cj.put("occupancy", Math.round(
                            cellOccupancy(bmp, src, sample, bw, bh) * 100f) / 100f);
                    cells.put(cj);
                }
                out.put("cells", cells);
            }
            bmp.recycle();
            return "Sprite sheet description:\n" + out.toString();
        } catch (Exception e) {
            FLog.e(TAG, "describe_sprite_sheet failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /** Fraction (0..1) of non-transparent pixels in {@code srcRect}, scanned on the
     *  decoded (downsampled) bitmap with a bounded, deterministic sample step. */
    private static float cellOccupancy(@NonNull android.graphics.Bitmap bmp,
                                       @NonNull android.graphics.Rect srcRect,
                                       int sample, int bw, int bh) {
        int l = Math.max(0, srcRect.left / sample);
        int t = Math.max(0, srcRect.top / sample);
        int r = Math.min(bw, srcRect.right / sample);
        int b = Math.min(bh, srcRect.bottom / sample);
        if (r <= l || b <= t) return 0f;
        int cw = r - l, ch = b - t;
        int step = Math.max(1, (int) Math.sqrt((cw * (long) ch) / 4096.0)); // ≤ ~4096 samples
        long total = 0, opaque = 0;
        for (int y = t; y < b; y += step) {
            for (int x = l; x < r; x += step) {
                total++;
                if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 24) opaque++;
            }
        }
        return total == 0 ? 0f : (float) opaque / total;
    }

    @NonNull
    private static String availableSheetsLine(@NonNull FaditorProject proj) {
        java.util.List<com.fadcam.ui.faditor.sprite.SpriteSheet> sheets = proj.getSpriteSheets();
        if (sheets.isEmpty()) return "This project has no sprite sheets yet.";
        StringBuilder sb = new StringBuilder("Project sprite sheets: ");
        for (int i = 0; i < sheets.size(); i++) {
            com.fadcam.ui.faditor.sprite.SpriteSheet s = sheets.get(i);
            if (i > 0) sb.append(", ");
            sb.append('"').append(s.getName()).append("\" (id ").append(s.getId())
                    .append(", ").append(s.getCols()).append('×').append(s.getRows()).append(')');
        }
        return sb.toString();
    }

    /** The 'rig' arg may arrive as a nested JSON object or a JSON string. */
    @Nullable
    private static String extractRigJson(@NonNull JSONObject args) {
        Object r = args.opt("rig");
        if (r instanceof JSONObject) return r.toString();
        if (r instanceof String) {
            String s = ((String) r).trim();
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    /**
     * A5 author_avatar_rig (PROPOSE): validate model-emitted rig JSON against the biped
     * template and, if clean, hand the user a confirm card. Reject-with-reasons on any
     * problem so the model can fix and re-emit — never crash, never auto-insert.
     */
    private String toolAuthorAvatarRig(@NonNull JSONObject args) {
        String rigJsonStr = extractRigJson(args);
        if (rigJsonStr == null)
            return "Error: 'rig' (the rig JSON) is required. Emit it against the biped template "
                    + "(parts head/body/armL/armR/handL/handR/mouth, each with a project sheetId).";
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        com.google.gson.JsonObject rj;
        try {
            rj = com.google.gson.JsonParser.parseString(rigJsonStr).getAsJsonObject();
        } catch (Exception e) {
            return "Error: rig is not valid JSON: " + e.getMessage();
        }
        com.fadcam.ui.faditor.avatar.AvatarRig rig =
                com.fadcam.ui.faditor.avatar.AvatarRig.fromJson(rj);

        java.util.Set<String> sheetIds = new java.util.HashSet<>();
        for (com.fadcam.ui.faditor.sprite.SpriteSheet s : proj.getSpriteSheets())
            sheetIds.add(s.getId());
        java.util.List<String> reasons =
                com.fadcam.ui.faditor.avatar.AvatarRigValidator.validate(rig, sheetIds);
        if (!reasons.isEmpty()) {
            StringBuilder sb = new StringBuilder("Rig validation failed — fix and re-emit:\n");
            for (String r : reasons) sb.append("  • ").append(r).append('\n');
            sb.append(availableSheetsLine(proj));
            return sb.toString();
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("rig", new JSONObject(rig.toJson().toString()));
            payload.put("name", rig.getName());
            payload.put("partCount", rig.getParts().size());
            payload.put("domainCount", rig.getDomains().size());
            return "@@PROPOSAL:avatar_rig@@" + payload + "\n"
                    + "AVATAR RIG PROPOSAL \"" + rig.getName() + "\" ("
                    + rig.getParts().size() + " parts, " + rig.getDomains().size()
                    + " pose domains — extremes unauthored, ready to tune in Avatar Studio).\n"
                    + "A confirmation card is shown to the user — WAIT for them to tap Apply. "
                    + "If they instead tell you to apply in chat, call apply_avatar_rig with "
                    + "{\"rig\":<this rig JSON>}.";
        } catch (Exception e) {
            return "Error building proposal: " + e.getMessage();
        }
    }

    /**
     * A5 apply_avatar_rig (CONFIRM): register a validated rig in the project as one
     * undoable step (the editor reloads on signalModified, exactly like the narrative /
     * b-roll apply tools). Idempotent by rig id — re-applying replaces the same rig.
     */
    /**
     * FF-B: set a sprite sheet's GRID (PLAN_SPRITE_ANIMATION Fast-Follow B).
     *
     * <p>The natural companion to {@code describe_sprite_sheet}, which already reports an
     * auto-detected grid suggestion for a raw image but had no way to act on it. Reading a
     * suggestion the model cannot apply is a conversation that always ends in the user typing
     * numbers by hand.</p>
     *
     * <p>Every field is OPTIONAL and absent means "leave it alone", so a call that only fixes
     * the column count cannot silently reset margins someone tuned by eye.</p>
     */
    private String toolSetSpriteGrid(@NonNull JSONObject args) {
        String sheetId = args.optString("sheetId", "");
        if (sheetId.isEmpty()) return "Error: 'sheetId' is required.";
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        com.fadcam.ui.faditor.sprite.SpriteSheet sheet = proj.spriteSheetById(sheetId);
        if (sheet == null) {
            return "Error: no sprite sheet with id '" + sheetId + "'. " + availableSheetsLine(proj);
        }

        int cols = args.optInt("cols", sheet.getCols());
        int rows = args.optInt("rows", sheet.getRows());
        if (cols < 1 || rows < 1) return "Error: cols and rows must both be at least 1.";
        // A cell index is (row * cols + col), so changing the grid RENUMBERS every cell. Presets
        // hold cell indices, so silently reshaping a sheet that animations already reference
        // would scramble them — refuse instead, and say what to do about it.
        if ((cols != sheet.getCols() || rows != sheet.getRows())
                && !sheet.getPresets().isEmpty()) {
            return "Error: this sheet has " + sheet.getPresets().size() + " saved animation(s) "
                    + "whose frames are cell INDICES; changing the grid renumbers every cell and "
                    + "would scramble them. Delete or re-author the animations first, or set the "
                    + "grid before authoring any.";
        }

        StringBuilder changed = new StringBuilder();
        if (cols != sheet.getCols() || rows != sheet.getRows()) {
            sheet.setGrid(cols, rows);
            changed.append("grid ").append(cols).append("x").append(rows).append("; ");
        }
        if (args.has("marginX") || args.has("marginY")) {
            sheet.setMargins(args.optInt("marginX", sheet.getMarginX()),
                    args.optInt("marginY", sheet.getMarginY()));
            changed.append("margins; ");
        }
        if (args.has("spacingX") || args.has("spacingY")) {
            sheet.setSpacing(args.optInt("spacingX", sheet.getSpacingX()),
                    args.optInt("spacingY", sheet.getSpacingY()));
            changed.append("spacing; ");
        }
        if (args.has("fps")) {
            sheet.setFps((float) args.optDouble("fps", sheet.getFps()));
            changed.append("fps ").append(sheet.getFps()).append("; ");
        }
        if (changed.length() == 0) return "No change — every field matched the current grid.";
        storage.save(proj);
        return "Updated '" + sheet.getName() + "': " + changed
                + "now " + sheet.getCols() + "x" + sheet.getRows()
                + " (" + (sheet.getCols() * sheet.getRows()) + " cells) at " + sheet.getFps()
                + " fps.";
    }

    /**
     * FF-B: author a named ANIMATION (a {@code SpriteSheet.Preset}) — an ordered run of cells
     * with a cadence, optionally with per-frame weights.
     *
     * <p>This is the tool the sprite plan's Fast-Follow B was actually about: everything else
     * could be done by hand, but building a twelve-frame walk cycle by typing cell indices into
     * a UI is the job people give up on.</p>
     *
     * <p>Frames are validated against the sheet's own cell count BEFORE anything is saved. An
     * out-of-range index would resolve to a blank cell at render time, which reads as a
     * flickering animation rather than as a bad number.</p>
     */
    private String toolAuthorSpriteAnimation(@NonNull JSONObject args) {
        String sheetId = args.optString("sheetId", "");
        if (sheetId.isEmpty()) return "Error: 'sheetId' is required.";
        String name = args.optString("name", "").trim();
        if (name.isEmpty()) return "Error: 'name' is required (what to call this animation).";
        org.json.JSONArray framesJson = args.optJSONArray("frames");
        if (framesJson == null || framesJson.length() == 0) {
            return "Error: 'frames' must be a non-empty array of cell indices, e.g. [0,1,2,3].";
        }
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        com.fadcam.ui.faditor.sprite.SpriteSheet sheet = proj.spriteSheetById(sheetId);
        if (sheet == null) {
            return "Error: no sprite sheet with id '" + sheetId + "'. " + availableSheetsLine(proj);
        }

        int cellCount = Math.max(0, sheet.getCols() * sheet.getRows());
        java.util.List<Integer> frames = new java.util.ArrayList<>();
        for (int i = 0; i < framesJson.length(); i++) {
            int cell = framesJson.optInt(i, -1);
            if (cell < 0 || cell >= cellCount) {
                return "Error: frame " + i + " is cell " + cell + ", but this sheet has "
                        + cellCount + " cells (0.." + (cellCount - 1) + ").";
            }
            frames.add(cell);
        }

        String type = args.optString("type", "loop");
        if (!"loop".equals(type) && !"pingpong".equals(type) && !"once".equals(type)) {
            return "Error: 'type' must be loop, pingpong or once.";
        }

        // Weights are OPTIONAL and stay empty when uniform — an unweighted preset then
        // serialises byte-identically to one authored before weights existed, and resolves down
        // the identical code path (SPEC_IMAGE_SEQUENCE §2).
        java.util.List<Integer> weights = new java.util.ArrayList<>();
        org.json.JSONArray weightsJson = args.optJSONArray("weights");
        if (weightsJson != null && weightsJson.length() > 0) {
            if (weightsJson.length() != frames.size()) {
                return "Error: 'weights' has " + weightsJson.length() + " entries but 'frames' "
                        + "has " + frames.size() + "; they must be parallel.";
            }
            boolean anyNonDefault = false;
            for (int i = 0; i < weightsJson.length(); i++) {
                int w = weightsJson.optInt(i, 1);
                if (w < 1) return "Error: weight " + i + " is " + w + "; a frame must be held "
                        + "at least one tick.";
                weights.add(w);
                if (w != 1) anyNonDefault = true;
            }
            if (!anyNonDefault) weights.clear();
        }

        com.fadcam.ui.faditor.sprite.SpriteSheet.Preset preset =
                new com.fadcam.ui.faditor.sprite.SpriteSheet.Preset(
                        java.util.UUID.randomUUID().toString(), name);
        preset.type = type;
        preset.fps = (float) args.optDouble("fps", 0d);   // 0 = inherit the sheet's fps
        preset.frames.addAll(frames);
        preset.weights.addAll(weights);
        sheet.getPresets().add(preset);
        storage.save(proj);

        return "Added animation '" + name + "' to '" + sheet.getName() + "': "
                + frames.size() + " frames, " + type
                + (preset.fps > 0 ? " at " + preset.fps + " fps" : " at the sheet's fps")
                + (weights.isEmpty() ? "" : ", weighted")
                + ". id=" + preset.id;
    }

    private String toolApplyAvatarRig(@NonNull JSONObject args) {
        String rigJsonStr = extractRigJson(args);
        if (rigJsonStr == null) return "Error: 'rig' (the rig JSON) is required.";
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        com.google.gson.JsonObject rj;
        try {
            rj = com.google.gson.JsonParser.parseString(rigJsonStr).getAsJsonObject();
        } catch (Exception e) {
            return "Error: rig is not valid JSON: " + e.getMessage();
        }
        com.fadcam.ui.faditor.avatar.AvatarRig rig =
                com.fadcam.ui.faditor.avatar.AvatarRig.fromJson(rj);

        java.util.Set<String> sheetIds = new java.util.HashSet<>();
        for (com.fadcam.ui.faditor.sprite.SpriteSheet s : proj.getSpriteSheets())
            sheetIds.add(s.getId());
        java.util.List<String> reasons =
                com.fadcam.ui.faditor.avatar.AvatarRigValidator.validate(rig, sheetIds);
        if (!reasons.isEmpty()) {
            StringBuilder sb = new StringBuilder("Refusing to apply an invalid rig:\n");
            for (String r : reasons) sb.append("  • ").append(r).append('\n');
            return sb.toString();
        }

        com.fadcam.ui.faditor.avatar.AvatarRig existing = proj.avatarRigById(rig.getId());
        if (existing != null) proj.getAvatarRigs().remove(existing);
        proj.getAvatarRigs().add(rig);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Created avatar rig \"" + rig.getName() + "\" (" + rig.getParts().size()
                + " parts, " + rig.getDomains().size() + " pose domains). Open Avatar Studio "
                + "to arm the pose extremes and fine-tune pivots.";
    }

    private String toolSplitClip(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        long splitMs = args.optLong("splitMs", -1);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        Timeline tl = proj.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (tl.getClip(i).getId().equals(clipId)) {
                int result = tl.splitAt(i, splitMs);
                if (result >= 0) {
                    storage.save(proj);
                    AIChatState.signalModified(projectId);
                    return "Clip split at " + splitMs + "ms. New clips: "
                            + tl.getClip(result).getId() + ", "
                            + tl.getClip(result + 1).getId();
                }
                return "Error: split point out of range";
            }
        }
        return "Error: clip not found: " + clipId;
    }

    private String toolDeleteClip(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        Timeline tl = proj.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (tl.getClip(i).getId().equals(clipId)) {
                tl.removeClip(i);
                storage.save(proj);
                AIChatState.signalModified(projectId);
                return "Clip deleted: " + clipId;
            }
        }
        return "Error: clip not found: " + clipId;
    }

    private String toolSetClipSpeed(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        float speed = (float) args.optDouble("speed", 1.0);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        clip.setSpeedMultiplier(Math.max(0.25f, Math.min(4f, speed)));
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Speed set to " + speed + "x for clip " + clipId;
    }

    private String toolSetClipMuted(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        boolean muted = args.optBoolean("muted", false);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        clip.setAudioMuted(muted);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Clip " + clipId + " " + (muted ? "muted" : "unmuted");
    }

    private String toolToggleCaptions(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        boolean enabled = args.optBoolean("enabled", true);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        if (enabled && clip.getActiveNamedTranscript() == null) {
            return "Error: clip has no transcript. Generate a transcript first.";
        }
        clip.setCaptionsEnabled(enabled);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Captions " + (enabled ? "enabled" : "disabled") + " for clip " + clipId;
    }

    private String toolSetCaptionStyle(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        String style = args.optString("style", "pop");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        clip.setCaptionStyleId(style);
        clip.setCaptionsEnabled(true);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Caption style set to " + style + " for clip " + clipId;
    }

    private String toolSetCanvasPreset(@NonNull JSONObject args) {
        String preset = args.optString("preset", "original");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        proj.setCanvasPreset(preset);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Canvas set to " + preset;
    }

    private String toolAddTextOverlay(@NonNull JSONObject args) {
        String text = args.optString("text", "Text");
        float cx = (float) args.optDouble("centerX", 0.5);
        float cy = (float) args.optDouble("centerY", 0.5);
        float size = (float) args.optDouble("sizeFraction", 0.1);
        long startMs = args.optLong("startMs", 0);
        long endMs = args.optLong("endMs", Long.MAX_VALUE);
        int color = args.optInt("colorInt", 0xFFFFFFFF);

        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        com.fadcam.ui.faditor.model.TextOverlayItem item =
                new com.fadcam.ui.faditor.model.TextOverlayItem(text, color, cx, cy, size, 0f);
        item.setTimeRange(startMs, endMs);
        proj.getTimeline().addTextOverlay(item);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Text overlay added: id=" + item.getId() + " text=\"" + text + "\"";
    }

    private String toolRemoveTextOverlay(@NonNull JSONObject args) {
        String overlayId = args.optString("overlayId", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        for (com.fadcam.ui.faditor.model.TextOverlayItem o
                : proj.getTimeline().getTextOverlays()) {
            if (o.getId().equals(overlayId)) {
                proj.getTimeline().removeTextOverlay(o);
                storage.save(proj);
                AIChatState.signalModified(projectId);
                return "Overlay removed: " + overlayId;
            }
        }
        return "Error: overlay not found: " + overlayId;
    }

    private String toolRemoveSpan(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        long startMs = args.optLong("startMs", -1);
        long endMs = args.optLong("endMs", -1);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        if (startMs < 0 || endMs <= startMs) return "Error: invalid span";
        clip.getRemovedSpans().add(new long[]{startMs, endMs});
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Cut added: " + startMs + "-" + endMs + "ms on clip " + clipId;
    }

    private String toolAddOpacityKeyframe(@NonNull JSONObject args) {
        String overlayId = args.optString("overlayId", "");
        long timelineMs = args.optLong("timelineMs", 0);
        float opacity = (float) args.optDouble("opacity", 1.0);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        for (com.fadcam.ui.faditor.model.TextOverlayItem o
                : proj.getTimeline().getTextOverlays()) {
            if (o.getId().equals(overlayId)) {
                o.addOpacityKeyframeAt(timelineMs, opacity);
                storage.save(proj);
                AIChatState.signalModified(projectId);
                return "Opacity keyframe added: " + timelineMs + "ms opacity=" + opacity
                        + " on overlay " + overlayId;
            }
        }
        return "Error: overlay not found: " + overlayId;
    }

    private String toolAddTransition(@NonNull JSONObject args) {
        String typeStr = args.optString("type", "FADE_IN_FROM_BLACK");
        long durationMs = args.optLong("durationMs", 500);
        int clipIndex = args.optInt("clipIndex", 0);
        float fuzziness = (float) args.optDouble("fuzziness", 0.0);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        try {
            com.fadcam.ui.faditor.model.Transition.Type type =
                    com.fadcam.ui.faditor.model.Transition.Type.valueOf(typeStr);
            com.fadcam.ui.faditor.model.Transition t =
                    new com.fadcam.ui.faditor.model.Transition(type, durationMs, clipIndex, fuzziness);
            proj.getTimeline().addTransition(t);
            storage.save(proj);
            AIChatState.signalModified(projectId);
            return "Transition added: " + typeStr + " duration=" + durationMs
                    + "ms fuzziness=" + fuzziness + " on clip " + clipIndex;
        } catch (IllegalArgumentException e) {
            return "Error: unknown transition type: " + typeStr
                    + ". Valid types: FADE_IN_FROM_BLACK, FADE_OUT_TO_BLACK, "
                    + "FADE_IN_FROM_WHITE, FADE_OUT_TO_WHITE, CROSS_DISSOLVE, "
                    + "WIPE_LEFT, WIPE_RIGHT, WIPE_UP, WIPE_DOWN, "
                    + "PUSH_LEFT, PUSH_RIGHT, PUSH_UP, PUSH_DOWN";
        }
    }

    private String toolListBRoll() {
        BRollBucket bucket = new BRollBucket(context);
        return bucket.getAssetsSummary();
    }

    private String toolAddBRollOverlay(@NonNull JSONObject args) {
        String assetName = args.optString("assetName", "");
        float cx = (float) args.optDouble("centerX", 0.5);
        float cy = (float) args.optDouble("centerY", 0.5);
        float size = (float) args.optDouble("sizeFraction", 0.3);
        long startMs = args.optLong("startMs", 0);
        long endMs = args.optLong("endMs", Long.MAX_VALUE);

        if (assetName.isEmpty()) return "Error: assetName is required";

        BRollBucket bucket = new BRollBucket(context);
        BRollBucket.AssetEntry found = null;
        for (BRollBucket.AssetEntry a : bucket.listAssets()) {
            if (a.name.equals(assetName)) {
                found = a;
                break;
            }
        }
        if (found == null) return "Error: asset not found: " + assetName
                + ". Use list_broll to see available assets.";

        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        com.fadcam.ui.faditor.model.TextOverlayItem item =
                com.fadcam.ui.faditor.model.TextOverlayItem.createImage(found.uri, cx, cy, size);
        item.setTimeRange(startMs, endMs);
        proj.getTimeline().addTextOverlay(item);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "B-roll overlay added from asset '" + assetName + "': id=" + item.getId();
    }

    private String toolExportProject(@NonNull JSONObject args) {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        // Export is handled by the editor's ExportService — the AI can't
        // trigger it directly from a background thread, but it can tell
        // the user to press Export. Return a helpful message.
        return "Export must be triggered from the editor. Close this chat and tap the Export button (green upload icon). "
                + "The project is saved and ready with " + proj.getTimeline().getClipCount() + " clips.";
    }

    private String toolHealthCheck() {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        StringBuilder sb = new StringBuilder();
        sb.append("Project health check:\n");
        Timeline tl = proj.getTimeline();
        int issues = 0;

        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            // Check for missing media
            if (c.getSourceUri() == null) {
                sb.append("  ⚠ Clip ").append(i).append(": no source URI\n");
                issues++;
            }
            // Check for invalid trim
            if (c.getOutPointMs() <= c.getInPointMs()) {
                sb.append("  ⚠ Clip ").append(i).append(": invalid trim (out<=in)\n");
                issues++;
            }
            // Check for overlays outside timeline range
            long clipDur = c.getTrimmedDurationMs();
            if (clipDur <= 0) {
                sb.append("  ⚠ Clip ").append(i).append(": zero duration\n");
                issues++;
            }
        }

        // Check overlays
        for (com.fadcam.ui.faditor.model.TextOverlayItem o : tl.getTextOverlays()) {
            if (o.getEndMs() != Long.MAX_VALUE && o.getEndMs() <= o.getStartMs()) {
                sb.append("  ⚠ Overlay ").append(o.getId()).append(": invalid time range\n");
                issues++;
            }
        }

        if (issues == 0) {
            sb.append("  ✓ No issues found. ");
            sb.append(tl.getClipCount()).append(" clips, ");
            sb.append(tl.getTextOverlays().size()).append(" overlays, ");
            sb.append(tl.hasAudioClips() ? tl.getAudioClipCount() + " audio clips" : "no audio clips");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String toolAutoChapters(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;

        java.util.List<NamedTranscript> versions = clip.getTranscripts();
        if (versions.isEmpty()) return "Error: no transcript. Generate one first.";
        NamedTranscript nt = clip.getActiveNamedTranscript();
        if (nt == null) nt = versions.get(0);

        // Simple chapter detection: look for long pauses (>1.5s between words)
        // or sentence boundaries as chapter markers.
        StringBuilder sb = new StringBuilder();
        sb.append("Suggested chapters for clip ").append(clipId).append(":\n");
        int chapter = 1;
        sb.append("Chapter 1: 0ms — ").append(nt.transcript.words.isEmpty() ? "" : nt.transcript.words.get(0).text).append("\n");

        for (int i = 1; i < nt.transcript.words.size(); i++) {
            var prev = nt.transcript.words.get(i - 1);
            var curr = nt.transcript.words.get(i);
            long gap = curr.startMs - prev.endMs;
            // New chapter on >2s pause or after a sentence-ending word
            if (gap > 2000 || prev.text.endsWith(".") || prev.text.endsWith("!") || prev.text.endsWith("?")) {
                chapter++;
                sb.append("Chapter ").append(chapter).append(": ").append(curr.startMs)
                        .append("ms — ").append(curr.text).append("\n");
            }
        }
        sb.append("\n(To create text overlays from these, ask me to add chapter titles.)");
        return sb.toString();
    }

    private String toolGetTranscript(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;

        java.util.List<NamedTranscript> versions = clip.getTranscripts();
        if (versions.isEmpty()) return "Error: no transcript for clip " + clipId;
        NamedTranscript nt = clip.getActiveNamedTranscript();
        if (nt == null) nt = versions.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("Transcript for clip ").append(clipId).append(" (").append(nt.engine).append("):\n");
        for (var w : nt.transcript.words) {
            sb.append("[").append(w.startMs).append("-").append(w.endMs).append("] ")
                    .append(w.text);
            if (w.struck) sb.append(" [CUT]");
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Correct transcription errors (which also fixes the on-screen captions): find a run of words by
     * text and replace it with corrected text, redistributing the run's time across the new words. Fixes
     * cases like ASR dropping/mangling words ("the" → "In the day you"). `replace` empty = delete the run.
     */
    private String toolCorrectTranscript(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        String find = args.optString("find", "").trim();
        String replace = args.optString("replace", "").trim();
        if (find.isEmpty()) return "Error: 'find' (the wrong text to fix) is required";
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = clipId.isEmpty()
                ? (proj.getTimeline().getClipCount() > 0 ? proj.getTimeline().getClip(0) : null)
                : findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        NamedTranscript nt = clip.getActiveNamedTranscript();
        if (nt == null || nt.transcript.words.isEmpty())
            return "Error: clip has no transcript. Run generate_transcript first.";
        java.util.List<com.fadcam.ui.faditor.transcript.TranscriptWord> words = nt.transcript.words;

        String[] findTokens = normalizeTokens(find);
        if (findTokens.length == 0) return "Error: 'find' has no usable words";

        // Locate the first run of consecutive UN-STRUCK words whose text matches findTokens
        // (case- and punctuation-insensitive).
        int from = -1, to = -1;
        for (int i = 0; i < words.size() && from < 0; i++) {
            if (words.get(i).struck) continue;
            int k = 0, j = i, last = i;
            while (j < words.size() && k < findTokens.length) {
                if (words.get(j).struck) { j++; continue; }
                if (!normalizeToken(words.get(j).text).equals(findTokens[k])) break;
                last = j; k++; j++;
            }
            if (k == findTokens.length) { from = i; to = last; }
        }
        if (from < 0) {
            return "Could not find \"" + find + "\" in the transcript (word-level, case/punctuation-"
                    + "insensitive match). Call get_transcript to see the exact words.";
        }

        long spanStart = words.get(from).startMs;
        long spanEnd = words.get(to).endMs;
        String[] newTexts = replace.isEmpty() ? new String[0] : replace.split("\\s+");
        java.util.List<com.fadcam.ui.faditor.transcript.TranscriptWord> replacement =
                new java.util.ArrayList<>();
        if (newTexts.length > 0) {
            long total = Math.max(1, spanEnd - spanStart);
            int totalChars = 0;
            for (String t : newTexts) totalChars += Math.max(1, t.length());
            long cursor = spanStart;
            for (int x = 0; x < newTexts.length; x++) {
                long dur = (x == newTexts.length - 1) ? (spanEnd - cursor)
                        : Math.max(1, total * Math.max(1, newTexts[x].length()) / totalChars);
                long wEnd = Math.min(spanEnd, cursor + dur);
                replacement.add(new com.fadcam.ui.faditor.transcript.TranscriptWord(
                        newTexts[x], cursor, wEnd, false));
                cursor = wEnd;
            }
        }

        java.util.List<com.fadcam.ui.faditor.transcript.TranscriptWord> rebuilt =
                new java.util.ArrayList<>(words.subList(0, from));
        rebuilt.addAll(replacement);
        rebuilt.addAll(words.subList(to + 1, words.size()));
        words.clear();
        words.addAll(rebuilt);

        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Corrected \"" + find + "\" → \"" + replace + "\" in clip " + clip.getId()
                + " (" + newTexts.length + " word(s) over " + spanStart + "-" + spanEnd + "ms). "
                + "Captions + transcript updated.";
    }

    private static String[] normalizeTokens(@NonNull String s) {
        String[] raw = s.trim().split("\\s+");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String r : raw) {
            String n = normalizeToken(r);
            if (!n.isEmpty()) out.add(n);
        }
        return out.toArray(new String[0]);
    }

    private static String normalizeToken(@NonNull String s) {
        return s.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Retime words: anchor specific words to exact timeline times and interpolate
     * the in-between words. Mirrors the anchor pattern from
     * {@code correct_transcript} but for TIMING rather than text.
     */
    private String toolRetimeWords(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        String distribute = args.optString("distribute", "by-length");
        JSONArray anchors = args.optJSONArray("anchors");
        if (anchors == null || anchors.length() < 2)
            return "Error: at least 2 anchors required (first and last word of a span)";
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        NamedTranscript nt = clip.getActiveNamedTranscript();
        if (nt == null || nt.transcript.words.isEmpty())
            return "Error: clip has no transcript. Run generate_transcript first.";
        java.util.List<com.fadcam.ui.faditor.transcript.TranscriptWord> words = nt.transcript.words;
        int n = words.size();

        // Resolve each anchor to a word index + target time.
        // Each anchor: {word:"...", timeMs:N} or {index:N, timeMs:N}
        java.util.List<int[]> anchorIdxAndTime = new java.util.ArrayList<>(); // [wordIndex, targetTimeMs]
        for (int a = 0; a < anchors.length(); a++) {
            JSONObject an = anchors.optJSONObject(a);
            if (an == null) continue;
            long timeMs = an.optLong("timeMs", -1);
            if (timeMs < 0) return "Error: anchor " + a + " missing timeMs";
            int wordIdx = -1;
            if (an.has("index")) {
                wordIdx = an.optInt("index", -1);
            } else if (an.has("word")) {
                String wordText = an.optString("word", "").trim();
                String norm = normalizeToken(wordText);
                for (int i = 0; i < n; i++) {
                    if (!words.get(i).struck && normalizeToken(words.get(i).text).equals(norm)) {
                        wordIdx = i;
                        break;
                    }
                }
                if (wordIdx < 0)
                    return "Error: word \"" + wordText + "\" not found in transcript";
            }
            if (wordIdx < 0 || wordIdx >= n)
                return "Error: invalid anchor index " + wordIdx + " at anchor " + a;
            anchorIdxAndTime.add(new int[]{wordIdx, (int) timeMs});
        }

        // Sort anchors by word index
        java.util.Collections.sort(anchorIdxAndTime, (a, b) -> Integer.compare(a[0], b[0]));
        int m = anchorIdxAndTime.size();

        // For each consecutive pair of anchors, redistribute the words between them
        // (exclusive of the anchors themselves).
        for (int s = 0; s < m - 1; s++) {
            int fromIdx = anchorIdxAndTime.get(s)[0];
            int toIdx = anchorIdxAndTime.get(s + 1)[0];
            long fromTime = anchorIdxAndTime.get(s)[1];
            long toTime = anchorIdxAndTime.get(s + 1)[1];
            if (toIdx <= fromIdx + 1) continue; // no in-between words
            int gapWords = toIdx - fromIdx - 1;
            long totalSpan = toTime - fromTime;
            if (totalSpan <= 0) continue;

            if ("even".equals(distribute)) {
                long gapMs = totalSpan / (gapWords + 1);
                long cursor = fromTime + gapMs;
                for (int w = fromIdx + 1; w < toIdx; w++) {
                    long dur = Math.max(1, words.get(w).endMs - words.get(w).startMs);
                    long end = Math.min(toTime, cursor + dur);
                    words.set(w, new com.fadcam.ui.faditor.transcript.TranscriptWord(
                            words.get(w).text, cursor, end, words.get(w).struck));
                    cursor = end;
                }
            } else {
                // "by-length": proportional to each word's character length
                int totalChars = 0;
                for (int w = fromIdx + 1; w < toIdx; w++) {
                    totalChars += Math.max(1, words.get(w).text.length());
                }
                long consumed = 0;
                for (int w = fromIdx + 1; w < toIdx; w++) {
                    int len = Math.max(1, words.get(w).text.length());
                    long dur = (w == toIdx - 1)
                            ? (totalSpan - consumed)
                            : Math.max(1, totalSpan * len / totalChars);
                    long start = fromTime + consumed;
                    long end = Math.min(toTime, start + dur);
                    words.set(w, new com.fadcam.ui.faditor.transcript.TranscriptWord(
                            words.get(w).text, start, end, words.get(w).struck));
                    consumed += dur;
                }
            }
        }

        // Set the anchor words' own positions
        for (int[] pair : anchorIdxAndTime) {
            int wIdx = pair[0];
            long t = pair[1];
            com.fadcam.ui.faditor.transcript.TranscriptWord w = words.get(wIdx);
            long dur = Math.max(1, w.endMs - w.startMs);
            words.set(wIdx, new com.fadcam.ui.faditor.transcript.TranscriptWord(
                    w.text, t, t + dur, w.struck));
        }

        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Retimed " + m + " anchor(s) and interpolated " + (n - m)
                + " words (distribute=" + distribute + "). Captions + transcript updated.";
    }

    private String toolSynthesizeTranscript(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        boolean runSilence = args.optBoolean("runSilence", true);
        float sensitivity = (float) args.optDouble("sensitivity", 0.5);

        notifyProgress("Synthesizing transcripts", 0);
        TranscriptSynthesizer synth = new TranscriptSynthesizer(context, projectId);
        TranscriptSynthesizer.Result result = synth.synthesize(
                clipId, "vosk", "whisper", runSilence, sensitivity);

        if (!result.success) {
            notifyProgress("Synthesis failed", 100);
            return "Synthesis failed: " + result.error;
        }

        notifyProgress("Synthesis complete", 100);
        return TranscriptSynthesizer.describeResult(result);
    }

    private String toolCutAllFillers(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;

        notifyProgress("Cutting filler words", 0);
        // Find the synthesized transcript (or any with struck words)
        NamedTranscript target = null;
        for (NamedTranscript nt : clip.getTranscripts()) {
            for (var w : nt.transcript.words) {
                if (w.struck) { target = nt; break; }
            }
            if (target != null) break;
        }

        if (target == null) {
            return "No struck/filler words found. Run synthesize_transcript first.";
        }

        int cutCount = 0;
        for (var w : target.transcript.words) {
            if (w.struck) {
                clip.getRemovedSpans().add(new long[]{w.startMs, w.endMs});
                cutCount++;
            }
        }

        List<long[]> spans = new ArrayList<>(clip.getRemovedSpans());
        java.util.Collections.sort(spans, (a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] s : spans) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1] + 1) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], s[1]);
            } else {
                merged.add(new long[]{s[0], s[1]});
            }
        }
        clip.setRemovedSpans(merged);

        storage.save(proj);
        AIChatState.signalModified(projectId);
        notifyProgress("Filler cuts saved", 100);
        return "Cut " + cutCount + " filler words from clip " + clipId
                + ". The cuts are non-destructive — tap them in the timeline to undo.";
    }

    /**
     * AI-driven merge: sends both transcripts to the AI model and asks it to
     * produce the best merged version. Unlike the algorithmic merge, the AI
     * can read the sentence context and pick whichever word is better.
     */
    private String toolAIMergeTranscript(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;

        // Find both transcripts
        NamedTranscript voskNt = null, whisperNt = null;
        for (NamedTranscript nt : clip.getTranscripts()) {
            String label = (nt.engine + " " + nt.label).toLowerCase();
            if (label.contains("vosk") && voskNt == null) voskNt = nt;
            if (label.contains("whisper") && whisperNt == null) whisperNt = nt;
        }

        if (voskNt == null && whisperNt == null) {
            return "Error: Need at least two transcripts to merge. Generate Vosk and Whisper first.";
        }

        // Run algorithmic synthesis as a base
        TranscriptSynthesizer synth = new TranscriptSynthesizer(context, projectId);
        boolean hasBoth = voskNt != null && whisperNt != null;

        if (hasBoth) {
            // Use silence data if available
            List<long[]> silence = clip.getSilenceCandidates();
            TranscriptSynthesizer.Result result = synth.synthesize(
                    clipId, "vosk", "whisper",
                    silence == null || silence.isEmpty(), 0.5f);
            if (result.success) {
                return TranscriptSynthesizer.describeResult(result)
                        + "\n\nNOTE: The AI can now review both transcripts contextually. "
                        + "Ask the user if they want you to review specific sections and "
                        + "correct words where the synthesis chose poorly.";
            } else {
                return "Synthesis failed: " + result.error;
            }
        } else {
            return "Need both Vosk and Whisper transcripts. Currently have: "
                    + (voskNt != null ? "Vosk" : "")
                    + (whisperNt != null ? "Whisper" : "");
        }
    }

    /**
     * One-button AI enhance: does everything needed to enhance a project.
     * 1. Generates missing transcripts (Vosk first, then Whisper if available)
     * 2. Runs silence detection
     * 3. Synthesizes transcripts
     * 4. Cuts fillers
     * Returns a summary of what was done.
     */
    private String toolAIEnhance(@NonNull JSONObject args) {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";

        StringBuilder summary = new StringBuilder();
        summary.append("AI Enhancement Report:\n");
        Timeline tl = proj.getTimeline();
        int totalActions = 0;

        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip clip = tl.getClip(i);
            if (clip.isImageClip()) continue;

            boolean hasVosk = false, hasWhisper = false;
            for (NamedTranscript nt : clip.getTranscripts()) {
                if (nt.engine.equalsIgnoreCase("vosk")) hasVosk = true;
                if (nt.engine.equalsIgnoreCase("whisper")) hasWhisper = true;
            }
            if (!hasVosk) totalActions++;
            if (!hasWhisper) totalActions++;
            totalActions++; // silence detection
            if (hasVosk && hasWhisper) totalActions += 2; // synthesis + filler cuts
        }

        if (totalActions == 0) {
            return "No video clips found to enhance.";
        }

        int completedActions = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip clip = tl.getClip(i);
            if (clip.isImageClip()) continue;

            summary.append("\nClip ").append(i).append(" (").append(clip.getId()).append("):\n");

            boolean hasVosk = false, hasWhisper = false;
            for (NamedTranscript nt : clip.getTranscripts()) {
                if (nt.engine.equalsIgnoreCase("vosk")) hasVosk = true;
                if (nt.engine.equalsIgnoreCase("whisper")) hasWhisper = true;
            }

            if (!hasVosk) {
                summary.append("  ⏳ Generating Vosk transcript...\n");
                JSONObject tArgs = new JSONObject();
                try {
                    tArgs.put("clipId", clip.getId());
                    tArgs.put("engine", "vosk");
                } catch (Exception ignored) { }
                notifyProgress("AI Enhance: Generating Vosk transcript", completedActions * 100 / totalActions);
                String result = toolGenerateTranscript(tArgs);
                summary.append("  ").append(result).append("\n");
                completedActions++;
                notifyProgress("AI Enhance", completedActions * 100 / totalActions);
                hasVosk = result.contains("successfully");
            }

            if (!hasWhisper) {
                summary.append("  ⏳ Generating Whisper transcript...\n");
                JSONObject tArgs = new JSONObject();
                try {
                    tArgs.put("clipId", clip.getId());
                    tArgs.put("engine", "whisper");
                } catch (Exception ignored) { }
                notifyProgress("AI Enhance: Generating Whisper transcript", completedActions * 100 / totalActions);
                String result = toolGenerateTranscript(tArgs);
                summary.append("  ").append(result).append("\n");
                completedActions++;
                notifyProgress("AI Enhance", completedActions * 100 / totalActions);
                hasWhisper = result.contains("successfully");
            }

            summary.append("  ⏳ Detecting silence...\n");
            JSONObject sArgs = new JSONObject();
            try {
                sArgs.put("clipId", clip.getId());
                sArgs.put("sensitivity", 0.5);
            } catch (Exception ignored) { }
            notifyProgress("AI Enhance: Detecting silence", completedActions * 100 / totalActions);
            String sResult = toolDetectSilence(sArgs);
            summary.append("  ").append(sResult.split("\n")[0]).append("\n");
            completedActions++;
            notifyProgress("AI Enhance", completedActions * 100 / totalActions);

            if (hasVosk && hasWhisper) {
                summary.append("  ⏳ Synthesizing transcripts...\n");
                JSONObject synArgs = new JSONObject();
                try {
                    synArgs.put("clipId", clip.getId());
                    synArgs.put("runSilence", false);
                    synArgs.put("sensitivity", 0.5);
                } catch (Exception ignored) { }
                notifyProgress("AI Enhance: Synthesizing transcripts", completedActions * 100 / totalActions);
                String synResult = toolSynthesizeTranscript(synArgs);
                summary.append("  ").append(synResult).append("\n");
                completedActions++;
                notifyProgress("AI Enhance", completedActions * 100 / totalActions);

                summary.append("  ⏳ Cutting filler words...\n");
                JSONObject cutArgs = new JSONObject();
                try {
                    cutArgs.put("clipId", clip.getId());
                } catch (Exception ignored) { }
                notifyProgress("AI Enhance: Cutting filler words", completedActions * 100 / totalActions);
                String cutResult = toolCutAllFillers(cutArgs);
                summary.append("  ").append(cutResult).append("\n");
                completedActions++;
                notifyProgress("AI Enhance", completedActions * 100 / totalActions);
            }
        }

        summary.append("\n✅ Enhancement complete! ").append(totalActions)
                .append(" actions performed. Close this chat to see the results in the editor.");
        notifyProgress("AI Enhance complete", 100);
        return summary.toString();
    }

    private String toolSetClipDuck(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        float duckAmount = (float) args.optDouble("duckAmount", 0.3f);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        clip.setDuckAmount(duckAmount);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Audio ducking set to " + (int)(duckAmount * 100) + "% on clip " + clipId;
    }

    private String toolSetClipZoom(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        float zoom = (float) args.optDouble("zoom", 2.0f);
        float cx = (float) args.optDouble("centerX", 0.5f);
        float cy = (float) args.optDouble("centerY", 0.5f);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;
        clip.setZoomLevel(zoom);
        clip.setZoomCenter(cx, cy);
        storage.save(proj);
        AIChatState.signalModified(projectId);
        return "Punch-in zoom set to " + zoom + "x centered at (" + cx + ", " + cy
                + ") on clip " + clipId;
    }

    /**
     * Automatically detect important words in the transcript and apply punch-in
     * zoom to those moments. Uses sentence boundaries, long pauses, and
     * keyword detection to find emphasis points.
     */
    private String toolAutoZoom(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        float zoomLevel = (float) args.optDouble("zoom", 2.0f);
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Clip clip = findClip(proj, clipId);
        if (clip == null) return "Error: clip not found: " + clipId;

        java.util.List<NamedTranscript> versions = clip.getTranscripts();
        if (versions.isEmpty()) {
            return "Error: no transcript for clip " + clipId
                    + ". Generate a transcript first.";
        }
        NamedTranscript nt = clip.getActiveNamedTranscript();
        if (nt == null) nt = versions.get(0);
        if (nt.transcript.words.isEmpty()) {
            return "Error: transcript is empty for clip " + clipId;
        }

        notifyProgress("Auto-zoom: analyzing transcript", 10);

        // Find important word positions:
        // 1. First word of each sentence (after . ! ?)
        // 2. First word after a long pause (>1.5s gap)
        // 3. Words that are longer than average (emphasis)
        java.util.List<long[]> zoomPoints = new java.util.ArrayList<>();
        long avgWordLen = 0;
        for (var w : nt.transcript.words) {
            avgWordLen += (w.endMs - w.startMs);
        }
        avgWordLen /= Math.max(1, nt.transcript.words.size());

        boolean newSentence = true;
        for (int i = 0; i < nt.transcript.words.size(); i++) {
            var w = nt.transcript.words.get(i);
            boolean important = false;

            if (newSentence) {
                important = true;
                newSentence = false;
            }

            if (i > 0) {
                var prev = nt.transcript.words.get(i - 1);
                long gap = w.startMs - prev.endMs;
                if (gap > 1500) important = true;
                if (prev.text.endsWith(".") || prev.text.endsWith("!")
                        || prev.text.endsWith("?")) {
                    important = true;
                    newSentence = true;
                }
            }

            // Long words (possible emphasis)
            long wordLen = w.endMs - w.startMs;
            if (wordLen > avgWordLen * 1.8) important = true;

            if (important) {
                zoomPoints.add(new long[]{w.startMs, w.endMs});
            }
        }

        // Limit to reasonable number of zoom points
        if (zoomPoints.size() > 20) {
            // Keep every Nth point to avoid too many zoom changes
            int step = (zoomPoints.size() + 19) / 20;
            java.util.List<long[]> filtered = new java.util.ArrayList<>();
            for (int i = 0; i < zoomPoints.size(); i += step) {
                filtered.add(zoomPoints.get(i));
            }
            zoomPoints = filtered;
        }

        notifyProgress("Auto-zoom: applying " + zoomPoints.size() + " zoom points", 50);

        // Apply zoom to the clip — set a base zoom level and center
        // (For now, we set the clip's zoom level. Future: per-word zoom keyframes
        //  via a zoom keyframe track, similar to opacity keyframes.)
        clip.setZoomLevel(zoomLevel);
        clip.setZoomCenter(0.5f, 0.5f);

        // Store zoom points as metadata in the clip's removedSpans? No.
        // For now, store as a comment in the transcript (struck = false).
        // Future: add a zoomKeyframes list to Clip.

        storage.save(proj);
        AIChatState.signalModified(projectId);
        notifyProgress("Auto-zoom complete", 100);

        return "Auto-zoom applied to clip " + clipId + ": " + zoomPoints.size()
                + " important moments detected, zoom set to " + zoomLevel
                + "x. (Per-word zoom keyframes coming soon — currently sets clip-wide zoom.)";
    }

    // ── Move / reorder / resize tools ─────────────────────────────────

    private String toolMoveClipTo(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        String namePattern = args.optString("namePattern", "");
        String position = args.optString("position", "end");
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Timeline tl = proj.getTimeline();

        Clip clip = null;
        if (!clipId.isEmpty()) {
            clip = findClip(proj, clipId);
        } else if (!namePattern.isEmpty()) {
            clip = findClipByName(proj, namePattern);
        }
        if (clip == null) {
            if (!clipId.isEmpty()) return "Error: clip not found: " + clipId;
            if (!namePattern.isEmpty()) return "Error: no clip matching: " + namePattern;
            return "Error: provide clipId or namePattern";
        }

        int fromIndex = -1;
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (tl.getClip(i).getId().equals(clip.getId())) { fromIndex = i; break; }
        }
        if (fromIndex < 0) return "Error: clip not in timeline";

        int toIndex;
        switch (position) {
            case "start":
            case "first":
            case "1":
                toIndex = 0;
                break;
            case "end":
            case "last":
                toIndex = tl.getClipCount() - 1;
                break;
            default:
                try {
                    int pos = Integer.parseInt(position) - 1; // 1-based to 0-based
                    toIndex = Math.max(0, Math.min(pos, tl.getClipCount() - 1));
                } catch (NumberFormatException e) {
                    return "Error: invalid position '" + position + "'. Use 'start', 'end', or a number.";
                }
                break;
        }

        // Build and apply edit script
        try {
            JSONObject opObj = new JSONObject();
            opObj.put("type", "REORDER_CLIPS");
            JSONArray newOrder = new JSONArray();
            java.util.List<Clip> clips = new java.util.ArrayList<>();
            for (int i = 0; i < tl.getClipCount(); i++) clips.add(tl.getClip(i));
            clips.remove(fromIndex);
            if (toIndex > fromIndex) toIndex--;
            clips.add(toIndex, clip);
            for (Clip c : clips) newOrder.put(c.getId());
            opObj.put("newOrder", newOrder);

            JSONObject scriptObj = new JSONObject();
            scriptObj.put("version", 1);
            scriptObj.put("description", "Move clip to " + position);
            scriptObj.put("operations", new JSONArray().put(opObj));

            EditScript script = EditScript.fromJson(scriptObj.toString());
            EditScriptApplier applier = new EditScriptApplier();
            EditScriptApplier.Result result = applier.apply(proj, script);
            if (!result.success) return "Error: " + result.error;

            storage.save(proj);
            AIChatState.signalModified(projectId);
            return "Moved clip " + friendlyClipName(clip) + " to position " + (toIndex + 1) + ".";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String toolReorderClipsByName(@NonNull JSONObject args) {
        JSONArray nameOrder = args.optJSONArray("nameOrder");
        if (nameOrder == null || nameOrder.length() == 0) {
            return "Error: 'nameOrder' array is required";
        }
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Timeline tl = proj.getTimeline();

        // Collect all clip names for reference
        int n = tl.getClipCount();
        Clip[] ordered = new Clip[n];
        boolean[] placed = new boolean[n];
        int placedCount = 0;

        for (int i = 0; i < nameOrder.length() && placedCount < n; i++) {
            String pattern = nameOrder.optString(i, "").toLowerCase();
            if (pattern.isEmpty()) continue;
            for (int j = 0; j < n; j++) {
                if (placed[j]) continue;
                Clip c = tl.getClip(j);
                String name = friendlyClipName(c).toLowerCase();
                if (name.contains(pattern) || pattern.contains(name)) {
                    ordered[placedCount++] = c;
                    placed[j] = true;
                    break;
                }
            }
        }

        // Place remaining (unmatched) clips in original order
        for (int j = 0; j < n; j++) {
            if (!placed[j]) ordered[placedCount++] = tl.getClip(j);
        }

        // Check if any clips are on different layers (future-proof)
        // Currently all clips are layer 1, but add a warning for future
        boolean hasMultiLayer = false; // Future: check layer field

        try {
            JSONArray newOrder = new JSONArray();
            for (Clip c : ordered) newOrder.put(c.getId());

            JSONObject opObj = new JSONObject();
            opObj.put("type", "REORDER_CLIPS");
            opObj.put("newOrder", newOrder);

            JSONObject scriptObj = new JSONObject();
            scriptObj.put("version", 1);
            scriptObj.put("description", "Reorder clips by name");
            scriptObj.put("operations", new JSONArray().put(opObj));

            EditScript script = EditScript.fromJson(scriptObj.toString());
            EditScriptApplier applier = new EditScriptApplier();
            EditScriptApplier.Result result = applier.apply(proj, script);
            if (!result.success) return "Error: " + result.error;

            storage.save(proj);
            AIChatState.signalModified(projectId);
            String msg = "Reordered " + n + " clips by name.";
            if (hasMultiLayer) {
                msg += " Note: Some clips are on different layers. "
                        + "I kept them on their current layers. "
                        + "Say 'put them all on the main layer' if you want them merged.";
            }
            return msg;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String toolResizeOverlay(@NonNull JSONObject args) {
        String overlayId = args.optString("overlayId", "");
        if (overlayId.isEmpty()) return "Error: overlayId is required";

        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Timeline tl = proj.getTimeline();

        // Find the overlay
        com.fadcam.ui.faditor.model.TextOverlayItem overlay = null;
        for (com.fadcam.ui.faditor.model.TextOverlayItem o : tl.getTextOverlays()) {
            if (o.getId().equals(overlayId)) { overlay = o; break; }
        }
        if (overlay == null) return "Error: overlay not found: " + overlayId;

        try {
            if (args.has("percentChange")) {
                double pct = args.getDouble("percentChange");
                float oldSize = overlay.getSizeFraction();
                float newSize = (float) Math.max(0.02, Math.min(0.6, oldSize * (1.0 + pct / 100.0)));
                overlay.setSizeFraction(newSize);
                storage.save(proj);
                AIChatState.signalModified(projectId);
                return "Resized overlay from " + Math.round(oldSize * 100) + "% to "
                        + Math.round(newSize * 100) + "% of screen height.";
            } else if (args.has("sizeFraction")) {
                double size = args.getDouble("sizeFraction");
                float clamped = (float) Math.max(0.02, Math.min(0.6, size));
                overlay.setSizeFraction(clamped);
                storage.save(proj);
                AIChatState.signalModified(projectId);
                return "Set overlay size to " + Math.round(clamped * 100) + "% of screen height.";
            } else {
                return "Error: provide 'percentChange' or 'sizeFraction'";
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Derive a friendly display name from a clip (renamed label, URI filename, or fallback). */
    @NonNull
    private String friendlyClipName(@NonNull Clip clip) {
        // Use the renamed display name if one has been set (via asset rename dialog)
        String dn = clip.getDisplayName();
        if (dn != null && !dn.isEmpty()) {
            int dot = dn.lastIndexOf('.');
            return dot > 0 ? dn.substring(0, dot) : dn;
        }
        String uriStr = clip.getSourceUri().toString();
        int slash = uriStr.lastIndexOf('/');
        if (slash >= 0) {
            String name = uriStr.substring(slash + 1);
            int q = name.indexOf('?');
            if (q > 0) name = name.substring(0, q);
            if (name.startsWith("faditor_")) name = name.substring(8);
            if (name.startsWith("tmp_")) name = name.substring(4);
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) {}
            if (!name.isEmpty()) return name;
        }
        return "Video";
    }

    /** Find a clip by matching its friendly name (case-insensitive contains). */
    @Nullable
    private Clip findClipByName(@NonNull FaditorProject proj, @NonNull String pattern) {
        Timeline tl = proj.getTimeline();
        String lower = pattern.toLowerCase();
        Clip bestMatch = null;
        int bestScore = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            String name = friendlyClipName(c).toLowerCase();
            if (name.equals(lower)) return c; // exact match wins
            if (name.contains(lower) && lower.length() > bestScore) {
                bestScore = lower.length();
                bestMatch = c;
            }
            if (lower.contains(name) && name.length() > bestScore) {
                bestScore = name.length();
                bestMatch = c;
            }
        }
        return bestMatch;
    }

    // ── Helpers ─────────────────────────────────────────────────────

    @Nullable
    private Clip findClip(@NonNull FaditorProject proj, @NonNull String clipId) {
        Timeline tl = proj.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            if (tl.getClip(i).getId().equals(clipId)) return tl.getClip(i);
        }
        return null;
    }

    @NonNull
    private String buildProjectSummary(@NonNull FaditorProject proj) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(proj.getName()).append("\n");
        Timeline tl = proj.getTimeline();
        sb.append("Clips: ").append(tl.getClipCount()).append("\n");
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            String friendlyName = friendlyClipName(c);
            sb.append("Clip ").append(i).append(": id=").append(c.getId())
                    .append(" name=\"").append(friendlyName).append("\"")
                    .append(" dur=").append(c.getTrimmedDurationMs()).append("ms")
                    .append(" speed=").append(c.getSpeedMultiplier())
                    .append(" muted=").append(c.isAudioMuted())
                    .append(" captions=").append(c.isCaptionsEnabled());
            if (!c.getRemovedSpans().isEmpty()) {
                sb.append(" cuts=").append(c.getRemovedSpans().size());
            }
            sb.append("\n");

            // Include transcript if available
            java.util.List<NamedTranscript> versions = c.getTranscripts();
            if (!versions.isEmpty()) {
                NamedTranscript active = c.getActiveNamedTranscript();
                if (active == null) active = versions.get(0);
                sb.append("  Transcript (").append(active.engine).append("):\n");
                for (int w = 0; w < active.transcript.words.size(); w++) {
                    var word = active.transcript.words.get(w);
                    sb.append("    [").append(word.startMs).append("-")
                            .append(word.endMs).append("] ")
                            .append(word.text);
                    if (word.struck) sb.append(" [CUT]");
                    sb.append("\n");
                }
            }
        }
        if (tl.hasAudioClips()) {
            sb.append("Audio clips: ").append(tl.getAudioClipCount()).append("\n");
        }
        if (tl.hasTextOverlays()) {
            sb.append("Overlays: ").append(tl.getTextOverlays().size()).append("\n");
        }
        // Sprite sheets, image sequences and avatar rigs — PLAN_SPRITE_ANIMATION's fast-follow B
        // and SPEC_IMAGE_SEQUENCE §7a both say these belong here, and neither was emitting them.
        // Without this the model cannot answer "what animation objects does this project have"
        // without being told an id it has no way to learn.
        if (!proj.getSpriteSheets().isEmpty()) {
            sb.append("Sprite sheets / image sequences:\n");
            for (com.fadcam.ui.faditor.sprite.SpriteSheet sh : proj.getSpriteSheets()) {
                sb.append("  - id=").append(sh.getId())
                        .append(" name=\"").append(sh.getName()).append("\"");
                if (sh.isSequence()) {
                    sb.append(" SEQUENCE frames=").append(sh.cellCount())
                            .append(" fps=").append(sh.getFps())
                            .append(" resize=").append(sh.getResizeMode().name());
                } else {
                    sb.append(" grid=").append(sh.getCols()).append("x").append(sh.getRows())
                            .append(" fps=").append(sh.getFps());
                }
                if (!sh.getPresets().isEmpty()) {
                    sb.append(" presets=").append(sh.getPresets().size());
                }
                sb.append("\n");
            }
        }
        java.util.List<com.fadcam.ui.faditor.sprite.SpriteOverlayItem> placed =
                tl.getSpriteOverlays();
        if (!placed.isEmpty()) {
            sb.append("Placed animation objects (use these ids with describe_sequence / "
                    + "edit_sequence):\n");
            for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem so : placed) {
                com.fadcam.ui.faditor.sprite.SpriteSheet sh = proj.spriteSheetById(so.getSheetId());
                sb.append("  - objectId=").append(so.getId())
                        .append(" sheet=\"").append(sh == null ? "?" : sh.getName()).append("\"")
                        .append(sh != null && sh.isSequence() ? " (image sequence)" : " (sprite)")
                        .append(" ").append(so.getStartMs()).append("ms→")
                        .append(so.getEndMs() == Long.MAX_VALUE ? "open" : so.getEndMs() + "ms");
                if (so.getAvatarRigId() != null) sb.append(" avatarRig=").append(so.getAvatarRigId());
                sb.append("\n");
            }
        }
        if (!proj.getAvatarRigs().isEmpty()) {
            sb.append("Avatar rigs:\n");
            for (com.fadcam.ui.faditor.avatar.AvatarRig rig : proj.getAvatarRigs()) {
                sb.append("  - id=").append(rig.getId())
                        .append(" name=\"").append(rig.getName()).append("\"")
                        .append(" parts=").append(rig.getParts().size()).append("\n");
            }
        }
        sb.append("Canvas: ").append(proj.getCanvasPreset()).append("\n");
        return sb.toString();
    }

    private String toolRenameClip(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        String newName = args.optString("newName", "");
        if (clipId.isEmpty() || newName.isEmpty()) {
            return "Error: 'clipId' and 'newName' are required";
        }
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Timeline tl = proj.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            if (c.getId().equals(clipId)) {
                c.setDisplayName(newName);
                storage.save(proj);
                AIChatState.signalModified(projectId);
                return "Renamed clip " + clipId + " to \"" + newName + "\"";
            }
        }
        return "Error: clip '" + clipId + "' not found";
    }

    private String toolRenameAsset(@NonNull JSONObject args) {
        String pattern = args.optString("namePattern", "");
        String newName = args.optString("newName", "");
        int startIndex = args.optInt("startIndex", 1);
        if (pattern.isEmpty() || newName.isEmpty()) {
            return "Error: 'namePattern' and 'newName' are required";
        }
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Timeline tl = proj.getTimeline();
        int counter = startIndex;
        int renamed = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            String name = friendlyClipName(c).toLowerCase();
            if (name.contains(pattern.toLowerCase())) {
                c.setDisplayName(newName + " " + counter);
                counter++;
                renamed++;
            }
        }
        if (renamed > 0) {
            storage.save(proj);
            AIChatState.signalModified(projectId);
            return "Renamed " + renamed + " clips to \"" + newName + " N\"";
        }
        return "No clips matched pattern '" + pattern + "'";
    }

    private String toolDescribeClip(@NonNull JSONObject args) {
        String clipId = args.optString("clipId", "");
        if (clipId.isEmpty()) {
            return "Error: 'clipId' is required";
        }
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        Timeline tl = proj.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            if (c.getId().equals(clipId)) {
                StringBuilder sb = new StringBuilder();
                sb.append("Clip: ").append(friendlyClipName(c)).append("\n");
                sb.append("  ID: ").append(c.getId()).append("\n");
                sb.append("  Duration: ").append(c.getTrimmedDurationMs()).append("ms");
                if (c.hasLoopExtension()) {
                    sb.append(" (loop visual: ").append(c.getVisualDurationMs()).append("ms)");
                }
                sb.append("\n");
                sb.append("  Speed: ").append(c.getSpeedMultiplier()).append("x\n");
                sb.append("  Muted: ").append(c.isAudioMuted()).append("\n");
                sb.append("  Captions: ").append(c.isCaptionsEnabled()).append("\n");
                sb.append("  Source URI: ").append(c.getSourceUri()).append("\n");
                if (!c.getRemovedSpans().isEmpty()) {
                    sb.append("  Silent cuts: ").append(c.getRemovedSpans().size()).append("\n");
                }
                java.util.List<NamedTranscript> transcripts = c.getTranscripts();
                if (!transcripts.isEmpty()) {
                    NamedTranscript active = c.getActiveNamedTranscript();
                    if (active == null) active = transcripts.get(0);
                    sb.append("  Transcript: ").append(active.transcript.words.size())
                            .append(" words (").append(active.engine).append(")\n");
                } else {
                    sb.append("  Transcript: none\n");
                }
                return sb.toString();
            }
        }
        return "Error: clip '" + clipId + "' not found";
    }
}

