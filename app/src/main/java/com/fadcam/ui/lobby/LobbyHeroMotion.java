package com.fadcam.ui.lobby;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.Settings;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.joyfolder.SheetName;
import com.fadcam.ui.library.SheetDrawable;

import java.io.File;

/**
 * Quiet motion for the lobby hero (owner, 11-13: "for vislab could we have it play animated preview in the lobby
 * hero? ... perhaps for joybrush its just slow pan or zoom of the last image drawn?").
 *
 * <p>Two kinds, both cheap:</p>
 * <ul>
 *   <li><b>A moving preview</b>, when the room left one beside its hero still: a sprite sheet named the Library's
 *       way ({@code <still base>.<C>x<R>.sprite.webp} or {@code .png}, see {@link SheetName}) in the still's
 *       folder. It plays with the Library's own {@link SheetDrawable}. VisLab and TextLab keep the still today; the
 *       file name is the whole contract, so a room that starts writing a sheet beside it needs no lobby change.</li>
 *   <li><b>A drift</b> over the still (a slow zoom with a little pan, there and back, 18 seconds each way), for
 *       every other picture: the last drawing, the newest project's frame, a sheet's or character's picture.</li>
 * </ul>
 *
 * <p>The owner's rules for ambient extras: subtle and cheap. The drift moves the ImageView's own scale and
 * translation, which the GPU composites without redrawing the picture (no extra layer); the sheet draws 10 frames a second only
 * while it is on screen. Nothing runs while the lobby is paused or the hero is hidden ({@link #pause}), and nothing
 * moves at all when the phone's "remove animations" is on (animator scale 0): the still stays.</p>
 */
final class LobbyHeroMotion {

    /** One way of the drift, then back. Long enough that it reads as stillness that breathes. */
    private static final long DRIFT_MS = 18_000L;
    /** How far it zooms: 6%, enough to see, never enough to crop what matters. */
    private static final float ZOOM = 0.06f;

    private final ImageView art;
    @Nullable private ValueAnimator drift;
    @Nullable private SheetDrawable sheet;
    private boolean wantDrift;
    private boolean paused = true;

    LobbyHeroMotion(@NonNull ImageView art) {
        this.art = art;
    }

    /** True when the person asked the phone for no animations. */
    static boolean animationsOff(@NonNull Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /** The hero shows a new picture: stop whatever moved before; the drift starts when {@link #resume} allows. */
    void still() {
        stopDrift();
        sheet = null;
        wantDrift = false;
    }

    /** Drift over the picture now in the ImageView. */
    void drift() {
        sheet = null;
        wantDrift = true;
        if (!paused) startDrift();
    }

    /**
     * The moving preview beside {@code stillPath}, decoded off the main thread, or null when the room left none.
     * Small on purpose: a sheet bigger than 8 MB is not a lobby extra.
     */
    @Nullable
    static Bitmap readSheetBeside(@Nullable String stillPath, @NonNull String[] nameOut) {
        File f = sheetBeside(stillPath);
        if (f == null || f.length() > 8L * 1024 * 1024) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) nameOut[0] = f.getName();
            return b;
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    /** The sheet file beside the still: same folder, same base name, a {@link SheetName} grid ending. */
    @Nullable
    static File sheetBeside(@Nullable String stillPath) {
        if (stillPath == null || stillPath.isEmpty() || stillPath.contains("://")) return null;
        File still = new File(stillPath);
        File dir = still.getParentFile();
        if (dir == null) return null;
        String name = still.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File[] found = dir.listFiles((d, n) -> {
            SheetName s = SheetName.parse(n);
            return s != null && s.base.equals(base) && s.frames() > 1;
        });
        if (found == null || found.length == 0) return null;
        File best = found[0];
        for (File f : found) if (f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    /** Play a decoded moving preview in place of the still; null keeps the still and drifts over it instead. */
    void play(@Nullable Bitmap sheetBitmap, @Nullable String fileName) {
        SheetDrawable d = sheetBitmap == null ? null : SheetDrawable.of(sheetBitmap, fileName);
        if (d == null) { drift(); return; }
        stopDrift();
        wantDrift = false;
        sheet = d;
        art.setImageDrawable(d);
        if (paused || animationsOff(art.getContext())) d.setPlaying(false);
        else d.setPlaying(true);
    }

    /** The lobby is in front and the hero shown. */
    void resume() {
        paused = false;
        if (sheet != null) {
            sheet.setPlaying(!animationsOff(art.getContext()));
        } else if (wantDrift) {
            startDrift();
        }
    }

    /** The lobby went behind something, or the hero was hidden: everything stops where it is. */
    void pause() {
        paused = true;
        if (sheet != null) sheet.setPlaying(false);
        if (drift != null) drift.pause();
    }

    private void startDrift() {
        if (animationsOff(art.getContext())) { resetView(); return; }
        if (drift != null) {
            if (drift.isPaused()) drift.resume();
            if (drift.isStarted()) return;
        }
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(DRIFT_MS);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setInterpolator(new LinearInterpolator());
        drift.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Ease each end so the turn-around is never seen: a slow sine, not a bounce.
            float e = (float) (0.5 - 0.5 * Math.cos(Math.PI * t));
            float s = 1f + ZOOM * e;
            art.setScaleX(s);
            art.setScaleY(s);
            // A little pan inside the zoom's margin, so no edge ever shows.
            float room = (s - 1f) * 0.5f;
            art.setTranslationX(art.getWidth() * room * (e - 0.5f) * 0.8f);
            art.setTranslationY(art.getHeight() * room * (0.5f - e) * 0.4f);
        });
        drift.start();
    }

    private void stopDrift() {
        if (drift != null) {
            drift.cancel();
            drift = null;
        }
        resetView();
    }

    private void resetView() {
        art.setScaleX(1f);
        art.setScaleY(1f);
        art.setTranslationX(0f);
        art.setTranslationY(0f);
    }
}
