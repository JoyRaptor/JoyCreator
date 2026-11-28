package cc.joycreator.avatar.ui;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.face.Shrugs;
import cc.joycreator.avatar.file.CharacterDoc;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import java.util.Locale;

/**
 * Motion, Shrug (SHRUG-1; the pilot's {@code al-pose.js} section): shrugs with your shoulders on or off, how far the arms
 * lift and the head sinks (one named undo step each, when the finger lifts), the shoulder sensitivity (this phone's, not
 * the character's) and Try, a shrug to see without the camera while the finger is on it. Only for a character a shrug
 * moves: an arm or the head to rig, or shrug Links already.
 */
public final class ShrugSections {

    public interface Host {
        /** Shrugs on (rigged from the drawing) or off (the Links taken away), as one named undo step. */
        void setShrugs(boolean on, String stepName);

        /** The arms' lift or the head's sink, in pixels, as one named undo step. */
        void setShrugAmount(boolean arms, double px, String stepName);

        float shoulderSensitivity();

        void setShoulderSensitivity(float v);

        /** Try: this much shrug now (0 lets go). */
        void tryShrug(double v);
    }

    private ShrugSections() {}

    static void add(Context c, LinearLayout col, CharacterDoc doc, Host host, float dp) {
        org.json.JSONObject ch = doc.json;
        if (!Shrugs.rigs(ch) && !Shrugs.has(ch)) return;
        boolean on = Shrugs.has(ch);
        col.addView(MotionSections.label(c, c.getString(R.string.avatar_shrug), dp));
        col.addView(WalkSections.tick(c, c.getString(R.string.avatar_shrug_on), on, c.getString(R.string.avatar_hover_shrug_on), dp,
                v -> host.setShrugs(v, c.getString(v ? R.string.avatar_step_shrugs : R.string.avatar_step_no_shrugs))));
        if (!on) return;
        double arms = Shrugs.amount(ch, true), head = Shrugs.amount(ch, false);
        if (!Double.isNaN(arms))
            col.addView(MotionSections.slider(c, c.getString(R.string.avatar_arms_lift), 0, 60, 0.5, arms, v -> c.getString(R.string.avatar_px, num(v)),
                    c.getString(R.string.avatar_hover_arms_lift), v -> host.setShrugAmount(true, v, c.getString(R.string.avatar_step_arms_lift)), dp));
        if (!Double.isNaN(head))
            col.addView(MotionSections.slider(c, c.getString(R.string.avatar_head_sinks), 0, 30, 0.5, head, v -> c.getString(R.string.avatar_px, num(v)),
                    c.getString(R.string.avatar_hover_head_sinks), v -> host.setShrugAmount(false, v, c.getString(R.string.avatar_step_head_sinks)), dp));
        col.addView(sensitivity(c, host, dp));
        col.addView(trySlider(c, host, dp));
    }

    /** Shoulder sensitivity (Motion and Perform): 0.5 to 3 times, kept on this phone. */
    static android.view.View sensitivity(Context c, Host host, float dp) {
        return MotionSections.slider(c, c.getString(R.string.avatar_shoulder_sens), 0.5, 3, 0.1, host.shoulderSensitivity(),
                v -> c.getString(R.string.avatar_times, String.format(Locale.US, "%.1f", v)), c.getString(R.string.avatar_hover_shoulder_sens),
                v -> host.setShoulderSensitivity((float) v), dp);
    }

    /** Try: live while the finger is on it; let go and it rests. */
    private static android.view.View trySlider(Context c, Host host, float dp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        TextView l = MotionSections.text(c, c.getString(R.string.avatar_shrug_try), 13, 0xFFD4D4D8);
        TextView val = MotionSections.text(c, c.getString(R.string.avatar_shrug_rest), 12, 0xFFA1A1AA);
        head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(val);
        box.addView(head);
        SeekBar sb = new SeekBar(c);
        sb.setMax(20);
        sb.getProgressDrawable().setTint(Studio.ARMED);
        sb.getThumb().setTint(Studio.ARMED);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(p > 1 ? R.string.avatar_shrug_shrug : R.string.avatar_shrug_rest);
                if (user) host.tryShrug(p / 20.0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                host.tryShrug(0);
                s.setProgress(0);
            }
        });
        box.addView(sb);
        box.setPadding(0, Math.round(4 * dp), 0, Math.round(2 * dp));
        String hover = c.getString(R.string.avatar_hover_shrug_try);
        TooltipCompat.setTooltipText(box, hover);
        box.setContentDescription(c.getString(R.string.avatar_shrug_try) + ": " + hover);
        sb.setContentDescription(c.getString(R.string.avatar_shrug_try));
        return box;
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }
}
