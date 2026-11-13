package cc.joycreator.textlab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.ui.faditor.model.TextOverlayItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The Look row in Studio's text drawer (TEXT-1, Studio step): pick a TextLab look by its picture, then how its light
 * moves (Still, Sway, or Record tilt: the owner's rulings) and, for looks that burn, blow away, grow or shatter,
 * whether they play by themselves or are pinned. Every change is one undo step through the {@link Host}.
 *
 * <p>Built in code like the rest of the drawer, in the house style: old black, zinc greys, ON = solid cyan with the
 * words cut out, a hover label on every control.</p>
 */
public final class TextLookRow extends LinearLayout {

    private static final int CYAN = 0xFF22D3EE, GROUND = 0xFF000000, CONTROL = 0xFF1F1F26, INK = 0xFFD4D4D8,
            DIM = 0xFF8A8A96;

    /** What the row needs from the editor. */
    public interface Host extends LightTake.Host {
        /** Put {@code after} on the title (null = plain words) as one undo step named {@code label}. */
        void apply(@Nullable TitleLook before, @Nullable TitleLook after, @NonNull String label);

        /**
         * While armed: catch the next hold on the preview. {@code down} runs when the thumb lands, {@code up} when it
         * lifts (or the gesture is taken away). Pass nulls to stop catching.
         */
        void catchHold(@Nullable Runnable down, @Nullable Runnable up, @Nullable String hint);
    }

    private final TextOverlayItem item;
    private final Host host;
    private final LookChipRow chips;
    private final List<TextLookShelf.Item> shelf = new ArrayList<>();
    private final LinearLayout lightRow, progressRow;
    private final TextView still, sway, record, byItself, pinned, note;
    private final LightPad pad;
    private final SeekBar progress;
    private final LightTake take;
    private boolean armed;

    public TextLookRow(@NonNull Context c, @NonNull TextOverlayItem item, @NonNull Host host) {
        super(c);
        this.item = item;
        this.host = host;
        this.take = new LightTake(c);
        setOrientation(VERTICAL);
        setPadding(0, dp(4), 0, dp(4));

        TextView label = new TextView(c);
        label.setText("LOOK");
        label.setTextColor(DIM);
        label.setTextSize(11);
        label.setLetterSpacing(0.08f);
        addView(label);

        chips = new LookChipRow(c);
        chips.setPadding(0, dp(6), 0, dp(6));
        addView(chips, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        lightRow = row(c);
        lightRow.addView(small(c, "Light"));
        still = pill(c, "Still", "The light stays where you put it (drag the little pad)");
        sway = pill(c, "Sway", "The light drifts gently round where you put it");
        record = pill(c, "Record tilt", "Arm, then hold your thumb on the picture and tilt the phone: the light follows");
        lightRow.addView(still);
        lightRow.addView(sway);
        lightRow.addView(record);
        pad = new LightPad(c);
        LayoutParams plp = new LayoutParams(dp(64), dp(40));
        plp.setMarginStart(dp(8));
        lightRow.addView(pad, plp);
        addView(lightRow);

        progressRow = row(c);
        progressRow.addView(small(c, "Plays"));
        byItself = pill(c, "By itself", "Burns, blows away or shatters by itself from the title's start");
        pinned = pill(c, "Pinned", "Set how far along it is at the playhead (a key)");
        progressRow.addView(byItself);
        progressRow.addView(pinned);
        progress = new SeekBar(c);
        progress.setMax(1000);
        progressRow.addView(progress, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addView(progressRow);

        note = new TextView(c);
        note.setTextColor(DIM);
        note.setTextSize(11);
        addView(note);

        wire();
        loadShelf(c);
        refresh();
    }

    // ---- building -----------------------------------------------------------------------------------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        return r;
    }

    private TextView small(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(12);
        t.setMinWidth(dp(44));
        return t;
    }

    private TextView pill(Context c, String text, String hover) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(32));
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setContentDescription(hover);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) t.setTooltipText(hover);
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.setMarginEnd(dp(6));
        t.setLayoutParams(lp);
        paint(t, false);
        return t;
    }

    /** ON = solid cyan with the words cut out (ground-coloured); off = a zinc pill. */
    private void paint(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(on ? CYAN : CONTROL);
        t.setBackground(g);
        t.setTextColor(on ? GROUND : INK);
        t.setSelected(on);
    }

    private void loadShelf(Context c) {
        shelf.clear();
        shelf.addAll(TextLookShelf.builtIn(c));
        showChips();
        JoyFolderHome.io().execute(() -> {
            List<TextLookShelf.Item> more = TextLookShelf.inFolder(c);
            if (more.isEmpty()) return;
            post(() -> {
                shelf.addAll(more);
                showChips();
            });
        });
    }

    /** "Plain" first, then every look on the shelf; the title's own look chosen. */
    private void showChips() {
        List<LookChipRow.Chip> list = new ArrayList<>();
        list.add(new LookChipRow.Chip("Plain"));
        for (TextLookShelf.Item it : shelf) list.add(new LookChipRow.Chip(it.name));
        chips.setChips(list);
        for (int i = 0; i < shelf.size(); i++) {
            final int at = i + 1;
            JSONLook l = new JSONLook(shelf.get(i));
            TextLabPreviews.get(getContext()).chip(l.look, l.words, f -> chips.setSheet(at, f));
        }
        chips.choose(chosenIndex());
    }

    /** A shelf item's look and the words its chip shows. */
    private static final class JSONLook {
        final org.json.JSONObject look;
        final String words;

        JSONLook(TextLookShelf.Item it) {
            look = it.look;
            words = it.look.optString("sample", "TEXT");
        }
    }

    private int chosenIndex() {
        TitleLook t = item.getTextLook();
        if (t == null) return 0;
        for (int i = 0; i < shelf.size(); i++) if (shelf.get(i).key.equals(t.key)) return i + 1;
        return -1;   // a look from a pack no longer installed: the title keeps it, no chip lights
    }

    // ---- behaviour ----------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private void wire() {
        chips.setOnPick(i -> {
            TitleLook before = item.getTextLook() == null ? null : item.getTextLook().copy();
            TitleLook after = null;
            if (i > 0) {
                after = TitleLook.of(shelf.get(i - 1));
                if (before != null) {   // keep the light the person set; knobs belonged to the old look
                    after.light = before.light;
                    after.stillX = before.stillX;
                    after.stillY = before.stillY;
                    after.keys.copyFrom(before.keys);
                    after.keys.removeProperty(TitleLook.PROGRESS);
                }
            }
            disarm();
            host.apply(before, after, after == null ? "Plain text" : "Look: " + after.name);
            refresh();
        });
        still.setOnClickListener(v -> setLight(TitleLook.STILL));
        sway.setOnClickListener(v -> setLight(TitleLook.SWAY));
        record.setOnClickListener(v -> {
            if (armed) disarm();
            else arm();
            refresh();
        });
        pad.onMoved = (x, y, done) -> {
            TitleLook t = item.getTextLook();
            if (t == null) return;
            if (!done) {   // live while dragging; one undo step on release
                if (padBefore == null) padBefore = t.copy();
                t.stillX = x;
                t.stillY = y;
                host.changed();
                return;
            }
            TitleLook before = padBefore != null ? padBefore : t.copy();
            padBefore = null;
            t.stillX = x;
            t.stillY = y;
            host.apply(before, t.copy(), "Move the light");
        };
        byItself.setOnClickListener(v -> {
            TitleLook t = item.getTextLook();
            if (t == null || !t.progressPinned()) return;
            TitleLook before = t.copy(), after = t.copy();
            after.keys.removeProperty(TitleLook.PROGRESS);
            host.apply(before, after, "Plays by itself");
            refresh();
        });
        pinned.setOnClickListener(v -> pinAtPlayhead(progress.getProgress() / 1000f));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                TitleLook t = item.getTextLook();
                if (t == null) return;
                if (progressBefore == null) progressBefore = t.copy();
                t.pinProgress(host.playheadLocalMs(), p / 1000f);
                host.changed();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
                TitleLook t = item.getTextLook();
                if (t == null || progressBefore == null) return;
                host.apply(progressBefore, t.copy(), "Set how far along");
                progressBefore = null;
                refresh();
            }
        });
    }

    @Nullable private TitleLook padBefore, progressBefore;

    private void setLight(String mode) {
        TitleLook t = item.getTextLook();
        if (t == null) return;
        disarm();
        if (mode.equals(t.light)) {
            refresh();
            return;
        }
        TitleLook before = t.copy(), after = t.copy();
        after.light = mode;
        host.apply(before, after, mode.equals(TitleLook.STILL) ? "Light: still" : "Light: sway");
        refresh();
    }

    private void pinAtPlayhead(float value) {
        TitleLook t = item.getTextLook();
        if (t == null) return;
        TitleLook before = t.copy(), after = t.copy();
        after.pinProgress(host.playheadLocalMs(), value);
        host.apply(before, after, "Pin how far along");
        refresh();
    }

    private void arm() {
        if (item.getTextLook() == null || !take.available()) return;
        armed = true;
        host.catchHold(() -> {
            TitleLook t = item.getTextLook();
            if (t != null) take.start(t, host);
        }, () -> {
            take.stop();
            refresh();
        }, "Hold your thumb on the picture and tilt the phone to record the light. Lift to stop.");
    }

    private void disarm() {
        if (take.recording()) take.stop();
        if (!armed) return;
        armed = false;
        host.catchHold(null, null, null);
    }

    /** The drawer closed or moved on: never leave a take running or the preview catching holds. */
    @Override
    protected void onDetachedFromWindow() {
        disarm();
        super.onDetachedFromWindow();
    }

    /** Show the title's state. */
    public void refresh() {
        TitleLook t = item.getTextLook();
        boolean on = t != null;
        lightRow.setVisibility(on ? VISIBLE : GONE);
        progressRow.setVisibility(on && t.hasProgress() ? VISIBLE : GONE);
        if (on) {
            paint(still, TitleLook.STILL.equals(t.light) && !armed);
            paint(sway, TitleLook.SWAY.equals(t.light) && !armed);
            paint(record, armed || TitleLook.TAKE.equals(t.light));
            record.setText(armed ? "Armed: hold the picture" : TitleLook.TAKE.equals(t.light) ? "Recorded tilt" : "Record tilt");
            pad.set(t.stillX, t.stillY);
            pad.setVisibility(TitleLook.TAKE.equals(t.light) && !armed ? GONE : VISIBLE);
            boolean pin = t.progressPinned();
            paint(byItself, !pin);
            paint(pinned, pin);
            progress.setEnabled(true);
            progress.setProgress(Math.round(1000 * t.keys.valueAt(TitleLook.PROGRESS, host.playheadLocalMs(), 0f)));
        }
        record.setEnabled(take.available());
        note.setText(!on ? "" : !take.available() ? "This phone has no tilt sensor: Still and Sway only."
                : armed ? "Hold your thumb on the picture and tilt the phone. Lift to stop." : "");
        note.setVisibility(note.getText().length() > 0 ? VISIBLE : GONE);
    }

    // ---- the little pad where the light sits ---------------------------------------------------------

    /** A small pad: the dot is where the light is (the middle = straight on); drag it. */
    private static final class LightPad extends View {
        interface Moved {
            void moved(float x, float y, boolean done);
        }

        @Nullable Moved onMoved;
        private float x, y;
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        LightPad(Context c) {
            super(c);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, c.getResources().getDisplayMetrics()));
            frame.setColor(0xFF3F3F46);
            dot.setColor(CYAN);
            setContentDescription("Where the light is: drag the dot");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTooltipText("Where the light is: drag the dot");
        }

        void set(float x, float y) {
            this.x = x;
            this.y = y;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            float r = getHeight() * 0.12f;
            c.drawRoundRect(1, 1, getWidth() - 1, getHeight() - 1, r, r, frame);
            float cx = (x + 1) / 2 * getWidth(), cy = (1 - (y + 1) / 2) * getHeight();
            c.drawCircle(cx, cy, Math.max(4, getHeight() * 0.11f), dot);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float nx = Math.max(-1, Math.min(1, e.getX() / Math.max(1, getWidth()) * 2 - 1));
            float ny = Math.max(-1, Math.min(1, 1 - e.getY() / Math.max(1, getHeight()) * 2));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    set(nx, ny);
                    if (onMoved != null) onMoved.moved(nx, ny, false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    set(nx, ny);
                    if (onMoved != null) onMoved.moved(nx, ny, true);
                    return true;
                default:
                    return true;
            }
        }
    }
}
