"""STU-F7 (11-28, Note 9): a ping-pong clip from a content:// source (Downloads, the Joy Creator folder) was never
baked reversed ("Cannot bake reverse - input file missing/unreadable"), so every leg played forward, in the preview and
the export. Runs the production ReversedSegmentCache on the host against stand-ins for Android and FFmpegKit: a
content:// source with no file must still bake, reading it through a SAF parameter, a fresh one for each attempt
(FFmpegKit closes a SAF descriptor when its session ends)."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/com/fadcam/ui/faditor/export/ReversedSegmentCache.java"

STUBS = {
    "androidx.annotation.NonNull": "public @interface NonNull {}",
    "androidx.annotation.Nullable": "public @interface Nullable {}",
    "android.content.Context": """public class Context {
        public static java.io.File cache;
        public Context getApplicationContext(){return this;}
        public java.io.File getCacheDir(){return cache;}
    }""",
    "android.net.Uri": """public class Uri {
        private final String s; public Uri(String s){this.s=s;}
        public String getScheme(){int i=s.indexOf(':');return i<0?null:s.substring(0,i);}
        public String getLastPathSegment(){return s.substring(s.lastIndexOf('/')+1);}
        public String getPath(){return s.startsWith("file://")?s.substring(7):null;}
        public String toString(){return s;}
    }""",
    "com.fadcam.FLog": "public class FLog {" + "".join(
        "public static void %s(String a,String b){} public static void %s(String a,String b,Throwable t){}" % (a, a)
        for a in "wied") + "}",
    "com.arthenica.ffmpegkit.ReturnCode": "public class ReturnCode { public static boolean isSuccess(Object o){return Integer.valueOf(0).equals(o);} }",
    "com.arthenica.ffmpegkit.FFmpegSession": """public class FFmpegSession {
        public final Integer code; public FFmpegSession(int c){code=c;}
        public Integer getReturnCode(){return code;} public String getOutput(){return "";}
    }""",
    "com.arthenica.ffmpegkit.FFmpegKitConfig": """public class FFmpegKitConfig {
        public static int next = 0;
        public static String getSafParameterForRead(android.content.Context c, android.net.Uri u){ return "saf:" + (++next) + ".mp4"; }
    }""",
    "com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback": "public interface FFmpegSessionCompleteCallback { void apply(FFmpegSession s); }",
    "com.arthenica.ffmpegkit.LogCallback": "public interface LogCallback { void apply(Log l); }",
    "com.arthenica.ffmpegkit.StatisticsCallback": "public interface StatisticsCallback { void apply(Statistics s); }",
    "com.arthenica.ffmpegkit.Log": "public class Log { public String getMessage(){return \"\";} }",
    "com.arthenica.ffmpegkit.Statistics": "public class Statistics {}",
    "com.arthenica.ffmpegkit.FFmpegKit": """public class FFmpegKit {
        public static java.util.List<String> commands = new java.util.ArrayList<>();
        public static int failFirst = 0;
        public static FFmpegSession execute(String cmd) {
            commands.add(cmd);
            if (commands.size() <= failFirst) return new FFmpegSession(1);
            String out = cmd.substring(cmd.lastIndexOf(" \\"") + 2, cmd.length() - 1);
            try { java.nio.file.Files.write(java.nio.file.Path.of(out), new byte[4096]); } catch (Exception e) { throw new RuntimeException(e); }
            return new FFmpegSession(0);
        }
        public static FFmpegSession executeAsync(String cmd, FFmpegSessionCompleteCallback done, LogCallback log, StatisticsCallback stats) {
            FFmpegSession s = execute(cmd); done.apply(s); return s;
        }
    }""",
}

TEST = r'''
import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.fadcam.ui.faditor.export.ReversedSegmentCache;
import java.io.File;

public class ReverseBakeTest {
    static int failed = 0, ran = 0;
    static void check(boolean ok, String what) { ran++; System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) failed++; }
    public static void main(String[] a) throws Exception {
        Context.cache = new File(a[0]);
        ReversedSegmentCache cache = new ReversedSegmentCache(new Context());
        Uri download = new Uri("content://com.android.providers.downloads.documents/document/msf%3A133588");

        File baked = cache.bakeSync(download, null, 0L, 18_000L);
        check(baked != null && baked.isFile(), "a content:// clip with no file is baked reversed");
        check(!FFmpegKit.commands.isEmpty() && FFmpegKit.commands.get(0).contains("-i \"saf:"), "it is read through a SAF parameter");
        check(cache.isCached(download, 0L, 18_000L), "and the preview and export find it");

        // The first encoder refuses: the next attempt gets a fresh SAF parameter, not the closed one.
        FFmpegKit.commands.clear(); FFmpegKit.failFirst = 1;
        Uri other = new Uri("content://com.android.externalstorage.documents/document/primary%3AJoy%20Creator%2Fclip.mp4");
        File second = cache.bakeSync(other, null, 1_000L, 9_000L);
        check(second != null, "a second encoder bakes it when the first refuses");
        String in1 = FFmpegKit.commands.get(0).replaceAll(".*-i \"(saf:[^\"]+)\".*", "$1");
        String in2 = FFmpegKit.commands.get(1).replaceAll(".*-i \"(saf:[^\"]+)\".*", "$1");
        check(!in1.equals(in2), "each attempt reads through its own SAF parameter (" + in1 + ", " + in2 + ")");

        // A file:// clip that exists is still read as a file.
        FFmpegKit.commands.clear(); FFmpegKit.failFirst = 0;
        File src = new File(a[0], "clip.mp4"); java.nio.file.Files.write(src.toPath(), new byte[10]);
        cache.bakeSync(new Uri("file://" + src.getPath()), src, 0L, 5_000L);
        check(FFmpegKit.commands.get(0).contains("-i \"" + src.getAbsolutePath() + "\""), "a clip on disk is read from its file");
        System.out.println("ReverseBake: " + (ran - failed) + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
'''


def main() -> int:
    work = Path(tempfile.mkdtemp(prefix="reversebake-"))
    try:
        src = work / "src"
        for name, body in STUBS.items():
            pkg, cls = name.rsplit(".", 1)
            d = src / pkg.replace(".", "/")
            d.mkdir(parents=True, exist_ok=True)
            (d / f"{cls}.java").write_text(f"package {pkg};\n{body}\n", encoding="utf-8")
        prod = src / "com/fadcam/ui/faditor/export"
        prod.mkdir(parents=True, exist_ok=True)
        shutil.copy(SRC, prod / SRC.name)
        (work / "ReverseBakeTest.java").write_text(TEST, encoding="utf-8")
        out = work / "out"
        out.mkdir()
        files = [str(p) for p in src.rglob("*.java")] + [str(work / "ReverseBakeTest.java")]
        r = subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-d", str(out)] + files, capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout + r.stderr)
            return 1
        cache = work / "cache"
        cache.mkdir()
        r = subprocess.run(["java", "-cp", str(out), "ReverseBakeTest", str(cache)], capture_output=True, text=True)
        print(r.stdout + r.stderr)
        return r.returncode
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
