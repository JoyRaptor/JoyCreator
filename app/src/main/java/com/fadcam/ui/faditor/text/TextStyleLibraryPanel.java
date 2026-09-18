package com.fadcam.ui.faditor.text;

import android.content.Context;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import java.util.List;

public class TextStyleLibraryPanel extends LinearLayout {

    public interface Callback {
        void onTextStyleSelected(@NonNull TextStyle style);
    }

    private Callback callback;

    public TextStyleLibraryPanel(@NonNull Context context) {
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
        for (TextStyle style : TextStyleIO.loadBuiltIns(getContext())) {
            android.widget.TextView button = new android.widget.TextView(getContext());
            button.setText(style.getName());
            button.setTextColor(0xFFF4F4F5);
            button.setPadding(24, 18, 24, 18);
            button.setOnClickListener(v -> {
                if (callback != null) callback.onTextStyleSelected(style);
            });
            addView(button, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

    @NonNull
    public List<TextStyle> styles() {
        return TextStyleIO.loadBuiltIns(getContext());
    }
}
