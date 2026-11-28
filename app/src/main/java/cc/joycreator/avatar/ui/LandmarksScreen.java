package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.Marks;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matching the angles of the head (spec A9, the owner's model of 11-23): ONE numbered set of landmarks, every angle
 * places the SAME points. The stage shows the chosen angle solid with its points numbered and a neighbouring angle as a
 * tinted ghost (the first drawing is always cyan, the second always magenta). Tap + Add, then the picture, to place a
 * point; drag any dot to move it in that drawing only; say whether the chosen point is here, behind or not here;
 * remove it from every angle; set where two drawings meet. Every change is one named undo step through {@link RoomEdit}.
 *
 * <p>Not yet here: matching a piece of the head on its own (the whole head only), the guess that a dot let go off the
 * drawing is "behind", and the preview slider (the solver draws the turn).</p>
 */
public final class LandmarksScreen implements StageView.LandmarkLayer {

    public interface Host {
        void closeLandmarks();
    }

    private final Context c;
    private final StageView stage;
    private final RoomEdit edit;
    private final Host host;
    private final float dp;
    private final ScrollView view;
    private final LinearLayout column;

    private CharacterDoc doc;
    private String setId, aId, bId;
    private int sel = -1;
    private boolean adding;

    public LandmarksScreen(Context c, StageView stage, RoomEdit edit, Host host) {
        this.c = c;
        this.stage = stage;
        this.edit = edit;
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        view = new ScrollView(c);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(12), px(6), px(12), px(16));
        view.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    public View view() {
        return view;
    }

    public boolean isOpen() {
        return setId != null;
    }

    /** Opens the screen on a head set (its angles are its non-mirrored drawings). */
    public void open(CharacterDoc d, String setNodeId) {
        doc = d;
        setId = setNodeId;
        sel = -1;
        adding = false;
        List<CharacterDoc.Node> angles = angles();
        aId = angles.isEmpty() ? null : angles.get(0).id;
        bId = angles.size() > 1 ? angles.get(1).id : null;
        stage.setLandmarks(this);
        stage.setHint(c.getString(R.string.avatar_marks_hint));
        rebuild();
    }

    public void close() {
        setId = null;
        stage.setLandmarks(null);
        stage.setHint(c.getString(R.string.avatar_hint_rest));
    }

    /** The character changed (a step, an undo or a redo): the screen reads it again. */
    public void refresh(CharacterDoc d) {
        if (setId == null) return;
        doc = d;
        if (doc.find(setId) == null) {
            host.closeLandmarks();
            return;
        }
        if (sel >= pointCount()) sel = -1;
        rebuild();
        stage.invalidate();
    }

    // ── what is there ─────────────────────────────────────────────────────────────────────────────

    private CharacterDoc.Node set() {
        return doc == null || setId == null ? null : doc.find(setId);
    }

    private List<CharacterDoc.Node> angles() {
        List<CharacterDoc.Node> out = new ArrayList<>();
        CharacterDoc.Node s = set();
        if (s != null) for (CharacterDoc.Node k : s.kids) if (!"mirror".equals(k.type)) out.add(k);
        return out;
    }

    private JSONObject marks() {
        CharacterDoc.Node s = set();
        try {
            return s == null ? null : Marks.piece(s.json, "", false);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private int pointCount() {
        JSONObject mk = marks();
        JSONArray pts = mk == null ? null : mk.optJSONArray("pts");
        return pts == null ? 0 : pts.length();
    }

    private JSONArray place(String drawing, int i) {
        JSONObject mk = marks();
        JSONObject at = mk == null ? null : mk.optJSONObject("at");
        JSONArray row = at == null || drawing == null ? null : at.optJSONArray(drawing);
        return row == null ? null : row.optJSONArray(i);
    }

    private String nameOf(String id) {
        CharacterDoc.Node n = id == null ? null : doc.find(id);
        return n == null ? "" : n.name.isEmpty() ? n.id : n.name;
    }

    // ── the stage layer ───────────────────────────────────────────────────────────────────────────

    @Override
    public CharacterDoc.Node solid() {
        return aId == null ? null : doc.find(aId);
    }

    @Override
    public CharacterDoc.Node ghost() {
        return bId == null ? null : doc.find(bId);
    }

    @Override
    public int ghostTint() {
        return Studio.LIVE;
    }

    @Override
    public List<StageView.Dot> dots() {
        List<StageView.Dot> out = new ArrayList<>();
        int n = pointCount();
        String[] ids = {aId, bId};
        for (int i = 0; i < n; i++) {
            JSONArray qa = place(aId, i), qb = place(bId, i);
            // a point that is "not here" in both drawings on show belongs to other angles: left out, unless it is the one chosen
            if (sel != i && Marks.off(qa) && Marks.off(qb)) continue;
            for (int s = 0; s < 2; s++) {
                JSONArray q = s == 0 ? qa : qb;
                if (ids[s] == null || q == null) continue;
                StageView.Dot d = new StageView.Dot();
                d.x = (float) q.optDouble(0);
                d.y = (float) q.optDouble(1);
                d.state = Marks.stateOf(q);
                d.color = s == 0 ? Studio.ARMED : Studio.LIVE;
                d.small = s == 1;
                d.label = s == 0 || sel == i ? String.valueOf(i + 1) : null;
                d.tag = new int[]{s, i};
                out.add(d);
            }
        }
        return out;
    }

    @Override
    public void tap(float x, float y) {
        if (adding && aId != null) {
            final String a = aId, b = bId;
            final float px = x, py = y;
            final int[] made = {-1};
            boolean ok = edit.apply(c.getString(R.string.avatar_step_add_mark), d -> {
                JSONObject mk = Marks.piece(d.find(setId).json, "", true);
                Map<String, double[]> places = new LinkedHashMap<>();
                places.put(a, new double[]{px, py});
                if (b != null) places.put(b, new double[]{px, py});
                made[0] = Marks.add(mk, places);
            });
            adding = false;
            if (ok) sel = made[0];
        } else {
            sel = -1;
        }
        rebuild();
        stage.invalidate();
    }

    @Override
    public void picked(StageView.Dot d) {
        sel = ((int[]) d.tag)[1];
        rebuild();
    }

    @Override
    public void moved(StageView.Dot d, float x, float y, boolean last) {
        if (!last) return;
        final int s = ((int[]) d.tag)[0], i = ((int[]) d.tag)[1];
        final String id = s == 0 ? aId : bId;
        final float nx = x, ny = y;
        edit.apply(c.getString(R.string.avatar_step_move_mark, i + 1), dd -> {
            JSONObject mk = Marks.piece(dd.find(setId).json, "", false);
            String was = Marks.stateOf(mk.getJSONObject("at").getJSONArray(id).getJSONArray(i));
            Marks.move(mk, id, i, nx, ny);
            if ("off".equals(was)) Marks.setState(mk, id, i, "here");       // a dragged "not here" dot counts again
        });
    }

    // ── the controls ──────────────────────────────────────────────────────────────────────────────

    private void rebuild() {
        column.removeAllViews();
        if (doc == null || set() == null) return;
        int n = pointCount();
        column.addView(heading(c.getString(R.string.avatar_marks_title)));
        column.addView(body(c.getResources().getQuantityString(R.plurals.avatar_marks_count, n, n)));

        List<CharacterDoc.Node> angles = angles();
        column.addView(label(c.getString(R.string.avatar_marks_solid)));
        column.addView(anglesRow(angles, aId, true));
        if (angles.size() > 1) {
            column.addView(label(c.getString(R.string.avatar_marks_ghost)));
            column.addView(anglesRow(angles, bId, false));
        }

        LinearLayout actions = new LinearLayout(c);
        actions.setPadding(0, px(10), 0, px(4));
        actions.addView(chip(c.getString(R.string.avatar_marks_add), adding, c.getString(R.string.avatar_hover_marks_add), v -> {
            adding = !adding;
            sel = -1;
            rebuild();
        }));
        if (sel >= 0 && sel < n) {
            actions.addView(chip(c.getString(R.string.avatar_marks_remove, sel + 1), false, c.getString(R.string.avatar_hover_marks_remove, sel + 1), v -> removeSelected()));
        }
        column.addView(scrollRow(actions));

        if (adding) column.addView(body(c.getString(R.string.avatar_marks_adding)));
        else if (sel >= 0 && sel < n && aId != null) {
            column.addView(label(c.getString(R.string.avatar_marks_in, sel + 1, nameOf(aId))));
            JSONArray q = place(aId, sel);
            String st = Marks.stateOf(q);
            LinearLayout seg = new LinearLayout(c);
            seg.addView(chip(c.getString(R.string.avatar_marks_here), "here".equals(st), c.getString(R.string.avatar_hover_marks_here), v -> setState("here")));
            seg.addView(chip(c.getString(R.string.avatar_marks_behind), "behind".equals(st), c.getString(R.string.avatar_hover_marks_behind), v -> setState("behind")));
            seg.addView(chip(c.getString(R.string.avatar_marks_off), "off".equals(st), c.getString(R.string.avatar_hover_marks_off), v -> setState("off")));
            column.addView(scrollRow(seg));
        } else if (n > 0) {
            column.addView(body(c.getString(R.string.avatar_marks_drag)));
        } else {
            column.addView(body(c.getString(R.string.avatar_marks_none)));
        }

        // where the two drawings meet on the way between them
        JSONObject mk = marks();
        if (mk != null && aId != null && bId != null && place(aId, 0) != null && place(bId, 0) != null) {
            column.addView(label(c.getString(R.string.avatar_marks_meet)));
            SeekBar sb = new SeekBar(c);
            sb.setMax(100);
            sb.setProgress((int) Math.round(Marks.meet(mk, aId, bId) * 100));
            sb.getProgressDrawable().setTint(Studio.ARMED);
            sb.getThumb().setTint(Studio.ARMED);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    final double v = s.getProgress() / 100.0;
                    final String a = aId, b = bId;
                    edit.apply(c.getString(R.string.avatar_step_meet), d -> Marks.setMeet(Marks.piece(d.find(setId).json, "", true), a, b, v));
                }
            });
            TooltipCompat.setTooltipText(sb, c.getString(R.string.avatar_hover_marks_meet));
            sb.setContentDescription(c.getString(R.string.avatar_hover_marks_meet));
            column.addView(sb);
        }

        LinearLayout done = new LinearLayout(c);
        done.setPadding(0, px(12), 0, 0);
        done.addView(chip(c.getString(R.string.avatar_marks_done), false, c.getString(R.string.avatar_hover_marks_done), v -> host.closeLandmarks()));
        column.addView(done);
    }

    private View anglesRow(List<CharacterDoc.Node> angles, String chosen, boolean solid) {
        LinearLayout row = new LinearLayout(c);
        for (CharacterDoc.Node k : angles) {
            final String id = k.id;
            if (!solid && id.equals(aId)) continue;          // the ghost is a different drawing
            row.addView(chip(k.name.isEmpty() ? k.id : k.name, id.equals(chosen), c.getString(solid ? R.string.avatar_hover_marks_solid : R.string.avatar_hover_marks_ghost, k.name), v -> {
                if (solid) {
                    if (id.equals(bId)) bId = aId;         // the colours never swap: a drawing picked as solid leaves the ghost's place
                    aId = id;
                } else bId = id;
                sel = -1;
                rebuild();
                stage.invalidate();
            }));
        }
        return scrollRow(row);
    }

    private void setState(final String state) {
        final String id = aId;
        final int i = sel;
        // every drawing needs at least one landmark that is here or behind (the file's rule), so the last one cannot go "not here"
        if ("off".equals(state)) {
            int live = 0, n = pointCount();
            for (int k = 0; k < n; k++) if (k != i && !Marks.off(place(id, k))) live++;
            if (live == 0) {
                android.widget.Toast.makeText(c, R.string.avatar_marks_last_one, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }
        edit.apply(c.getString(R.string.avatar_step_mark_state, i + 1, state.equals("off") ? c.getString(R.string.avatar_marks_off).toLowerCase() : state), d -> {
            JSONObject mk = Marks.piece(d.find(setId).json, "", false);
            Marks.setState(mk, id, i, state);
        });
    }

    private void removeSelected() {
        final int i = sel;
        sel = -1;
        edit.apply(c.getString(R.string.avatar_step_remove_mark, i + 1), d -> Marks.remove(d.find(setId).json, "", i));
    }

    // ── small pieces ──────────────────────────────────────────────────────────────────────────────

    private View scrollRow(LinearLayout row) {
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    private TextView chip(String text, boolean on, String hover, View.OnClickListener tap) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), 0, px(12), 0);
        t.setTextColor(on ? Studio.GROUND : Studio.LABEL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(16));
        g.setColor(on ? Studio.ARMED : Studio.RAISED);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32));
        lp.rightMargin = px(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    private TextView heading(String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(16);
        t.setTextColor(Studio.INK);
        RoomType.heading(t);
        t.setPadding(0, px(4), 0, px(2));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Studio.INK_FAINT);
        RoomType.label(t);
        t.setPadding(0, px(8), 0, px(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Studio.INK_DIM);
        RoomType.body(t);
        t.setPadding(0, px(6), 0, px(6));
        return t;
    }
}
