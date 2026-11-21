"""Exercise StudioVoiceRender with file inputs and desktop FFmpeg. Optional argument: original rain recording."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import wave
import math
import struct

root = Path(__file__).resolve().parents[2]
app = root / "app/src/main/java"
audio = app / "com/fadcam/ui/faditor/audio"
stubs = {
    "android.content.Context": """
        public Context getApplicationContext(){return this;}
        public android.content.ContentResolver getContentResolver(){return null;}
        public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}
        public java.io.File getCacheDir(){return new java.io.File("cache");}
        public java.io.File getNoBackupFilesDir(){return new java.io.File("model");}
    """,
    "android.content.ContentResolver": """public android.database.Cursor query(android.net.Uri u,String[] cols,
        String selection,String[] args,String order){return null;}""",
    "android.content.res.AssetManager": """public java.io.InputStream open(String path) throws java.io.IOException {
        return java.nio.file.Files.newInputStream(java.nio.file.Paths.get(System.getProperty("assets"),path));}""",
    "android.net.Uri": """private final java.io.File file; public Uri(java.io.File f){file=f;}
        public String getScheme(){return "file";} public String getPath(){return file.getPath();}
        public String toString(){return file.toURI().toString();}""",
    "android.os.CancellationSignal": """public void throwIfCanceled(){} public boolean isCanceled(){return false;}
        public void setOnCancelListener(Runnable r){}""",
    "android.database.Cursor": """public boolean moveToFirst(){return false;} public boolean isNull(int i){return true;}
        public long getLong(int i){return 0;} public void close(){}""",
    "android.provider.DocumentsContract": """public static class Document {public static final String COLUMN_LAST_MODIFIED="modified";}""",
    "android.provider.OpenableColumns": """public static final String SIZE="size";""",
    "com.fadcam.FLog": """public static void w(String s,String m){System.err.println(m);}""",
    "com.fadcam.ui.faditor.audio.BakedAudioCache": """public static final String BAKE_DIR="audio_baked";""",
    "com.arthenica.ffmpegkit.ReturnCode": """public static boolean isSuccess(int v){return v==0;}""",
    "com.arthenica.ffmpegkit.FFmpegKit": """public static void cancel(long id){}""",
    "com.arthenica.ffmpegkit.FFmpegSession": """String[] args; int status; String output;
        public static FFmpegSession create(String[] args,Object done,Object log,java.util.function.Consumer<Statistics> progress){
            FFmpegSession s=new FFmpegSession();s.args=args;return s;}
        public long getSessionId(){return 1;} public int getReturnCode(){return status;}
        public String getOutput(){return output;}""",
    "com.arthenica.ffmpegkit.Statistics": """public double getTime(){return 0;} public long getSessionId(){return 1;}""",
    "com.arthenica.ffmpegkit.FFmpegKitConfig": r"""public static int decodes;
        public static String getSafParameterForRead(android.content.Context c,android.net.Uri u){return u.getPath();}
        public static void ffmpegExecute(FFmpegSession s){
            decodes++;
            java.util.List<String> cmd=new java.util.ArrayList<>();cmd.add("ffmpeg");
            for(String arg:s.args)cmd.add(arg.replace('\\','/'));
            try {Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
                s.output=new String(p.getInputStream().readAllBytes());s.status=p.waitFor();}
            catch(Exception e){throw new RuntimeException(e);}
        }""",
    "com.arthenica.ffmpegkit.FFprobeKit": """public static int calls;
        public static FFprobeKit getMediaInformation(String file){FFprobeKit r=new FFprobeKit();calls++;
            try {Process p=new ProcessBuilder("ffprobe","-v","error","-select_streams","a:0","-show_entries",
                "stream=channel_layout,channels,sample_rate:format=duration","-of","default=noprint_wrappers=1",file).start();
                java.util.Properties props=new java.util.Properties();props.load(p.getInputStream());
                if(p.waitFor()==0)r.info=new MediaInformation(props);
            }catch(Exception e){throw new RuntimeException(e);}return r;}
        MediaInformation info;public MediaInformation getMediaInformation(){return info;}""",
    "com.arthenica.ffmpegkit.MediaInformation": """java.util.Properties p;
        MediaInformation(java.util.Properties p){this.p=p;}
        public java.util.List<StreamInformation> getStreams(){return java.util.Collections.singletonList(new StreamInformation(p));}
        public String getDuration(){return p.getProperty("duration");}""",
    "com.arthenica.ffmpegkit.StreamInformation": """java.util.Properties p; StreamInformation(java.util.Properties p){this.p=p;}
        public String getType(){return "audio";} public String getChannelLayout(){return p.getProperty("channel_layout");}
        public Long getNumberProperty(String k){return Long.valueOf(p.getProperty(k));}
        public String getSampleRate(){return p.getProperty("sample_rate");}""",
}

with tempfile.TemporaryDirectory() as temp:
    work = Path(temp)
    for d in ("cache","project","model","classes"):
        (work/d).mkdir()
    files=[]
    for name,body in stubs.items():
        package, cls = name.rsplit(".",1)
        path = work / (cls+".java")
        iface = " implements AutoCloseable" if cls=="Cursor" else ""
        path.write_text(f"package {package}; public class {cls}{iface} {{ {body} }}", encoding="utf-8")
        files.append(str(path))
    subprocess.run(["javac","-encoding","UTF-8","-d",str(work/"classes"),*files,
                    str(audio/"StudioVoiceRender.java"),str(audio/"StudioVoiceRecipe.java"),
                    str(Path(__file__).parent/"studio-voice/VoiceRenderCacheTest.java")],check=True)
    # Speech-like bursts with a digital-silence floor. A long span exercises the full confirmation pass.
    with wave.open(str(work/"quiet.wav"),"wb") as wav:
        wav.setparams((1,2,8000,0,"NONE","not compressed"))
        wav.writeframes(b"".join(struct.pack("<h",int(11000*math.sin(i*2*math.pi/40)) if i//800%4>=2 else 0)
                                 for i in range(16000*8)))
    if len(sys.argv)>1:
        subprocess.run(["ffmpeg","-v","error","-ss","4","-i",sys.argv[1],"-map","0:a:0","-t","8",
                        "-c:a","pcm_s16le",str(work/"noisy.wav")],check=True)
    args=["java","-Dassets="+str(root/"app/src/main/assets"),"-cp",str(work/"classes"),"VoiceRenderCacheTest"]
    subprocess.run(args,cwd=work,check=True)
    subprocess.run([*args,"reopen"],cwd=work,check=True)
    print("PASS: quiet decision survives restart; edits and unknown revisions trigger analysis")
