package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.perform.Inputs;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The performer's panel (spec A7): the take bar (Perform only), the head pad, Hold to talk, Blink, moods, the
 * character's buttons and the Breeze. Motion shows the same hands without the take bar: the character alive with
 * nothing recorded. Everything writes into {@link Inputs}; the picture follows through the solver when it lands.
 */
public final class PerformPanel extends ScrollView {

    public interface Host {
        Inputs inputs();

        /** Red dot: starts a pass, or ends it (the room keeps the take as one undo step). */
        void toggleRecord();

        void togglePlay();

        void takeBack();

        void clearTake();

        /** Face: take the face now as neutral (look at the screen, relaxed). */
        default void recentreFace() {
        }

        default boolean faceMirror() {
            return false;
        }

        /** Face: on = move like your reflection; off = like someone facing you. */
        default void setFaceMirror(boolean on) {
        }

        /** Check directions: five short moves that settle which way is which on this phone. */
        default void checkDirections() {
        }

        /** Shoulders (SHRUG-1): the camera can read them and a shrug moves something on this character. */
        default boolean shouldersShown() {
            return false;
        }

        /** Shoulders ticked: the camera reads the body too (this phone's setting). */
        default boolean shouldersOn() {
            return false;
        }

        default void setShouldersOn(boolean on) {
        }

        /** The small private preview of the face (never kept): the view to give the camera, or null. */
        default View facePreview() {
            return null;
        }

        /** The joystick for the character in hand (null: none). */
        default cc.joycreator.avatar.perform.Stick stick() {
            return null;
        }

        default boolean stickOpen() {
            return false;
        }

        default void setStickOpen(boolean on) {
        }

        /** The gestures sheet from the pad's corner button. */
        default void openStickGestures() {
        }

        /** Walk with your face: its chip and tick boxes, or null (the character does not walk). */
        default View faceWalkDrawer() {
            return null;
        }

        /** The part in hand (a node of the character), or null; Motion shows how it moves. */
        CharacterDoc.Node inHandNode();

        PoseRenderer renderer();

        MotionSections.Host motion();

        /** The Mic chip: lip-sync to the microphone (asks for the permission the first time). */
        void toggleMic();

        /** The Face chip: the face camera drives head, eyes, brows and mouth (asks for the camera the first time). */
        void toggleFace();

        /** A character setting (blinks, breathing ...) as one named undo step. */
        void setSetting(String key, boolean on, String stepName);
    }

    private final Host host;
    private final float dp;
    private final LinearLayout column;
    private HeadPad pad;
    private StickPad stickPad;
    private TextView stickChip;
    private TextView rec, play, back, clear;
    private TakeStrip strip;
    private final List<TextView> buttonChips = new ArrayList<>();
    private final List<String> buttonIds = new ArrayList<>();
    private final List<TextView> moodChips = new ArrayList<>();
    private final List<String> moodNames = new ArrayList<>();
    private TextView micChip, faceChip, recentreChip, mirrorChip;
    private boolean compact;
    private LinearLayout faceRow, shoulderBox;
    private int faceWords = R.string.avatar_face;
    private final List<TextView> tickBoxes = new ArrayList<>();
    private final List<String[]> tickKeys = new ArrayList<>();   // {settings key, "t" when a missing key means on}

    public PerformPanel(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(12), px(6), px(12), px(16));
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private Boolean boundTakeBar;                                   // the stage last built (Perform or Motion)

    /**
     * Builds the controls this character has. {@code takeBar}: Perform shows it, Motion does not. A stage opens at the top of
     * its panel (the face session, 11-27: Perform kept Motion's scroll and hid the head pad and its buttons); built again for
     * the same stage (a tick, a step) the panel stays where you are.
     */
    public void bind(CharacterDoc doc, boolean takeBar) {
        boolean newStage = boundTakeBar == null || boundTakeBar != takeBar;
        boundTakeBar = takeBar;
        column.removeAllViews();
        buttonChips.clear();
        buttonIds.clear();
        tickBoxes.clear();
        tickKeys.clear();
        moodChips.clear();
        moodNames.clear();
        Inputs in = host.inputs();
        Context c = getContext();

        if (takeBar) column.addView(takeBar(c));
        else MotionSections.add(c, column, doc, host.inHandNode(), host.renderer(), host.motion());

        // the head pad, and Hold to talk and Blink beside it
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, px(8), 0, px(8));
        pad = new HeadPad(c);
        pad.setAngles(headAngles(doc));
        pad.setListener(new HeadPad.Listener() {
            @Override
            public void head(double x, double y) {
                in.head(x, y);
            }

            @Override
            public void released() {
                in.releaseHead();
            }
        });
        TooltipCompat.setTooltipText(pad, c.getString(R.string.avatar_hover_head_pad));
        pad.setContentDescription(c.getString(R.string.avatar_hover_head_pad));
        row.addView(pad);
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        compact = host.stick() != null && host.stickOpen();                 // two pads share the width: the middle column steps in, as the pilot's
        side.setPadding(px(compact ? 6 : 12), 0, 0, 0);
        side.addView(holdButton(c, c.getString(R.string.avatar_hold_to_talk), c.getString(R.string.avatar_hover_hold_to_talk), new Hold() {
            @Override
            public void down() {
                in.talk(true);
            }

            @Override
            public void up() {
                in.talk(false);
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40));
        bl.topMargin = px(8);
        side.addView(holdButton(c, c.getString(R.string.avatar_blink), c.getString(R.string.avatar_hover_blink), new Hold() {
            @Override
            public void down() {
                in.blinkNow(System.currentTimeMillis());
                in.blinkHold(true);
            }

            @Override
            public void up() {
                in.blinkHold(false);
            }
        }), bl);
        if (doc != null && doc.json.toString().contains("\"face.cheekPuff\"")) {   // (only where a part follows the cheek puff: no dead button)
            side.addView(holdButton(c, c.getString(R.string.avatar_puff), c.getString(R.string.avatar_hover_puff), new Hold() {
                @Override
                public void down() {
                    in.puff(true);
                }

                @Override
                public void up() {
                    in.puff(false);
                }
            }), new LinearLayout.LayoutParams(bl));
        }
        micChip = chip(c, c.getString(R.string.avatar_mic), c.getString(R.string.avatar_hover_mic), v -> host.toggleMic());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36));
        ml.topMargin = px(8);
        HorizontalScrollView chipScroll = new HorizontalScrollView(c);                     // Mic, Face and Joystick in one row, as the pilot has them
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(c);
        chipScroll.addView(chipRow);
        side.addView(chipScroll, ml);
        chipRow.addView(micChip);
        faceChip = chip(c, c.getString(R.string.avatar_face), c.getString(R.string.avatar_hover_face_cycle), v -> host.toggleFace());
        faceWords = R.string.avatar_face;                               // (a new chip: its words follow the mode from the next paint)
        chipRow.addView(faceChip);
        if (host.stick() != null) {
            stickChip = chip(c, c.getString(R.string.avatar_joystick), c.getString(R.string.avatar_hover_joystick), v -> {
                host.setStickOpen(!host.stickOpen());
                bind(doc, takeBar);
            });
            paintChip(stickChip, host.stickOpen());
            if (compact) {                                                   // Mic, Face and Joystick become pictures only (their words are in their hover labels)
                chipRow.addView(stickChip, 0);
                micChip.setTag(R.drawable.ic_avatar_mic);
                faceChip.setTag(R.drawable.ic_avatar_face);
                stickChip.setTag(R.drawable.ic_avatar_joystick);
                micChip.setText("");
                faceChip.setText("");
                stickChip.setText("");
                paintChip(micChip, false);
                paintChip(faceChip, false);
                paintChip(stickChip, true);
                for (int i = 0; i < chipRow.getChildCount(); i++) {              // three pictures fit the middle column: 18 dp each, close together
                    View ch = chipRow.getChildAt(i);
                    ch.setPadding(px(7), 0, px(7), 0);
                    ((LinearLayout.LayoutParams) ch.getLayoutParams()).rightMargin = px(3);
                }
            } else chipRow.addView(stickChip);
        }
        // the face's own controls, there only while the camera is on: a private preview, Re-centre, Mirror
        faceRow = new LinearLayout(c);
        faceRow.setGravity(Gravity.CENTER_VERTICAL);
        faceRow.setVisibility(View.GONE);
        View preview = host.facePreview();
        if (preview != null) {
            if (preview.getParent() instanceof ViewGroup) ((ViewGroup) preview.getParent()).removeView(preview);
            faceRow.addView(preview, new LinearLayout.LayoutParams(px(72), px(54)));
        }
        recentreChip = chip(c, c.getString(R.string.avatar_recentre), c.getString(R.string.avatar_hover_recentre), v -> host.recentreFace());
        mirrorChip = chip(c, c.getString(R.string.avatar_mirror), c.getString(R.string.avatar_hover_mirror), v -> {
            host.setFaceMirror(!host.faceMirror());
            paintChips();
        });
        LinearLayout faceChips = new LinearLayout(c);
        faceChips.setOrientation(LinearLayout.VERTICAL);
        faceChips.setPadding(px(8), 0, 0, 0);
        faceChips.addView(recentreChip);
        faceChips.addView(mirrorChip);
        faceChips.addView(chip(c, c.getString(R.string.avatar_face_dirs), c.getString(R.string.avatar_hover_face_dirs), v -> host.checkDirections()));
        faceRow.addView(faceChips);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = px(6);
        side.addView(faceRow, fl);
        // SHRUG-1: Shoulders (the pilot's tick box beside Mirror) and, while on, how little shrug makes a full one
        shoulderBox = null;
        if (host.shouldersShown()) {
            shoulderBox = new LinearLayout(c);
            shoulderBox.setOrientation(LinearLayout.VERTICAL);
            shoulderBox.setVisibility(View.GONE);
            boolean shOn = host.shouldersOn();
            shoulderBox.addView(WalkSections.tick(c, c.getString(R.string.avatar_shoulders), shOn, c.getString(R.string.avatar_hover_shoulders), dp, v -> {
                host.setShouldersOn(v);
                bind(doc, takeBar);
            }));
            if (shOn && host instanceof ShrugSections.Host) shoulderBox.addView(ShrugSections.sensitivity(c, (ShrugSections.Host) host, dp));
            side.addView(shoulderBox);
        }
        View walkWithFace = host.faceWalkDrawer();
        if (walkWithFace != null) {
            if (walkWithFace.getParent() instanceof ViewGroup) ((ViewGroup) walkWithFace.getParent()).removeView(walkWithFace);
            side.addView(walkWithFace);
        }
        row.addView(side, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stickPad = null;
        if (host.stick() != null && host.stickOpen()) {                      // the second pad, for the other thumb
            stickPad = new StickPad(c, host.stick(), host::openStickGestures);
            TooltipCompat.setTooltipText(stickPad, c.getString(R.string.avatar_hover_stick_pad));
            stickPad.setContentDescription(c.getString(R.string.avatar_hover_stick_pad));
            row.addView(stickPad);
        }
        column.addView(row);

        // moods: only for a character that has eyelids
        if (hasRole(doc, "lidU") || hasRole(doc, "lidL")) {
            column.addView(label(c.getString(R.string.avatar_moods)));
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(c);
            for (String m : Inputs.MOODS.keySet()) {
                TextView t = chip(c, m, c.getString(R.string.avatar_hover_mood, m.toLowerCase(Locale.ROOT)), v -> {
                    in.mood(m, System.currentTimeMillis());
                    paintChips();
                });
                moodChips.add(t);
                moodNames.add(m);
                chips.addView(t);
            }
            hs.addView(chips);
            column.addView(hs);
        }

        // the character's buttons
        JSONArray triggers = doc.json.optJSONArray("triggers");
        if (triggers != null && triggers.length() > 0) {
            column.addView(label(c.getString(R.string.avatar_buttons)));
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(c);
            for (int i = 0; i < triggers.length(); i++) {
                JSONObject t = triggers.optJSONObject(i);
                if (t == null) continue;
                final String id = t.optString("id"), name = t.optString("name", id);
                TextView chip = chip(c, name, c.getString(R.string.avatar_hover_button, name), v -> {
                    in.toggleButton(id);
                    paintChips();
                });
                buttonChips.add(chip);
                buttonIds.add(id);
                chips.addView(chip);
            }
            hs.addView(chips);
            column.addView(hs);
        }

        // the Breeze: for anything that swings
        if (hasKey(doc, "swing")) {
            final TextView weather = label(c.getString(R.string.avatar_breeze) + "  " + c.getString(R.string.avatar_weather_still));
            column.addView(weather);
            SeekBar sb = new SeekBar(c);
            sb.setMax(200);
            sb.setProgress(100);
            sb.getProgressDrawable().setTint(Studio.ARMED);
            sb.getThumb().setTint(Studio.ARMED);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user) in.breeze((p - 100) / 100.0, System.currentTimeMillis());
                    int w = Math.round((p - 100) * 0.4f);                              // the readout: still, ← n or n →
                    weather.setText(c.getString(R.string.avatar_breeze) + "  " + (w == 0 ? c.getString(R.string.avatar_weather_still) : w < 0 ? "← " + (-w) : w + " →"));
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            TooltipCompat.setTooltipText(sb, c.getString(R.string.avatar_hover_breeze));
            sb.setContentDescription(c.getString(R.string.avatar_hover_breeze));
            column.addView(sb);
        }
        LinearLayout letGo = new LinearLayout(c);
        letGo.setPadding(0, px(10), 0, 0);
        letGo.addView(chip(c, c.getString(R.string.avatar_let_go), c.getString(R.string.avatar_hover_let_go), v -> {
            in.letGoOfEverything();
            paintChips();
        }));
        column.addView(letGo);
        // alive by itself: the character's own habits (tick boxes: on or off, never two equals)
        column.addView(label(c.getString(R.string.avatar_alive)));
        column.addView(tickRow(c, "blinkAuto", true, R.string.avatar_blinks, R.string.avatar_hover_blinks));
        column.addView(tickRow(c, "breathe", true, R.string.avatar_breathes, R.string.avatar_hover_breathes));
        column.addView(tickRow(c, "idle", true, R.string.avatar_never_still, R.string.avatar_hover_never_still));
        if (!"front".equals(doc.json.optJSONObject("settings") == null ? "front" : doc.json.optJSONObject("settings").optString("facing", "front")))
            column.addView(tickRow(c, "autoFlip", false, R.string.avatar_turns_around, R.string.avatar_hover_turns_around));
        refreshSettings(doc);
        paintChips();
        if (newStage) post(() -> scrollTo(0, 0));
    }

    /** Reads the habits' tick boxes from the character (after an undo or redo changed them). */
    public void refreshSettings(CharacterDoc doc) {
        JSONObject st = doc.json.optJSONObject("settings");
        for (int i = 0; i < tickBoxes.size(); i++) {
            String key = tickKeys.get(i)[0];
            boolean def = "t".equals(tickKeys.get(i)[1]);
            boolean on = st == null || !st.has(key) ? def : st.optBoolean(key, def);
            paintTick(tickBoxes.get(i), on);
        }
    }

    private View tickRow(Context c, String key, boolean defaultOn, int label, int hover) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(40));
        TextView box = new TextView(c);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(14);
        row.addView(box, new LinearLayout.LayoutParams(px(24), px(24)));
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(0xFFD4D4D8);
        t.setPadding(px(10), 0, 0, 0);
        row.addView(t);
        tickBoxes.add(box);
        tickKeys.add(new String[]{key, defaultOn ? "t" : "f"});
        row.setOnClickListener(v -> {
            boolean now = !"\u2713".contentEquals(box.getText());
            host.setSetting(key, now, c.getString(now ? R.string.avatar_step_on : R.string.avatar_step_off, c.getString(label)));
        });
        TooltipCompat.setTooltipText(row, c.getString(hover));
        row.setContentDescription(c.getString(hover));
        return row;
    }

    /** A tick box: ON = solid cyan with the tick cut out of it; off = a grey outline. */
    private void paintTick(TextView box, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(6));
        if (on) g.setColor(Studio.ARMED);
        else g.setStroke(Math.max(1, px(1.5f)), 0xFF52525B);
        box.setBackground(g);
        box.setText(on ? "\u2713" : "");
        box.setTextColor(Studio.GROUND);
    }

    // ── per frame ─────────────────────────────────────────────────────────────────────────────────

    /** Called each frame: the strip, the pad's knob following a take, the buttons as they now are. */
    public void tick() {
        Inputs in = host.inputs();
        if (strip != null) strip.invalidate();
        if (stickPad != null) stickPad.tick();
        if (rec != null) paintTakeBar();
        paintChips();
    }

    /** Where the head is now (from the signals), so the pad's knob follows a take that is playing. */
    public void showHead(double x, double y) {
        if (pad != null) pad.setHead(x, y);
    }

    private void paintChips() {
        Inputs in = host.inputs();
        for (int i = 0; i < buttonChips.size(); i++) paintChip(buttonChips.get(i), in.isOn(buttonIds.get(i)));
        for (int i = 0; i < moodChips.size(); i++) paintChip(moodChips.get(i), moodNames.get(i).equals(in.moodName()));
        if (micChip != null) paintChip(micChip, in.micOn());
        if (faceChip != null) {
            paintChip(faceChip, in.faceOn());
            int words = !in.faceOn() ? R.string.avatar_face : in.faceDrive().mode() == cc.joycreator.avatar.face.FaceDrive.Mode.TALK
                    ? R.string.avatar_face_talk : R.string.avatar_face_always;
            if (words != faceWords && !compact) {
                faceWords = words;
                faceChip.setText(words);
            }
        }
        if (faceRow != null) faceRow.setVisibility(in.faceOn() ? View.VISIBLE : View.GONE);
        if (shoulderBox != null) shoulderBox.setVisibility(in.faceOn() ? View.VISIBLE : View.GONE);
        if (mirrorChip != null) paintChip(mirrorChip, host.faceMirror());
    }

    // ── the take bar ──────────────────────────────────────────────────────────────────────────────

    private View takeBar(Context c) {
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        rec = round(c, "●", c.getString(R.string.avatar_hover_record));
        rec.setOnClickListener(v -> host.toggleRecord());
        bar.addView(rec, new LinearLayout.LayoutParams(px(40), px(40)));
        strip = new TakeStrip(c);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, px(40), 1f);
        sl.leftMargin = px(8);
        sl.rightMargin = px(8);
        bar.addView(strip, sl);
        play = round(c, "▶", c.getString(R.string.avatar_hover_play));
        play.setOnClickListener(v -> host.togglePlay());
        bar.addView(play, new LinearLayout.LayoutParams(px(40), px(40)));
        back = round(c, "↺", c.getString(R.string.avatar_hover_take_back));
        back.setOnClickListener(v -> host.takeBack());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(px(40), px(40));
        gap.leftMargin = px(6);
        bar.addView(back, gap);
        clear = round(c, "✕", c.getString(R.string.avatar_hover_clear_take));
        clear.setOnClickListener(v -> host.clearTake());
        bar.addView(clear, gap);
        paintTakeBar();
        return bar;
    }

    private void paintTakeBar() {
        Inputs in = host.inputs();
        boolean recording = in.recorder.recording(), playing = in.recorder.playing() && !recording;
        boolean hasTake = in.recorder.take() != null && in.recorder.take().n > 0;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(recording ? Studio.LIVE : 0xFF27272A);
        rec.setBackground(g);
        rec.setTextColor(recording ? Studio.GROUND : Studio.LIVE);
        paintRound(play, playing);
        play.setText(playing ? "■" : "▶");
        play.setAlpha(hasTake || playing ? 1f : 0.35f);
        back.setAlpha(!recording && in.recorder.hasPassToTakeBack() ? 1f : 0.35f);
        clear.setAlpha(hasTake ? 1f : 0.35f);
    }

    private void paintRound(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        t.setBackground(g);
        t.setTextColor(on ? Studio.GROUND : 0xFFE4E4E7);
    }

    /** The take strip: drag to move through the take (silent), the time and length written on it. */
    private final class TakeStrip extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        TakeStrip(Context c) {
            super(c);
            setContentDescription(c.getString(R.string.avatar_hover_strip));
            TooltipCompat.setTooltipText(this, c.getString(R.string.avatar_hover_strip));
        }

        @Override
        protected void onDraw(Canvas c) {
            Inputs in = host.inputs();
            float w = getWidth(), h = getHeight(), r = 10 * dp;
            p.setColor(0xFF18181B);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            double dur = in.recorder.duration(), now = in.recorder.clock();
            double total = Math.max(dur, in.recorder.recording() ? now : 0);
            if (total > 0) {
                p.setColor(in.recorder.recording() ? Studio.LIVE : Studio.ARMED);
                float x = (float) (6 * dp + (w - 12 * dp) * Math.min(1, now / total));
                c.drawRect(x - dp, 4 * dp, x + dp, h - 4 * dp, p);
            }
            p.setColor(0xFFA1A1AA);
            p.setTextSize(11 * dp);
            String t = total <= 0 ? getContext().getString(R.string.avatar_no_take) : String.format(Locale.US, "%.1f / %.1f s", now, total);
            c.drawText(t, 10 * dp, h / 2 + 4 * dp, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            Inputs in = host.inputs();
            double dur = in.recorder.duration();
            if (dur <= 0 || in.recorder.recording()) return true;
            getParent().requestDisallowInterceptTouchEvent(true);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                double f = Math.max(0, Math.min(0.999, (e.getX() - 6 * dp) / (getWidth() - 12 * dp)));
                in.recorder.stop();
                in.recorder.scrubTo(f * dur);
                invalidate();
            }
            return true;
        }
    }

    // ── small pieces ──────────────────────────────────────────────────────────────────────────────

    private interface Hold {
        void down();

        void up();
    }

    private TextView holdButton(Context c, String text, String hover, Hold h) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(compact ? 12 : 14);
        paintHold(t, false);
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    paintHold(t, true);
                    h.down();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    paintHold(t, false);
                    h.up();
                    return true;
                default:
                    return true;
            }
        });
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    private void paintHold(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(14));
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        t.setBackground(g);
        t.setTextColor(on ? Studio.GROUND : 0xFFE4E4E7);
    }

    private TextView round(Context c, String glyph, String hover) {
        TextView t = new TextView(c);
        t.setText(glyph);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(16);
        paintRound(t, false);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    private TextView chip(Context c, String text, String hover, View.OnClickListener tap) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), 0, px(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32));
        lp.rightMargin = px(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        paintChip(t, false);
        return t;
    }

    /** ON = solid cyan with the label punched out to the ground colour. */
    private void paintChip(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(16));
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        t.setBackground(g);
        t.setTextColor(on ? Studio.GROUND : 0xFFD4D4D8);
        if (t.getTag() instanceof Integer) {                         // a picture-only chip: the room's own vector, white, cyan cut-out when ON
            android.graphics.drawable.Drawable d = getContext().getDrawable((Integer) t.getTag()).mutate();
            d.setTint(on ? Studio.GROUND : 0xFFFFFFFF);
            d.setBounds(0, 0, px(18), px(18));
            t.setCompoundDrawables(d, null, null, null);
        }
    }

    private TextView label(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(0xFF71717A);
        t.setPadding(0, px(8), 0, px(6));
        return t;
    }

    private static boolean hasRole(CharacterDoc d, String role) {
        final boolean[] has = {false};
        d.walk((n, depth, p) -> {
            if (role.equals(n.role)) has[0] = true;
        });
        return has[0];
    }

    private static boolean hasKey(CharacterDoc d, String key) {
        final boolean[] has = {false};
        d.walk((n, depth, p) -> {
            if (n.json.has(key)) has[0] = true;
        });
        return has[0];
    }

    /** The turn positions of the head's drawings: each angle's {@code at} (the first set that turns). */
    private static List<float[]> headAngles(CharacterDoc d) {
        final List<float[]> out = new ArrayList<>();
        d.walk((n, depth, p) -> {
            if (!out.isEmpty() || !"set".equals(n.type) || !"turn".equals(n.pickBy)) return;
            for (CharacterDoc.Node k : n.kids) {
                JSONArray at = k.json.optJSONArray("at");
                if (at != null && at.length() >= 2) out.add(new float[]{(float) at.optDouble(0), (float) at.optDouble(1)});
            }
        });
        return out;
    }
}
