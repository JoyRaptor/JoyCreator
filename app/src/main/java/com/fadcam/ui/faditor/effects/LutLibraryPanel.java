package com.fadcam.ui.faditor.effects;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import java.util.List;

public class LutLibraryPanel extends LinearLayout {

    public interface Callback {
        void onLutSelected(@NonNull LutPreset preset);
    }

    private Callback callback;

    public LutLibraryPanel(@NonNull Context context) {
        super(context);
        setOrientation(VERTICAL);
        rebuild();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        for (LutPreset preset : LutManager.builtInPresets()) {
            android.widget.TextView button = new android.widget.TextView(getContext());
            button.setText(preset.displayName);
            // A drawer list row — record 06 `.dr`: 46dp, single line, drawer ink (this panel is
            // drawer content, so it sits on the scrim). Padding was 24/18 raw PIXELS, which is a
            // different size on every screen density; it is dp now.
            button.setTextColor(Studio.DRAWER_INK);
            button.setTextSize(13f);
            float d = getResources().getDisplayMetrics().density;
            button.setPadding(Math.round(12 * d), 0, Math.round(12 * d), 0);
            button.setMinHeight(Math.round(46 * d));
            button.setGravity(android.view.Gravity.CENTER_VERTICAL);
            button.setSingleLine(true);
            button.setEllipsize(android.text.TextUtils.TruncateAt.END);
            com.fadcam.ui.faditor.tools.TextOverlayDrawer.Kit.pressable(button);
            button.setOnClickListener(v -> {
                if (callback != null) callback.onLutSelected(preset);
            });
            addView(button, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

    @NonNull
    public List<LutPreset> presets() {
        return LutManager.builtInPresets();
    }
}
