package com.fadcam.ui.faditor.ai.bridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.TooltipCompat;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.ai.joybot.IdeaEngine;

/**
 * "Connect your computer" (BOT-6): opens the bridge and shows how to point Claude Code, Codex or any
 * MCP client at it. The person's own words are the main path: "say this to Claude Code", which can add
 * the server itself; the exact commands sit under "Do it yourself". Nothing goes through us.
 */
public class BridgeActivity extends AppCompatActivity implements BridgeService.Watcher {

    public static final String EXTRA_PROJECT = "project_id";
    public static final String EXTRA_ROOM = "room";

    private LinearLayout col;
    @Nullable private String projectId;
    @NonNull private IdeaEngine.Room room = IdeaEngine.Room.LOBBY;
    private boolean showCommands;

    public static Intent intent(@NonNull Context c, @Nullable String projectId, @NonNull IdeaEngine.Room room) {
        return new Intent(c, BridgeActivity.class).putExtra(EXTRA_PROJECT, projectId).putExtra(EXTRA_ROOM, room.name());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Studio.GROUND);
        projectId = getIntent().getStringExtra(EXTRA_PROJECT);
        try {
            String r = getIntent().getStringExtra(EXTRA_ROOM);
            if (r != null) room = IdeaEngine.Room.valueOf(r);
        } catch (IllegalArgumentException ignored) { }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Studio.GROUND);
        scroll.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeService.watch(this);
    }

    @Override
    protected void onPause() {
        BridgeService.watch(null);
        super.onPause();
    }

    @Override
    public void changed(@NonNull BridgeService.State s) {
        if (isFinishing()) return;
        col.removeAllViews();
        header();
        if (!s.on) {
            off(s);
        } else {
            on(s);
        }
    }

    private void header() {
        TextView back = text("‹  Back", 15, Studio.INK_DIM, false);
        back.setPadding(0, 0, 0, dp(12));
        back.setOnClickListener(v -> finish());
        TooltipCompat.setTooltipText(back, "Back to Joybot");
        col.addView(back);
        col.addView(text("Connect your computer", 24, Studio.INK, true));
        TextView sub = text("Claude Code, Codex or any AI app on your computer gets Joybot's tools: your video, VisLab, "
                + "JoyBrush. Your phone and computer talk over your own Wi-Fi; nothing goes through us.", 14, Studio.INK_DIM, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        col.addView(sub);
    }

    private void off(@NonNull BridgeService.State s) {
        if (s.problem != null) {
            TextView p = text(s.problem, 14, Studio.CAREFUL, false);
            p.setPadding(0, 0, 0, dp(12));
            col.addView(p);
        }
        String addr = BridgeAddress.wifi(this);
        if (addr == null) {
            TextView w = text("Your phone isn't on Wi-Fi right now. Join the same Wi-Fi as your computer, or plug in a USB "
                    + "cable (see “Do it yourself” once it's open).", 14, Studio.CAREFUL, false);
            w.setPadding(0, 0, 0, dp(12));
            col.addView(w);
        }
        col.addView(goButton("Open the door", "Let your computer's AI use Joybot's tools until you tap Stop",
                v -> BridgeService.start(this, projectId, room)));
        TextView note = text("While it's open, a notification shows it, with Stop on it. Locked things stay locked, "
                + "deletes wait for your tap here, and every burst of changes is one Undo.", 13, Studio.INK_FAINT, false);
        note.setPadding(0, dp(14), 0, 0);
        col.addView(note);
    }

    private void on(@NonNull BridgeService.State s) {
        String addr = BridgeAddress.wifi(this);
        String host = addr == null ? "localhost" : addr;
        String url = "http://" + host + ":" + s.port + "/mcp";
        String say = BridgeWords.sayThis(url, s.code);

        // The QR and the code
        int size = Math.min(getResources().getDisplayMetrics().widthPixels - dp(80), dp(260));
        Bitmap qr = BridgeQr.draw(this, say, size, Studio.GROUND);
        if (qr != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            iv.setContentDescription("Pairing code as a QR picture");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            iv.setLayoutParams(lp);
            col.addView(iv);
        }
        TextView code = text(BridgeAuth.spaced(s.code), 34, Studio.GO, true);
        code.setGravity(Gravity.CENTER_HORIZONTAL);
        code.setLetterSpacing(0.08f);
        code.setPadding(0, dp(10), 0, 0);
        col.addView(code);
        TextView where = text(addr == null ? "Not on Wi-Fi: use a USB cable (below)" : "On your Wi-Fi at " + host + ":" + s.port,
                13, Studio.INK_FAINT, false);
        where.setGravity(Gravity.CENTER_HORIZONTAL);
        where.setPadding(0, 0, 0, dp(16));
        col.addView(where);

        // Status
        String status = s.connectedTo != null ? "✓ Connected: " + s.connectedTo + ". Ask it for anything, like “make the title red”."
                : "Waiting for your computer…";
        TextView st = text(status, 15, s.connectedTo != null ? Studio.GO : Studio.INK, true);
        st.setPadding(0, 0, 0, dp(12));
        col.addView(st);

        // The words to say
        col.addView(text("1. On your computer, open Claude Code (or Codex).", 14, Studio.INK, false));
        col.addView(text("2. Say this to it:", 14, Studio.INK, false));
        TextView box = text(say, 14, Studio.DRAWER_INK, false);
        box.setTextIsSelectable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.SUNK);
        bg.setCornerRadius(dp(12));
        box.setBackground(bg);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(6), 0, dp(6));
        box.setLayoutParams(blp);
        col.addView(box);
        LinearLayout row = new LinearLayout(this);
        row.addView(pill("Copy", "Copy these words", v -> copy(say)));
        row.addView(pill("Send to my computer", "Share these words (email, chat, Quick Share…)", v -> share(say)));
        col.addView(row);
        TextView three = text("3. When it says it's added, type /mcp in Claude Code (or restart Codex). Then ask it for anything.",
                14, Studio.INK, false);
        three.setPadding(0, dp(10), 0, dp(16));
        col.addView(three);

        // Do it yourself
        TextView more = text(showCommands ? "▾ Do it yourself" : "▸ Do it yourself", 14, Studio.INK_DIM, true);
        more.setOnClickListener(v -> { showCommands = !showCommands; changed(BridgeService.state()); });
        TooltipCompat.setTooltipText(more, "The exact commands, and the USB cable way");
        col.addView(more);
        if (showCommands) {
            for (String[] c : BridgeWords.commands(url, s.port, s.code)) {
                TextView h = text(c[0], 13, Studio.LABEL, true);
                h.setPadding(0, dp(12), 0, dp(4));
                col.addView(h);
                TextView cmd = text(c[1], 12, Studio.DRAWER_INK, false);
                cmd.setTypeface(Typeface.MONOSPACE);
                cmd.setTextIsSelectable(true);
                GradientDrawable cb = new GradientDrawable();
                cb.setColor(Studio.SUNK);
                cb.setCornerRadius(dp(8));
                cmd.setBackground(cb);
                cmd.setPadding(dp(10), dp(8), dp(10), dp(8));
                cmd.setOnLongClickListener(v -> { copy(c[1]); return true; });
                col.addView(cmd);
            }
        }

        // What happened
        if (!s.lines.isEmpty()) {
            TextView h = text("What happened", 13, Studio.LABEL, true);
            h.setPadding(0, dp(18), 0, dp(4));
            col.addView(h);
            for (int i = s.lines.size() - 1; i >= Math.max(0, s.lines.size() - 12); i--) {
                col.addView(text(s.lines.get(i), 13, Studio.INK_DIM, false));
            }
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(0, dp(22), 0, 0);
        bottom.addView(pill("Stop", "Close the door: your computer can't use Joybot's tools any more", v -> BridgeService.stop(this)));
        bottom.addView(pill("New code", "A new code; your computer will need the new words", v -> {
            BridgeService.forgetCode(this);
            BridgeService.stop(this);
            Toast.makeText(this, "Open the door again for the new code", Toast.LENGTH_SHORT).show();
        }));
        col.addView(bottom);
    }

    // ── small views ──

    @NonNull
    private TextView text(@NonNull String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    @NonNull
    private TextView pill(@NonNull String label, @NonNull String hint, @NonNull View.OnClickListener l) {
        TextView b = text(label, 14, Studio.INK, true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.PRESSED);
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMarginEnd(dp(8));
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        TooltipCompat.setTooltipText(b, hint);
        b.setContentDescription(label + ". " + hint);
        return b;
    }

    @NonNull
    private TextView goButton(@NonNull String label, @NonNull String hint, @NonNull View.OnClickListener l) {
        TextView b = text(label, 16, Studio.ON_GO, true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Studio.GO, Studio.GO_END});
        bg.setCornerRadius(dp(24));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        b.setOnClickListener(l);
        TooltipCompat.setTooltipText(b, hint);
        b.setContentDescription(label + ". " + hint);
        return b;
    }

    private void copy(@NonNull String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Joy Creator bridge", s));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void share(@NonNull String s) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s);
        try {
            startActivity(Intent.createChooser(send, "Send to your computer"));
        } catch (Exception e) {
            copy(s);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
