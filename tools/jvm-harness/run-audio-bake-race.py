"""Run production BakedAudioCache on the host with file URIs and real FFmpeg encoding."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "app/src/main/java/com/fadcam/ui/faditor/audio/BakedAudioCache.java"
TOOLS = Path(__file__).resolve().parent
stubs = {
    "androidx.annotation.NonNull": "public @interface NonNull {}",
    "androidx.annotation.Nullable": "public @interface Nullable {}",
    "android.content.Context": "public class Context { public Context getApplicationContext(){return this;} }",
    "android.net.Uri": """public class Uri {
        private final String path; public Uri(String path){this.path=path;}
        public String getScheme(){return "file";} public String getPath(){return path;}
        public String toString(){return new java.io.File(path).toURI().toString();}
    }""",
    "android.media.MediaMetadataRetriever": """public class MediaMetadataRetriever {
        public static final int METADATA_KEY_DURATION=9,METADATA_KEY_HAS_AUDIO=16;
        private String path;
        public void setDataSource(String s){path=s;}
        public String extractMetadata(int k){try {
            Process p=new ProcessBuilder("ffprobe","-v","error","-select_streams","a:0","-show_entries",
                k==9?"format=duration":"stream=codec_type","-of","default=nw=1:nk=1",path).start();
            String result=new String(p.getInputStream().readAllBytes()).trim();
            if(p.waitFor()!=0)return null;
            return k==9?Long.toString(Math.round(Double.parseDouble(result)*1000)):("audio".equals(result)?"yes":null);
        }catch(Exception e){throw new IllegalStateException(e);}}
        public void release(){}
    }""",
    "com.fadcam.FLog": "public class FLog {" + "".join("public static void %s(String a,String b){}" % a for a in "wied") + "}",
    "com.arthenica.ffmpegkit.FFmpegKitConfig": "public class FFmpegKitConfig { public static String getSafParameterForRead(android.content.Context c,android.net.Uri u){return u.getPath();} }",
    "com.arthenica.ffmpegkit.ReturnCode": "public class ReturnCode { public static boolean isSuccess(int i){return i==0;} }",
    "com.arthenica.ffmpegkit.Statistics": "public class Statistics { public long getTime(){return 0;} }",
    "com.arthenica.ffmpegkit.Log": "public class Log { public String getMessage(){return \"\";} }",
    "com.arthenica.ffmpegkit.FFmpegSession": """public class FFmpegSession {
        int code; String log; public int getReturnCode(){return code;} public String getAllLogsAsString(){return log;}
    }""",
    "com.arthenica.ffmpegkit.FFmpegKit": r"""import java.util.*;
    import java.util.concurrent.*; import java.util.concurrent.atomic.AtomicInteger;
    import java.util.function.Consumer; import java.util.regex.*;
    public class FFmpegKit {
        public static final AtomicInteger starts=new AtomicInteger();
        public static final CountDownLatch gate=new CountDownLatch(1);
        public static FFmpegSession execute(String cmd){
            starts.incrementAndGet(); FFmpegSession s=new FFmpegSession();
            try {gate.await(); List<String> args=new ArrayList<>(Arrays.asList("ffmpeg","-v","error"));
                Matcher tokens=Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(cmd);
                while(tokens.find())args.add(tokens.group(1)==null?tokens.group(2):tokens.group(1));
                Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
                s.log=new String(p.getInputStream().readAllBytes()); s.code=p.waitFor();
            }catch(Exception e){s.code=1;s.log=e.toString();}
            return s;
        }
        public static void executeAsync(String c,Consumer<FFmpegSession> done, Consumer<Log> log,Consumer<Statistics> stats){
            new Thread(()->done.accept(execute(c))).start();
        }
    }""",
}

with tempfile.TemporaryDirectory() as tmp:
    temp = Path(tmp)
    files = []
    for name, body in stubs.items():
        f = temp / (name.replace(".", "/") + ".java")
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf8")
        files.append(str(f))
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", tmp, *files, str(CACHE),
                    str(TOOLS / "AudioBakeRaceRegression.java"), str(TOOLS / "AudioBakeWaitersTest.java")],check=True)
    subprocess.run(["java","-cp",tmp,"AudioBakeRaceRegression"],check=True)
    original = temp / "original.wav"
    subprocess.run(["ffmpeg","-v","error","-f","lavfi","-i","sine=frequency=730:duration=1",str(original)],check=True)
    shutil.copyfile(original, temp / "second.wav")
    subprocess.run(["java","-cp",tmp,"AudioBakeWaitersTest",tmp],check=True,timeout=30)
    outputs = list((temp / "audio_baked").glob("*.m4a"))
    assert len(outputs) == 2
    for f in [original, *outputs]:
        subprocess.run(["ffmpeg","-v","error","-i",str(f),"-f","null","-"],check=True)
    print("PASS: original and both published AAC outputs decode")
