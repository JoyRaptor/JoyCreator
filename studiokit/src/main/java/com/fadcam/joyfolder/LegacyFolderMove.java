package com.fadcam.joyfolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Moves FadCam's old shared folders (Download/FadCam, Pictures/FadCam/assets, Documents/FadRec/Projects) to their
 * Joy Creator names, without ever losing a file (SHELL-44, FREE-2 step 3). Plain Java so it is tested off the phone.
 *
 * <p>The rules (the orchestrator's conditions, 2026-11-14):
 * <ul>
 * <li><b>Dry run first.</b> The first run that finds anything to move only writes the plan ({@link #plan}).
 * <li><b>Two phases.</b> Phase 1 (a run) copies each file to {@code <name>.partial}, checks size and SHA-256 against
 * the original, renames the copy into place, points projects at it ({@link Hooks#repoint}) and logs {@code copied}.
 * Phase 2 (a LATER run) removes an original only when its copy is still whole and no saved project names the
 * original any more. A crash at any point leaves the original, the copy, or both: never neither.
 * <li><b>A move log</b>, one readable line per file (state, old path, new path, SHA-256, time, note), kept by the
 * caller in the Joy Creator folder ({@link LogStore}); it is rewritten after every change.
 * <li><b>Any failed check keeps the old file</b> and logs {@code kept} with a plain reason; nothing is thrown at the
 * person.
 * </ul>
 */
public final class LegacyFolderMove {

    public static final String PARTIAL = ".partial";

    /** One old folder and its new name. Each direct child (a file or a whole folder) moves as one unit. */
    public static final class Root {
        public final File legacy;
        public final File fresh;
        /** Folders become "<name>_2" on a clash (screen-drawing projects are looked up by a name without spaces). */
        public final boolean underscoreClash;

        public Root(File legacy, File fresh, boolean underscoreClash) {
            this.legacy = legacy;
            this.fresh = fresh;
            this.underscoreClash = underscoreClash;
        }
    }

    /** What the app does around a move: the parts that need Android. */
    public interface Hooks {
        /** Point every project that names {@code from} at {@code to}, and remember it for later loads. */
        void repoint(File from, File to);

        /** True while any saved project still names this original. */
        boolean stillReferenced(File original);

        /** Remove the original (the phone side also removes it from Android's media list). */
        boolean delete(File original);
    }

    /** Where the move log lives (the Joy Creator folder on the phone, a plain file in tests). */
    public interface LogStore {
        List<String> read() throws IOException;

        void write(List<String> lines) throws IOException;
    }

    /** One line of the log. */
    public static final class Line {
        public String state;   // planned, copied, removed, kept
        public String oldPath;
        public String newPath;
        public String sha;
        public long time;
        public String note;

        Line(String state, String oldPath, String newPath, String sha, long time, String note) {
            this.state = state;
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.sha = sha == null ? "" : sha;
            this.time = time;
            this.note = note == null ? "" : note;
        }

        String format() {
            return state + "\t" + oldPath + "\t" + newPath + "\t" + sha + "\t" + iso(time) + "\t" + note;
        }

        static Line parse(String s) {
            if (s == null || s.isEmpty() || s.startsWith("#")) return null;
            String[] p = s.split("\t", -1);
            if (p.length < 5) return null;
            return new Line(p[0], p[1], p[2], p[3], parseIso(p[4]), p.length > 5 ? p[5] : "");
        }
    }

    /** What a run did, for the app's own log line and the tests. */
    public static final class Result {
        public int copied, removed, kept, waiting;
        /** Copies whose originals still wait for a later start: the caller keeps coming back while this is above 0. */
        public int stillCopied;
        public final List<String> planned = new ArrayList<>();
    }

    static final String[] HEADER = {
        "# Joy Creator moved your files out of the old FadCam folders. One line per file:",
        "# state, where it was, where it is now, its fingerprint (SHA-256), when, and a note.",
        "# copied = the new copy is checked and in use; the old file is removed on a later start.",
        "# removed = the old file is gone; the copy is the file.   kept = something did not check out, so the",
        "# old file was left exactly where it was.   planned = a folder that is about to be copied.",
    };

    private LegacyFolderMove() { }

    // ── the dry run ───────────────────────────────────────────────────────────────────────────────

    /** Lines saying what a real run would copy, from where to where. Touches nothing. */
    public static List<String> plan(List<Root> roots) {
        List<String> out = new ArrayList<>();
        for (Root r : roots) {
            File[] units = r.legacy.listFiles();
            if (units == null) continue;
            Arrays.sort(units);
            for (File unit : units) {
                if (unit.getName().endsWith(PARTIAL)) continue;
                File dest = new File(r.fresh, unit.getName());
                for (File f : filesUnder(unit)) {
                    out.add("would-move\t" + f.getPath() + "\t" + new File(dest, relative(unit, f)).getPath());
                }
            }
        }
        return out;
    }

    /** True when any old folder still holds a file. */
    public static boolean anythingToMove(List<Root> roots) {
        for (Root r : roots) if (!filesUnder(r.legacy).isEmpty()) return true;
        return false;
    }

    // ── the real run ──────────────────────────────────────────────────────────────────────────────

    /**
     * One start's work: phase 2 for files copied by an EARLIER start ({@code time < runStart}), then phase 1 for
     * everything not moved yet. Safe to call again at any time, and after being killed at any point.
     */
    public static Result run(List<Root> roots, LogStore store, Hooks hooks, long runStart, List<File> pruneAlso)
            throws IOException {
        Result res = new Result();
        Map<String, Line> latest = new LinkedHashMap<>();
        for (String s : store.read()) {
            Line l = Line.parse(s);
            if (l != null) latest.put(key(l), l);
        }
        Log log = new Log(store, latest);

        // Phase 2: remove originals whose checked copies came from an earlier start.
        for (Line l : new ArrayList<>(latest.values())) {
            if (!"copied".equals(l.state)) continue;
            if (l.time >= runStart) continue;
            File from = new File(l.oldPath);
            File to = new File(l.newPath);
            if (!from.exists()) {
                log.set(l, "removed", "the old file was already gone", runStart);
                res.removed++;
                continue;
            }
            if (!to.isFile() || !l.sha.equals(sha256(to))) {
                log.set(l, "kept", "the new copy is missing or changed, so the old file stays", runStart);
                res.kept++;
                continue;
            }
            if (hooks.stillReferenced(from)) {
                res.waiting++;   // a project still names it: try again on a later start
                continue;
            }
            if (hooks.delete(from) && !from.exists()) {
                log.set(l, "removed", "", runStart);
                res.removed++;
            } else {
                log.set(l, "kept", "the old file could not be removed", runStart);
                res.kept++;
            }
        }
        for (Root r : roots) pruneEmpty(r.legacy);
        for (File f : pruneAlso) pruneEmpty(f);

        // Phase 1: copy and check everything not moved yet.
        for (Root r : roots) {
            removePartials(r.fresh);
            File[] units = r.legacy.listFiles();
            if (units == null) continue;
            Arrays.sort(units);
            for (File unit : units) {
                if (unit.getName().endsWith(PARTIAL)) continue;
                List<File> files = filesUnder(unit);
                if (files.isEmpty()) continue;
                boolean pending = false;
                for (File f : files) if (!latest.containsKey(f.getPath())) pending = true;
                if (!pending) continue;
                File dest = destFor(r, unit, latest, log, runStart);
                for (File f : files) {
                    if (latest.containsKey(f.getPath())) continue;   // copied, removed or kept already
                    copyOne(f, new File(dest, relative(unit, f)), unit.isFile(), hooks, log, runStart, res);
                }
            }
        }
        for (Line l : latest.values()) if ("copied".equals(l.state)) res.stillCopied++;
        return res;
    }

    private static void copyOne(File from, File wanted, boolean renameOnClash, Hooks hooks, Log log, long now,
                                Result res) throws IOException {
        File to = wanted;
        File partial = null;
        try {
            String sha = sha256(from);
            if (to.exists()) {
                if (to.isFile() && to.length() == from.length() && sha.equals(sha256(to))) {
                    // Copied before a crash, not yet logged: the copy is the same file.
                    hooks.repoint(from, to);
                    log.add(new Line("copied", from.getPath(), to.getPath(), sha, now, "copy found already in place"));
                    res.copied++;
                    return;
                }
                if (!renameOnClash) {
                    log.add(new Line("kept", from.getPath(), to.getPath(), sha, now,
                            "a different file already has that name, so the old file stays"));
                    res.kept++;
                    return;
                }
                to = freeFileName(to);
            }
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("could not make the folder");
            }
            partial = new File(to.getPath() + PARTIAL);
            copy(from, partial);
            if (partial.length() != from.length() || !sha.equals(sha256(partial))) {
                throw new IOException("the copy did not match the original");
            }
            if (!partial.renameTo(to)) throw new IOException("the copy could not be renamed into place");
            partial = null;
            hooks.repoint(from, to);
            log.add(new Line("copied", from.getPath(), to.getPath(), sha, now, ""));
            res.copied++;
        } catch (IOException | RuntimeException e) {
            if (partial != null) //noinspection ResultOfMethodCallIgnored
                partial.delete();
            log.add(new Line("kept", from.getPath(), to.getPath(), "", now,
                    "the copy could not be made or checked, so the old file stays"));
            res.kept++;
        }
    }

    /** The new folder (or file) a unit goes to: the one a crashed run already planned, else a free name. */
    private static File destFor(Root r, File unit, Map<String, Line> latest, Log log, long now) throws IOException {
        if (unit.isFile()) return r.fresh;   // a single file goes straight into the new folder
        Line planned = latest.get("planned:" + unit.getPath());
        if (planned != null) return new File(planned.newPath);
        File dest = new File(r.fresh, unit.getName());
        int n = 2;
        while (dest.exists()) {
            dest = new File(r.fresh, unit.getName() + (r.underscoreClash ? "_" : " ") + n++);
        }
        log.add(new Line("planned", unit.getPath(), dest.getPath(), "", now, "folder"));
        return dest;
    }

    // ── the log ───────────────────────────────────────────────────────────────────────────────────

    private static final class Log {
        final LogStore store;
        final Map<String, Line> latest;

        Log(LogStore store, Map<String, Line> latest) {
            this.store = store;
            this.latest = latest;
        }

        void add(Line l) throws IOException {
            latest.put(key(l), l);
            save();
        }

        void set(Line l, String state, String note, long now) throws IOException {
            l.state = state;
            l.note = note;
            l.time = now;
            save();
        }

        void save() throws IOException {
            List<String> out = new ArrayList<>(Arrays.asList(HEADER));
            for (Line l : latest.values()) out.add(l.format());
            store.write(out);
        }
    }

    private static String key(Line l) {
        return "planned".equals(l.state) ? "planned:" + l.oldPath : l.oldPath;
    }

    // ── files ─────────────────────────────────────────────────────────────────────────────────────

    static List<File> filesUnder(File f) {
        List<File> out = new ArrayList<>();
        if (f.isFile()) {
            if (!f.getName().endsWith(PARTIAL)) out.add(f);
            return out;
        }
        File[] kids = f.listFiles();
        if (kids == null) return out;
        Arrays.sort(kids);
        for (File k : kids) out.addAll(filesUnder(k));
        return out;
    }

    private static String relative(File unit, File f) {
        if (unit.isFile()) return unit.getName();
        return f.getPath().substring(unit.getPath().length() + 1);
    }

    private static File freeFileName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int n = 2;
        File c;
        do {
            c = new File(f.getParentFile(), base + " " + n++ + ext);
        } while (c.exists());
        return c;
    }

    private static void removePartials(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) removePartials(k);
            else if (k.getName().endsWith(PARTIAL)) //noinspection ResultOfMethodCallIgnored
                k.delete();
        }
    }

    /** Removes empty folders inside {@code dir}, then {@code dir} itself if empty. Never a file. */
    static void pruneEmpty(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) if (k.isDirectory()) pruneEmpty(k);
        String[] left = dir.list();
        if (left != null && left.length == 0) //noinspection ResultOfMethodCallIgnored
            dir.delete();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.getFD().sync();
        }
    }

    public static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    private static long parseIso(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** A {@link LogStore} on a plain file (tests, and the phone's fallback). */
    public static LogStore fileStore(File f) {
        return new LogStore() {
            @Override public List<String> read() throws IOException {
                if (!f.isFile()) return new ArrayList<>();
                return new ArrayList<>(java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override public void write(List<String> lines) throws IOException {
                File tmp = new File(f.getPath() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write((String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(f)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    if (!tmp.renameTo(f)) throw new IOException("could not save the move log");
                }
            }
        };
    }
}
