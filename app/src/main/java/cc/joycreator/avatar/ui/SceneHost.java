package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.widget.Toast;

import cc.joycreator.avatar.file.CharacterDoc;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * What {@link SceneUi} asks the room for, answered from the room's own parts (the character on the stage, the stage, the GL surface).
 * The room only makes one of these and hands it its pieces: everything about the frame and the strip stays in the scene classes.
 */
public final class SceneHost implements SceneUi.Host {
    private final Context ctx;
    private final BooleanSupplier performing;
    private final Supplier<CharacterDoc> doc;
    private final Supplier<String> name, key;
    private final Supplier<StageView> stage;
    private final Supplier<SolverStage> gl;
    private final Runnable setUp;
    private Bitmap pic;
    private CharacterDoc picOf;
    private String picName;

    public SceneHost(Context ctx, BooleanSupplier performing, Supplier<CharacterDoc> doc, Supplier<String> name, Supplier<String> key,
                     Supplier<StageView> stage, Supplier<SolverStage> gl, Runnable setUp) {
        this.ctx = ctx;
        this.performing = performing;
        this.doc = doc;
        this.name = name;
        this.key = key;
        this.stage = stage;
        this.gl = gl;
        this.setUp = setUp;
    }

    @Override
    public boolean performing() {
        return performing.getAsBoolean();
    }

    @Override
    public String characterName() {
        String n = name.get();
        return n == null ? "" : n;
    }

    @Override
    public String characterKey() {
        String k = key.get();
        return k == null || k.isEmpty() ? characterName() : k;
    }

    /** A small picture of the character standing, drawn once for each character and name. */
    @Override
    public Bitmap characterPic() {
        CharacterDoc d = doc.get();
        PoseRenderer r = stage.get().renderer();
        if (d == null || r == null) return null;
        if (pic != null && picOf == d && characterName().equals(picName)) return pic;
        float[] b = characterBox();
        if (b == null) return null;
        try {
            int w = 96, h = Math.max(24, Math.round(96 * (b[3] - b[1]) / Math.max(1f, b[2] - b[0])));
            if (h > 140) {
                h = 140;
                w = Math.max(24, Math.round(140 * (b[2] - b[0]) / Math.max(1f, b[3] - b[1])));
            }
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            float s = Math.min(w / (b[2] - b[0]), h / (b[3] - b[1]));
            c.translate((w - (b[2] - b[0]) * s) / 2f - b[0] * s, (h - (b[3] - b[1]) * s) / 2f - b[1] * s);
            c.scale(s, s);
            r.draw(c);
            pic = bm;
            picOf = d;
            picName = characterName();
            return pic;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    @Override
    public float[] characterBox() {
        CharacterDoc d = doc.get();
        PoseRenderer r = stage.get().renderer();
        if (d == null || r == null || d.root == null) return null;
        double[] b = r.restBounds(d.root);
        return b == null || b.length < 4 ? null : new float[]{(float) b[0], (float) b[1], (float) b[2], (float) b[3]};
    }

    @Override
    public float[] headBox() {
        CharacterDoc d = doc.get();
        PoseRenderer r = stage.get().renderer();
        if (d == null || r == null || d.root == null) return null;
        CharacterDoc.Node head = findRole(d.root, "head");
        if (head == null) return null;
        double[] b = r.restBounds(head);
        return b == null || b.length < 4 ? null : new float[]{(float) b[0], (float) b[1], (float) b[2], (float) b[3]};
    }

    private static CharacterDoc.Node findRole(CharacterDoc.Node n, String role) {
        if (role.equals(n.role)) return n;
        for (CharacterDoc.Node k : n.kids) {
            CharacterDoc.Node f = findRole(k, role);
            if (f != null) return f;
        }
        return null;
    }

    @Override
    public StageView stage() {
        return stage.get();
    }

    @Override
    public SolverStage gl() {
        return gl.get();
    }

    @Override
    public void setUpCharacter() {
        setUp.run();
    }

    @Override
    public void toast(String text) {
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }
}
