from pathlib import Path
import tempfile, subprocess
root = Path(r'C:/+Projects/Screenrecorder/FadCam')
out = Path(tempfile.mkdtemp(prefix='vislab-wordfeed-'))
sources = {
'android/content/Context.java': '''package android.content; public class Context { public Context getApplicationContext(){return this;} public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();} }''',
'android/content/res/AssetManager.java': '''package android.content.res; public class AssetManager { public java.io.InputStream open(String p) throws java.io.IOException {throw new java.io.IOException();} }''',
'android/graphics/Bitmap.java': '''package android.graphics; public class Bitmap {public enum Config{ARGB_8888} public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap();} public void recycle(){} }''',
'android/graphics/Canvas.java': '''package android.graphics; public class Canvas {public Canvas(Bitmap b){} public void drawColor(int c,PorterDuff.Mode m){} public void drawText(String t,float x,float y,Paint p){} }''',
'android/graphics/Color.java': '''package android.graphics; public class Color {public static final int WHITE=-1;}''',
'android/graphics/Typeface.java': '''package android.graphics; public class Typeface {public static final Typeface DEFAULT_BOLD=new Typeface();}''',
'android/graphics/PorterDuff.java': '''package android.graphics; public class PorterDuff {public enum Mode{CLEAR}}''',
'android/graphics/Paint.java': '''package android.graphics; public class Paint {public static final int ANTI_ALIAS_FLAG=1; public Paint(int x){} public void setTypeface(Typeface t){} public void setTextSize(float x){} public void setColor(int x){} public float measureText(String s){return s.length()*48;} public static class FontMetrics {public float ascent=-80,descent=20;} public FontMetrics getFontMetrics(){return new FontMetrics();} }''',
'android/util/Log.java': '''package android.util; public class Log {public static int w(String a,String b,Throwable t){return 0;} }''',
'android/opengl/GLES30.java': '''package android.opengl; public class GLES30 {public static final int GL_TEXTURE_2D=1,GL_RGBA8=2,GL_RGBA=3,GL_UNSIGNED_BYTE=4,GL_TEXTURE_MIN_FILTER=5,GL_LINEAR_MIPMAP_LINEAR=6,GL_TEXTURE_MAG_FILTER=7,GL_LINEAR=8,GL_TEXTURE_WRAP_S=9,GL_TEXTURE_WRAP_T=10,GL_CLAMP_TO_EDGE=11; public static int mipmaps,gens; public static void glGenTextures(int n,int[] a,int x){a[x]=++gens;} public static void glBindTexture(int a,int b){} public static void glTexImage2D(int a,int b,int c,int d,int e,int f,int g,int h,java.nio.Buffer i){} public static void glTexParameteri(int a,int b,int c){} public static void glGenerateMipmap(int t){mipmaps++;} public static void glDeleteTextures(int n,int[] a,int x){} }''',
'android/opengl/GLUtils.java': '''package android.opengl; public class GLUtils { public static int uploads; public static void texSubImage2D(int a,int b,int c,int d,android.graphics.Bitmap e){uploads++;} }''',
'com/fadcam/ui/type/Type.java': '''package com.fadcam.ui.type; public class Type {public static final int EXTRA=1; public static android.graphics.Typeface display(android.content.Context c,int x){return null;} }''',
'com/fadcam/vislab/gl/ShaderLib.java': '''package com.fadcam.vislab.gl; public class ShaderLib {public static String readAll(java.io.InputStream i){return "";} }''',
'WordFeedProbe.java': '''import com.fadcam.vislab.words.WordFeed; import android.opengl.*; public class WordFeedProbe {public static void main(String[]args)throws Exception {WordFeed f=new WordFeed(new android.content.Context()); f.setTyped("HELLO"); f.update(10,3,4); f.texture(); int u0=GLUtils.uploads,m0=GLES30.mipmaps; for(int i=0;i<60;i++){f.update(10,3,4);f.texture();} System.out.println("Same feed / 60 frozen frames: uploads="+(GLUtils.uploads-u0)+", mipmap builds="+(GLES30.mipmaps-m0)); u0=GLUtils.uploads;m0=GLES30.mipmaps; for(int i=0;i<60;i++){f.setTyped("WORLD");f.update(10,3,4);f.texture(); f.setTyped("HELLO");f.update(10,3,4);f.texture();} System.out.println("Two different typed layers / 60 frozen frames: uploads="+(GLUtils.uploads-u0)+", mipmap builds="+(GLES30.mipmaps-m0)+", slots per draw="+f.count); System.out.println("CPU slot upload bytes="+((long)(GLUtils.uploads-u0)*2048*128*4)); java.lang.reflect.Field t=WordFeed.class.getDeclaredField("tex");t.setAccessible(true);System.out.println("Typed texture handle survives without feed.release/contextLost: "+t.getInt(f)); } }'''
}
for name,src in sources.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(src)
gson = next(Path(r'C:/Users/JoyRaptor/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.13.2').rglob('*.jar'))
args = [str(p) for p in out.rglob('*.java')] + [str(root/'tools/jvm-harness/stubs/androidx/annotation/NonNull.java'), str(root/'tools/jvm-harness/stubs/androidx/annotation/Nullable.java'), str(root/'app/src/main/java/com/fadcam/vislab/words/WordFeed.java')]
subprocess.run(['javac','-encoding','UTF-8','-cp',str(gson),'-d',str(out)]+args,check=True)
subprocess.run(['java','-cp',str(out)+';'+str(gson),'WordFeedProbe'],check=True)
print('STUB_PROBE_CLASSES='+str(out))
