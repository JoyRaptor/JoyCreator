package cc.joycreator.avatar.face;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

/**
 * Walk with your face, in Perform (the pilot's drawer in {@code al-face-walk.js}): a chip under Walking opens three tick
 * boxes. Left and right, and Closer and further, are the room's (kept on this phone); Up close belongs to the character in
 * hand (not kept). The two depth boxes are shown only on a walker that walks toward the camera (no dead controls: the lead, 11-27);
 * the room shows the drawer only for a character that walks, as the pilot's Walking row.
 */
public final class FaceWalkDrawer extends LinearLayout {
    private static final String PREFS = "avatar_room", LR = "faceWalk.lr", DEPTH = "faceWalk.depth";

    /** What the drawer asks of the room. */
    public interface Host {
        /** The face camera is on (the boxes still work; they say a face is needed). */
        boolean faceCameraOn();

        /** The walker can walk toward and away from the camera. */
        boolean walksInDepth();
    }

    private final FaceWalk walk;
    private final Host host;
    private final TextView chip, lrBox, depthBox, upBox, note;
    private final LinearLayout boxes;
    private boolean open;

    /** Reads the room's two settings into {@code walk}. */
    public static void load(Context c, FaceWalk walk) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        walk.setLeftRight(p.getBoolean(LR, false));
        walk.setCloser(p.getBoolean(DEPTH, false));
    }

    public FaceWalkDrawer(Context c, FaceWalk walk, Host host) {
        super(c);
        this.walk = walk;
        this.host = host;
        setOrientation(VERTICAL);
        load(c, walk);
        chip = new TextView(c);
        chip.setText(R.string.avatar_face_walk);
        chip.setTextSize(12);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(px(12), 0, px(12), 0);
        tip(chip, c.getString(R.string.avatar_hover_face_walk));
        chip.setOnClickListener(v -> {
            open = !open;
            paint();
        });
        LayoutParams cl = new LayoutParams(LayoutParams.WRAP_CONTENT, px(32));
        cl.topMargin = px(6);
        addView(chip, cl);
        boxes = new LinearLayout(c);
        boxes.setOrientation(VERTICAL);
        boxes.setPadding(px(4), px(4), 0, 0);
        lrBox = row(c, R.string.avatar_face_walk_lr, R.string.avatar_face_walk_lr_sub, R.string.avatar_hover_face_walk_lr, () -> {
            walk.setLeftRight(!walk.leftRight());
            keep(LR, walk.leftRight());
        });
        depthBox = row(c, R.string.avatar_face_walk_depth, R.string.avatar_face_walk_depth_sub, R.string.avatar_hover_face_walk_depth, () -> {
            walk.setCloser(!walk.closer());
            keep(DEPTH, walk.closer());
        });
        upBox = row(c, R.string.avatar_face_walk_up, R.string.avatar_face_walk_up_sub, R.string.avatar_hover_face_walk_up, () -> walk.setUpClose(!walk.upClose()));
        note = new TextView(c);
        note.setTextSize(11);
        note.setTextColor(Studio.INK_FAINT);
        note.setPadding(0, px(2), 0, px(4));
        boxes.addView(note);
        addView(boxes);
        paint();
    }

    /** Repaint (the room calls it when the camera or the walker changes). */
    public void paint() {
        boolean deep = host.walksInDepth();
        paintChip(chip, open || walk.leftRight() || (deep && (walk.closer() || walk.upClose())));
        boxes.setVisibility(open ? VISIBLE : GONE);
        paintTick(lrBox, walk.leftRight());
        paintTick(depthBox, walk.closer());
        paintTick(upBox, walk.upClose());
        ((View) depthBox.getParent()).setVisibility(deep ? VISIBLE : GONE);      // only where it can walk toward the camera
        ((View) upBox.getParent()).setVisibility(deep ? VISIBLE : GONE);
        String n = !host.faceCameraOn() ? getContext().getString(R.string.avatar_face_walk_needs_face) : "";   // (as the pilot: while the camera is off)
        note.setText(n);
        note.setVisibility(n.isEmpty() ? GONE : VISIBLE);
    }

    private void keep(String k, boolean v) {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(k, v).apply();
    }

    private TextView row(Context c, int label, int sub, int hover, Runnable flip) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(40));
        TextView box = new TextView(c);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(14);
        row.addView(box, new LayoutParams(px(24), px(24)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        words.setPadding(px(10), 0, 0, 0);
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(Studio.LABEL);
        TextView s = new TextView(c);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(Studio.INK_FAINT);
        words.addView(t);
        words.addView(s);
        row.addView(words, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            flip.run();
            paint();
        });
        tip(row, c.getString(hover));
        boxes.addView(row);
        return box;
    }

    /** ON = solid cyan with the tick cut out; off = a grey outline. */
    private void paintTick(TextView box, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(6));
        if (on) g.setColor(Studio.ARMED);
        else g.setStroke(Math.max(1, px(1.5f)), Studio.INK_OFF);
        box.setBackground(g);
        box.setTypeface(com.fadcam.ui.faditor.SheetKit.icons(box.getContext()));             // (Studio's tick, not a typed mark)
        box.setText(on ? "check" : "");
        box.setTextColor(Studio.GROUND);
    }

    private void paintChip(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(16));
        g.setColor(on ? Studio.ARMED : Studio.RAISED);
        t.setBackground(g);
        t.setTextColor(on ? Studio.GROUND : Studio.LABEL);
    }

    private static void tip(View v, String s) {
        TooltipCompat.setTooltipText(v, s);
        v.setContentDescription(s);
    }

    private int px(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
