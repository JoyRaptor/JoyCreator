package com.fadcam.ui.type;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.SparseArray;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;

/**
 * JOY CREATOR'S VOICE.
 *
 * <p>Three faces, no more. Archivo for display, IBM Plex Sans for reading, IBM Plex Mono for
 * anything the machine is telling you exactly — timecodes, frame counts, file sizes.
 *
 * <p>This class exists because the lobby is built in Java rather than XML, and because
 * {@code Typeface.create("sans-serif-black", NORMAL)} — what the lobby used before — is not a
 * font request at all. It is a request for whatever the OEM decided black should be. On the
 * Note 9 that is Samsung One, on a Pixel it is Roboto, on a Xiaomi it is something else again.
 * The same screen would have had a different personality on every phone it shipped to.
 *
 * <h3>Why weight is a number here and not a style flag</h3>
 * {@link Typeface#BOLD} is a single bit. It cannot express the difference between the 600 of a
 * section label and the 900 of the word STUDIO, and that difference is most of what makes a
 * typographic hierarchy read as deliberate rather than accidental. Archivo is a variable font,
 * so the weight axis is continuous and asking for exactly 800 is free.
 *
 * <h3>Fake bold is switched off, on purpose</h3>
 * When a TextView is asked for a bold style it does not have, Android SYNTHESISES one by
 * smearing the outline sideways. On a real 900 weight that smear lands on top of an already
 * dense letterform and turns counters into mud. Every setter here clears the style bit and
 * carries the weight in the typeface itself, so synthesis never triggers.
 */
public final class Type {

    private Type() { }

    // ── the weights the design actually uses ────────────────────────────────
    // Named rather than numeric at call sites so a change of mind about, say,
    // what a section label weighs is one edit instead of forty.
    public static final int REGULAR  = 400;
    public static final int MEDIUM   = 500;
    public static final int SEMIBOLD = 600;
    public static final int BOLD     = 700;
    public static final int EXTRA    = 800;
    public static final int BLACK    = 900;

    // Typeface resolution walks the resource table and, for variable fonts, re-instances the
    // outline at the requested axis value. Neither is expensive once, both are expensive in a
    // RecyclerView bind or a marquee tick. Keyed on family*1000+weight.
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    private static final int FAM_DISPLAY = 1, FAM_BODY = 2, FAM_MONO = 3;

    /** Archivo. For anything that is a title, a label, or a word meant to be LOOKED AT. */
    public static Typeface display(Context c, int weight) {
        return get(c, FAM_DISPLAY, weight);
    }

    /** IBM Plex Sans. For anything meant to be READ. */
    public static Typeface body(Context c, int weight) {
        return get(c, FAM_BODY, weight);
    }

    /**
     * IBM Plex Mono. For values that must line up in a column, or that change character by
     * character while you watch — a running timecode in a proportional face visibly jitters
     * as the digits change width, which reads as the app being unstable.
     */
    public static Typeface mono(Context c, int weight) {
        return get(c, FAM_MONO, weight);
    }

    // ── convenience setters ─────────────────────────────────────────────────
    // These also clear the style bit, which is the half that is easy to forget.

    public static void display(TextView v, int weight) { apply(v, display(v.getContext(), weight)); }
    public static void body(TextView v, int weight)    { apply(v, body(v.getContext(), weight)); }
    public static void mono(TextView v, int weight)    { apply(v, mono(v.getContext(), weight)); }

    private static void apply(TextView v, Typeface t) {
        if (t == null) return;
        // NORMAL, not the view's current style: passing the existing style back in is what
        // re-enables synthesis. The weight already lives in `t`.
        v.setTypeface(t, Typeface.NORMAL);
        v.setPaintFlags(v.getPaintFlags() & ~android.graphics.Paint.FAKE_BOLD_TEXT_FLAG);
    }

    // ── resolution ──────────────────────────────────────────────────────────

    private static Typeface get(Context c, int family, int weight) {
        int key = family * 1000 + weight;
        Typeface cached = CACHE.get(key);
        if (cached != null) return cached;

        Typeface t = null;
        try {
            if (family == FAM_MONO) {
                // Plex Mono ships as discrete files, so this is a straight pick and the
                // result is exact on every API level.
                int res = weight >= SEMIBOLD ? R.font.plex_mono_semibold
                        : weight >= MEDIUM   ? R.font.plex_mono_medium
                        :                      R.font.plex_mono_regular;
                t = ResourcesCompat.getFont(c, res);
            } else {
                // The family XML (joy_display.xml / joy_body.xml) declares one entry per
                // weight, each pointing at the same variable file with a different
                // 'wght' axis value. Loading the FAMILY rather than the raw .ttf is what
                // gives the framework something to choose from.
                int res = family == FAM_DISPLAY ? R.font.joy_display : R.font.joy_body;
                Typeface base = ResourcesCompat.getFont(c, res);
                if (base != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Numeric weight selection arrived in API 28. This picks the exact
                        // declared entry, so 800 really is 800 and not a bolded 400.
                        t = Typeface.create(base, weight, false);
                    } else {
                        // API 24-27 have no numeric selector. One bit is all that is on
                        // offer, so everything at semibold and above gets the family's bold
                        // and everything under it gets regular. Still Archivo, still not the
                        // system voice - just two steps of hierarchy instead of six.
                        t = Typeface.create(base,
                                weight >= SEMIBOLD ? Typeface.BOLD : Typeface.NORMAL);
                    }
                }
            }
        } catch (Exception ignored) {
            // A missing or corrupt font must never take a screen down with it.
        }

        if (t == null) {
            t = weight >= BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        }
        CACHE.put(key, t);
        return t;
    }
}
