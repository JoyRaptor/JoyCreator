package com.fadcam.ui.trash;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.ui.library.LibraryViews;

import java.util.ArrayList;
import java.util.List;

/**
 * The grid of deleted things. Tap and long-press are handed to the screen, which decides (preview,
 * choose); a change of choice repaints only the marks, never the pictures.
 */
final class TrashAdapter extends RecyclerView.Adapter<TrashAdapter.Holder> {

    interface Listener {
        void onTap(@NonNull TrashEntry e);
        void onHold(@NonNull TrashEntry e);
    }

    private static final Object CHOICE = new Object();

    private final Listener listener;
    private final TrashSelection selection;
    private List<TrashEntry> rows = new ArrayList<>();
    private long keepMillis = -1;
    private long now;

    TrashAdapter(@NonNull TrashSelection selection, @NonNull Listener listener) {
        this.selection = selection;
        this.listener = listener;
        setHasStableIds(true);
    }

    void submit(@NonNull List<TrashEntry> rows, long keepMillis) {
        this.rows = rows;
        this.keepMillis = keepMillis;
        this.now = System.currentTimeMillis();
        notifyDataSetChanged();
    }

    /** The choice changed: repaint the marks. */
    void choiceChanged() {
        notifyItemRangeChanged(0, rows.size(), CHOICE);
    }

    @NonNull
    List<TrashEntry> rows() {
        return rows;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public long getItemId(int position) {
        return hash64(rows.get(position).key) & Long.MAX_VALUE;
    }

    /** FNV-1a, 64 bits, as the Library's grid uses. */
    private static long hash64(@NonNull String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context c = parent.getContext();
        TrashCardView card = new TrashCardView(c);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = LibraryViews.dp(c, 4);
        lp.setMargins(m, m, m, m);
        card.setLayoutParams(lp);
        Holder h = new Holder(card);
        card.setOnClickListener(v -> {
            if (h.entry != null) listener.onTap(h.entry);
        });
        card.setOnLongClickListener(v -> {
            if (h.entry == null) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onHold(h.entry);
            return true;
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        TrashEntry e = rows.get(position);
        h.entry = e;
        ((TrashCardView) h.itemView).bind(e, expiry(e), !selection.isEmpty(), selection.contains(e.key));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (!payloads.contains(CHOICE) || h.entry != rows.get(position)) {
            onBindViewHolder(h, position);
            return;
        }
        TrashEntry e = rows.get(position);
        ((TrashCardView) h.itemView).paintChoice(e, expiry(e), !selection.isEmpty(), selection.contains(e.key));
    }

    @NonNull
    private TrashExpiry expiry(@NonNull TrashEntry e) {
        return TrashExpiry.of(e.deletedAt, now, keepMillis);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        TrashEntry entry;

        Holder(@NonNull TrashCardView v) {
            super(v);
        }
    }
}
