package com.fadcam.ui.faditor.ai.actions;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.joyfolder.FolderRoles;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.fadcam.joyfolder.studio.StudioMirror;
import com.fadcam.ui.VideoItem;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.layers.Track;
import com.fadcam.ui.faditor.layers.TrackKind;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The sources family (gap F2): Joybot can bring media in. {@link #listMedia} shows what the person
 * can add (the files beside this project in their Joy Creator folder, the Assets folder, and recent
 * recordings); {@link #addMedia} puts one in the way the editor's share-in and asset insert do.
 *
 * <p>Mirrors, method by method: FaditorEditorActivity.takeSharedFiles / insertAssetAtPlayhead (the
 * line insert and the audio insert), onImageAsNewLayerPicked (picture on top), the overlay-video
 * (PiP) picker callback, importInsertedAsset (copy into the project), getVideoDuration /
 * getAudioDuration (probing), computeInsertIndexAtTimelineMs, laneRangeOccupied and
 * assignOverlayClipToFreeLane (lanes), topLayerZIndex. Those are private to the activity, so the
 * model-only parts are copied here rather than reached into.</p>
 *
 * <p>Not reproduced: the Fit of a new picture (it needs the live preview's size, so the picture
 * comes in at the editor's 30% default), the playhead (Joybot passes atMs), the snackbar for a
 * large linked file, and every view refresh, toast and selection. The host saves once and the open
 * editor adopts the change.</p>
 */
final class SourcesActions {
    private SourcesActions() { }

    private static final String FAMILY = "sources";
    private static final String TAG = "SourcesActions";

    /** FaditorEditorActivity.IMAGE_CLIP_DURATION_MS / IMAGE_CLIP_MAX_MS (newImageClip). */
    private static final long IMAGE_CLIP_DURATION_MS = 5000L;
    private static final long IMAGE_CLIP_MAX_MS = 60 * 60 * 1000L;
    /** FaditorEditorActivity.ASSET_COPY_MAX_BYTES: bigger videos are linked, not copied. */
    private static final long ASSET_COPY_MAX_BYTES = 25L * 1024 * 1024;
    /** FaditorEditorActivity.getAudioDuration fallback. */
    private static final long AUDIO_FALLBACK_MS = 5000L;
    private static final int RECENT_RECORDINGS = 10;

    static final List<String> AS = Arrays.asList("clip", "layer");

    private static final List<String> PICTURE_EXT = Arrays.asList(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".heif", ".bmp");
    private static final List<String> VIDEO_EXT = Arrays.asList(
            ".mp4", ".mov", ".m4v", ".mkv", ".webm", ".3gp");
    private static final List<String> AUDIO_EXT = Arrays.asList(
            ".m4a", ".mp3", ".wav", ".aac", ".ogg", ".opus", ".flac", ".amr");

    private enum Kind { PICTURE, VIDEO, AUDIO }

    /** One file the person can add, as listed. */
    private static final class Item {
        final Kind kind;
        final String name;
        final long durationMs;
        final String ref;
        final String where;

        Item(Kind kind, String name, long durationMs, String ref, String where) {
            this.kind = kind; this.name = name; this.durationMs = durationMs; this.ref = ref; this.where = where;
        }
    }

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("list_media", "List the files you can add")
                .help("The pictures, videos and audio the person can add to this project: the files beside it in"
                        + " their Joy Creator folder, the Assets folder, and recent recordings. Each line gives the"
                        + " kind, the name, the length when it is known, and the reference to pass to add_media."
                        + " Call this before add_media when you don't have a reference.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › Add")
                .build(), SourcesActions::listMedia);

        r.add(ActionSpec.of("add_media", "Add a picture, video or audio file")
                .help("Bring a file into this project. as=clip puts it in line on the main track (a picture becomes"
                        + " a still). as=layer puts it on top as a new layer (a picture is an image layer, a video a"
                        + " picture-in-picture, audio an audio clip). Audio always goes on the audio track. Give the"
                        + " reference from list_media, or the file's name.")
                .wing("STUDIO", FAMILY)
                .where("Studio › Add")
                .text("file", true, "A reference from list_media, or the file's name")
                .choice("as", true, "clip = in line on the main track; layer = on top as a new layer", AS)
                .time("atMs", false, "Where it starts on the timeline. Default: the end of the line for a clip,"
                        + " the start for a layer.", 0.0, null)
                .build(), SourcesActions::addMedia);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult listMedia(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        List<Item> items = gather(ctx);
        if (items.isEmpty()) {
            return ActionResult.unchanged("There are no pictures, videos or audio files you can add right now.");
        }
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            sb.append("- ").append(kindWord(it.kind)).append(": ").append(it.name);
            if (it.durationMs > 0) sb.append(" (").append(StudioCtx.clock(it.durationMs)).append(')');
            sb.append(" — ").append(it.where).append(" — ref ").append(it.ref).append('\n');
        }
        return ActionResult.unchanged(items.size() + " file(s) you can add.").withDetail(sb.toString().trim());
    }

    @NonNull
    private static ActionResult addMedia(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Item src = resolve(ctx, a.str("file"));
        boolean layer = "layer".equals(a.choice("as", AS));
        Long atArg = a.whole("atMs");

        // Copy into the project first (the editor copies before it probes), then probe the copy.
        Uri stored = importToProject(ctx, Uri.parse(src.ref), src.kind, src.name);
        long dur = src.kind == Kind.PICTURE ? IMAGE_CLIP_DURATION_MS : probe(ctx, src.kind, stored);
        if (src.kind != Kind.PICTURE && dur <= 0) {
            if (!stored.equals(Uri.parse(src.ref)) && "file".equals(stored.getScheme()) && stored.getPath() != null) {
                new File(stored.getPath()).delete();   // do not leave an unused copy behind
            }
            throw new ActionError("I couldn't read how long \"" + src.name + "\" is, so it wasn't added.");
        }

        Timeline tl = ctx.project.getTimeline();
        if (src.kind == Kind.AUDIO) {
            // Editor: audio always goes on the audio track (addAudioClip resolves overlaps). Offset = atMs.
            long at = atArg == null ? 0L : atArg;
            AudioClip ac = new AudioClip(stored, dur);
            ac.setLabel(src.name);
            ac.setOffsetMs(at);
            tl.addAudioClip(ac);
            return changedOne("Added the audio \"" + src.name + "\" at " + StudioCtx.clock(at) + ".", ac.getId());
        }
        if (layer) {
            long at = atArg == null ? 0L : atArg;
            if (src.kind == Kind.PICTURE) return pictureLayer(ctx, stored, src.name, at);
            return videoLayer(ctx, stored, src.name, dur, at);
        }
        long at = atArg == null ? spineEnd(tl) : atArg;
        return lineClip(ctx, stored, src, dur, at);
    }

    /** Editor: insertAssetAtPlayhead (video / picture branch) with the playhead replaced by atMs. */
    @NonNull
    private static ActionResult lineClip(@NonNull StudioCtx ctx, @NonNull Uri stored, @NonNull Item src,
                                         long dur, long at) {
        Timeline tl = ctx.project.getTimeline();
        Clip newClip;
        if (src.kind == Kind.PICTURE) {
            // newImageClip(uri) + setImageClip(true) + setAudioMuted(true)
            newClip = new Clip(stored, Math.max(100L, IMAGE_CLIP_DURATION_MS));
            newClip.setSourceDurationMs(Math.max(IMAGE_CLIP_MAX_MS, Math.max(100L, IMAGE_CLIP_DURATION_MS)));
            newClip.setImageClip(true);
            newClip.setAudioMuted(true);
        } else {
            newClip = new Clip(stored, dur);
        }

        int insertIndex = computeInsertIndexAtTimelineMs(tl, at);

        // If the point is mid-clip, split first (editor: split only strictly inside a non-still clip).
        long segStart = 0;
        int hitIdx = -1;
        long hitStart = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            long eff = c.getEffectiveDurationMs();
            if (at >= segStart && at < segStart + eff) { hitIdx = i; hitStart = segStart; break; }
            segStart += eff;
        }
        if (hitIdx >= 0) {
            Clip cur = tl.getClip(hitIdx);
            if (!cur.isImageClip()) {
                long localMs = at - hitStart;
                long eff = cur.getEffectiveDurationMs();
                if (localMs > 200 && localMs < eff - 200) {
                    long sourceSplitMs = cur.getInPointMs() + (long) (localMs * cur.getSpeedMultiplier());
                    tl.splitAt(hitIdx, sourceSplitMs);
                    tl.shiftTransitionsAfterSplit(hitIdx);
                    insertIndex = hitIdx + 1;
                }
            }
        }

        tl.addClip(insertIndex, newClip);
        tl.shiftTransitionsAfterInsert(insertIndex);
        return changedOne("Put the " + kindWord(src.kind) + " \"" + src.name + "\" in the line at "
                + StudioCtx.clock(at) + ".", newClip.getId());
    }

    /** Editor: onImageAsNewLayerPicked — a new lane above all layers, the picture on top of them. */
    @NonNull
    private static ActionResult pictureLayer(@NonNull StudioCtx ctx, @NonNull Uri stored, @NonNull String name,
                                             long at) {
        Timeline tl = ctx.project.getTimeline();
        String newTrackId = tl.createLayerTrack(TrackKind.LAYER, "Image " + (tl.getLayers().size() + 1));
        int topZ = topLayerZIndex(tl) + 1;
        tl.getOrCreateTrackFlags(newTrackId).zIndex = topZ;

        // createImage(uri, 0.5, 0.5, 0.30) + placeNewOverlayAtPlayhead (5 s from atMs). Fit is not applied here.
        TextOverlayItem item = TextOverlayItem.createImage(stored.toString(), 0.5f, 0.5f, 0.30f);
        item.setLayerId(newTrackId);
        item.setTimeRange(at, at + IMAGE_CLIP_DURATION_MS);
        tl.addTextOverlay(item);
        return changedOne("Put the picture \"" + name + "\" on top as a new layer at " + StudioCtx.clock(at) + ".",
                item.getId());
    }

    /** Editor: the overlay-video (PiP) picker callback — fit and centred, on the first free video lane. */
    @NonNull
    private static ActionResult videoLayer(@NonNull StudioCtx ctx, @NonNull Uri stored, @NonNull String name,
                                           long dur, long at) {
        Timeline tl = ctx.project.getTimeline();
        Clip overlay = new Clip(stored, dur);
        overlay.setOverlayStartMs(Math.max(0, at));
        assignOverlayClipToFreeLane(tl, overlay, overlay.getOverlayStartMs(),
                overlay.getOverlayStartMs() + Math.max(0, dur));
        overlay.setAudioMuted(true);
        KeyframeSet kf = new KeyframeSet();
        kf.getOrCreate(KeyframeSet.X).put(0L, 0.5f, Easing.LINEAR);
        kf.getOrCreate(KeyframeSet.Y).put(0L, 0.5f, Easing.LINEAR);
        kf.getOrCreate(KeyframeSet.SCALE).put(0L, 1.0f, Easing.LINEAR);
        overlay.setOverlayTransform(kf);
        tl.addOverlayClip(overlay);
        return changedOne("Put the video \"" + name + "\" on top as a picture-in-picture at "
                + StudioCtx.clock(at) + ".", overlay.getId());
    }

    // ── finding files ────────────────────────────────────────────────────────

    /**
     * The one file the reference names: a reference from list_media (exact), else a name matched
     * exactly, then by part of the name. Several matches is an error that lists each, so the model asks.
     */
    @NonNull
    private static Item resolve(@NonNull StudioCtx ctx, @NonNull String file) throws ActionError {
        String ref = file.trim();
        List<Item> items = gather(ctx);
        for (Item it : items) if (it.ref.equals(ref)) return it;
        List<Item> hit = new ArrayList<>();
        for (Item it : items) if (it.name.equalsIgnoreCase(ref)) hit.add(it);
        if (hit.isEmpty()) {
            String want = ref.toLowerCase(Locale.ROOT);
            for (Item it : items) if (it.name.toLowerCase(Locale.ROOT).contains(want)) hit.add(it);
        }
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            if (items.isEmpty()) {
                throw new ActionError("There's no file called \"" + ref + "\": there are no files you can add right now.");
            }
            throw new ActionError("There's no file called \"" + ref + "\". You can add: " + names(items, 12)
                    + ". Use list_media for the full list.");
        }
        throw new ActionError(hit.size() + " files match \"" + ref + "\": " + names(hit, 12)
                + ". Ask which one, or pass its reference.");
    }

    /** What the person can add: beside this project and in Assets (Joy Creator folder), then recordings. */
    @NonNull
    private static List<Item> gather(@NonNull StudioCtx ctx) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            JoyFolder jf = JoyFolderHome.open(ctx.app);
            SafJoyFs saf = JoyFolderHome.fsOf(jf);
            if (jf != null && saf != null) {
                // ShareInActivity / Placement.SHARE_IN: beside the open work, else Assets.
                String work = StudioMirror.folderPathOf(ctx.app, ctx.projectId);
                if (work != null) {
                    addFolder(out, seen, jf, saf, Placement.parentOf(work), "beside this project in your Joy Creator folder");
                }
                String assets = jf.roles.get(FolderRoles.Role.ASSETS);
                if (assets != null) addFolder(out, seen, jf, saf, assets, "in your Assets folder");
            }
        } catch (Exception e) {
            FLog.d(TAG, "Joy Creator folder not read: " + e.getMessage());
        }
        try {
            VideoIndexRepository repo = VideoIndexRepository.getInstance(ctx.app);
            List<VideoItem> library = repo.getVideos(SharedPreferencesManager.getInstance(ctx.app));
            List<VideoItem> recent = new ArrayList<>();
            for (VideoItem v : library) {
                if (v == null || v.uri == null || v.mediaType != VideoItem.MediaType.VIDEO) continue;
                if (v.isTemporary || v.isProcessingUri || v.isSkeleton) continue;
                recent.add(v);
            }
            Collections.sort(recent, (x, y) -> Long.compare(y.lastModified, x.lastModified));
            int added = 0;
            for (VideoItem v : recent) {
                if (added >= RECENT_RECORDINGS) break;
                String ref = v.uri.toString();
                if (!seen.add(ref)) continue;
                String name = v.displayName == null || v.displayName.trim().isEmpty()
                        ? "Recording" : v.displayName.trim();
                out.add(new Item(Kind.VIDEO, name, repo.getCachedDuration(ref), ref, "a recent recording"));
                added++;
            }
        } catch (Exception e) {
            FLog.d(TAG, "Recordings not read: " + e.getMessage());
        }
        return out;
    }

    /** The media files directly in one folder of the Joy Creator folder (not hidden, not subfolders). */
    private static void addFolder(@NonNull List<Item> out, @NonNull Set<String> seen, @NonNull JoyFolder jf,
                                  @NonNull SafJoyFs saf, @NonNull String dir, @NonNull String where) {
        try {
            for (JoyFs.Entry e : jf.fs.list(dir)) {
                if (e.dir || e.name.startsWith(".")) continue;
                Kind k = kindOf(e.name, null);
                if (k == null) continue;
                String ref = saf.documentUri(Placement.join(dir, e.name)).toString();
                if (!seen.add(ref)) continue;
                out.add(new Item(k, e.name, -1L, ref, where));
            }
        } catch (IOException e) {
            FLog.d(TAG, "Folder not listed: " + e.getMessage());
        }
    }

    @Nullable
    private static Kind kindOf(@NonNull String name, @Nullable String mime) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String x : PICTURE_EXT) if (n.endsWith(x)) return Kind.PICTURE;
        for (String x : VIDEO_EXT) if (n.endsWith(x)) return Kind.VIDEO;
        for (String x : AUDIO_EXT) if (n.endsWith(x)) return Kind.AUDIO;
        if (mime != null) {
            if (mime.startsWith("image/")) return Kind.PICTURE;
            if (mime.startsWith("video/")) return Kind.VIDEO;
            if (mime.startsWith("audio/")) return Kind.AUDIO;
        }
        return null;
    }

    @NonNull
    private static String names(@NonNull List<Item> items, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Item it : items) {
            if (n++ == max) { sb.append(", … ").append(items.size() - max).append(" more"); break; }
            sb.append(sb.length() > 0 ? ", " : "").append('"').append(it.name).append("\" (ref ")
                    .append(it.ref).append(')');
        }
        return sb.toString();
    }

    // ── copying and probing (editor helpers, copied: they are private to the activity) ──

    /** Editor: importInsertedAsset. Pictures are always copied; other files up to 25 MB; bigger ones are linked. */
    @NonNull
    private static Uri importToProject(@NonNull StudioCtx ctx, @NonNull Uri src, @NonNull Kind kind,
                                       @NonNull String name) {
        try {
            long size = sizeOf(ctx.app, src);
            boolean shouldCopy = kind == Kind.PICTURE || (size >= 0 && size <= ASSET_COPY_MAX_BYTES);
            if (!shouldCopy) return src;
            File assetsDir = new File(new ProjectStorage(ctx.app).projectDir(ctx.projectId), "assets");
            if (!assetsDir.exists() && !assetsDir.mkdirs()) return src;
            File dest = new File(assetsDir, UUID.randomUUID() + extensionOf(ctx.app, src, name, kind));
            try (InputStream in = ctx.app.getContentResolver().openInputStream(src);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) { dest.delete(); return src; }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return Uri.fromFile(dest);
        } catch (Exception e) {
            FLog.d(TAG, "Copy into the project failed; referencing the original: " + e.getMessage());
            return src;
        }
    }

    /** Editor: queryAssetSize. Bytes, or -1 when unknown. */
    private static long sizeOf(@NonNull Context app, @NonNull Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            return f.exists() ? f.length() : -1L;
        }
        try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) { }
        return -1L;
    }

    /** Editor: assetExtension. The name's extension, else the type's, else a default for the kind. */
    @NonNull
    private static String extensionOf(@NonNull Context app, @NonNull Uri src, @NonNull String name, @NonNull Kind kind) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) return name.substring(dot);
        String mime = app.getContentResolver().getType(src);
        String ext = mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (ext != null) return "." + ext;
        switch (kind) {
            case PICTURE: return ".jpg";
            case AUDIO: return ".m4a";
            default: return ".mp4";
        }
    }

    /** Editor: getVideoDuration (FFprobe for a plain file, then MediaMetadataRetriever) / getAudioDuration. */
    private static long probe(@NonNull StudioCtx ctx, @NonNull Kind kind, @NonNull Uri uri) {
        if (kind == Kind.AUDIO) {
            long d = retrieverMs(ctx.app, uri);
            return d > 0 ? d : AUDIO_FALLBACK_MS;
        }
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            try {
                MediaInformationSession session = FFprobeKit.getMediaInformation(uri.getPath());
                MediaInformation info = session.getMediaInformation();
                if (info != null && info.getDuration() != null) {
                    return (long) (Double.parseDouble(info.getDuration()) * 1000);
                }
            } catch (Exception e) {
                FLog.d(TAG, "FFprobe duration failed: " + e.getMessage());
            }
        }
        return retrieverMs(ctx.app, uri);
    }

    private static long retrieverMs(@NonNull Context app, @NonNull Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(app, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Long.parseLong(d);
        } catch (Exception e) {
            FLog.d(TAG, "Duration not read: " + e.getMessage());
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return -1L;
    }

    // ── timeline model (editor helpers, copied) ───────────────────────────────

    /** Editor: computeInsertIndexAtTimelineMs. */
    private static int computeInsertIndexAtTimelineMs(@NonNull Timeline tl, long ms) {
        if (tl.getClipCount() == 0) return 0;
        long cumul = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            long eff = tl.getClip(i).getEffectiveDurationMs();
            if (ms <= cumul + eff / 2) return i;
            cumul += eff;
        }
        return tl.getClipCount();
    }

    /** The end of the line: where a clip goes when no atMs is given. */
    private static long spineEnd(@NonNull Timeline tl) {
        long end = 0;
        for (int i = 0; i < tl.getClipCount(); i++) end += tl.getClip(i).getEffectiveDurationMs();
        return end;
    }

    /** Editor: topLayerZIndex. */
    private static int topLayerZIndex(@NonNull Timeline timeline) {
        int max = 0;
        boolean any = false;
        for (Track t : timeline.getLayers()) {
            if (!any || t.getZIndex() > max) { max = t.getZIndex(); any = true; }
        }
        return any ? max : 0;
    }

    /** Editor: assignOverlayClipToFreeLane — the first lane free over the range, else a new video lane. */
    private static void assignOverlayClipToFreeLane(@NonNull Timeline tl, @NonNull Clip clip,
                                                    long startMs, long endMs) {
        List<String> candidates = new ArrayList<>();
        for (com.fadcam.ui.faditor.layers.LayerTrackDef def : tl.getExtraLayerTracks()) {
            if (def.getKind() == TrackKind.AUDIO) continue;
            candidates.add(def.getId());
        }
        Collections.reverse(candidates);
        candidates.add("video");
        for (String laneId : candidates) {
            if (!laneRangeOccupied(tl, laneId, startMs, endMs, clip.getId())) {
                clip.setLayerId(laneId);
                return;
            }
        }
        String newId = tl.createLayerTrack(TrackKind.VIDEO, "PiP " + (tl.getOverlayClips().size() + 1));
        clip.setLayerId(newId);
    }

    /** Editor: laneRangeOccupied — does anything on this lane overlap the range (any payload type). */
    private static boolean laneRangeOccupied(@NonNull Timeline tl, @Nullable String trackId,
                                             long startMs, long endMs, @Nullable String ignoreId) {
        String candKey = trackId == null ? "text" : trackId;
        for (TextOverlayItem o : tl.getTextOverlays()) {
            if (o.getId().equals(ignoreId)) continue;
            String key = o.getLayerId() == null ? "text" : o.getLayerId();
            if (!key.equals(candKey)) continue;
            long e = o.getEndMs();
            if (e == Long.MAX_VALUE || e <= o.getStartMs()) e = Long.MAX_VALUE;
            if (Timeline.rangesOverlap(startMs, endMs, o.getStartMs(), e)) return true;
        }
        for (SpriteOverlayItem s : tl.getSpriteOverlays()) {
            if (s.getId().equals(ignoreId)) continue;
            String key = s.getLayerId() == null ? "sprite" : s.getLayerId();
            if (!key.equals(candKey)) continue;
            long e = s.getEndMs();
            if (e == Long.MAX_VALUE || e <= s.getStartMs()) e = Long.MAX_VALUE;
            if (Timeline.rangesOverlap(startMs, endMs, s.getStartMs(), e)) return true;
        }
        for (Clip oc : tl.getOverlayClips()) {
            if (oc.getId().equals(ignoreId)) continue;
            String key = oc.getLayerId() == null ? "video" : oc.getLayerId();
            if (!key.equals(candKey)) continue;
            long s = oc.getOverlayStartMs();
            long dur = oc.hasLoopExtension() ? oc.getVisualDurationMs() : oc.getTrimmedDurationMs();
            if (Timeline.rangesOverlap(startMs, endMs, s, s + Math.max(0, dur))) return true;
        }
        for (AdjustmentLayer al : tl.getAdjustmentLayers()) {
            if (al.getId().equals(ignoreId)) continue;
            String key = al.getLayerId().isEmpty() ? "adjustment" : al.getLayerId();
            if (!key.equals(candKey)) continue;
            long e = al.getDurationMs() <= 0L ? Long.MAX_VALUE : al.getEndMs();
            if (Timeline.rangesOverlap(startMs, endMs, al.getStartMs(), e)) return true;
        }
        return false;
    }

    // ── small helpers ────────────────────────────────────────────────────────

    @NonNull
    private static String kindWord(@NonNull Kind k) {
        switch (k) {
            case PICTURE: return "picture";
            case AUDIO: return "audio";
            default: return "video";
        }
    }

    @NonNull
    private static ActionResult changedOne(@NonNull String summary, @NonNull String id) {
        return ActionResult.changed(summary, Collections.singletonList(id));
    }
}
