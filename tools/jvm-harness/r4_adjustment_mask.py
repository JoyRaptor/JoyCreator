"""Fault-inject at export's buildSteps boundary, using the production compile and catch policies."""
from pathlib import Path
import tempfile
import subprocess

root = Path(__file__).resolve().parents[2]
s = (root / "app/src/main/java/com/fadcam/ui/faditor/export/AdjustmentLayerGlEffect.java").read_text(encoding="utf-8")
def between(start, end, offset=0):
    i = s.index(start, offset)
    return s[i:s.index(end, i)]
policy = between("            if (!buildSteps(plan, maskShapes))", "            sourceKey = key.toString();")
catch = between("            } catch (Exception e) {", "\n        }\n", s.index("public void drawFrame"))
fixture = """
class MaskFault {
    static class VideoFrameProcessingException extends Exception {
        long pts;
        VideoFrameProcessingException(Throwable e){super(e);}
        VideoFrameProcessingException(String s){super(s);}
        VideoFrameProcessingException(String s, Throwable e, long pts){super(s,e);this.pts=pts;}
    }
    static class Layer {String getName(){return "Title grade";} String getId(){return "layer-1";}}
    Layer layer = new Layer();
    boolean degraded;
    int compiledShapes, budget, attempts, last;
    Object plan;
    boolean buildSteps(Object p,int n){attempts++;last=n;return n<=budget;}
    boolean compile(int maskShapes) throws VideoFrameProcessingException { POLICY return true; }
    void passthrough(int tex) {}
    static class FLog {static void w(String t,String s,Throwable e) {}}
    boolean draw(int n) throws VideoFrameProcessingException {
        int inputTexId=1;long presentationTimeUs=315000, editorMs=1115;
        try { return compile(n); CATCH
        return false;
    }
    public static void main(String[] args) throws Exception {
        MaskFault good=new MaskFault();good.budget=4;
        if(!good.draw(4)||good.last!=4)throw new AssertionError("full mask");
        for(int cap:new int[]{0,1}) {
            MaskFault limited=new MaskFault();limited.budget=cap;
            try {limited.draw(4);throw new AssertionError("export completed with an incomplete mask");}
            catch(VideoFrameProcessingException e) {
                if(limited.attempts!=1 || e.pts!=315000 || !e.getMessage().contains("Title grade")
                       || !e.getMessage().contains("1115")) throw new AssertionError("missing failure context",e);
            }
        }
    }
}
"""
# A fallback that returns normally must remain visible to the test. Use a nonconstant runtime branch
# so javac accepts the shared trailing return even when the new catch always throws.
fixture = fixture.replace("try { return compile(n);", "try { if(n>0) return compile(n);")
fixture = fixture.replace("POLICY", policy).replace("CATCH", catch)
with tempfile.TemporaryDirectory() as t:
    f = Path(t) / "MaskFault.java"
    f.write_text(fixture, encoding="utf-8")
    subprocess.run(["javac", "-d", t, str(f)], check=True)
    subprocess.run(["java", "-cp", t, "MaskFault"], check=True)
