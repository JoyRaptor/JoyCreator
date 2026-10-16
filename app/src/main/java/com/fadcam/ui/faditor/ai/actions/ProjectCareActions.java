package com.fadcam.ui.faditor.ai.actions;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.joyfolder.FolderIndex;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.fadcam.joyfolder.studio.StudioMirror;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.AssetResolver;
import com.fadcam.ui.faditor.project.ProjectIntegrity;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The project care family (gap F2): "is anything in this project missing?" and "find my missing
 * files again". The editor's Relink screen (FaditorEditorActivity.showRelinkCatalog) is the model:
 * a missing file is matched by its file name, the picked file's folder is searched beside it
 * (autoRelinkSiblings), and the clip or audio source is rewritten with its trims clamped (applyRelink).
 *
 * <p>Differences from the editor, on purpose: no file picker, so the places searched are the folder
 * the person names and the Joy Creator folder (read through its index, the copies made by Copy/Move
 * live there). A missing file's content hash is not stored anywhere, so the match is by name, and a
 * found file whose length is more than 20% off is not used (the editor only warns about that).
 * Nothing is copied into the app. Consolidate and bundle are not here.</p>
 */
final class ProjectCareActions {
    private ProjectCareActions() { }

    private static final String FAMILY = "care";
    /** A candidate whose length is further off than this from the clip's own is a different file. */
    private static final double LENGTH_TOLERANCE = 0.2;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("check_project", "Check the project's files")
                .help("Find what this project is missing or at risk of losing: media files that can't be opened,"
                        + " files linked from outside the Joy Creator folder that are not brought in yet, and fonts"
                        + " not kept with the project. Use it when the person asks whether anything is missing, and"
                        + " before relink_missing.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › opening a project (the missing-file message)")
                .build(), ProjectCareActions::checkProject);

        r.add(ActionSpec.of("relink_missing", "Find missing files again")
                .help("Point a project's missing clips and audio back at their files. A file is found by its name"
                        + " in the folder the person names and in the Joy Creator folder, and only taken when its length"
                        + " is close to the original. Renamed files are not found. Overlay layers and picture sequences"
                        + " are not relinked here; say so and point the person to the editor.")
                .wing("STUDIO", FAMILY)
                .where("Studio › the missing-file message › Relink")
                .text("folder", false, "A folder on this phone to search, e.g. /storage/emulated/0/Movies/Camera")
                .build(), ProjectCareActions::relinkMissing);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult checkProject(@NonNull StudioCtx ctx, @NonNull Args a) {
        Set<String> gone = goneUris(ctx);
        List<String> missing = new ArrayList<>(), outside = new ArrayList<>(), fonts = new ArrayList<>();
        for (Ref r : refs(ctx.project)) {
            if (gone.contains(r.uri.toString())) missing.add(r.describe());
            else if (StudioMirror.isOutside(ctx.app, r.uri.toString())) outside.add(r.describe());
        }
        missing.addAll(sequenceLines(ctx, gone));

        // Fonts: a font file the project does not hold itself (not under its own folder).
        File dir = new ProjectStorage(ctx.app).projectDir(ctx.projectId);
        Set<String> seenFont = new HashSet<>();
        Timeline tl = ctx.project.getTimeline();
        for (TextOverlayItem t : tl.getTextOverlays()) {
            String f = t.getFontFamily();
            if (f == null || !f.startsWith("file:") || t.isImage() || t.isGeneratedSlide()) continue;
            if (AssetResolver.isInsideProjectFont(dir, f) || !seenFont.add(f)) continue;
            fonts.add("Font \"" + lastSegment(f) + "\" (text id " + t.getId() + ")");
        }

        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) parts.add(missing.size() + " missing");
        if (!outside.isEmpty()) parts.add(outside.size() + " linked from outside your Joy Creator folder");
        if (!fonts.isEmpty()) parts.add(fonts.size() + " font(s) not kept with the project");
        String summary = parts.isEmpty()
                ? "Everything this project uses is in place."
                : "Checked the files: " + join(parts) + ".";

        StringBuilder d = new StringBuilder();
        section(d, "Missing", missing);
        section(d, "Linked from outside the Joy Creator folder (not brought in yet)", outside);
        section(d, "Fonts not kept with the project", fonts);
        if (!missing.isEmpty()) {
            d.append("relink_missing can point the missing clips and audio back at copies it finds.");
        }
        return ActionResult.unchanged(summary).withDetail(d.toString().trim());
    }

    @NonNull
    private static ActionResult relinkMissing(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Timeline tl = ctx.project.getTimeline();
        Set<String> gone = goneUris(ctx);
        String hint = a.str("folder");
        File hintDir = hint == null ? null : folderFrom(hint);
        if (hintDir == null && !JoyFolderHome.isChosen(ctx.app)) {
            throw new ActionError("Name a folder on this phone to search: the Joy Creator folder isn't set up.");
        }

        // What can be relinked: missing clips and audio. Overlays and sequences are reported, not touched.
        List<Ref> todo = new ArrayList<>();
        List<String> notHere = new ArrayList<>();
        for (Ref r : refs(ctx.project)) {
            if (!gone.contains(r.uri.toString())) continue;
            if (r.clip != null || r.audio != null) todo.add(r);
            else notHere.add(r.describe());
        }
        notHere.addAll(sequenceLines(ctx, gone));

        if (todo.isEmpty()) {
            return ActionResult.unchanged("Nothing that can be relinked is missing.")
                    .withDetail(notHere.isEmpty() ? "" : joinLines("Not relinked here", notHere));
        }

        // Candidate files, by name: the folder the person named, then the Joy Creator folder.
        Map<String, List<Uri>> byName = new HashMap<>();
        Set<String> seenUri = new HashSet<>();
        if (hintDir != null) {
            File[] files = hintDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.canRead()) addCandidate(byName, seenUri, f.getName(), Uri.fromFile(f));
                }
            }
        }
        for (Found f : joyFolderFiles(ctx.app)) addCandidate(byName, seenUri, f.name, f.uri);

        Map<String, Long> lengths = new HashMap<>();
        List<String> relinked = new ArrayList<>(), touched = new ArrayList<>(), stillMissing = new ArrayList<>();
        for (Ref r : todo) {
            List<Uri> hits = hitsFor(byName, r.key);
            if (hits.isEmpty()) {
                stillMissing.add(r.describe() + ": no file with that name in the folders searched");
                continue;
            }
            if (hits.size() > 1) {
                stillMissing.add(r.describe() + ": " + hits.size() + " files have that name, so none was picked");
                continue;
            }
            Uri found = hits.get(0);
            long newLen = lengthOf(ctx.app, found, lengths);
            long oldLen = r.clip != null ? r.clip.getSourceDurationMs() : r.audio.getSourceDurationMs();
            if (oldLen > 0 && newLen > 0 && Math.abs(newLen - oldLen) > oldLen * LENGTH_TOLERANCE) {
                stillMissing.add(r.describe() + ": a file with that name is a different length, so it was not used");
                continue;
            }
            if (r.clip != null) applyClip(tl, r.index, found, newLen);
            else applyAudio(r.audio, found, newLen);
            relinked.add(r.display);
            touched.add(r.id);
        }

        StringBuilder d = new StringBuilder();
        section(d, "Still missing", stillMissing);
        section(d, "Not relinked here", notHere);
        d.append("Searched: ").append(hintDir != null ? hintDir.getPath() + " and " : "")
                .append("the Joy Creator folder. Consolidate and bundle are not done here.");
        if (relinked.isEmpty()) {
            return ActionResult.unchanged("None of the missing files were found in the folders searched.")
                    .withDetail(d.toString().trim());
        }
        return ActionResult.changed("Relinked " + relinked.size() + " file(s): " + join(relinked) + ".", touched)
                .withDetail(d.toString().trim());
    }

    // ── what is missing ──────────────────────────────────────────────────────

    /** Media URIs ProjectIntegrity cannot open (its own check: grants, deleted files, cloud documents). */
    @NonNull
    private static Set<String> goneUris(@NonNull StudioCtx ctx) {
        Set<String> out = new HashSet<>();
        for (ProjectIntegrity.Missing m : ProjectIntegrity.scan(ctx.app, ctx.project).missing) out.add(m.uri);
        return out;
    }

    /** One line per picture sequence with missing frames. Not relinked here. */
    @NonNull
    private static List<String> sequenceLines(@NonNull StudioCtx ctx, @NonNull Set<String> gone) {
        List<String> out = new ArrayList<>();
        for (SpriteSheet sh : ctx.project.getSpriteSheets()) {
            if (sh == null || !sh.isSequence()) continue;
            int n = 0;
            for (String u : sh.getFrameUris()) {
                if (u != null && !u.isEmpty() && gone.contains(Uri.parse(u).toString())) n++;
            }
            if (n > 0) out.add("Picture sequence \"" + sh.getName() + "\" (id " + sh.getId() + "): "
                    + n + " frame file(s) missing");
        }
        return out;
    }

    /** Every clip, overlay and audio source with the words the person would see for it. */
    @NonNull
    private static List<Ref> refs(@NonNull FaditorProject p) {
        List<Ref> out = new ArrayList<>();
        Timeline tl = p.getTimeline();
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            if (c == null || c.isGeneratedSlide() || c.getSourceUri() == null) continue;
            out.add(new Ref("Clip " + (i + 1), i, c.getId(), c.getSourceUri(), c.getDisplayName(), c, null));
        }
        int n = 0;
        for (Clip oc : tl.getOverlayClips()) {
            n++;
            if (oc == null || oc.getSourceUri() == null) continue;
            out.add(new Ref("Overlay " + n, -1, oc.getId(), oc.getSourceUri(), oc.getDisplayName(), null, null));
        }
        n = 0;
        for (AudioClip ac : tl.getAudioClips()) {
            n++;
            if (ac == null || ac.getSourceUri() == null) continue;
            out.add(new Ref("Audio " + n, -1, ac.getId(), ac.getSourceUri(), ac.getLabel(), null, ac));
        }
        return out;
    }

    // ── finding candidates ───────────────────────────────────────────────────

    /** A file found in a folder: its name and where it can be opened. */
    private static final class Found {
        final String name;
        final Uri uri;
        Found(String name, Uri uri) { this.name = name; this.uri = uri; }
    }

    /**
     * The folder the person named, as a plain folder on this phone. A file inside it stands for its
     * folder, as the editor searches beside the file it was given.
     */
    @NonNull
    private static File folderFrom(@NonNull String hint) throws ActionError {
        String h = hint.trim();
        if (h.startsWith("content:")) {
            throw new ActionError("I can only search a folder given by its path on this phone, like"
                    + " /storage/emulated/0/Movies. Leave the folder out to search the Joy Creator folder.");
        }
        if (h.startsWith("file://")) {
            String p = Uri.parse(h).getPath();
            h = p == null ? "" : p;
        }
        File f = new File(h);
        if (f.isFile()) f = f.getParentFile();
        if (f == null || !f.isDirectory() || !f.canRead()) {
            throw new ActionError("I can't open a folder at \"" + hint.trim()
                    + "\". Give a folder path on this phone, or leave it out.");
        }
        return f;
    }

    /**
     * The files in the Joy Creator folder, from its index, each with a link that opens it. Its
     * folder I/O runs only on the folder's own thread (JoyFolderHome.io()); this caller waits for it.
     * Never call this from that thread, or it would wait on itself.
     */
    @NonNull
    private static List<Found> joyFolderFiles(@NonNull Context app) {
        try {
            return JoyFolderHome.io().submit(() -> {
                List<Found> out = new ArrayList<>();
                JoyFolder jf = JoyFolderHome.open(app);
                SafJoyFs saf = JoyFolderHome.fsOf(jf);
                if (jf == null || saf == null) return out;
                for (FolderIndex.Item it : jf.index.items()) {
                    if (jf.fs.stat(it.path) == null) continue;
                    Uri uri = saf.documentUri(it.path);
                    if (uri != null) out.add(new Found(lastSegment(it.path), uri));
                }
                return out;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ArrayList<>();   // not reachable right now: nothing to offer
        }
    }

    private static void addCandidate(@NonNull Map<String, List<Uri>> byName, @NonNull Set<String> seen,
                                     @NonNull String name, @NonNull Uri uri) {
        if (!seen.add(uri.toString())) return;
        List<Uri> l = byName.get(name);
        if (l == null) byName.put(name, l = new ArrayList<>());
        l.add(uri);
    }

    /**
     * Files called this name; if none, called the name without a leading "remuxed_" (the editor
     * does the same: a remuxed copy keeps the original's name).
     */
    @NonNull
    private static List<Uri> hitsFor(@NonNull Map<String, List<Uri>> byName, @NonNull String name) {
        List<Uri> hits = byName.get(name);
        if (hits == null || hits.isEmpty()) {
            String clean = name.replaceFirst("(?i)^remuxed_", "");
            hits = byName.get(clean);
        }
        return hits == null ? new ArrayList<Uri>() : hits;
    }

    /** Length in ms as the phone reports it, 0 when unknown. Probed once per file. */
    private static long lengthOf(@NonNull Context app, @NonNull Uri uri, @NonNull Map<String, Long> cache) {
        String k = uri.toString();
        Long known = cache.get(k);
        if (known != null) return known;
        long ms = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(app, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) ms = Long.parseLong(d);
        } catch (Exception ignored) {
            // Unreadable: length stays unknown, and the length check is skipped.
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        cache.put(k, ms);
        return ms;
    }

    // ── the rewrite (same as the editor's autoRelinkSiblings / applyRelink) ──

    /** Swap a spine clip's source, keeping its edits; trims clamped to the new length. */
    private static void applyClip(@NonNull Timeline tl, int index, @NonNull Uri uri, long newLen) {
        Clip old = tl.getClip(index);
        Clip nu = old.relinked(uri);
        if (newLen > 0) {
            nu.setSourceDurationMs(newLen);
            long in = Math.min(old.getInPointMs(), newLen - 1);
            long out = Math.min(old.getOutPointMs(), newLen);
            if (out <= in) { in = 0; out = newLen; }
            nu.setInPointMs(in);
            nu.setOutPointMs(out);
        }
        tl.removeClip(index);
        tl.addClip(index, nu);
    }

    private static void applyAudio(@NonNull AudioClip ac, @NonNull Uri uri, long newLen) {
        ac.setSourceUri(uri);
        if (newLen > 0) {
            ac.setSourceDurationMs(newLen);
            ac.setOutPointMs(newLen);
        }
    }

    // ── words ────────────────────────────────────────────────────────────────

    /** One missing or checked item: where it sits, what it is called, and what it points at. */
    private static final class Ref {
        final String label;      // "Clip 2", "Overlay 1", "Audio 1"
        final int index;         // spine clip index, or -1
        final String id;
        final Uri uri;
        final String key;        // the file name a match is made on
        final String display;    // what the person calls it
        @Nullable final Clip clip;
        @Nullable final AudioClip audio;

        Ref(String label, int index, String id, Uri uri, @Nullable String displayName,
            @Nullable Clip clip, @Nullable AudioClip audio) {
            this.label = label;
            this.index = index;
            this.id = id;
            this.uri = uri;
            String file = AssetResolver.filename(uri);
            this.key = file != null ? file : (displayName != null ? displayName : label);
            this.display = displayName != null && !displayName.isEmpty() ? displayName : this.key;
            this.clip = clip;
            this.audio = audio;
        }

        @NonNull
        String describe() {
            return label + " \"" + display + "\" (id " + id + ")";
        }
    }

    @NonNull
    private static String lastSegment(@NonNull String s) {
        int k = s.lastIndexOf('/');
        return k >= 0 ? s.substring(k + 1) : s;
    }

    private static void section(@NonNull StringBuilder d, @NonNull String head, @NonNull List<String> lines) {
        if (lines.isEmpty()) return;
        d.append(head).append(" (").append(lines.size()).append("):\n");
        for (String l : lines) d.append("- ").append(l).append('\n');
    }

    @NonNull
    private static String joinLines(@NonNull String head, @NonNull List<String> lines) {
        StringBuilder d = new StringBuilder();
        section(d, head, lines);
        return d.toString().trim();
    }

    @NonNull
    private static String join(@NonNull List<String> l) {
        StringBuilder sb = new StringBuilder();   // String.join is API 26; minSdk is 24
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }
}
