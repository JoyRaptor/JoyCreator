package cc.joycreator.textlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.joyfolder.SheetName;
import com.fadcam.ui.library.SheetDrawable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * Looks as picture chips (owner, 2026-11-12): pick a look by seeing it, not by reading its name. Each chip is
 * the look's own moving preview ({@link LookPreview}); a chip not chosen holds its first frame, the chosen one
 * plays and wears the cyan ring. The name is the chip's hover label and spoken description.
 *
 * <p>Memory: a chip not chosen keeps only its first frame (256 x 144); only the chosen chip holds its whole
 * sheet. Studio's look carousel is meant to reuse this row.</p>
 */
public final class LookChipRow extends RecyclerView {

    private static final int CYAN = 0xFF22D3EE, CONTROL = 0xFF1F1F26;

    /** One chip: the look's name, and its sheet once it is made (null until then). */
    public static final class Chip {
        public final String name;
        @Nullable File sheet;

        public Chip(@NonNull String name) {
            this.name = name;
        }
    }

    private final List<Chip> chips = new ArrayList<>();
    private final LruCache<String, Bitmap> firsts = new LruCache<>(64);
    private final ExecutorService decode = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private Bitmap chosenSheet;
    @Nullable private String chosenSheetPath;
    private int chosen = -1;
    @Nullable private IntConsumer onPick;

    public LookChipRow(@NonNull Context c) {
        super(c);
        setLayoutManager(new LinearLayoutManager(c, HORIZONTAL, false));
        setAdapter(new ChipAdapter());
        setClipToPadding(false);
        int p = dp(8);
        setPadding(p, p, p, p);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public void setChips(@NonNull List<Chip> list) {
        chips.clear();
        chips.addAll(list);
        getAdapter().notifyDataSetChanged();
    }

    public void setOnPick(@Nullable IntConsumer pick) {
        onPick = pick;
    }

    /** A chip's sheet is ready (main thread). */
    public void setSheet(int i, @Nullable File sheet) {
        if (i < 0 || i >= chips.size()) return;
        chips.get(i).sheet = sheet;
        getAdapter().notifyItemChanged(i);
    }

    public void choose(int i) {
        int old = chosen;
        chosen = i;
        if (old >= 0) getAdapter().notifyItemChanged(old);
        if (i >= 0) {
            getAdapter().notifyItemChanged(i);
            smoothScrollToPosition(i);
        }
    }

    public int chosen() {
        return chosen;
    }

    // ---- pictures ---------------------------------------------------------------------------------

    /** The first frame of a sheet (a chip at rest), decoded alone. */
    private void first(@NonNull File f, @NonNull ImageView into) {
        Bitmap b = firsts.get(f.getPath());
        if (b != null) {
            into.setImageBitmap(b);
            return;
        }
        into.setTag(f.getPath());
        decode.execute(() -> {
            Bitmap got = null;
            SheetName n = SheetName.parse(f.getName());
            try {
                BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(f.getPath(), false);
                int w = d.getWidth() / (n != null ? n.cols : 1), h = d.getHeight() / (n != null ? n.rows : 1);
                got = d.decodeRegion(new Rect(0, 0, w, h), null);
                d.recycle();
            } catch (Exception e) {
                got = null;
            }
            Bitmap r = got;
            main.post(() -> {
                if (r == null) return;
                firsts.put(f.getPath(), r);
                if (f.getPath().equals(into.getTag())) into.setImageBitmap(r);
            });
        });
    }

    /** The whole sheet of the chosen chip, playing. */
    private void playing(@NonNull File f, @NonNull ImageView into) {
        if (chosenSheet != null && f.getPath().equals(chosenSheetPath)) {
            SheetDrawable d = SheetDrawable.of(chosenSheet, f.getName());
            if (d != null) {
                into.setImageDrawable(d);
                d.start();
            }
            return;
        }
        first(f, into);   // hold the first frame while the whole sheet loads
        decode.execute(() -> {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            main.post(() -> {
                if (b == null) return;
                chosenSheet = b;
                chosenSheetPath = f.getPath();
                int i = chosen;
                if (i >= 0 && i < chips.size() && f.equals(chips.get(i).sheet)) getAdapter().notifyItemChanged(i);
            });
        });
    }

    // ---- the row ----------------------------------------------------------------------------------

    private final class ChipAdapter extends Adapter<ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout ring = new FrameLayout(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(116), dp(67));
            lp.setMarginEnd(dp(6));
            ring.setLayoutParams(lp);
            int r = dp(2);
            ring.setPadding(r, r, r, r);
            ImageView pic = new ImageView(parent.getContext());
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setBackgroundColor(CONTROL);
            pic.setClipToOutline(true);
            pic.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(6));
                }
            });
            ring.addView(pic, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // a chip with no picture (yet, or ever: "Plain") says what it is in words
            android.widget.TextView word = new android.widget.TextView(parent.getContext());
            word.setTextColor(0xFFD4D4D8);
            word.setTextSize(12);
            word.setGravity(android.view.Gravity.CENTER);
            word.setMaxLines(2);
            ring.addView(word, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new ViewHolder(ring) {};
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int i) {
            Chip c = chips.get(i);
            FrameLayout ring = (FrameLayout) h.itemView;
            ImageView pic = (ImageView) ring.getChildAt(0);
            boolean on = i == chosen;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(on ? CYAN : 0x00000000);
            ring.setBackground(bg);
            ring.setContentDescription(c.name);
            // every button gets a hover label (stylus and mouse)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ring.setTooltipText(c.name);
            ring.setSelected(on);
            ring.setOnClickListener(v -> {
                int at = h.getBindingAdapterPosition();
                if (at == NO_POSITION) return;
                choose(at);
                if (onPick != null) onPick.accept(at);
            });
            pic.setImageDrawable(null);
            pic.setTag(null);
            android.widget.TextView word = (android.widget.TextView) ring.getChildAt(1);
            word.setText(c.name);
            word.setVisibility(c.sheet == null ? View.VISIBLE : View.GONE);
            if (c.sheet == null) return;
            if (on) playing(c.sheet, pic);
            else first(c.sheet, pic);
        }

        @Override
        public int getItemCount() {
            return chips.size();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        decode.shutdownNow();
    }
}
