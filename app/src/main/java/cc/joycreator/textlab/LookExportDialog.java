package cc.joycreator.textlab;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.Locale;

/**
 * TextLab's quick-export dialog: what to make (a picture, a video, a moving sheet), its shape and size, and for
 * a picture or sheet whether it keeps the look's stage or is see-through; for a video, how long. The choices are
 * remembered. Export turns the dialog into its progress, with Stop. House style: old black, zinc greys, the
 * chosen option solid cyan with its words cut out.
 */
final class LookExportDialog {

    private static final int BLACK = 0xFF0B0B0C, PANEL = 0xFF141417, CONTROL = 0xFF1F1F26, ZINC = 0xFFD4D4D8,
            ZINC_DIM = 0xFF8A8A96, CYAN = 0xFF22D3EE;
    private static final String PREFS = "textlab_export";

    private static final String[] TYPES = {"Picture", "Video", "Sheet"};
    private static final String[] TYPE_TIPS = {"A still PNG of the look", "An MP4 from the letters arriving",
            "A moving sprite sheet, 5 × 4: two seconds at 10 frames a second, for Studio and Sprite Lab"};
    private static final String[] SHAPES = {"Wide", "Square", "Tall"};
    private static final String[] SHAPE_TIPS = {"16:9, like a video", "1:1, for posts", "9:16, for phone stories"};
    private static final String[] SIZES = {"720", "1080", "4K"};
    private static final int[] SHORT_SIDE = {720, 1080, 2160};
    private static final String[] CELLS = {"Small", "Medium", "Large"};
    private static final int[] CELL_SHORT = {144, 270, 540};
    private static final String[] BACKS = {"Stage", "See-through"};
    private static final String[] BACK_TIPS = {"The look's own background", "Only the words, transparent round them"};
    private static final String[] LENGTHS = {"3 s", "5 s", "10 s"};
    private static final double[] SECONDS = {3, 5, 10};

    private final Activity a;
    private final JSONObject look;
    private final String words;
    private final Runnable before, after;
    private final SharedPreferences prefs;
    private int type, shape, size, back, length;
    private android.app.Dialog dialog;
    private LinearLayout body;
    private LookExport running;

    /** {@code before} runs when an export starts (pause the live picture), {@code after} when it ends. */
    static void show(@NonNull Activity a, @NonNull JSONObject look, @NonNull String words,
                     @NonNull Runnable before, @NonNull Runnable after) {
        new LookExportDialog(a, look, words, before, after).open();
    }

    private LookExportDialog(Activity a, JSONObject look, String words, Runnable before, Runnable after) {
        this.a = a;
        this.look = look;
        this.words = words;
        this.before = before;
        this.after = after;
        prefs = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        type = prefs.getInt("type", 0);
        shape = prefs.getInt("shape", 0);
        size = prefs.getInt("size", 1);
        back = prefs.getInt("back", 0);
        length = prefs.getInt("length", 1);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    private void open() {
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(18), dp(20), dp(12));
        body.setBackground(rounded(PANEL, 16));
        build();
        // a plain dialog: the screen's old black theme would put a grey frame round an AlertDialog
        dialog = new android.app.Dialog(a);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(body, new ViewGroup.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            dialog.getWindow().setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(d -> {
            if (running != null) running.stop();
        });
        dialog.show();
    }

    // ---- choosing ---------------------------------------------------------------------------------

    private void build() {
        body.removeAllViews();
        TextView title = text("Export", 20, ZINC);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        body.addView(title);
        row("Type", TYPES, TYPE_TIPS, type, i -> { type = i; build(); });
        row("Shape", SHAPES, SHAPE_TIPS, shape, i -> { shape = i; build(); });
        boolean sheet = type == 2;
        row(sheet ? "Frame size" : "Size", sheet ? CELLS : SIZES, null, size, i -> { size = i; build(); });
        if (type == 1) row("Length", LENGTHS, null, length, i -> { length = i; build(); });
        else row("Background", BACKS, BACK_TIPS, back, i -> { back = i; build(); });
        TextView summary = text(describe(), 13, ZINC_DIM);
        summary.setPadding(0, dp(14), 0, dp(6));
        body.addView(summary);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.addView(button("Cancel", CONTROL, ZINC, "Close without exporting", v -> dialog.dismiss()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        Button go = button("Export", CYAN, BLACK, "Make it and put it in the Joy Creator folder's exports", v -> go());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMarginStart(dp(8));
        buttons.addView(go, lp);
        body.addView(buttons);
    }

    private interface Pick {
        void at(int i);
    }

    private void row(String label, String[] options, String[] tips, int chosen, Pick pick) {
        TextView l = text(label.toUpperCase(Locale.ROOT), 11, ZINC_DIM);
        l.setLetterSpacing(0.1f);
        l.setPadding(0, dp(14), 0, dp(6));
        body.addView(l);
        LinearLayout r = new LinearLayout(a);
        for (int i = 0; i < options.length; i++) {
            final int at = i;
            boolean on = i == chosen;
            Button b = button(options[i], on ? CYAN : CONTROL, on ? BLACK : ZINC,
                    tips != null ? tips[i] : label + ": " + options[i], v -> pick.at(at));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
            if (i > 0) lp.setMarginStart(dp(6));
            r.addView(b, lp);
        }
        body.addView(r);
    }

    private int[] dims() {
        int s = type == 2 ? CELL_SHORT[size] : SHORT_SIDE[size];
        int l = s * 16 / 9;
        return shape == 0 ? new int[]{l, s} : shape == 1 ? new int[]{s, s} : new int[]{s, l};
    }

    @NonNull
    private String describe() {
        int[] d = dims();
        String px = d[0] + " × " + d[1];
        switch (type) {
            case 1:
                return "MP4 video, " + px + ", " + LENGTHS[length] + " at 30 frames a second, silent.";
            case 2:
                return "Sprite sheet " + d[0] * LookPreview.COLS + " × " + d[1] * LookPreview.ROWS + ": 20 frames of "
                        + px + (back == 1 ? ", see-through." : ".");
            default:
                return "PNG picture, " + px + (back == 1 ? ", see-through." : ".");
        }
    }

    // ---- making it --------------------------------------------------------------------------------

    private void go() {
        prefs.edit().putInt("type", type).putInt("shape", shape).putInt("size", size).putInt("back", back)
                .putInt("length", length).apply();
        int[] d = dims();
        LookExport.Kind kind = type == 1 ? LookExport.Kind.VIDEO : type == 2 ? LookExport.Kind.SHEET : LookExport.Kind.PICTURE;
        LookExport.Job job = new LookExport.Job(look, words, kind, d[0], d[1], back == 1, SECONDS[length]);

        body.removeAllViews();
        String[] what = {"picture", "video", "moving sheet"};
        TextView title = text("Making the " + what[type] + "…", 18, ZINC);
        body.addView(title);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        bp.topMargin = dp(16);
        body.addView(bar, bp);
        TextView line = text(describe(), 13, ZINC_DIM);
        line.setPadding(0, dp(10), 0, dp(6));
        body.addView(line);
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        buttons.addView(button("Stop", CONTROL, ZINC, "Stop; nothing is saved", v -> dialog.dismiss()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        body.addView(buttons);
        dialog.setCanceledOnTouchOutside(false);

        before.run();
        running = LookExport.start(a, job, new LookExport.Listener() {
            @Override
            public void progress(float done) {
                bar.setProgress(Math.round(done * 1000));
            }

            @Override
            public void done(String where, String problem) {
                running = null;
                after.run();
                if (dialog.isShowing()) dialog.dismiss();
                if (where != null) Toast.makeText(a, "Exported: " + where, Toast.LENGTH_LONG).show();
                else if (problem != null) Toast.makeText(a, problem, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- house pieces -----------------------------------------------------------------------------

    private TextView text(String s, float sp, int colour) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(colour);
        return t;
    }

    private Button button(String s, int fill, int ink, String tip, View.OnClickListener click) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(ink);
        b.setBackground(rounded(fill, 999));
        b.setMinWidth(0);
        b.setMinimumWidth(dp(72));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setContentDescription(tip);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTooltipText(tip);
        b.setOnClickListener(click);
        return b;
    }

    private GradientDrawable rounded(int colour, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(radiusDp >= 999 ? 999 : dp(radiusDp));
        return g;
    }
}
