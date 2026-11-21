"""Run the view's actual scheduling methods against a deterministic display and GL queue."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
java = root / "app/src/main/java/com/fadcam/vislab"
view = (java / "view/VisTextureView.java").read_text(encoding="utf-8")
model = (java / "model/VisScene.java").read_text(encoding="utf-8")


def block(src, marker):
    start = src.index(marker)
    brace = src.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (src[end] == "{") - (src[end] == "}")
        end += 1
    return src[start:end]


methods = "\n".join(block(view, sig) for sig in (
    "public void setPaused(", "public void requestRedraw(", "public void setScene(",
    "public void onSurfaceTextureSizeChanged(", "private final Choreographer.FrameCallback"))
if "private void scheduleFrame(" in view:
    methods += ";\n" + block(view, "private void scheduleFrame(")
else:
    methods += ";"

draw = block(view, "private void drawFrame(")
# Keep the real clock and feature selection, stand in for the window surface and GPU.
timing = draw[draw.index("        boolean hold =") if "boolean hold =" in draw else draw.index("        double dt"):draw.index("        try {")]
timing = timing.replace("if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return;", "").replace("applyBufferSize();", "")

extra = "\n".join(line.strip() for line in view.splitlines()
                  if "Runnable wakeFrame =" in line or "Runnable sceneEdited =" in line)
observer = "\n".join(block(model, sig) for sig in (
    "public void addEditListener(", "public void removeEditListener(") if sig in model)
scene_fields = "\n".join(line for line in model.splitlines() if " editListeners =" in line)
fixture = """
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
class VisScene {
  int edits; SCENE_FIELDS
  OBSERVER
  TOUCH
}
class SurfaceTexture {}
@interface Nullable {}
@interface NonNull {}
class Choreographer {
  interface FrameCallback { void doFrame(long t); }
  static final Choreographer instance = new Choreographer();
  static Choreographer getInstance(){return instance;}
  List<FrameCallback> pending = new ArrayList<>();
  void postFrameCallback(FrameCallback cb){ pending.add(cb); }
  void tick(long t){var q=new ArrayList<>(pending); pending.clear(); for(var cb:q)cb.doFrame(t);}
}
class Handler {
  List<Runnable> queue = new ArrayList<>();
  void removeCallbacks(Runnable r){queue.removeIf(x->x==r);}
  void post(Runnable r){queue.add(r);}
  void drain(){var q=new ArrayList<>(queue);queue.clear();q.forEach(Runnable::run);}
}
class Replay {
  boolean running=true, paused, redraw, framePosted, wasPaused;
  int drawnEdits,drawnW,drawnH,surfW,surfH,bufW,bufH,ceilingStrikes;
  int draws; long lastFrameNs,ceilingUntilNs; VisScene scene; Handler handler=new Handler();
  interface FeatureSource { Features next(double dt); }
  static class Features { int level; void copyTo(Features h){h.level=level;} }
  Features held=new Features(),silence=new Features(),last; FeatureSource source;
  double clock,step; Replay renderer;
  void drawFrame(long nowNs){draws++; TIMING last=f;step=dt;}
  EXTRA
  METHODS
  static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
  public static void main(String[] a){
    Replay v=new Replay(); v.renderer=new Replay(){public void setScene(VisScene s){}};
    Choreographer c=Choreographer.getInstance();
    c.postFrameCallback(v.frameCallback); v.framePosted=true;
    v.source=dt->{Features f=new Features();f.level=42;return f;};
    v.held.level=19;v.setPaused(true); v.handler.drain(); c.tick(0);
    for(int i=1;i<=3600;i++)c.tick(i);
    check(v.draws==1,"paused callbacks delivered: "+v.draws);
    VisScene s=new VisScene(); v.setScene(s);v.handler.drain();c.tick(3601);
    check(v.draws==2,"new scene"); s.touch(); s.touch();v.requestRedraw();
    v.handler.drain();c.tick(3602);check(v.draws==3,"coalesced edit and colour");
    s.touch();v.handler.drain();c.tick(3603);check(v.draws==4,"in-place edit");
    v.onSurfaceTextureSizeChanged(new SurfaceTexture(),99,50);
    v.handler.drain();c.tick(3604);check(v.draws==5,"paused resize");
    check(v.clock==0 && v.last.level==19,"held features and clock");
    v.setPaused(false);v.handler.drain();c.tick(60_000_000_000L);
    check(v.last.level==42 && Math.abs(v.step-1./60)<1e-8,"first resumed step");c.tick(60_016_666_667L);
    check(v.draws==7 && c.pending.size()==1,"resume once then continuous");
    v.setScene(new VisScene());v.setPaused(true);v.handler.drain();c.tick(3607);
    s.touch();v.handler.drain();c.tick(3608);check(v.draws==8,"old scene detached");
    System.out.println("3600 idle ticks: 0 callbacks after held frame; edits, resize, resume and detach passed");
  }
}
""".replace("SCENE_FIELDS", scene_fields).replace("OBSERVER", observer).replace(
    "TOUCH", block(model, "public void touch(")).replace("EXTRA", extra).replace("METHODS", methods).replace("TIMING", timing)
with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / "Replay.java"
    path.write_text(fixture, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", str(path)], check=True)
    subprocess.run(["java", "-cp", tmp, "Replay"], check=True)
