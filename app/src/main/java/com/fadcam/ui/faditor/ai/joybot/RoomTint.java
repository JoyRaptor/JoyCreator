package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Paints {@link RoomColours} onto the chat's views (BOT-7). The split agreed with the Joybot
 * sprites lane, 11-15: this lane decides each message's room and owns this class; that lane owns
 * the drawables and layout (chat_bubble_joybot's layers stay 0 = tail, 1 = gradient), so every
 * drawable is mutate()d before it is recoloured and studio_action_pill is only tinted, never changed.
 */
public final class RoomTint {
    private RoomTint() { }

    /** {@link RoomColours#of}, with JoyBrush's live pair from its tokens. */
    @NonNull
    public static RoomColours.Pair forRoom(@NonNull Context c, @Nullable String room) {
        RoomColours.Pair p = RoomColours.of(room);
        if (!"joybrush".equals(p.room)) return p;
        try {
            cc.joycreator.joybrush.android.Palette jb = cc.joycreator.joybrush.android.JbColors.palette(c);
            return new RoomColours.Pair(p.room, jb.getRoomStart(), jb.getRoomEnd(), p.ink);
        } catch (Throwable t) {
            return p;
        }
    }

    /** Joybot's answer bubble (chat_bubble_joybot) in {@code p}'s gradient, its words in {@code p}'s ink. */
    public static void bubble(@NonNull TextView tv, @NonNull RoomColours.Pair p) {
        Drawable bg = tv.getBackground();
        if (bg == null) return;
        bg = bg.mutate();
        if (bg instanceof LayerDrawable && ((LayerDrawable) bg).getNumberOfLayers() >= 2) {
            LayerDrawable l = (LayerDrawable) bg;
            Drawable tail = l.getDrawable(0).mutate();
            tail.setTint(p.start);
            Drawable body = l.getDrawable(1).mutate();
            if (body instanceof GradientDrawable) ((GradientDrawable) body).setColors(new int[]{p.start, p.end});
        } else if (bg instanceof GradientDrawable) {
            ((GradientDrawable) bg).setColors(new int[]{p.start, p.end});
        }
        tv.setBackground(bg);
        tv.setTextColor(p.ink);
    }

    /** A flat pill (a button) in {@code p}'s gradient, left to right. */
    public static void pill(@NonNull TextView t, @NonNull RoomColours.Pair p) {
        Drawable bg = t.getBackground();
        float radius = bg instanceof GradientDrawable ? ((GradientDrawable) bg).getCornerRadius() : t.getMinHeight() / 2f;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{p.start, p.end});
        g.setCornerRadius(radius);
        t.setBackground(g);
        t.setTextColor(p.ink);
    }

    /** The send button: its pill tinted (studio_action_pill is shared, so tint only), its arrow in the ink. */
    public static void send(@NonNull ImageView send, @NonNull RoomColours.Pair p) {
        send.setBackgroundTintList(ColorStateList.valueOf(p.start));
        send.setImageTintList(ColorStateList.valueOf(p.ink));
    }
}
