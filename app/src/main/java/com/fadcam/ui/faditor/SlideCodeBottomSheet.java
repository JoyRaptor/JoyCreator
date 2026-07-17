package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Code view/editor for an AI-authored slide (JoyRaptor 2026-07-16): double-tap a
 * slide in the preview to see its HTML, tweak it in place, or select-all and
 * paste a completely different slide from an external chatbot. Apply feeds the
 * same contract validation + re-render path as paste-import, on the SAME clip.
 */
public class SlideCodeBottomSheet extends BottomSheetDialogFragment {

    /** Receives the edited HTML when the user hits Apply. */
    public interface Callback {
        void onApply(@NonNull String html);
    }

    @Nullable private Callback callback;
    @Nullable private String initialHtml;
    @Nullable private EditText codeBox;

    public static SlideCodeBottomSheet newInstance() {
        return new SlideCodeBottomSheet();
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    public void setInitialHtml(@Nullable String html) {
        this.initialHtml = html;
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        });
        // The editor activity runs fullscreen-immersive; make sure this sheet's
        // window can actually take the IME — otherwise tapping the code box
        // never raises the keyboard.
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams
                    .SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams
                    .SOFT_INPUT_STATE_HIDDEN);
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (16 * dp), (int) (12 * dp),
                (int) (16 * dp), (int) (24 * dp));

        // TODO(strings)
        TextView title = new TextView(requireContext());
        title.setText("Slide code");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (4 * dp), (int) (8 * dp), (int) (4 * dp), (int) (2 * dp));
        root.addView(title);

        // TODO(strings)
        TextView subtitle = new TextView(requireContext());
        subtitle.setText("Edit the HTML, or select all and paste a different slide. "
                + "Apply re-renders the clip.");
        subtitle.setTextColor(0xFF999999);
        subtitle.setTextSize(13);
        subtitle.setPadding((int) (4 * dp), 0, (int) (4 * dp), (int) (10 * dp));
        root.addView(subtitle);

        codeBox = new EditText(requireContext());
        codeBox.setTypeface(Typeface.MONOSPACE);
        codeBox.setTextSize(12);
        codeBox.setTextColor(0xFFD8D8D8);
        codeBox.setBackgroundColor(0xFF14161B);
        codeBox.setPadding((int) (10 * dp), (int) (10 * dp),
                (int) (10 * dp), (int) (10 * dp));
        codeBox.setGravity(Gravity.TOP | Gravity.START);
        codeBox.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeBox.setHorizontallyScrolling(true);
        codeBox.setVerticalScrollBarEnabled(true);
        codeBox.setFocusableInTouchMode(true);
        codeBox.setOnClickListener(v -> {
            codeBox.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) requireContext()
                            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(codeBox,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        codeBox.setText(initialHtml != null ? initialHtml : "");
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (340 * dp));
        codeLp.bottomMargin = (int) (12 * dp);
        codeBox.setLayoutParams(codeLp);
        root.addView(codeBox);

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        // TODO(strings)
        buttons.addView(makeButton("Copy all", false, dp, () -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null && codeBox != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "Faditor slide HTML", codeBox.getText().toString()));
                // TODO(strings)
                Toast.makeText(requireContext(), "Slide code copied", Toast.LENGTH_SHORT).show();
            }
        }));
        // TODO(strings)
        buttons.addView(makeButton("Paste & replace", false, dp, () -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            CharSequence text = null;
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                text = cm.getPrimaryClip().getItemAt(0).coerceToText(requireContext());
            }
            if (text == null || text.toString().trim().isEmpty()) {
                // TODO(strings)
                Toast.makeText(requireContext(),
                        "Clipboard is empty — copy the new slide HTML first",
                        Toast.LENGTH_SHORT).show();
            } else if (codeBox != null) {
                codeBox.setText(text);
            }
        }));
        // TODO(strings)
        buttons.addView(makeButton("Apply", true, dp, () -> {
            if (callback != null && codeBox != null) {
                callback.onApply(codeBox.getText().toString());
            }
            dismiss();
        }));
        root.addView(buttons);

        androidx.core.widget.NestedScrollView scroll =
                new androidx.core.widget.NestedScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private View makeButton(String label, boolean primary, float dp,
                            @NonNull Runnable onClick) {
        TextView btn = new TextView(requireContext());
        btn.setText(label);
        btn.setTextSize(14);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(primary ? Color.BLACK : 0xFFCCCCCC);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding((int) (16 * dp), (int) (10 * dp),
                (int) (16 * dp), (int) (10 * dp));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(10 * dp);
        bg.setColor(primary ? 0xFF66BB6A : 0xFF2A2D33);
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) (8 * dp));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }
}
