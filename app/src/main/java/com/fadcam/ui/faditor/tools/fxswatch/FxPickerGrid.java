package com.fadcam.ui.faditor.tools.fxswatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.fx.FxBlurbs;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxPreviewTier;
import com.fadcam.ui.faditor.fx.FxRegistry;
import com.fadcam.ui.faditor.tools.ObjectDrawer;
import com.fadcam.ui.faditor.tools.TextOverlayDrawer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The effect picker as pictures (STU-6 stage 1, owner 2026-11-12: "all just a text list"): every
 * effect as a tile, its still at default settings above its name, grouped by family as the name
 * list is. A "Pictures | Names" pair on top keeps the old list one tap away (and remembers the
 * choice).
 *
 * <p>The stills are asked for only when the picker is first opened ({@link #shown}), from
 * {@link FxSwatches}: drawn once, then from the cache.</p>
 */
public final class FxPickerGrid {

    /** What a tile does when tapped. */
    public interface Pick {
        /** Add this effect to the stack (one undo step: the panel's job). */
        void add(@NonNull FxEffectDef def);

        /** Say why this effect cannot go here, and what would let it. */
        void explain(@NonNull FxEffectDef def);
    }

    private static final String PREFS = "fx_picker";
    private static final String KEY_NAMES = "names";
    private static final int COLUMNS = 4;
    private static final int LOAD_TAG = 0x2A5F_0051;

    private FxPickerGrid() {}

    /**
     * The picker's content: the Pictures/Names choice, then either the grid or {@code names}
     * (the old list, built by the caller and kept exactly as it was).
     */
    @NonNull
    public static View build(@NonNull Context ctx, @NonNull FxPreviewTier.Subject subject,
                             @NonNull View names, @NonNull Pick pick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        List<Runnable> loads = new ArrayList<>();
        for (FxEffectDef.Family family : FxEffectDef.Family.values()) {
            List<FxEffectDef> defs = FxRegistry.byFamily(family);
            if (defs.isEmpty()) continue;
            grid.addView(TextOverlayDrawer.Kit.sectionLabel(ctx, pretty(family.name())));
            LinearLayout row = null;
            for (int i = 0; i < defs.size(); i++) {
                if (i % COLUMNS == 0) {
                    row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row);
                }
                row.addView(tile(ctx, defs.get(i), subject, pick, d, loads));
            }
            // Fill the last row so every tile keeps one width.
            for (int i = defs.size() % COLUMNS; i > 0 && i < COLUMNS; i++) {
                View spacer = new View(ctx);
                row.addView(spacer, cell(d));
            }
        }

        LinearLayout choice = new LinearLayout(ctx);
        choice.setOrientation(LinearLayout.HORIZONTAL);
        choice.setGravity(Gravity.CENTER_VERTICAL);
        TextView pictures = TextOverlayDrawer.Kit.chip(ctx,
                ctx.getString(com.fadcam.R.string.faditor_fx_picker_pictures));
        TextView list = TextOverlayDrawer.Kit.chip(ctx,
                ctx.getString(com.fadcam.R.string.faditor_fx_picker_names));
        TextOverlayDrawer.Kit.describe(pictures,
                ctx.getString(com.fadcam.R.string.faditor_fx_picker_pictures_hint));
        TextOverlayDrawer.Kit.describe(list,
                ctx.getString(com.fadcam.R.string.faditor_fx_picker_names_hint));
        choice.addView(pictures);
        choice.addView(list);
        root.addView(choice);
        root.addView(grid);
        root.addView(names);

        Runnable[] show = new Runnable[1];
        boolean[] loaded = {false};
        show[0] = () -> {
            boolean asNames = prefs.getBoolean(KEY_NAMES, false);
            ObjectDrawer.Kit.setChipOn(pictures, !asNames, Studio.ARMED);
            ObjectDrawer.Kit.setChipOn(list, asNames, Studio.ARMED);
            grid.setVisibility(asNames ? View.GONE : View.VISIBLE);
            names.setVisibility(asNames ? View.VISIBLE : View.GONE);
            if (!asNames && root.getTag(LOAD_TAG) == Boolean.TRUE && !loaded[0]) {
                loaded[0] = true;
                for (Runnable r : loads) r.run();
            }
        };
        pictures.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_NAMES, false).apply();
            show[0].run();
        });
        list.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_NAMES, true).apply();
            show[0].run();
        });
        root.setTag(show);
        show[0].run();
        return root;
    }

    /** The picker was opened: load the stills (once) if pictures are showing. */
    public static void shown(@NonNull View picker) {
        Object t = picker.getTag();
        if (!(t instanceof Runnable[])) return;
        picker.setTag(LOAD_TAG, Boolean.TRUE);
        ((Runnable[]) t)[0].run();
    }

    @NonNull
    private static View tile(@NonNull Context ctx, @NonNull FxEffectDef def,
                             @NonNull FxPreviewTier.Subject subject, @NonNull Pick pick, float d,
                             @NonNull List<Runnable> loads) {
        boolean canRender = FxPreviewTier.canExportOn(def, subject);
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setLayoutParams(cell(d));

        ImageView img = new SixteenNine(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.SUNK);
        bg.setCornerRadius(6 * d);
        img.setBackground(bg);
        img.setClipToOutline(true);
        t.addView(img, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(ctx);
        name.setText(def.displayName);
        name.setTextSize(10f);
        name.setTextColor(canRender ? Studio.DRAWER_INK : Studio.DRAWER_LABEL);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        // Two lines, always: "Brightness / Exposure" is read whole, and every row lines up.
        name.setLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, Math.round(3 * d), 0, 0);
        t.addView(name);

        if (!canRender) {
            // Unavailable HERE, still legible and still answerable (the name list's rule).
            img.setAlpha(0.35f);
        }
        TextOverlayDrawer.Kit.describe(t, def.displayName + ": " + FxBlurbs.of(def));
        TextOverlayDrawer.Kit.pressable(t);
        t.setOnClickListener(v -> {
            if (canRender) pick.add(def);
            else pick.explain(def);
        });

        Bitmap ready = FxSwatches.get(ctx).stillIfReady(def);
        if (ready != null) img.setImageBitmap(ready);
        else loads.add(() -> FxSwatches.get(ctx).still(def, b -> {
            if (b != null) img.setImageBitmap(b);
        }));
        return t;
    }

    @NonNull
    private static LinearLayout.LayoutParams cell(float d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        // 3dp each side: the drawer's 6dp gap between neighbours.
        int m = Math.round(3 * d);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    @NonNull
    private static String pretty(@NonNull String family) {
        return family.charAt(0) + family.substring(1).toLowerCase(Locale.ROOT);
    }

    /** An image whose height is nine sixteenths of its width. */
    private static final class SixteenNine extends ImageView {
        SixteenNine(@NonNull Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = View.MeasureSpec.getSize(widthSpec);
            setMeasuredDimension(w, w * 9 / 16);
        }
    }
}
