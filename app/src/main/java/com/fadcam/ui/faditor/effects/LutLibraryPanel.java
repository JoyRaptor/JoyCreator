package com.fadcam.ui.faditor.effects;

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
            button.setTextColor(0xFFFFFFFF);
            button.setPadding(24, 18, 24, 18);
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
