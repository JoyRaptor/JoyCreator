import com.fadcam.ui.faditor.sprite.RollDragController;

/** Run with javac + java: no Android or test framework required. */
public final class RollDragControllerTest {
    static final class Host implements RollDragController.Bounds, RollDragController.Listener {
        int gap = -9, scroll, drops, from, to; boolean remove, active; float dx, dy;
        public int size() { return 3; }
        public float centerX(int i) { return 100 + i * 50; }
        public float centerY(int i) { return 100; }
        public float height(int i) { return 40; }
        public float viewportLeft() { return 50; }
        public float viewportRight() { return 300; }
        public void scrollBy(int p) { scroll += p; }
        public void onLift(int i,float x,float y,boolean r) { dx=x;dy=y;remove=r; }
        public void onGap(int g) { gap=g; }
        public void onHint(boolean a,boolean r) { active=a; }
        public void onDrop(int f,int g,boolean r) { drops++;from=f;to=g;remove=r; }
    }
    static int checks;
    static void check(boolean good) { checks++; if (!good) throw new AssertionError("check " + checks); }
    public static void main(String[] ignored) {
        Host h = new Host(); RollDragController c = new RollDragController(h,h,2);
        c.begin(1); check(c.isDragging(1)); check(h.gap == 1 && h.active);
        c.move(199,100); check(h.gap == 2); check(h.dx == 49 && h.dy == 0);
        c.move(200,100); check(h.gap == 3);
        c.move(100,60); check(h.gap == 1); // exact removal threshold stays on strip
        c.move(100,59); check(h.gap == -1 && h.remove); check(h.scroll == -48);
        c.end(true); check(h.drops == 1 && h.from == 1 && h.remove);
        check(!c.isDragging(1) && h.gap == -1 && !h.active && h.dx == 0 && h.dy == 0);
        c.begin(0); c.move(299,100); check(h.scroll == -24); c.end(false); check(h.drops == 1);
        c.begin(0); c.move(175,100); c.end(true); check(h.to == 2 && !h.remove && h.drops == 2);
        c.begin(-1); check(!c.isDragging(-1)); c.end(true); check(h.drops == 2);
        c = new RollDragController(h,h,1); c.begin(0); h.scroll = 0;
        c.move(94,100); check(h.scroll == 0); c.move(93,100); check(h.scroll == -12);
        c.move(256,100); check(h.scroll == -12); c.move(257,100); check(h.scroll == 0);
        boolean rejected=false; try {new RollDragController(h,h,Float.NaN);} catch(IllegalArgumentException e){rejected=true;}
        check(rejected);
        System.out.println("RollDragController: " + checks + " checks passed");
    }
}
