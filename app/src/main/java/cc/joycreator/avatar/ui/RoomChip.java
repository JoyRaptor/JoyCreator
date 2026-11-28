package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;

/**
 * The room's chip, the same on every card (AVA look, 11-28: six copies had drifted apart): Studio's sheet chip in size, padding,
 * words and press feedback ({@link SheetKit#chip}), and the owner's ON (solid cyan, the words cut out to the ground colour),
 * not Studio's faint wash. A pill: 32 dp tall, 16 dp corners.
 */
public final class RoomChip {
    private RoomChip() {
    }

    /** A chip with its hover label; {@code tap} may be null (a chip that only shows). */
    public static TextView make(Context c, String text, boolean on, String hover, View.OnClickListener tap) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(SheetKit.CHIP_SP);
        RoomType.control(t);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        int dp13 = SheetKit.dp(c, 13);
        t.setPadding(dp13, 0, dp13, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, SheetKit.dp(c, 32));
        lp.rightMargin = SheetKit.dp(c, 6);
        t.setLayoutParams(lp);
        if (tap != null) t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        SheetKit.press(t);
        paint(t, on);
        return t;
    }

    /** ON = solid cyan with the words cut out to the ground colour; at rest, Studio's raised grey. */
    public static void paint(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(SheetKit.dp(t.getContext(), 16));
        g.setColor(on ? Studio.ARMED : Studio.RAISED);
        t.setBackground(g);
        t.setTextColor(on ? Studio.GROUND : Studio.LABEL);
    }
}
