package cc.joycreator.avatar.face;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.avatar.face.FacePoints;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Face pins in Set up (the pilot's Face pins card): a chip opens it; pick a point of your face (named in your own words), then
 * tap the character where it should pull (the stage's {@link FacePinsLayer}). Each pin: its colour and number as on the picture,
 * its strength, Remove. Under them the All pins dial, and Make one piece while older pins still bend single slices of a head.
 * Every change is one named undo step (the room makes them).
 */
public final class FacePinsSection extends LinearLayout {

    public interface Host {
        JSONObject character();

        boolean facePinsOpen();

        void setFacePinsOpen(boolean on);

        int pendingPoint();

        void pickPoint(int pt);

        String pickedFacePin();

        void pickFacePin(String id);

        void facePinStrength(String id, double v);

        void removeFacePin(String id);

        void allFacePins(double g);

        void facePinsOnePiece();
    }

    private final Host host;

    public FacePinsSection(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        build();
    }

    private void build() {
        Context c = getContext();
        removeAllViews();
        JSONObject ch = host.character();
        JSONArray list = FacePins.list(ch);
        addView(label(c.getString(R.string.avatar_facepins_your_face)));
        TextView open = chip(list.length() == 0 ? c.getString(R.string.avatar_facepins) : c.getString(R.string.avatar_facepins_n, list.length()),
                host.facePinsOpen(), c.getString(R.string.avatar_hover_facepins), v -> {
                    host.setFacePinsOpen(!host.facePinsOpen());
                    build();
                });
        addView(open);
        if (!host.facePinsOpen()) return;
        addView(body(c.getString(host.pendingPoint() >= 0 ? R.string.avatar_facepins_now_tap : R.string.avatar_facepins_hint)));

        // the points of your face, named in your own words (the pilot's map's named points)
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout pts = new LinearLayout(c);
        for (Object[] r : FacePoints.NAMED) {
            int pt = (Integer) r[0];
            String name = FacePins.nameOf(pt);
            pts.addView(chip(name, host.pendingPoint() == pt, c.getString(R.string.avatar_hover_facepins_point, name), v -> {
                host.pickPoint(host.pendingPoint() == pt ? -1 : pt);
                build();
            }));
        }
        hs.addView(pts);
        addView(hs);

        // the pins: colour and number as on the picture, strength, Remove
        for (int i = 0; i < list.length(); i++) {
            JSONObject q = list.optJSONObject(i);
            final String id = q.optString("id");
            boolean picked = id.equals(host.pickedFacePin());
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, px(4), 0, px(4));
            TextView dot = new TextView(c);
            dot.setText(String.valueOf(i + 1));
            dot.setGravity(Gravity.CENTER);
            dot.setTextSize(11);
            dot.setTextColor(Studio.GROUND);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(FacePinsLayer.colorOf(i));
            dot.setBackground(g);
            row.addView(dot, new LayoutParams(px(22), px(22)));
            TextView name = new TextView(c);
            name.setText(FacePins.nameOf(q.optInt("point")));
            name.setTextSize(13);
            name.setTextColor(picked ? Studio.ARMED : Studio.LABEL);
            name.setPadding(px(8), 0, px(8), 0);
            name.setOnClickListener(v -> {
                host.pickFacePin(picked ? null : id);
                build();
            });
            String hover = c.getString(picked ? R.string.avatar_hover_facepins_unpick : R.string.avatar_hover_facepins_pick, FacePins.nameOf(q.optInt("point")));
            TooltipCompat.setTooltipText(name, hover);                     // (X6 audit: the pin's name is a button, so it says what it does)
            name.setContentDescription(hover);
            row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.addView(chip(c.getString(R.string.avatar_facepins_remove), false, c.getString(R.string.avatar_hover_facepins_remove), v -> host.removeFacePin(id)));
            addView(row);
            if (picked) addView(slider(c.getString(R.string.avatar_facepins_strength), q.has("amt") ? q.optDouble("amt", 1) : 1, 3,
                    c.getString(R.string.avatar_hover_facepins_strength), v -> host.facePinStrength(id, v)));
        }
        if (list.length() > 0) addView(slider(c.getString(R.string.avatar_facepins_all), FacePins.gain(ch), 3, c.getString(R.string.avatar_hover_facepins_all), host::allFacePins));
        int slices = FacePins.onSlices(ch);
        if (slices > 0) addView(chip(c.getString(R.string.avatar_facepins_one_piece, slices), false, c.getString(R.string.avatar_hover_facepins_one_piece), v -> host.facePinsOnePiece()));
    }

    private interface Value {
        void set(double v);
    }

    /** A slider 0..max that makes its change when the finger lifts (one undo step a slide). */
    private View slider(String title, double now, double max, String hover, Value done) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(c);
        t.setTextSize(12);
        t.setTextColor(Studio.INK_DIM);
        t.setText(title + "  " + String.format(java.util.Locale.ROOT, "%.1f", now));
        row.addView(t, new LayoutParams(px(120), LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(c);
        sb.setMax(300);
        sb.setProgress((int) Math.round(now / max * 300));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                t.setText(title + "  " + String.format(java.util.Locale.ROOT, "%.1f", p / 300.0 * max));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                done.set(Math.round(s.getProgress() / 300.0 * max * 100) / 100.0);
            }
        });
        TooltipCompat.setTooltipText(sb, hover);
        sb.setContentDescription(hover);
        row.addView(sb, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView label(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(Studio.INK_FAINT);
        t.setPadding(0, px(12), 0, px(4));
        return cc.joycreator.avatar.ui.RoomType.label(t);              // (as every other card's section label)
    }

    private TextView body(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Studio.INK_DIM);
        t.setPadding(0, px(4), 0, px(6));
        return cc.joycreator.avatar.ui.RoomType.body(t);
    }

    /** ON = solid cyan with the label punched out; off = the zinc pill. */
    private TextView chip(String text, boolean on, String hover, View.OnClickListener tap) {
        TextView t = cc.joycreator.avatar.ui.RoomChip.make(getContext(), text, on, hover, tap);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = px(4);
        return t;
    }

    private int px(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
