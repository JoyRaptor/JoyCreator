package com.fadcam.ui.settingshub;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The hub's list: section headings and rows, as {@link SettingsLines} lays them out. Ids are the
 * rows' own, so a refreshed value repaints one row and a search that keeps a row keeps its view.
 */
final class SettingsHubAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onOpen(@NonNull SettingsKey key);
    }

    private static final int TYPE_HEADER = 0, TYPE_ROW = 1;
    /** Gap between rows of one group, and between search hits, in dp. */
    private static final float GROUP_GAP_DP = 2f, HIT_GAP_DP = 6f;

    private final Listener listener;
    private List<SettingsLines.Line> lines = Collections.emptyList();
    private Map<SettingsKey, SettingsStatus.Value> values = Collections.emptyMap();

    SettingsHubAdapter(@NonNull Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    void submit(@NonNull List<SettingsLines.Line> lines) {
        this.lines = lines;
        notifyDataSetChanged();
    }

    void setValues(@NonNull Map<SettingsKey, SettingsStatus.Value> values) {
        this.values = values;
        for (int i = 0; i < lines.size(); i++) if (!lines.get(i).isHeader()) notifyItemChanged(i);
    }

    @NonNull
    List<SettingsLines.Line> lines() {
        return lines;
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    @Override
    public int getItemViewType(int position) {
        return lines.get(position).isHeader() ? TYPE_HEADER : TYPE_ROW;
    }

    @Override
    public long getItemId(int position) {
        SettingsLines.Line l = lines.get(position);
        return l.isHeader() ? -1L - l.section : l.key.ordinal();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context c = parent.getContext();
        View v = type == TYPE_HEADER ? new SettingsSectionView(c) : new SettingsRowView(c);
        v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        Holder h = new Holder(v);
        if (type == TYPE_ROW) {
            v.setOnClickListener(x -> {
                if (h.key != null) listener.onOpen(h.key);
            });
        }
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SettingsLines.Line l = lines.get(position);
        Holder h = (Holder) holder;
        Context c = h.itemView.getContext();
        h.key = l.key;
        if (l.isHeader()) {
            SettingsCatalog.Section s = SettingsCatalog.SECTIONS.get(l.section);
            ((SettingsSectionView) h.itemView).bind(c.getString(s.title), s.glyph);
            return;
        }
        String section = l.namesSection && l.section >= 0
                ? c.getString(SettingsCatalog.SECTIONS.get(l.section).title) : null;
        ((SettingsRowView) h.itemView).bind(SettingsCatalog.row(l.key), section, l.shape, values.get(l.key));

        RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) h.itemView.getLayoutParams();
        boolean groupEnd = l.shape == SettingsLines.Shape.LAST || l.shape == SettingsLines.Shape.ONLY;
        lp.bottomMargin = SettingsViews.dp(c, l.namesSection ? HIT_GAP_DP : groupEnd ? 0 : GROUP_GAP_DP);
        h.itemView.setLayoutParams(lp);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        SettingsKey key;

        Holder(@NonNull View v) {
            super(v);
        }
    }
}
