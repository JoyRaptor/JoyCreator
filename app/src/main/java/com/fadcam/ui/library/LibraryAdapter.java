package com.fadcam.ui.library;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.ui.faditor.SheetKit;

import java.util.ArrayList;
import java.util.List;

/**
 * The grid (or list) itself: date-band headings that span the full width, and cards. Tap opens,
 * long-press asks what to do with it. Ids are stable, so a refresh that changes nothing visible
 * repaints nothing visible.
 */
final class LibraryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onOpen(@NonNull LibraryItem item);
        void onMenu(@NonNull LibraryItem item);
    }

    static final int TYPE_HEADER = 0, TYPE_CARD = 1, TYPE_ROW = 2;

    private final Listener listener;
    private List<LibrarySections.Row> rows = new ArrayList<>();
    private boolean list;

    LibraryAdapter(@NonNull Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    void submit(@NonNull List<LibrarySections.Row> rows, boolean list) {
        this.rows = rows;
        this.list = list;
        notifyDataSetChanged();
    }

    /** Headings take the whole row; cards take one column. */
    @NonNull
    GridLayoutManager.SpanSizeLookup spans(@NonNull GridLayoutManager glm) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return getItemViewType(position) == TYPE_HEADER ? glm.getSpanCount() : 1;
            }
        };
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (rows.get(position).isHeader()) return TYPE_HEADER;
        return list ? TYPE_ROW : TYPE_CARD;
    }

    @Override
    public long getItemId(int position) {
        LibrarySections.Row r = rows.get(position);
        return r.isHeader() ? -1L - r.band.ordinal() : (hash64(r.item.key) & Long.MAX_VALUE);
    }

    /** FNV-1a, 64 bits: a 32-bit String.hashCode can collide across a few thousand keys. */
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
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context c = parent.getContext();
        if (type == TYPE_HEADER) {
            View h = SheetKit.sectionLabel(c, "", 0);
            h.setPadding(LibraryViews.dp(c, 6), LibraryViews.dp(c, 14), LibraryViews.dp(c, 6), LibraryViews.dp(c, 6));
            h.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(h);
        }
        LibraryCardView card = new LibraryCardView(c, type == TYPE_ROW);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = LibraryViews.dp(c, type == TYPE_ROW ? 1 : 4);
        lp.setMargins(m, m, m, m);
        card.setLayoutParams(lp);
        Holder h = new Holder(card);
        card.setOnClickListener(v -> {
            LibraryItem it = h.item;
            if (it != null) listener.onOpen(it);
        });
        card.setOnLongClickListener(v -> {
            LibraryItem it = h.item;
            if (it == null) return false;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            listener.onMenu(it);
            return true;
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        LibrarySections.Row r = rows.get(position);
        Holder h = (Holder) holder;
        if (r.isHeader()) {
            ViewGroup label = (ViewGroup) h.itemView;
            android.widget.TextView t = (android.widget.TextView) label.getChildAt(label.getChildCount() - 1);
            Context c = t.getContext();
            t.setText(c.getString(com.fadcam.R.string.library_band_count,
                    LibraryFormat.bandLabel(c, r.band), r.bandCount));
            h.item = null;
            return;
        }
        h.item = r.item;
        ((LibraryCardView) h.itemView).bind(r.item);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        LibraryItem item;

        Holder(@NonNull View v) {
            super(v);
        }
    }
}
