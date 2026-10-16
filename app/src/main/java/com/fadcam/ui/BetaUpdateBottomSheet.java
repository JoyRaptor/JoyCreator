package com.fadcam.ui;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.type.Type;

public class BetaUpdateBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_VERSION = "version";
    private static final String ARG_URL = "url";
    private static final String ARG_CURRENT = "current";

    public static BetaUpdateBottomSheet newInstance(String version, String url, String current) {
        BetaUpdateBottomSheet f = new BetaUpdateBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_VERSION, version);
        args.putString(ARG_URL, url);
        args.putString(ARG_CURRENT, current);
        f.setArguments(args);
        return f;
    }

    @NonNull @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        SheetKit.install(dialog, null);
        return dialog;
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_beta_update, container, false);

        String ver = getArguments() != null ? getArguments().getString(ARG_VERSION, "") : "";
        String url = getArguments() != null ? getArguments().getString(ARG_URL, "") : "";
        String cur = getArguments() != null ? getArguments().getString(ARG_CURRENT, "") : "";

        TextView tvVer = v.findViewById(R.id.tv_beta_sheet_version);
        if (tvVer != null) {
            String text = getString(R.string.legacyv2_update_versions, cur, ver);
            android.text.SpannableString sp = new android.text.SpannableString(text);
            int curEnd = Math.min(text.length(), ("v" + cur).length());
            sp.setSpan(new android.text.style.ForegroundColorSpan(Studio.INK_DIM), 0, curEnd, 0);
            sp.setSpan(new android.text.style.ForegroundColorSpan(Studio.GUIDE), curEnd, text.length(), 0);
            Type.mono(tvVer, Type.SEMIBOLD);
            SheetKit.label(tvVer, getString(R.string.legacyv2_update_versions_desc, cur, ver));
            tvVer.setText(sp);
        }

        Type.display((TextView) v.findViewById(R.id.tv_beta_sheet_title), Type.EXTRA);
        Type.body((TextView) v.findViewById(R.id.tv_beta_sheet_desc), Type.REGULAR);
        TextView btnGet = v.findViewById(R.id.btn_beta_get);
        TextView btnLater = v.findViewById(R.id.btn_beta_dismiss);
        Type.body(btnGet, Type.BOLD);
        Type.body(btnLater, Type.SEMIBOLD);
        SheetKit.label(btnGet, btnGet.getText());
        SheetKit.label(btnLater, btnLater.getText());
        if (btnGet != null) {
            btnGet.setOnClickListener(view -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                dismiss();
            });
        }

        btnLater.setOnClickListener(view -> dismiss());
        return v;
    }
}
