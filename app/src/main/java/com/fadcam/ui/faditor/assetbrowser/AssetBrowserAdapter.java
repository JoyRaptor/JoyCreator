package com.fadcam.ui.faditor.assetbrowser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fadcam.FLog;
import com.fadcam.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the asset browser grid.
 *
 * <p>Shows a 4-column grid of media thumbnails with:
 * <ul>
 *   <li>Type badge (film/photo/music icon) in top-left</li>
 *   <li>"Used" checkmark badge in top-right (green) when file is in project</li>
 *   <li>Filename below thumbnail (2 lines max)</li>
 *   <li>Duration overlay for video/audio (bottom-right of thumbnail)</li>
 * </ul></p>
 *
 * <p><b>For future AI developers:</b> Long-press is currently wired to trigger
 * immediate insert at playhead. When implementing drag-and-drop (Phase 4),
 * replace the long-press handler with a drag-start callback that passes
 * the touch coordinates to the activity's drag system.</p>
 */
public class AssetBrowserAdapter extends RecyclerView.Adapter<AssetBrowserAdapter.AssetViewHolder> {

    private static final String TAG = "AssetBrowserAdapter";

    private final List<AssetItem> items = new ArrayList<>();
    // SPEC_20260829_MEDIA_IMPORT §2.3: ordered multi-select — kept here so badge numbers are live
    private final List<AssetItem> selectedOrdered = new ArrayList<>();
    @Nullable
    private Callback callback;
    @Nullable
    private AssetItem highlightedItem;

    public interface Callback {
        void onItemTapped(@NonNull AssetItem item);
        void onItemDragStarted(@NonNull AssetItem item, @NonNull View sourceView, float localX, float localY);
        void onItemRenameRequested(@NonNull AssetItem item);
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    public void setItems(@NonNull List<AssetItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void setHighlightedItem(@Nullable AssetItem item) {
        highlightedItem = item;
        notifyDataSetChanged();
    }

    /** SPEC_20260829_MEDIA_IMPORT §2.3: set the ordered selection — badge shows 1..N in selection order. */
    public void setSelectedOrdered(@NonNull List<AssetItem> ordered) {
        selectedOrdered.clear();
        selectedOrdered.addAll(ordered);
        notifyDataSetChanged();
    }

    @NonNull
    public List<AssetItem> getSelectedOrdered() {
        return new ArrayList<>(selectedOrdered);
    }

    /** 0-based index in selection order, or -1 if not selected. */
    public int selectionIndexOf(@NonNull AssetItem item) {
        for (int i = 0; i < selectedOrdered.size(); i++) {
            if (sameAsset(selectedOrdered.get(i), item)) return i;
        }
        return -1;
    }

    public void clearSelection() {
        selectedOrdered.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AssetViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context ctx = parent.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;

        // Container
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = (int)(4 * density);
        container.setPadding(padding, padding, padding, padding);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Thumbnail frame (square aspect)
        FrameLayout thumbFrame = new FrameLayout(ctx);
        int thumbSize = (int)(80 * density);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(thumbSize, thumbSize);
        thumbFrame.setLayoutParams(thumbLp);

        // Thumbnail ImageView
        ImageView imageView = new ImageView(ctx);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        imageView.setBackgroundColor(Color.parseColor("#FF1F1F26"));
        thumbFrame.addView(imageView);

        // Type badge (top-left)
        TextView typeBadge = new TextView(ctx);
        typeBadge.setTypeface(ResourcesCompat.getFont(ctx, R.font.materialicons));
        typeBadge.setTextSize(11);
        typeBadge.setTextColor(Color.WHITE);
        typeBadge.setBackgroundColor(Color.argb(160, 0, 0, 0));
        typeBadge.setGravity(Gravity.CENTER);
        typeBadge.setPadding((int)(3 * density), (int)(2 * density),
                (int)(3 * density), (int)(2 * density));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                (int)(22 * density), (int)(22 * density), Gravity.TOP | Gravity.START);
        typeBadge.setLayoutParams(badgeLp);
        thumbFrame.addView(typeBadge);

        // Used badge (top-right)
        TextView usedBadge = new TextView(ctx);
        usedBadge.setTypeface(ResourcesCompat.getFont(ctx, R.font.materialicons));
        usedBadge.setText("check_circle");
        usedBadge.setTextSize(12);
        usedBadge.setTextColor(Color.parseColor("#FF35F6BF"));
        usedBadge.setGravity(Gravity.CENTER);
        usedBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams usedLp = new FrameLayout.LayoutParams(
                (int)(20 * density), (int)(20 * density), Gravity.TOP | Gravity.END);
        usedBadge.setLayoutParams(usedLp);
        thumbFrame.addView(usedBadge);

        // Selection numbered badge (SPEC_20260829_MEDIA_IMPORT §2.3) — circular green with white number, top-end.
        TextView selectionBadge = new TextView(ctx);
        selectionBadge.setTextColor(Color.WHITE);
        selectionBadge.setTextSize(10);
        selectionBadge.setTypeface(Typeface.DEFAULT_BOLD);
        selectionBadge.setGravity(Gravity.CENTER);
        selectionBadge.setVisibility(View.GONE);
        android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
        selBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        selBg.setColor(Color.parseColor("#FF35F6BF"));
        selBg.setStroke((int)(1 * density), Color.WHITE);
        selectionBadge.setBackground(selBg);
        selectionBadge.setElevation(2 * density);
        FrameLayout.LayoutParams selLp = new FrameLayout.LayoutParams(
                (int)(22 * density), (int)(22 * density), Gravity.TOP | Gravity.END);
        selLp.setMargins(0, (int)(2 * density), (int)(2 * density), 0);
        selectionBadge.setLayoutParams(selLp);
        thumbFrame.addView(selectionBadge);

        // Duration overlay (bottom-right)
        TextView durationText = new TextView(ctx);
        durationText.setTextColor(Color.WHITE);
        durationText.setTextSize(9);
        durationText.setTypeface(Typeface.MONOSPACE);
        durationText.setBackgroundColor(Color.argb(140, 0, 0, 0));
        durationText.setPadding((int)(3 * density), (int)(1 * density),
                (int)(3 * density), (int)(1 * density));
        durationText.setVisibility(View.GONE);
        FrameLayout.LayoutParams durLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        durationText.setLayoutParams(durLp);
        thumbFrame.addView(durationText);

        container.addView(thumbFrame);

        // Filename label
        TextView nameLabel = new TextView(ctx);
        nameLabel.setTextColor(Color.parseColor("#FFC4C4CE"));
        nameLabel.setTextSize(10);
        nameLabel.setMaxLines(2);
        nameLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        nameLabel.setPadding(0, (int)(3 * density), 0, 0);
        container.addView(nameLabel);

        return new AssetViewHolder(container, container, imageView, typeBadge, usedBadge,
                selectionBadge, durationText, nameLabel);
    }

    @Override
    public void onBindViewHolder(@NonNull AssetViewHolder holder, int position) {
        AssetItem item = items.get(position);
        Context ctx = holder.itemView.getContext();

        int selIdx = selectionIndexOf(item);
        boolean isSelected = selIdx >= 0;
        if (isSelected) {
            // Selected state: green border + badge number; selection takes visual precedence over highlight.
            holder.container.setBackgroundColor(Color.argb(60, 76, 175, 80));
            holder.selectionBadge.setVisibility(View.VISIBLE);
            holder.selectionBadge.setText(String.valueOf(selIdx + 1));
            // Hide used check when numbered badge occupies the same corner — number is the active state.
            holder.usedBadge.setVisibility(View.GONE);
            // Subtle outline for selected thumb
            holder.imageView.setBackgroundColor(Color.parseColor("#FF35F6BF"));
        } else if (sameAsset(highlightedItem, item)) {
            holder.container.setBackgroundColor(Color.parseColor("#FF35F6BF"));
            holder.selectionBadge.setVisibility(View.GONE);
            holder.imageView.setBackgroundColor(Color.parseColor("#FF1F1F26"));
        } else {
            holder.container.setBackgroundColor(Color.TRANSPARENT);
            holder.selectionBadge.setVisibility(View.GONE);
            holder.imageView.setBackgroundColor(Color.parseColor("#FF1F1F26"));
        }

        // Filename
        holder.nameLabel.setText(item.shortLabel());

        // Type badge
        switch (item.type) {
            case VIDEO:
                holder.typeBadge.setText("movie");
                break;
            case IMAGE:
                holder.typeBadge.setText("photo");
                break;
            case AUDIO:
                holder.typeBadge.setText("music_note");
                break;
        }

        // Used badge — hidden when selected (badge occupies corner, see above)
        if (!isSelected) {
            holder.usedBadge.setVisibility(item.isUsed ? View.VISIBLE : View.GONE);
        }

        // Duration
        if (item.durationMs > 0) {
            holder.durationText.setVisibility(View.VISIBLE);
            holder.durationText.setText(formatDuration(item.durationMs));
        } else {
            holder.durationText.setVisibility(View.GONE);
        }

        // Thumbnail loading -- SPEC_20260829_MEDIA_IMPORT S2.2
        // Images: Glide as before (SAF content:// is fine for images).
        // Videos: VideoThumbnailCache (MediaMetadataRetriever.getScaledFrameAtTime at
        // ~10% in, disk+mem cache via DurableCache "vidthumb", bounded pool 2 threads).
        // Do NOT use Glide for video -- it cannot pull a frame from SAF content:// on
        // these devices (grid stays black = the bug JOYRAPTOR reported).
        if (item.type == AssetItem.Type.IMAGE) {
            // Cancel any stale video thumb work if view was recycled from video.
            VideoThumbnailCache.cancel(holder.imageView);
            holder.imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(ctx).load(item.uri).centerCrop().into(holder.imageView);
        } else if (item.type == AssetItem.Type.VIDEO) {
            int thumbPx = (int) (80 * ctx.getResources().getDisplayMetrics().density);
            // Fallback to 160px minimum so scaled decode is not postage-stamp.
            if (thumbPx < 160) thumbPx = 160;
            VideoThumbnailCache.load(ctx, item, holder.imageView, thumbPx);
        } else {
            // Audio: show music note placeholder
            VideoThumbnailCache.cancel(holder.imageView);
            holder.imageView.setScaleType(ImageView.ScaleType.CENTER);
            // Keep selection green when selected, even for audio
            if (!isSelected) holder.imageView.setBackgroundColor(Color.parseColor("#FF2C2C35"));
            holder.imageView.setImageDrawable(null);
        }

        // Click
        holder.itemView.setOnClickListener(v -> {
            if (callback != null) callback.onItemTapped(item);
        });

        // Tap filename to rename
        holder.nameLabel.setOnClickListener(v -> {
            if (callback != null) callback.onItemRenameRequested(item);
        });

        // Long click: start drag for Phase 4 drop-to-timeline.
        holder.itemView.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (callback != null) {
                callback.onItemDragStarted(item, holder.itemView,
                        holder.itemView.getWidth() / 2f,
                        holder.itemView.getHeight() / 2f);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onViewRecycled(@NonNull AssetViewHolder holder) {
        super.onViewRecycled(holder);
        // SPEC_20260829_MEDIA_IMPORT S2.2: cancel extractions for rows that scrolled away
        VideoThumbnailCache.cancel(holder.imageView);
        // Clear Glide requests for images as well to avoid stale callbacks.
        try { com.bumptech.glide.Glide.with(holder.imageView.getContext()).clear(holder.imageView); } catch (Exception ignored) {}
    }

    private boolean sameAsset(@Nullable AssetItem a, @NonNull AssetItem b) {
        if (a == null) return false;
        return a.uri.toString().equals(b.uri.toString());
    }

    @NonNull
    private String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min > 0) {
            return String.format(Locale.US, "%d:%02d", min, sec);
        }
        return String.format(Locale.US, "0:%02d", sec);
    }

    static class AssetViewHolder extends RecyclerView.ViewHolder {
        final View container;
        final ImageView imageView;
        final TextView typeBadge;
        final TextView usedBadge;
        final TextView selectionBadge;
        final TextView durationText;
        final TextView nameLabel;

        AssetViewHolder(@NonNull View itemView, @NonNull View container, ImageView imageView,
                        TextView typeBadge, TextView usedBadge, TextView selectionBadge,
                        TextView durationText, TextView nameLabel) {
            super(itemView);
            this.container = container;
            this.imageView = imageView;
            this.typeBadge = typeBadge;
            this.usedBadge = usedBadge;
            this.selectionBadge = selectionBadge;
            this.durationText = durationText;
            this.nameLabel = nameLabel;
        }
    }
}
