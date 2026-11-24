package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import cc.joycreator.avatar.file.CharacterDoc;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import java.util.ArrayList;
import java.util.List;

/**
 * "Layers" (spec A2, the Parts / Layers switch): every layer of the file as a list with its picture, indented by how
 * deep it sits, with an eye to show or hide it. A tap puts the layer in hand (a cyan hairline on the picture).
 * The eye and the row are at least 36 dp (audit 22).
 */
public final class LayersView extends RecyclerView {

    public interface Host {
        void inHand(String id);

        void eye(CharacterDoc.Node node);
    }

    private static final class Row {
        final CharacterDoc.Node node;
        final int depth;

        Row(CharacterDoc.Node n, int d) {
            node = n;
            depth = d;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Thumbs thumbs = new Thumbs();
    private final Host host;
    private final float dp;
    private String inHand;

    public LayersView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        setLayoutManager(new LinearLayoutManager(c));
        setAdapter(new Adapter());
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    public void show(CharacterDoc doc, String inHandId) {
        thumbs.use(doc);
        inHand = inHandId;
        rows.clear();
        doc.walk((n, depth, parent) -> {
            if (parent != null) rows.add(new Row(n, depth - 1));   // the root itself is the character, not a layer
        });
        getAdapter().notifyDataSetChanged();
    }

    private final class Adapter extends RecyclerView.Adapter<RowHolder> {
        @Override
        public RowHolder onCreateViewHolder(ViewGroup parent, int type) {
            Context c = parent.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * dp)));
            ImageView pic = new ImageView(c);
            pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp));
            row.addView(pic, pl);
            TextView name = new TextView(c);
            name.setTextSize(14);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nl.leftMargin = Math.round(8 * dp);
            row.addView(name, nl);
            TextView eye = new TextView(c);
            eye.setGravity(Gravity.CENTER);
            eye.setTextSize(16);
            row.addView(eye, new LinearLayout.LayoutParams(Math.round(40 * dp), Math.round(40 * dp)));
            return new RowHolder(row, pic, name, eye);
        }

        @Override
        public void onBindViewHolder(RowHolder h, int i) {
            Row r = rows.get(i);
            CharacterDoc.Node n = r.node;
            h.itemView.setPadding(Math.round((12 + 14 * r.depth) * dp), 0, Math.round(4 * dp), 0);
            boolean on = n.id.equals(inHand);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(8 * dp);
            bg.setColor(on ? Studio.ARMED : 0x00000000);
            h.itemView.setBackground(bg);
            int ink = on ? Studio.GROUND : (n.hidden ? 0xFF71717A : 0xFFE4E4E7);
            h.name.setTextColor(ink);
            String label = labelOf(n);
            h.name.setText(label);
            Bitmap t = "art".equals(n.type) ? thumbs.of(n.img, 72) : null;
            h.pic.setImageBitmap(t);
            h.pic.setAlpha(n.hidden ? 0.35f : 1f);
            if (t == null) h.pic.setImageDrawable(null);
            h.itemView.setOnClickListener(v -> host.inHand(n.id));
            TooltipCompat.setTooltipText(h.itemView, getContext().getString(R.string.avatar_hover_layer, label));
            // the eye: on = solid cyan with the mark cut out, off = a grey outline (rule: ON = solid cyan)
            GradientDrawable e = new GradientDrawable();
            e.setCornerRadius(8 * dp);
            if (n.hidden) {
                e.setStroke(Math.max(1, Math.round(dp)), 0xFF52525B);
                h.eye.setText("");
            } else {
                e.setColor(on ? Studio.GROUND : Studio.ARMED);
                h.eye.setText("✓");
                h.eye.setTextColor(on ? Studio.ARMED : Studio.GROUND);
            }
            h.eye.setBackground(inset(e, Math.round(6 * dp)));
            h.eye.setOnClickListener(v -> host.eye(n));
            String hover = getContext().getString(n.hidden ? R.string.avatar_hover_show : R.string.avatar_hover_hide, label);
            TooltipCompat.setTooltipText(h.eye, hover);
            h.eye.setContentDescription(hover);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static android.graphics.drawable.Drawable inset(GradientDrawable g, int px) {
        return new android.graphics.drawable.InsetDrawable(g, px);
    }

    private static String labelOf(CharacterDoc.Node n) {
        if (!n.name.isEmpty()) return n.name;
        if (!n.role.isEmpty()) return n.role;
        return n.id;
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final ImageView pic;
        final TextView name, eye;

        RowHolder(View v, ImageView pic, TextView name, TextView eye) {
            super(v);
            this.pic = pic;
            this.name = name;
            this.eye = eye;
        }
    }
}
