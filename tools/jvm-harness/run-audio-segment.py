"""Host regression. Requires JDK 17+, Android SDK, FFmpeg and the project's FFmpegKit AAR."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/com/fadcam/ui/faditor"
RUN = lambda args: subprocess.run(args,check=True,stdout=subprocess.PIPE,text=True).stdout.strip()
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
platform = max((sdk/"platforms").glob("android-*/android.jar"),
               key=lambda f: int(f.parent.name.split("-")[1]) if f.parent.name.split("-")[1].isdigit() else 0)
aar = Path(os.environ.get("FFMPEGKIT_AAR", ROOT/"app/libs/ffmpeg-kit-full-6.0-2.LTS.aar"))
with tempfile.TemporaryDirectory() as td:
    work = Path(td)
    with zipfile.ZipFile(aar) as ar: ar.extract("classes.jar", work)
    cp = os.pathsep.join(str(s) for s in (work,platform,work/"classes.jar"))
    stubs = {
        "NonNull": "package androidx.annotation; public @interface NonNull {}",
        "Nullable": "package androidx.annotation; public @interface Nullable {}",
        "FLog": "package com.fadcam; public class FLog {" + "".join(
            "public static void %s(String a,String b){}"%k for k in "wied") + "}",
        "CaptionAnimator": "package com.fadcam.ui.faditor.transcript; public class CaptionAnimator {public static float clampZonePct(float x){return x;}}",
        "Uri": """package android.net; public class Uri {
            private final String path;private Uri(String s){path=s;}
            public static Uri parse(String s){return new Uri(s);}
            public static Uri fromFile(java.io.File f){return parse(f.toURI().toString());}
            public String getScheme(){return "file";} public String getPath(){return path;}
            public String toString(){return path;}
        }"""
    }
    for name,src in stubs.items(): (work/(name+".java")).write_text(src,encoding="utf8")
    tests = Path(__file__).parent
    classes = ["audio/BakedAudioCache","model/AudioClip","model/AudioParams","model/VolumeKeyframe",
               "transcript/TranscriptWord","transcript/Transcript","transcript/NamedTranscript","transcript/TranscriptParagraphs"]
    RUN(["javac","-encoding","UTF-8","-cp",cp,"-d",td,*map(str,work.glob("*.java")),
         *[str(SRC/(c+".java")) for c in classes], str(tests/"AudioSegmentClockTest.java"),str(tests/"AudioSegmentCommandTest.java")])
    print(RUN(["java","-cp",cp,"AudioSegmentClockTest"]))
    src = work/"fixture.wav"
    ff = shutil.which("ffmpeg")
    RUN([ff,"-v","error","-f","lavfi","-i","aevalsrc=sin(2*PI*(350*t+5*t*t)):s=48000:d=20",str(src)])
    for start in [7000,18000]:
        output, reference = work/"segment.m4a",work/"reference.m4a"
        RUN(["java","-cp",cp,"com.fadcam.ui.faditor.audio.AudioSegmentCommandTest",
             str(src),str(output),str(start),str(start+2000),ff])
        duration = float(RUN(["ffprobe","-v","error","-of","csv=p=0","-show_entries","format=duration",str(output)]))
        assert abs(duration-2)<.023, duration
        RUN([ff,"-v","error","-y","-ss",str(start/1000),"-i",str(src),"-af",
             "atrim=duration=2,asetpts=PTS-STARTPTS,afftdn=nf=-25","-c:a","aac","-b:a","192k",str(reference)])
        def pcm(path):
            return subprocess.check_output([ff,"-v","error","-i",str(path),"-f","s16le","-"])
        assert pcm(output)==pcm(reference), "different slice or filter delay"
    print("PASS: middle and end extracts encode exactly the requested 2 seconds with identical decoded PCM")
