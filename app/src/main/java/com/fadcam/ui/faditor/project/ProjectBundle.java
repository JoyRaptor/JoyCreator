package com.fadcam.ui.faditor.project;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.FaditorProject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonObject;

/**
 * Portable bundle: zip / unzip a project folder plus a small manifest.
 * Consolidate is a prerequisite — this class will refuse to export an
 * unconsolidated project that still has external references.
 */
public final class ProjectBundle {

    private static final String TAG = "ProjectBundle";
    private static final String MANIFEST = "manifest.json";

    private ProjectBundle() {}

    /** Manifest written into every bundle zip. */
    public static class Manifest {
        public String appVersion = "";
        public int schemaVersion = FaditorProject.SCHEMA_VERSION;
        public int fileCount = 0;
        public long totalBytes = 0;
        public String projectId = "";
        public String projectName = "";
        public long exportedAt = 0;
    }

    /** Result of an export. */
    public static class ExportResult {
        public boolean ok = false;
        public File zipFile;
        public String error;
        public Manifest manifest;
    }

    /** Result of an import. */
    public static class ImportResult {
        public boolean ok = false;
        public String newProjectId;
        public String error;
        public FaditorProject project;
    }

    /**
     * Whether this project is fully consolidated (every asset lives inside
     * its own folder as {@code project://}).
     */
    public static boolean isConsolidated(@NonNull Context ctx, @NonNull FaditorProject project) {
        ProjectStorage storage = new ProjectStorage(ctx);
        File dir = storage.projectDir(project.getId());
        // Walk timeline references — if any source is not project:// and not already inside, it's external.
        for (com.fadcam.ui.faditor.model.Clip c : project.getTimeline().getClips()) {
            String s = c.getSourceUri().toString();
            if (!s.startsWith(AssetResolver.PROJECT_URI_PREFIX)) {
                Uri u = c.getSourceUri();
                if (!AssetResolver.isInsideProject(dir, u)) return false;
            }
        }
        for (com.fadcam.ui.faditor.model.Clip c : project.getTimeline().getOverlayClips()) {
            String s = c.getSourceUri().toString();
            if (!s.startsWith(AssetResolver.PROJECT_URI_PREFIX)) {
                Uri u = c.getSourceUri();
                if (!AssetResolver.isInsideProject(dir, u)) return false;
            }
        }
        for (com.fadcam.ui.faditor.model.AudioClip ac : project.getTimeline().getAudioClips()) {
            String s = ac.getSourceUri().toString();
            if (!s.startsWith(AssetResolver.PROJECT_URI_PREFIX)) {
                Uri u = ac.getSourceUri();
                if (!AssetResolver.isInsideProject(dir, u)) return false;
            }
        }
        for (com.fadcam.ui.faditor.model.TextOverlayItem o : project.getTimeline().getTextOverlays()) {
            if (o.getImageUri() != null && !o.getImageUri().startsWith(AssetResolver.PROJECT_URI_PREFIX)) {
                Uri u = Uri.parse(o.getImageUri());
                if (!AssetResolver.isInsideProject(dir, u)) return false;
            }
            if (o.getFontFamily() != null && o.getFontFamily().startsWith("file:")) {
                if (!AssetResolver.isInsideProjectFont(dir, o.getFontFamily())) return false;
            }
            if (o.getStyleSpans() != null) {
                for (com.fadcam.ui.faditor.model.StyleSpan sp : o.getStyleSpans()) {
                    if (sp.fontFamily != null && sp.fontFamily.startsWith("file:")) {
                        if (!AssetResolver.isInsideProjectFont(dir, sp.fontFamily)) return false;
                    }
                }
            }
        }
        for (com.fadcam.ui.faditor.sprite.SpriteSheet sh : project.getSpriteSheets()) {
            if (!sh.getSheetUri().startsWith(AssetResolver.PROJECT_URI_PREFIX)) {
                Uri u = Uri.parse(sh.getSheetUri());
                if (!AssetResolver.isInsideProject(dir, u)) return false;
            }
            for (String fu : sh.getFrameUris()) {
                if (!fu.startsWith(AssetResolver.PROJECT_URI_PREFIX)) {
                    Uri u = Uri.parse(fu);
                    if (!AssetResolver.isInsideProject(dir, u)) return false;
                }
            }
        }
        return true;
    }

    /** Export project folder to a zip file at {@code destFile} (caller-chosen via picker). */
    @NonNull
    public static ExportResult exportToZip(@NonNull Context ctx,
                                           @NonNull String projectId,
                                           @NonNull File destFile) {
        ExportResult res = new ExportResult();
        res.zipFile = destFile;
        ProjectStorage storage = new ProjectStorage(ctx);
        File projectDir = new File(new File(ctx.getFilesDir(), "faditor/projects"), projectId);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            res.error = "Project not found: " + projectId;
            return res;
        }
        FaditorProject project = storage.load(projectId);
        if (project == null) {
            res.error = "Could not load project: " + projectId;
            return res;
        }
        if (!isConsolidated(ctx, project)) {
            res.error = "Project not consolidated — run Consolidate first";
            return res;
        }
        try {
            Manifest m = new Manifest();
            m.projectId = projectId;
            m.projectName = project.getName();
            m.schemaVersion = project.getSchemaVersion();
            m.exportedAt = System.currentTimeMillis();
            try {
                m.appVersion = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Exception ignored) { m.appVersion = "unknown"; }

            // Count files + bytes
            File[] all = projectDir.listFiles();
            int count = 0; long bytes = 0;
            if (all != null) {
                for (File f : all) {
                    if (f.isFile()) { count++; bytes += f.length(); }
                    else if (f.isDirectory()) {
                        File[] sub = f.listFiles();
                        if (sub != null) for (File sf : sub) if (sf.isFile()) { count++; bytes += sf.length(); }
                        else count++;
                    }
                }
            }
            m.fileCount = count;
            m.totalBytes = bytes;
            res.manifest = m;

            // Zip
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(destFile))) {
                // Manifest first
                ZipEntry me = new ZipEntry(MANIFEST);
                zos.putNextEntry(me);
                JsonObject mj = new JsonObject();
                mj.addProperty("appVersion", m.appVersion);
                mj.addProperty("schemaVersion", m.schemaVersion);
                mj.addProperty("fileCount", m.fileCount);
                mj.addProperty("totalBytes", m.totalBytes);
                mj.addProperty("projectId", m.projectId);
                mj.addProperty("projectName", m.projectName);
                mj.addProperty("exportedAt", m.exportedAt);
                byte[] mb = mj.toString().getBytes("UTF-8");
                zos.write(mb);
                zos.closeEntry();

                addDirToZip(projectDir, projectDir, zos);
            }
            res.ok = true;
            FLog.i(TAG, "Exported " + projectId + " → " + destFile.getAbsolutePath() + " (" + bytes + " bytes, " + count + " files)");
        } catch (Exception e) {
            res.error = e.getMessage();
            FLog.e(TAG, "Export failed", e);
        }
        return res;
    }

    private static void addDirToZip(@NonNull File root, @NonNull File dir, @NonNull ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                addDirToZip(root, f, zos);
            } else {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                // Don't double-add manifest
                if (MANIFEST.equals(rel)) continue;
                ZipEntry entry = new ZipEntry(rel);
                zos.putNextEntry(entry);
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    /** Import a bundle zip (from picker) into a NEW project id and return it. */
    @NonNull
    public static ImportResult importFromZip(@NonNull Context ctx, @NonNull Uri zipUri) {
        ImportResult res = new ImportResult();
        ProjectStorage storage = new ProjectStorage(ctx);
        String newId = UUID.randomUUID().toString();
        File newDir = new File(new File(ctx.getFilesDir(), "faditor/projects"), newId);
        if (!newDir.mkdirs()) {
            res.error = "Could not create project dir";
            return res;
        }
        try (InputStream raw = ctx.getContentResolver().openInputStream(zipUri)) {
            if (raw == null) { res.error = "Could not open zip"; return res; }
            try (ZipInputStream zis = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.contains("..") || name.startsWith("/")) { zis.closeEntry(); continue; }
                    if (entry.isDirectory()) {
                        new File(newDir, name).mkdirs();
                    } else {
                        File out = new File(newDir, name);
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (OutputStream outS = new FileOutputStream(out)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = zis.read(buf)) > 0) outS.write(buf, 0, n);
                        }
                    }
                    zis.closeEntry();
                }
            }
        } catch (Exception e) {
            res.error = e.getMessage();
            FLog.e(TAG, "Import failed", e);
            deleteRecursive(newDir);
            return res;
        }

        // Rewrite project.json id to newId so it doesn't collide with source.
        File projFile = new File(newDir, "project.json");
        if (!projFile.exists()) {
            res.error = "Bundle has no project.json";
            deleteRecursive(newDir);
            return res;
        }
        try {
            String jsonStr = readFile(projFile);
            com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(jsonStr, com.google.gson.JsonObject.class);
            if (obj != null && obj.has("id")) {
                obj.addProperty("id", newId);
                // Update lastModified to now so it sorts as recent
                obj.addProperty("lastModified", System.currentTimeMillis());
                try (FileOutputStream out = new FileOutputStream(projFile)) {
                    out.write(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj).getBytes("UTF-8"));
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not rewrite imported project id", e);
        }

        FaditorProject loaded = storage.load(newId);
        if (loaded == null) {
            res.error = "Imported project could not be loaded";
            deleteRecursive(newDir);
            return res;
        }
        res.ok = true;
        res.newProjectId = newId;
        res.project = loaded;
        FLog.i(TAG, "Imported bundle → " + newId + " (" + loaded.getName() + ")");
        return res;
    }

    /** Import from a plain File zip (for tests). */
    @NonNull
    public static ImportResult importFromFile(@NonNull Context ctx, @NonNull File zipFile) {
        return importFromZip(ctx, Uri.fromFile(zipFile));
    }

    @NonNull
    private static String readFile(@NonNull File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = in.read(buf);
            return new String(buf, 0, read, "UTF-8");
        }
    }

    private static void deleteRecursive(@NonNull File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
