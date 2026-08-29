package com.fadcam.ui.faditor.project;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.StyleSpan;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectConsolidator {
    private static final String TAG = "ProjectConsolidator";
    private static final String MEDIA_DIR = "media";
    private static final String FONTS_DIR = "fonts";
    private ProjectConsolidator() {}
    private static class Occurrence {
        enum Kind { VIDEO, AUDIO, IMAGE, FONT, SPRITE }
        Kind kind;
        String originalUri;
        Uri uri;
        String fontPath;
    }
    public static class Estimate {
        public int fileCount = 0;
        public long totalBytes = 0;
        public int alreadyInside = 0;
        public int deduped = 0;
        @NonNull public String humanSize = "";
    }
    public interface ProgressListener {
        void onProgress(int copied, int total, @NonNull String name);
        boolean isCancelled();
    }
    public static class Result {
        public boolean ok = false;
        public boolean cancelled = false;
        public int copied = 0;
        public int skippedInside = 0;
        public int deduped = 0;
        @Nullable public String error;
        @NonNull public Map<String, String> undoMap = new LinkedHashMap<>();
    }
    @NonNull
    public static Estimate estimate(@NonNull Context ctx, @NonNull FaditorProject project) {
        ProjectStorage storage = new ProjectStorage(ctx);
        File projectDir = storage.projectDir(project.getId());
        List<Occurrence> occs = collectOccurrences(project);
        Map<String, Occurrence> unique = dedupeKey(occs, ctx, projectDir);
        Estimate e = new Estimate();
        for (Occurrence o : unique.values()) {
            if (isAlreadyInside(projectDir, o)) { e.alreadyInside++; continue; }
            long sz = sizeOf(ctx, projectDir, o);
            if (sz >= 0) e.totalBytes += sz;
            e.fileCount++;
        }
        if (unique.size() < occs.size()) e.deduped = occs.size() - unique.size();
        e.humanSize = humanBytes(e.totalBytes);
        return e;
    }
    @NonNull
    public static Result consolidate(@NonNull Context ctx, @NonNull FaditorProject project, @Nullable ProgressListener listener) {
        return consolidateProject(ctx, project, listener);
    }
    @NonNull
    public static Result consolidateProject(@NonNull Context ctx, @NonNull FaditorProject project, @Nullable ProgressListener listener) {
        ProjectStorage storage = new ProjectStorage(ctx);
        File projectDir = storage.projectDir(project.getId());
        File mediaDir = new File(projectDir, MEDIA_DIR);
        File fontsDir = new File(projectDir, FONTS_DIR);
        if (!mediaDir.exists()) mediaDir.mkdirs();
        if (!fontsDir.exists()) fontsDir.mkdirs();
        List<Occurrence> occs = collectOccurrences(project);
        Map<String, List<Occurrence>> groups = new LinkedHashMap<>();
        Map<String, String> hashCache = new LinkedHashMap<>();
        for (Occurrence o : occs) {
            String key = dedupKeySingle(o, ctx, projectDir, hashCache);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }
        Map<String, File> hashToDest = new LinkedHashMap<>();
        Map<String, String> hashToOrig = new LinkedHashMap<>();
        Result result = new Result();
        int totalUnique = groups.size();
        int copied = 0;
        for (Map.Entry<String, List<Occurrence>> entry : groups.entrySet()) {
            if (listener != null && listener.isCancelled()) {
                result.cancelled = true; result.ok = false; result.error = "Cancelled";
                return result;
            }
            List<Occurrence> grp = entry.getValue();
            Occurrence first = grp.get(0);
            if (isAlreadyInside(projectDir, first)) { result.skippedInside++; continue; }
            String hashKey = entry.getKey();
            if (hashToDest.containsKey(hashKey)) { result.deduped++; continue; }
            String originalName = filenameOf(first);
            if (originalName == null || originalName.isEmpty()) originalName = "asset";
            originalName = AssetResolver.sanitize(originalName);
            File targetDir = (first.kind == Occurrence.Kind.FONT) ? fontsDir : mediaDir;
            File destFile = uniqueDest(targetDir, originalName, hashKey, hashToDest);
            File tmp = new File(destFile.getParentFile(), destFile.getName() + ".part");
            boolean copyOk = copyToTemp(ctx, projectDir, first, tmp);
            if (!copyOk) { if (tmp.exists()) tmp.delete(); continue; }
            if (tmp.length() == 0) { tmp.delete(); continue; }
            if (destFile.exists()) destFile.delete();
            boolean renamed = tmp.renameTo(destFile);
            if (!renamed) { try { copyFile(tmp, destFile); tmp.delete(); } catch (Exception e) { tmp.delete(); continue; } }
            copied++;
            hashToDest.put(hashKey, destFile);
            hashToOrig.put(hashKey, first.originalUri);
            if (listener != null) listener.onProgress(copied, totalUnique, destFile.getName());
        }
        result.deduped = occs.size() - groups.size();
        Map<String, File> origToDest = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : hashToOrig.entrySet()) {
            File dest = hashToDest.get(e.getKey());
            if (dest != null) origToDest.put(e.getValue(), dest);
        }
        for (Occurrence o : occs) {
            String hk = dedupKeySingle(o, ctx, projectDir, new LinkedHashMap<>());
            File dest = hashToDest.get(hk);
            if (dest != null) origToDest.put(o.originalUri, dest);
        }
        for (Clip c : project.getTimeline().getClips()) {
            File dest = origToDest.get(c.getSourceUri().toString());
            if (dest != null) { result.undoMap.put(c.getSourceUri().toString(), Uri.fromFile(dest).toString()); c.repointConsolidatedSource(Uri.fromFile(dest)); }
        }
        for (Clip c : project.getTimeline().getOverlayClips()) {
            File dest = origToDest.get(c.getSourceUri().toString());
            if (dest != null) { result.undoMap.put(c.getSourceUri().toString(), Uri.fromFile(dest).toString()); c.repointConsolidatedSource(Uri.fromFile(dest)); }
        }
        for (AudioClip ac : project.getTimeline().getAudioClips()) {
            File dest = origToDest.get(ac.getSourceUri().toString());
            if (dest != null) { result.undoMap.put(ac.getSourceUri().toString(), Uri.fromFile(dest).toString()); ac.setSourceUri(Uri.fromFile(dest)); }
        }
        for (TextOverlayItem oItem : project.getTimeline().getTextOverlays()) {
            if (oItem.getImageUri() != null) {
                File dest = origToDest.get(oItem.getImageUri());
                if (dest != null) { result.undoMap.put(oItem.getImageUri(), Uri.fromFile(dest).toString()); oItem.setImageUri(Uri.fromFile(dest).toString()); }
            }
            if (oItem.getFontFamily() != null && oItem.getFontFamily().startsWith("file:")) {
                File dest = origToDest.get(oItem.getFontFamily());
                if (dest != null) { result.undoMap.put(oItem.getFontFamily(), "file:" + dest.getAbsolutePath()); oItem.setFontFamily("file:" + dest.getAbsolutePath()); }
            }
            if (oItem.getStyleSpans() != null) {
                for (StyleSpan sp : oItem.getStyleSpans()) {
                    if (sp.fontFamily != null && sp.fontFamily.startsWith("file:")) {
                        File dest = origToDest.get(sp.fontFamily);
                        if (dest != null) { result.undoMap.put(sp.fontFamily, "file:" + dest.getAbsolutePath()); sp.fontFamily = "file:" + dest.getAbsolutePath(); }
                    }
                }
            }
        }
        for (SpriteSheet sh : project.getSpriteSheets()) {
            if (sh.getSheetUri() != null) {
                File dest = origToDest.get(sh.getSheetUri());
                if (dest != null) { result.undoMap.put(sh.getSheetUri(), Uri.fromFile(dest).toString()); sh.setSheetUri(Uri.fromFile(dest).toString()); }
            }
            List<String> fus = sh.getFrameUris();
            for (int i = 0; i < fus.size(); i++) {
                String cur = fus.get(i);
                File dest = origToDest.get(cur);
                if (dest != null) { result.undoMap.put(cur, Uri.fromFile(dest).toString()); fus.set(i, Uri.fromFile(dest).toString()); }
            }
        }
        if (result.undoMap.isEmpty() && copied == 0 && result.skippedInside == groups.size()) {
            result.ok = true; result.copied = 0; return result;
        }
        boolean saved = storage.save(project);
        if (!saved) { result.error = "Save failed"; result.ok = false; return result; }
        writeUndoSidecar(projectDir, result.undoMap);
        result.copied = copied; result.ok = !result.cancelled;
        return result;
    }
    public static boolean revert(@NonNull Context ctx, @NonNull String projectId) {
        ProjectStorage storage = new ProjectStorage(ctx);
        FaditorProject project = storage.load(projectId);
        if (project == null) return false;
        File dir = storage.projectDir(projectId);
        File sidecar = new File(dir, ".consolidate_undo.json");
        if (!sidecar.exists()) return false;
        try {
            String json = readFile(sidecar);
            com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);
            Map<String, String> destToOrig = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) destToOrig.put(e.getValue().getAsString(), e.getKey());
            for (Clip c : project.getTimeline().getClips()) {
                String cur = c.getSourceUri().toString();
                String resolved = cur;
                if (cur.startsWith(AssetResolver.PROJECT_URI_PREFIX)) resolved = Uri.fromFile(new File(dir, cur.substring(AssetResolver.PROJECT_URI_PREFIX.length()))).toString();
                if (destToOrig.containsKey(resolved) || destToOrig.containsKey(cur)) { String orig = destToOrig.getOrDefault(resolved, destToOrig.get(cur)); c.repointConsolidatedSource(Uri.parse(orig)); }
            }
            for (Clip c : project.getTimeline().getOverlayClips()) {
                String cur = c.getSourceUri().toString();
                String resolved = cur;
                if (cur.startsWith(AssetResolver.PROJECT_URI_PREFIX)) resolved = Uri.fromFile(new File(dir, cur.substring(AssetResolver.PROJECT_URI_PREFIX.length()))).toString();
                if (destToOrig.containsKey(resolved) || destToOrig.containsKey(cur)) { String orig = destToOrig.getOrDefault(resolved, destToOrig.get(cur)); c.repointConsolidatedSource(Uri.parse(orig)); }
            }
            for (AudioClip ac : project.getTimeline().getAudioClips()) {
                String cur = ac.getSourceUri().toString();
                String resolved = cur;
                if (cur.startsWith(AssetResolver.PROJECT_URI_PREFIX)) resolved = Uri.fromFile(new File(dir, cur.substring(AssetResolver.PROJECT_URI_PREFIX.length()))).toString();
                if (destToOrig.containsKey(resolved) || destToOrig.containsKey(cur)) { String orig = destToOrig.getOrDefault(resolved, destToOrig.get(cur)); ac.setSourceUri(Uri.parse(orig)); }
            }
            for (TextOverlayItem o : project.getTimeline().getTextOverlays()) {
                if (o.getImageUri() != null) {
                    String cur = o.getImageUri();
                    String resolved = cur;
                    if (cur.startsWith(AssetResolver.PROJECT_URI_PREFIX)) resolved = Uri.fromFile(new File(dir, cur.substring(AssetResolver.PROJECT_URI_PREFIX.length()))).toString();
                    if (destToOrig.containsKey(resolved) || destToOrig.containsKey(cur)) { String orig = destToOrig.getOrDefault(resolved, destToOrig.get(cur)); o.setImageUri(orig); }
                }
                if (o.getFontFamily() != null && destToOrig.containsKey(o.getFontFamily())) o.setFontFamily(destToOrig.get(o.getFontFamily()));
                else if (o.getFontFamily() != null && o.getFontFamily().startsWith("file:")) {
                    String cur = o.getFontFamily();
                    String resolved = Uri.fromFile(new File(dir, cur.substring(5).replaceFirst("^//",""))).toString();
                    // not needed for MVP revert
                }
            }
            for (SpriteSheet sh : project.getSpriteSheets()) {
                String cur = sh.getSheetUri();
                if (cur != null) {
                    String resolved = cur;
                    if (cur.startsWith(AssetResolver.PROJECT_URI_PREFIX)) resolved = Uri.fromFile(new File(dir, cur.substring(AssetResolver.PROJECT_URI_PREFIX.length()))).toString();
                    if (destToOrig.containsKey(resolved) || destToOrig.containsKey(cur)) { String orig = destToOrig.getOrDefault(resolved, destToOrig.get(cur)); sh.setSheetUri(orig); }
                }
            }
            boolean saved = storage.save(project);
            if (saved) sidecar.delete();
            return saved;
        } catch (Exception e) { FLog.e(TAG, "Revert failed", e); return false; }
    }
    private static List<Occurrence> collectOccurrences(@NonNull FaditorProject project) {
        List<Occurrence> out = new ArrayList<>();
        for (Clip c : project.getTimeline().getClips()) { Occurrence o = new Occurrence(); o.kind = c.isImageClip() ? Occurrence.Kind.IMAGE : Occurrence.Kind.VIDEO; o.originalUri = c.getSourceUri().toString(); o.uri = c.getSourceUri(); out.add(o); }
        for (Clip c : project.getTimeline().getOverlayClips()) { Occurrence o = new Occurrence(); o.kind = c.isImageClip() ? Occurrence.Kind.IMAGE : Occurrence.Kind.VIDEO; o.originalUri = c.getSourceUri().toString(); o.uri = c.getSourceUri(); out.add(o); }
        for (AudioClip ac : project.getTimeline().getAudioClips()) { Occurrence o = new Occurrence(); o.kind = Occurrence.Kind.AUDIO; o.originalUri = ac.getSourceUri().toString(); o.uri = ac.getSourceUri(); out.add(o); }
        for (TextOverlayItem oItem : project.getTimeline().getTextOverlays()) {
            if (oItem.getImageUri() != null) { Occurrence o = new Occurrence(); o.kind = Occurrence.Kind.IMAGE; o.originalUri = oItem.getImageUri(); o.uri = Uri.parse(oItem.getImageUri()); out.add(o); }
            if (oItem.getFontFamily() != null && oItem.getFontFamily().startsWith("file:")) { Occurrence o = new Occurrence(); o.kind = Occurrence.Kind.FONT; o.originalUri = oItem.getFontFamily(); o.fontPath = oItem.getFontFamily().substring(5); if (o.fontPath.startsWith("//")) o.fontPath = o.fontPath.substring(2); o.uri = Uri.parse("file://" + o.fontPath); out.add(o); }
            if (oItem.getStyleSpans() != null) for (StyleSpan sp : oItem.getStyleSpans()) if (sp.fontFamily != null && sp.fontFamily.startsWith("file:")) { Occurrence occ = new Occurrence(); occ.kind = Occurrence.Kind.FONT; occ.originalUri = sp.fontFamily; occ.fontPath = sp.fontFamily.substring(5); if (occ.fontPath.startsWith("//")) occ.fontPath = occ.fontPath.substring(2); occ.uri = Uri.parse("file://" + occ.fontPath); out.add(occ); }
        }
        for (SpriteSheet sh : project.getSpriteSheets()) {
            if (sh.getSheetUri() != null) { Occurrence o = new Occurrence(); o.kind = Occurrence.Kind.SPRITE; o.originalUri = sh.getSheetUri(); o.uri = Uri.parse(sh.getSheetUri()); out.add(o); }
            for (String fu : sh.getFrameUris()) { Occurrence o = new Occurrence(); o.kind = Occurrence.Kind.SPRITE; o.originalUri = fu; o.uri = Uri.parse(fu); out.add(o); }
        }
        return out;
    }
    private static boolean isAlreadyInside(@NonNull File projectDir, @NonNull Occurrence o) {
        if (o.kind == Occurrence.Kind.FONT) {
            if (o.fontPath == null) return false;
            String base = projectDir.getAbsolutePath(); return o.fontPath.equals(base) || o.fontPath.startsWith(base + File.separator);
        }
        if (o.uri == null) return false;
        String s = o.originalUri; if (s.startsWith(AssetResolver.PROJECT_URI_PREFIX)) return true;
        return AssetResolver.isInsideProject(projectDir, o.uri);
    }
    private static long sizeOf(@NonNull Context ctx, @NonNull File projectDir, @NonNull Occurrence o) {
        if (o.kind == Occurrence.Kind.FONT) { if (o.fontPath == null) return -1; File f = new File(o.fontPath); return f.exists() ? f.length() : -1; }
        if (o.uri == null) return -1; File f = AssetResolver.toFile(projectDir, o.uri); if (f != null && f.exists()) return f.length(); long sz = AssetResolver.size(ctx, projectDir, o.uri); return sz >= 0 ? sz : 0;
    }
    @Nullable private static String filenameOf(@NonNull Occurrence o) {
        if (o.kind == Occurrence.Kind.FONT) { if (o.fontPath == null) return null; int slash = o.fontPath.lastIndexOf('/'); return slash >= 0 ? o.fontPath.substring(slash+1) : o.fontPath; }
        if (o.uri != null) return AssetResolver.filename(o.uri); return null;
    }
    private static String dedupKeySingle(@NonNull Occurrence o, @NonNull Context ctx, @NonNull File projectDir, @NonNull Map<String, String> hashCache) {
        String cached = hashCache.get(o.originalUri); if (cached != null) return cached;
        String h = null;
        if (o.kind == Occurrence.Kind.FONT) { if (o.fontPath != null) h = AssetResolver.sha256(new File(o.fontPath)); }
        else if (o.uri != null) { File f = AssetResolver.toFile(projectDir, o.uri); if (f != null && f.exists()) h = AssetResolver.sha256(f); else h = AssetResolver.sha256(ctx, o.uri); }
        String key = (h != null) ? ("hash:" + h) : ("uri:" + o.originalUri);
        hashCache.put(o.originalUri, key); return key;
    }
    private static Map<String, Occurrence> dedupeKey(@NonNull List<Occurrence> occs, @NonNull Context ctx, @NonNull File projectDir) {
        Map<String, Occurrence> out = new LinkedHashMap<>(); Map<String, String> cache = new LinkedHashMap<>();
        for (Occurrence o : occs) { String k = dedupKeySingle(o, ctx, projectDir, cache); if (!out.containsKey(k)) out.put(k, o); } return out;
    }
    private static File uniqueDest(@NonNull File dir, @NonNull String name, @NonNull String hashKey, @NonNull Map<String, File> existing) {
        File base = new File(dir, name);
        if (!base.exists() && !existing.containsValue(base)) return base;
        for (File f : existing.values()) if (f.getName().equals(name)) return f;
        int dot = name.lastIndexOf('.'); String stem = dot>=0? name.substring(0,dot):name; String ext = dot>=0? name.substring(dot):"";
        int idx=1; while(true){ String candName = stem + "_" + idx + ext; File cand = new File(dir, candName); if (!cand.exists() && !existing.containsValue(cand)) return cand; idx++; if(idx>999) return new File(dir, hashKey.substring(5,13)+ext); }
    }
    private static boolean copyToTemp(@NonNull Context ctx, @NonNull File projectDir, @NonNull Occurrence o, @NonNull File tmp) {
        try {
            InputStream in = null;
            if (o.kind == Occurrence.Kind.FONT) { if (o.fontPath==null) return false; File src=new File(o.fontPath); if(!src.exists()) return false; in=new FileInputStream(src); }
            else { if(o.uri==null) return false; in=AssetResolver.open(ctx, projectDir, o.uri); if(in==null) return false; }
            try (InputStream cin=in; OutputStream out=new FileOutputStream(tmp)) { byte[] buf=new byte[64*1024]; int n; while((n=cin.read(buf))>0) out.write(buf,0,n); }
            return tmp.exists() && tmp.length()>0;
        } catch (Exception e) { return false; }
    }
    private static void copyFile(@NonNull File src, @NonNull File dst) throws Exception { try(InputStream in=new FileInputStream(src); OutputStream out=new FileOutputStream(dst)){ byte[] buf=new byte[64*1024]; int n; while((n=in.read(buf))>0) out.write(buf,0,n);} }
    private static void writeUndoSidecar(@NonNull File projectDir, @NonNull Map<String,String> map){ if(map.isEmpty())return; File sidecar=new File(projectDir,".consolidate_undo.json"); try{ com.google.gson.JsonObject obj=new com.google.gson.JsonObject(); for(Map.Entry<String,String> e: map.entrySet()) obj.addProperty(e.getKey(), e.getValue()); try(FileOutputStream out=new FileOutputStream(sidecar)){ out.write(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj).getBytes("UTF-8")); } } catch(Exception e){} }
    private static String readFile(@NonNull File f) throws Exception { try(FileInputStream in=new FileInputStream(f)){ byte[] buf=new byte[(int)f.length()]; int r=in.read(buf); return new String(buf,0,r,"UTF-8"); } }
    private static String humanBytes(long bytes){ if(bytes<1024) return bytes+" B"; if(bytes<1024*1024) return String.format("%.1f KB", bytes/1024f); if(bytes<1024*1024*1024) return String.format("%.1f MB", bytes/(1024f*1024f)); return String.format("%.2f GB", bytes/(1024f*1024f*1024f)); }
}
