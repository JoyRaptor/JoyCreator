"""Replay the adjustment admission gate from ExportManager against timed export pieces."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
export = root / "app/src/main/java/com/fadcam/ui/faditor/export"
source = (export / "ExportManager.java").read_text(encoding="utf-8")
gate = re.search(r"if \(al == null \|\| !al\.rendersAnything\(\).*?\) continue;", source, re.S)
assert gate, "production adjustment admission gate missing"
gate = gate.group(0)
fixture = """
package com.fadcam.ui.faditor.export;
class SpanProof {
    static class Layer {
        final long start, duration;
        Layer(long s,long d) { start=s; duration=d; }
        boolean rendersAnything() { return true; }
        long getStartMs() { return start; }
        long getDurationMs() { return duration; }
        boolean active(long t) { return t>=start && (duration==0 || t-start<duration); }
    }
    static boolean emit(Layer layer,long timelineCursorMs,long adjustmentOffset,long pieceDurationMs) {
        for (Layer al: new Layer[]{layer}) { GATE return true; }
        return false;
    }
    public static void main(String[] args) {
        int kept=0;
        for(int i=0;i<20;i++) if(emit(new Layer(40000+i*1000,500),0,10000,2000)) kept++;
        if(kept!=0) throw new AssertionError(kept+" disjoint GPU passes submitted");
        // Composition cursor + chunk/seam adjustment is precisely the drawFrame gate's clock.
        // A negative correction represents the loop-before head; zero duration is open-ended.
        for(long offset:new long[]{-600,0,15000})
            for(long dur:new long[]{1,175,3600})
                for(Layer layer:new Layer[]{new Layer(0,0),new Layer(2300,0),new Layer(3000,100),
                        new Layer(2399,1),new Layer(2400,1),new Layer(18123,2300)}) {
                    boolean admit=emit(layer,3000,offset,dur);
                    for(long dt=0;dt<dur;dt++)
                        if(layer.active(3000+offset+dt) && !admit) throw new AssertionError("lost active frame");
                }
        System.out.println("20 disjoint passes removed; every active frame retained");
    }
}
""".replace("GATE", gate)
with tempfile.TemporaryDirectory() as folder:
    path = Path(folder) / "SpanProof.java"
    path.write_text(fixture, encoding="utf-8")
    helpers = [str(p) for p in export.glob("AdjustmentSpan.java")]
    subprocess.run(["javac", "-d", folder, str(path), *helpers], check=True)
    subprocess.run(["java", "-cp", folder, "com.fadcam.ui.faditor.export.SpanProof"], check=True)
