package com.fadcam.ui.faditor.ai.joybot;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.TooltipCompat;

import com.fadcam.ui.faditor.Studio;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Find a model, star it, pick it: chat models and picture models in one searchable list.
 *
 * <p>Each row says what the model can do in plain badges — FREE, 👁 sees pictures, 🎨 makes
 * pictures — and has a star; starred models come first here and are the ones the chat's quick
 * switcher offers. Picking a picture model means "talk to it": each message makes a picture.</p>
 */
public final class ModelPicker {

    private ModelPicker() {}

    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor();

    public interface OnPick {
        /** A chat model was picked. */
        void chat(@NonNull ModelCatalog.Model m);
        /** A picture model was picked: messages now make pictures. */
        void pictures(@NonNull ModelCatalog.Model m);
    }

    public static void show(@NonNull Activity a, boolean startOnPictures, @NonNull OnPick onPick) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 16);
        root.setPadding(pad, dp(a, 8), pad, 0);

        // Chat | Pictures — a segmented choice; ON is solid cyan with the words cut out (house rule).
        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        TextView chatTab = tab(a, "Chat models", "Models that talk (and maybe look at pictures)");
        TextView picTab = tab(a, "Picture models", "Models that make pictures: talk to one and every message becomes a picture");
        tabs.addView(chatTab);
        tabs.addView(picTab);
        root.addView(tabs);

        EditText search = new EditText(a);
        search.setHint("Search: name, \"free\", \"vision\", \"image\"…");
        search.setSingleLine(true);
        search.setTextColor(Studio.INK);
        search.setHintTextColor(Studio.INK_FAINT);
        root.addView(search);

        TextView status = new TextView(a);
        status.setText("Loading the list from "
                + com.fadcam.ui.faditor.ai.AiAccounts.active(a).name + "…");
        status.setTextColor(Studio.INK_FAINT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(status);

        ListView list = new ListView(a);
        list.setDivider(null);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 420)));

        final List<ModelCatalog.Model> chat = new ArrayList<>();
        final List<ModelCatalog.Model> pics = new ArrayList<>();
        final boolean[] onPics = {startOnPictures};
        final Set<String> stars = ModelCatalog.stars(a);
        final List<ModelCatalog.Model> shown = new ArrayList<>();

        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                .setTitle("Choose a model")
                .setView(root)
                .setNegativeButton("Close", null)
                .create();

        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override public View getView(int i, View convert, ViewGroup parent) {
                ModelCatalog.Model m = shown.get(i);
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(a, 8), 0, dp(a, 8));

                TextView star = new TextView(a);
                boolean on = stars.contains(m.id);
                star.setText(on ? "★" : "☆");
                star.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                star.setTextColor(on ? Studio.CAREFUL : Studio.INK_FAINT);
                star.setPadding(0, 0, dp(a, 12), 0);
                TooltipCompat.setTooltipText(star, on ? "Unstar: take it out of your quick list"
                        : "Star: keep it in your quick list at the top of the chat");
                star.setOnClickListener(v -> {
                    boolean now = !stars.contains(m.id);
                    if (now) stars.add(m.id); else stars.remove(m.id);
                    ModelCatalog.setStar(a, m.id, now);
                    ModelCatalog.rememberName(a, m);
                    notifyDataSetChanged();
                });
                row.addView(star);

                LinearLayout words = new LinearLayout(a);
                words.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(a);
                name.setText(m.name);
                name.setTextColor(Studio.INK);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                words.addView(name);
                String b = m.badges();
                TextView meta = new TextView(a);
                meta.setText(b.isEmpty() ? m.id : b + "   " + m.id);
                meta.setTextColor(m.free ? Studio.GO : Studio.INK_FAINT);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                meta.setSingleLine(true);
                words.addView(meta);
                row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.setOnClickListener(v -> {
                    ModelCatalog.rememberName(a, m);
                    dialog.dismiss();
                    if (m.pictureModel) onPick.pictures(m); else onPick.chat(m);
                });
                TooltipCompat.setTooltipText(row, m.pictureModel
                        ? "Talk to " + m.name + ": each message makes a picture"
                        : "Chat with " + m.name);
                return row;
            }
        };
        list.setAdapter(adapter);

        Runnable refresh = () -> {
            shown.clear();
            shown.addAll(ModelCatalog.filter(onPics[0] ? pics : chat, search.getText().toString(), stars));
            paintTab(chatTab, !onPics[0]);
            paintTab(picTab, onPics[0]);
            adapter.notifyDataSetChanged();
            if (!chat.isEmpty() || !pics.isEmpty()) {
                status.setText(shown.size() + " models" + (onPics[0] ? " that make pictures (each costs a little)" : ""));
            }
        };
        chatTab.setOnClickListener(v -> { onPics[0] = false; refresh.run(); });
        picTab.setOnClickListener(v -> { onPics[0] = true; refresh.run(); });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { refresh.run(); }
        });
        refresh.run();
        dialog.show();

        IO.execute(() -> {
            List<ModelCatalog.Model> c = ModelCatalog.chatModels(a);
            List<ModelCatalog.Model> p = ModelCatalog.pictureModels(a);
            a.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                chat.clear(); chat.addAll(c);
                pics.clear(); pics.addAll(p);
                if (com.fadcam.ui.faditor.ai.AiService.isCustom(a) && c.isEmpty()) {
                    status.setText("Couldn't load this account's models (offline, or the key was refused?). "
                            + "You can still type a model name in ⚙.");
                } else if (c.size() <= 1) {
                    status.setText("Couldn't load the full list (offline?) — showing what I know.");
                }
                refresh.run();
            });
        });
    }

    private static TextView tab(@NonNull Activity a, String text, String hint) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(a, 6));
        t.setLayoutParams(lp);
        TooltipCompat.setTooltipText(t, hint);
        return t;
    }

    private static void paintTab(TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(t.getContext(), 18));
        bg.setColor(on ? Studio.ARMED : Studio.RAISED);
        t.setBackground(bg);
        t.setTextColor(on ? Studio.GROUND : Studio.INK_DIM);
    }

    private static int dp(@NonNull android.content.Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
