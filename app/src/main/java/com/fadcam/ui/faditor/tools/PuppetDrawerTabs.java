package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.KeyframeDiamondControl;
import com.fadcam.ui.faditor.ObjectMenuSheet;
import com.fadcam.ui.faditor.puppet.PuppetIcons;
import com.fadcam.ui.faditor.puppet.PuppetPalette;
import com.fadcam.ui.faditor.puppet.PuppetPin;
import com.fadcam.ui.faditor.puppet.PuppetRig;

import java.util.ArrayList;
import java.util.List;

/**
 * Content for the PUPPET tab of {@link ObjectDrawer} — SPEC_20260915_PUPPET_UI §2.
 *
 * <p>Sibling of {@link PipDrawerTabs}: the drawer chrome stays generic and knows nothing about
 * rigs, so nothing in {@code ObjectDrawer} changes to support this. The activity hands it an
 * {@link ObjectDrawer.Tab} whose content this class builds.
 *
 * <h3>The row order is the design, not a layout accident</h3>
 * <pre>
 *   1  &#9679; name ────────  &#8249; &#9670; &#8250;  3/10   (&#9679;)   &#8592; only once the item has a pin
 *   2  Grab  Pin  Stiff  Dangle  Free  Bone
 *   3  [ Selected | Character | Recording ]
 *   4+ whichever scope is showing
 * </pre>
 *
 * <p><b>Why row 1 arrives rather than being there all along.</b> JoyRaptor, 2026-09-15: before
 * you have made anything, the tools ARE the work and they belong at the top; the moment a pin
 * exists, naming and key navigation become the common job. So row 1 appears ABOVE the tools and
 * pushes them down — nothing is ever taken away, so the tool row is still there for the seventh
 * pin on day nine. The rule is exactly "row 1 exists iff the rig has at least one pin", which is
 * symmetric: delete the last pin and it goes.
 *
 * <p><b>Why the &#8249;&#9670;&#8250; is that high and carries no label.</b> It is the
 * program-wide {@link KeyframeDiamondControl}, so a label would be noise. It sits in the top row
 * because after a live take, landing the playhead one frame off a key authors a SECOND key beside
 * the first — which reads as a jitter in the picture and is very hard to find in a dense track.
 * Jumping exactly key-to-key is therefore the main way you move while editing a performance.
 * (See INBOX 2026-09-15 on the app-wide snapping work this leans on.)
 *
 * <p><b>Why there are three scopes.</b> Counting the manifest, v1 has about twenty-one controls.
 * That is far too many for one list and about right for three groups of seven. A scope is not a
 * tab — it is WHICH THING the setting belongs to, which is also why bones did not need a fourth:
 * a bone is just another thing you can select, and its settings replace a pin's the same way a
 * Dangle's replace a Free's.
 *
 * <p><b>Nothing here gets a row to itself.</b> Toggles pair up two per line, read-only numbers
 * pair up two per line, and sliders stop short of the right edge with the reach-eye on their left.
 * JoyRaptor: <i>"Mostly empty rows are a no-no."</i>
 */
public final class PuppetDrawerTabs {

    private PuppetDrawerTabs() {}

    // Matching PipDrawerTabs so the two drawers read as one app.
    // Over the frosted scrim, so this is the DRAWER ramp, not the screen ramp.
    // Same value it has always rendered; it simply asks for it by the right name now.
    private static final int TXT = Studio.DRAWER_INK;
    // One label ink: a TXT_FAINT that was the same DRAWER_LABEL as this is gone (drawer audit
    // 2026-09-24, U2).
    private static final int TXT_DIM = Studio.DRAWER_LABEL;

    // ── record 06 §02, the drawer's control vocabulary ──────────────────────────────────
    // The glass values, the slider, the checkbox face, the pill, press feedback and the hover
    // label all come from ObjectDrawer.Kit, the ONE drawer kit (drawer audit 2026-09-24, U1).
    // This file used to carry a private copy of each, and a drop shadow on every word that the
    // other drawers had already dropped, so the Puppet tab read heavier than its neighbours.
    /** {@code --dctl} rgba(255,255,255,.10): a control's fill on the scrim. */
    private static final int CTL_FILL = ObjectDrawer.Kit.CTL;
    /** {@code --dring} rgba(255,255,255,.12): a control's 1dp inset ring. */
    private static final int CTL_RING = ObjectDrawer.Kit.RING;
    /** {@code .lens} rgba(0,0,0,.36): the well a segmented switch sits in. */
    private static final int WELL = Studio.alpha(Studio.GROUND, 0x5C);
    /** Nothing: a hollow shape's fill, from the palette rather than a literal (U3/U4). */
    private static final int CLEAR = Studio.alpha(Studio.GROUND, 0);
    /** An ON control is its colour at 70% — {@code .dchip.on}, {@code .dcb.on}, the slider fill. */
    private static final int ON_ALPHA = ObjectDrawer.Kit.ON_ALPHA;
    /** Ink on a saturated fill: the record's #050507, which is {@link Studio#ON_GO}. */
    private static final int ON_FILL_INK = Studio.ON_GO;
    /**
     * RECORDING is LIVE, not DANGER. Studio.java draws the line in as many words: LIVE is the
     * magenta of recording and the playhead, a normal state you want to see; DANGER is the red
     * of something being lost. The record button wore DANGER, so "I am recording" and "this
     * will destroy" were the same colour on the same tab — the one place that must not happen,
     * because Start over lives three rows below it.
     */
    private static final int REC = Studio.LIVE;
    /** Start over, and only Start over. */
    private static final int DESTROY = Studio.DANGER;
    private static final int SLIDER_STEPS = 1000;

    /** What a touch on the picture means. Exactly one is armed at a time. */
    public enum Tool { GRAB, PIN, STIFF, DANGLE, FREE, BONE }

    /** Which thing the rows below the switcher belong to. */
    public enum Scope { SELECTED, CHARACTER, RECORDING }

    /**
     * Everything this tab needs from the editor, and the whole list of it.
     *
     * <p>Selection, tool and scope live on the HOST rather than in this class because the drawer
     * rebuilds its content ({@code ObjectDrawer.refreshCurrentTab}) and a field here would reset
     * the user's place every time a value changed.
     */
    public interface Host {
        @NonNull PuppetRig rig();

        /** Index into the rig's pins, or -1 when nothing is selected. */
        int selectedPin();
        void setSelectedPin(int index);

        /**
         * Index into the rig's BONES, or -1.
         *
         * <p>A bone is another thing you can select, and its settings replace a pin's the same
         * way a Dangle's replace a Free's — which is why bones never needed a fourth scope.
         * This class has said so in its own header since it was written; the selection simply
         * did not exist until 2026-09-16.
         */
        int selectedBone();
        void setSelectedBone(int index);

        @NonNull Tool tool();
        void setTool(@NonNull Tool t);

        @NonNull Scope scope();
        void setScope(@NonNull Scope s);

        long playheadMs();

        /** A value changed — repaint preview and timeline, and schedule a save. */
        void onChanged();

        /** Rebuild these rows in place (the tool row and the scope body both depend on state). */
        void rebuildRows();

        /** A pin was muted, or anything else that changes the WEIGHTS. Re-triangulate. */
        void onRigStructureChanged();

        /**
         * START OVER: every pin, every bone and every key, gone — after the user confirms.
         *
         * <p>The confirmation is the HOST's, not this class's, because it is a dialog and this
         * class builds rows. The host must not act until the user has said yes.
         */
        void confirmResetRig();

        /**
         * Show the reach ring for the duration of a drag on a slider that changes it.
         *
         * <p>Separate from the persisted {@code showReach} so that letting go of the slider puts
         * the picture back the way the user left it, rather than silently flipping their setting.
         */
        void setReachPreview(boolean on);

        /** Record ONE undo step. A chain drag is one press, so this is never called per pin. */
        void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);

        // ── the selected pin's keys, for the ‹♦› ──────────────────────────

        /** How many keys the selected pin has. 0 when it has none or nothing is selected. */
        int keyCount();

        /**
         * How many keys THAT pin has, for the chip row’s dots.
         *
         * <p>Must be the DISPLAY count — {@code PuppetKeys.displayKeyCount} — not the storage
         * one. A whole-pose track holds an entry for every pin at every instant any pin moved, so
         * the storage count would put a dot on every chip the moment one pin was performed, and
         * a row of dots that is always full says nothing at all.
         */
        int keyCountOf(int pin);
        /** 1-based position of the key at the playhead, or 0 when the playhead is between keys. */
        int keyIndexAtPlayhead();
        boolean playheadIsOnKey();
        void dropKeyAtPlayhead();
        void deleteKeyAtPlayhead();
        void jumpToPrevKey();
        void jumpToNextKey();

        /**
         * The colour an ON control wears — record 06: "every on control is [the object's
         * colour] at 70%". A puppet is a rigged PICTURE, so the default is the image's own
         * identity hue; teal also sits clear of every pin-type hue on this tab (amber, pink,
         * cyan, violet, blue), which a toggle's "on" must not be mistaken for.
         *
         * <p>Default so the editor needs no change to compile; a host that knows better can
         * hand in the drawer's accent.
         */
        default int accent() { return com.fadcam.ui.faditor.layers.ObjectPalette.IMAGE; }
    }

    // ── the tab ──────────────────────────────────────────────────────────

    /**
     * Build the whole tab. Safe to call again after anything changes — it holds no state.
     */
    @NonNull
    public static View build(@NonNull Context ctx, @NonNull Host host) {
        float d = density(ctx);
        PuppetRig rig = host.rig();

        LinearLayout root = column(ctx);
        root.setPadding(pad(d, 10), pad(d, 8), pad(d, 10), pad(d, 10));

        final List<Runnable> refreshers = new ArrayList<>();

        // A RIGGED PICTURE OPENS WITH A PIN SELECTED. The spec's rule is "row 1 exists iff the
        // item has at least one pin" — but the row names a pin, so with nothing selected it had
        // nothing to draw and vanished. Since the drawer clears the selection every time it opens,
        // that meant a fully rigged character opened with no identity row, no key controls and no
        // record button until the user happened to tap a pin. Selecting the first one costs
        // nothing and makes the rule the spec states true.
        if (rig.pinCount() > 0 && (host.selectedPin() < 0 || host.selectedPin() >= rig.pinCount())) {
            host.setSelectedPin(0);
        }

        // 1 · identity + keys + record. Only once something exists to name.
        if (rig.pinCount() > 0 && host.selectedPin() >= 0
                && host.selectedPin() < rig.pinCount()) {
            root.addView(identityRow(ctx, host, d, refreshers));
            gap(ctx, root, d, 7);
        }

        // 1b · THE CHIPS. Which pins hold animation, and a way to reach one without hunting for
        // it on the picture. The tape shows ONE pin’s keys at a time — that was JoyRaptor’s own
        // ruling, because a thousand grey marks for every other pin is "completely
        // incomprehensible" — so something has to say which OTHER pins have anything to show.
        if (rig.pinCount() > 1) {
            root.addView(chipRow(ctx, host, d));
            gap(ctx, root, d, 7);
        }

        // 2 · the six tools.
        root.addView(toolRow(ctx, host, d));
        gap(ctx, root, d, 7);

        // 3 · scope.
        root.addView(scopeRow(ctx, host, d));
        gap(ctx, root, d, 7);

        // 4+ · the scope's own rows.
        switch (host.scope()) {
            case CHARACTER: characterRows(ctx, host, root, d); break;
            case RECORDING: recordingRows(ctx, host, root, d); break;
            default: selectedRows(ctx, host, root, d); break;
        }

        if (!refreshers.isEmpty()) {
            root.setTag(R.id.faditor_tag_row_refresh, (Runnable) () -> {
                for (Runnable r : refreshers) r.run();
            });
        }
        return root;
    }

    // ── row 1 ────────────────────────────────────────────────────────────

    /**
     * Swatch, editable name, the program-wide ‹♦›, the key position, and the record button —
     * all on ONE line. Four things share this row because each is small and all four are wanted
     * at once, which is the test for putting controls together rather than stacking them.
     */
    @NonNull
    private static View identityRow(@NonNull Context ctx, @NonNull Host host, float d,
                                    @NonNull List<Runnable> refreshers) {
        PuppetRig rig = host.rig();
        final int index = host.selectedPin();
        PuppetPin pin = rig.pin(index);
        final int hue = PuppetPalette.of(pin.type, rig.locked);

        // Record 06 .dr: a 46dp line on the drawer's glass pill.
        LinearLayout row = row(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(pad(d, 46));
        row.setBackground(pill(ctx, CTL_FILL, CTL_RING));
        row.setPadding(pad(d, 5), pad(d, 3), pad(d, 3), pad(d, 3));

        // THE TYPE, as the pin's own silhouette — and it CHANGES the type rather than only
        // reporting it. The drawer could show a pin's type and not set it, so the only way to
        // change one was the helper strip's swatch, which is on the picture and not in the place
        // a user goes to edit a pin. Tap cycles; long-press opens the same four-way ring the
        // strip has. Same grammar in both places, deliberately.
        ImageView swatch = new ImageView(ctx);
        swatch.setImageDrawable(PuppetIcons.of(PuppetIcons.forType(pin.type), hue, pad(d, 17)));
        hoverLabel(swatch, ctx.getString(R.string.lane_b_puppet_swatch, pin.typeLabel()));
        // 22dp of glyph in a 32dp target: grow the hit area, not the icon.
        swatch.setPadding(pad(d, 5), pad(d, 5), pad(d, 5), pad(d, 5));
        swatch.setOnClickListener(v -> {
            PuppetPin.Type[] all = PuppetPin.Type.values();
            PuppetPin.Type was = pin.type;
            PuppetPin.Type now = all[(was.ordinal() + 1) % all.length];
            host.recordUndo(ctx.getString(R.string.lane_b_puppet_undo_pin_type),
                    () -> { pin.type = now; host.onRigStructureChanged(); host.rebuildRows(); },
                    () -> { pin.type = was; host.onRigStructureChanged(); host.rebuildRows(); });
            pin.type = now;
            // A type change changes the WEIGHTS — a stiff patch is a different table — so the
            // mesh has to be rebuilt, not merely repainted.
            host.onRigStructureChanged();
            host.rebuildRows();
            host.onChanged();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(pad(d, 32), pad(d, 32));
        slp.rightMargin = pad(d, 4);
        row.addView(swatch, slp);

        // the name. This is the thing the assistant reads. Record 06 .dh .nm: 12sp w700 in
        // drawer ink, sat in a dark well so it reads as a field you can type into.
        EditText name = new EditText(ctx);
        name.setText(pin.name);
        name.setTextColor(TXT);
        name.setTextSize(12f);
        com.fadcam.ui.type.Type.body(name, com.fadcam.ui.type.Type.BOLD);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        name.setBackground(pill(ctx, WELL, CTL_RING));
        name.setPadding(pad(d, 10), pad(d, 5), pad(d, 10), pad(d, 5));
        name.setHint(R.string.lane_b_puppet_name);
        name.setHintTextColor(TXT_DIM);
        androidx.core.view.ViewCompat.setTooltipText(name, ctx.getString(R.string.lane_b_puppet_name));
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                // No undo step per keystroke — a rename is not a gesture, and one undo press
                // per letter is exactly the behaviour the one-press ruling exists to prevent.
                host.rig().renamePin(index, e.toString());
                host.onChanged();
            }
        });
        LinearLayout.LayoutParams nlp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.rightMargin = pad(d, 6);
        row.addView(name, nlp);

        // ‹ ♦ › — the program-wide control, unlabelled by design.
        KeyframeDiamondControl diamond = new KeyframeDiamondControl(ctx);
        diamond.bind(keyProp(host, pin), new KeyframeDiamondControl.Host() {
            @Override public long playheadMs() { return host.playheadMs(); }
            @Override public void onFocus() { /* nothing above this row to promote */ }
            @Override public void onAction() { host.rebuildRows(); host.onChanged(); }
        });
        diamond.refresh(host.playheadMs());
        row.addView(diamond);

        // where you are in the pin's keys. Tiny, monospaced-ish, and the only reason the
        // diamond can go unlabelled: this says what it is acting on.
        TextView pos = text(ctx, d, 9.5f, TXT_DIM);
        // A count the machine keeps: the mono face, tabular so "3/10" does not jitter.
        com.fadcam.ui.type.Type.mono(pos, com.fadcam.ui.type.Type.MEDIUM);
        pos.setFontFeatureSettings("tnum");
        androidx.core.view.ViewCompat.setTooltipText(pos,
                ctx.getString(R.string.lane_b_puppet_key_position));
        pos.setMinWidth(pad(d, 28));
        pos.setGravity(Gravity.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = pad(d, 3);
        plp.rightMargin = pad(d, 5);
        row.addView(pos, plp);

        Runnable refreshPos = () -> {
            int n = host.keyCount();
            if (pin.isSimulated()) { pos.setText(R.string.lane_b_puppet_sim); return; }
            if (n == 0) { pos.setText("—"); return; }
            int at = host.keyIndexAtPlayhead();
            pos.setText(at > 0 ? (at + "/" + n) : ("·/" + n));
        };
        refreshPos.run();
        refreshers.add(() -> { refreshPos.run(); diamond.refresh(host.playheadMs()); });

        // record-when-I-touch. A button, not a labelled row: it is a state you glance at.
        row.addView(recordButton(ctx, host, d));
        return row;
    }

    /**
     * A {@link ObjectMenuSheet.Prop} that exists ONLY to drive the ‹♦›.
     *
     * <p>Its getter and setter are inert on purpose, and that is worth saying out loud: a pin's
     * value is a two-dimensional position living in the pose track's components, not a slider
     * float, so there is nothing honest for {@code get}/{@code set} to do. Everything the diamond
     * actually uses — is the playhead on a key, drop one, delete one, jump to the previous or
     * next — maps exactly, which is why reusing the program-wide control beats drawing a second
     * one that would drift from it.
     */
    @NonNull
    private static ObjectMenuSheet.Prop keyProp(@NonNull Host host, @NonNull PuppetPin pin) {
        boolean keyable = !pin.isSimulated();
        return new ObjectMenuSheet.Prop(
                "puppet.pin",
                pin.name,
                0f, 1f,
                v -> "",
                playheadMs -> 0f,
                (value, playheadMs) -> { },
                playheadMs -> keyable && host.playheadIsOnKey(),
                () -> { if (keyable) { host.dropKeyAtPlayhead(); host.onChanged(); } },
                host::jumpToPrevKey,
                host::jumpToNextKey,
                () -> { if (keyable) { host.deleteKeyAtPlayhead(); host.onChanged(); } },
                () -> keyable && host.keyCount() > 0,
                null, null);
    }

    @NonNull
    private static View recordButton(@NonNull Context ctx, @NonNull Host host, float d) {
        PuppetRig rig = host.rig();
        FrameLike wrap = new FrameLike(ctx);
        wrap.setGravity(Gravity.CENTER);
        // A 30dp ring inside a 40dp target. No press animation: this arms a take, and record
        // 06 never animates keying.
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(rig.recordOnTouch ? Studio.alpha(REC, 0x33) : CLEAR);
        ring.setStroke(Math.max(1, pad(d, 1)), rig.recordOnTouch ? REC : CTL_RING);
        wrap.setBackground(new android.graphics.drawable.InsetDrawable(ring, pad(d, 5)));

        View dotView = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        // Idle is grey in the drawer's label ink — "off is grey, never a faded colour" —
        // and bright enough to find on the scrim, which the old #4B4B55 dot was not.
        g.setColor(rig.recordOnTouch ? REC : TXT_DIM);
        dotView.setBackground(g);
        wrap.addView(dotView, new LinearLayout.LayoutParams(pad(d, 10), pad(d, 10)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pad(d, 40), pad(d, 40));
        wrap.setLayoutParams(lp);
        hoverLabel(wrap, ctx.getString(rig.recordOnTouch
                ? R.string.lane_b_puppet_rec_on
                : R.string.lane_b_puppet_rec_off));
        wrap.setOnClickListener(v -> {
            rig.recordOnTouch = !rig.recordOnTouch;
            host.rebuildRows();
            host.onChanged();
        });
        return wrap;
    }

    /** A LinearLayout that centres one child — a FrameLayout without another import. */
    private static final class FrameLike extends LinearLayout {
        FrameLike(@NonNull Context c) { super(c); setOrientation(HORIZONTAL); }
    }

    // ── row 2: the tools ─────────────────────────────────────────────────

    /**
     * Six equal tools. Approved at roughly 58dp each on a 390dp screen, which is tight — so the
     * button is TALLER than it is wide and the label is 10sp rather than the 8sp the study used,
     * because 8sp under a 58dp target is below legible. Dropping the labels was the alternative
     * and it is wrong: five pin types are not self-evident as glyphs, and this row is the
     * feature's front door.
     */
    @NonNull
    private static View toolRow(@NonNull Context ctx, @NonNull Host host, float d) {
        LinearLayout row = row(ctx);
        addTool(ctx, host, row, d, Tool.GRAB,   PuppetIcons.GRAB,
                ctx.getString(R.string.lane_b_puppet_tool_grab),   TXT);
        addTool(ctx, host, row, d, Tool.PIN,    PuppetIcons.PIN,
                ctx.getString(R.string.lane_b_puppet_tool_pin),    PuppetPalette.PIN);
        addTool(ctx, host, row, d, Tool.STIFF,  PuppetIcons.STIFF,
                ctx.getString(R.string.lane_b_puppet_tool_stiff),  PuppetPalette.STIFF);
        addTool(ctx, host, row, d, Tool.DANGLE, PuppetIcons.DANGLE,
                ctx.getString(R.string.lane_b_puppet_tool_dangle), PuppetPalette.DANGLE);
        addTool(ctx, host, row, d, Tool.FREE,   PuppetIcons.FREE,
                ctx.getString(R.string.lane_b_puppet_tool_free),   PuppetPalette.FREE);
        addTool(ctx, host, row, d, Tool.BONE,   PuppetIcons.BONE,
                ctx.getString(R.string.lane_b_puppet_tool_bone),   PuppetPalette.BONE);
        return row;
    }

    private static void addTool(@NonNull Context ctx, @NonNull Host host,
                                @NonNull LinearLayout parent, float d,
                                @NonNull Tool tool, @NonNull String icon,
                                @NonNull String label, int hue) {
        boolean on = host.tool() == tool;

        LinearLayout cell = column(ctx);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(pad(d, 1), pad(d, 5), pad(d, 1), pad(d, 5));
        cell.setMinimumHeight(pad(d, 44));
        // Record 06's fill-pill (.dchip): a fully round glass capsule with a 1dp ring when
        // idle; the tool in use is filled with its own colour at 70%, loses the ring, and its
        // glyph and word go dark. That fill IS the "what am I doing" signal. It replaces the
        // 4dp-cornered outlined box, which read as a form field rather than a control.
        // Glyph over word stays — five pin types are not self-evident as glyphs (see above).
        cell.setBackground(pill(ctx, on ? Studio.alpha(hue, ON_ALPHA) : CTL_FILL,
                on ? 0 : CTL_RING));

        ImageView glyph = new ImageView(ctx);
        // OFF is grey, never a faded colour — the rule SpriteIcons states and this set keeps.
        glyph.setImageDrawable(PuppetIcons.of(icon, on ? ON_FILL_INK : TXT_DIM, pad(d, 16)));
        cell.addView(glyph, new LinearLayout.LayoutParams(pad(d, 16), pad(d, 16)));

        TextView text = text(ctx, d, 10f, on ? ON_FILL_INK : TXT_DIM);
        com.fadcam.ui.type.Type.body(text, com.fadcam.ui.type.Type.SEMIBOLD);
        text.setText(label);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = pad(d, 2);
        cell.addView(text, tlp);

        hoverLabel(cell, ctx.getString(on ? R.string.lane_b_puppet_tool_on
                : R.string.lane_b_puppet_tool, label));
        press(cell);
        cell.setOnClickListener(v -> {
            host.setTool(tool);
            host.rebuildRows();
        });

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = pad(d, 4);
        parent.addView(cell, lp);
    }

    // ── row 3: scope ─────────────────────────────────────────────────────

    @NonNull
    private static View scopeRow(@NonNull Context ctx, @NonNull Host host, float d) {
        // Record 06's segmented switch (.lens): a dark round well, the showing segment filled
        // with the selected colour and dark ink. Concentric: well radius = segment radius + gap.
        LinearLayout row = row(ctx);
        row.setBackground(pill(ctx, WELL, 0));
        row.setPadding(pad(d, 2), pad(d, 2), pad(d, 2), pad(d, 2));
        addScope(ctx, host, row, d, Scope.SELECTED,
                ctx.getString(R.string.lane_b_puppet_scope_selected));
        addScope(ctx, host, row, d, Scope.CHARACTER,
                ctx.getString(R.string.lane_b_puppet_scope_character));
        addScope(ctx, host, row, d, Scope.RECORDING,
                ctx.getString(R.string.lane_b_puppet_scope_recording));
        return row;
    }

    private static void addScope(@NonNull Context ctx, @NonNull Host host,
                                 @NonNull LinearLayout parent, float d,
                                 @NonNull Scope scope, @NonNull String label) {
        boolean on = host.scope() == scope;
        TextView t = text(ctx, d, 10f, on ? ON_FILL_INK : TXT_DIM);
        com.fadcam.ui.type.Type.mono(t, com.fadcam.ui.type.Type.SEMIBOLD);
        t.setText(label);
        t.setLetterSpacing(0.06f);
        t.setSingleLine(true);
        t.setAllCaps(true);   // after setSingleLine: both are TransformationMethods, last one wins
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(pad(d, 34));
        t.setPadding(pad(d, 3), pad(d, 6), pad(d, 3), pad(d, 6));
        if (on) t.setBackground(pill(ctx, Studio.ARMED, 0));
        hoverLabel(t, ctx.getString(on ? R.string.lane_b_puppet_scope_on
                : R.string.lane_b_puppet_scope, label));
        press(t);
        t.setOnClickListener(v -> { host.setScope(scope); host.rebuildRows(); });
        parent.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    // ── scope: SELECTED ──────────────────────────────────────────────────

    private static void selectedRows(@NonNull Context ctx, @NonNull Host host,
                                     @NonNull LinearLayout root, float d) {
        PuppetRig rig = host.rig();
        int b = host.selectedBone();
        if (b >= 0 && b < rig.boneCount()) { boneRows(ctx, host, root, d, b); return; }
        int i = host.selectedPin();
        if (i < 0 || i >= rig.pinCount()) {
            root.addView(hint(ctx, d, ctx.getString(rig.pinCount() == 0
                    ? R.string.lane_b_puppet_hint_first_pin
                    : R.string.lane_b_puppet_hint_tap_pin)));
            return;
        }
        PuppetPin pin = rig.pin(i);
        int hue = PuppetPalette.of(pin.type, rig.locked);

        // DEPTH IS FIRST, and on every type. A shoulder behind the body with the hand in front of
        // it is one arm, so this cannot live on a piece or a layer — it has to be per pin, and the
        // engine blends the pins into a field so the limb hands over halfway along.
        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_pin_depth), hue,
                pin.depth, false, v -> pin.depth = v);

        // MUTE. The mesh builder has honoured it and the overlay has drawn a muted pin hollow
        // since both were written; there was simply never a way to set it. It is the answer to
        // JoyRaptor's condition on Dangle — "so long as it only disables keys and not erases or
        // overwrites them" — because muting takes a pin out of the weights while every key it
        // owns stays exactly where it is, and unmuting brings the whole performance back.
        LinearLayout pinToggles = row(ctx);
        addToggle(ctx, host, pinToggles, d, ctx.getString(pin.muted
                        ? R.string.lane_b_puppet_pin_muted : R.string.lane_b_puppet_pin_active),
                !pin.muted, v -> { pin.muted = !v; host.onRigStructureChanged(); });
        addToggle(ctx, host, pinToggles, d, ctx.getString(R.string.lane_b_puppet_pin_reach_ring),
                rig.showReach, v -> rig.showReach = v);
        root.addView(pinToggles);
        gap(ctx, root, d, 7);

        // THE WEIGHT OVERRIDE, and it starts as a toggle rather than a slider because the honest
        // default is "do not touch this". The falloff is already density-adaptive: put a second
        // pin beside the first and their influence splits with nothing to set. The slider only
        // appears once somebody has decided the automatic answer is wrong for this pin.
        LinearLayout weightRow = row(ctx);
        addToggle(ctx, host, weightRow, d, ctx.getString(R.string.lane_b_puppet_pin_auto_pull),
                pin.weightIsAuto(),
                v -> { pin.weight = v ? PuppetPin.WEIGHT_AUTO : 1f;
                       host.onRigStructureChanged(); });
        root.addView(weightRow);
        gap(ctx, root, d, 7);
        if (!pin.weightIsAuto()) {
            rangeSlider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_pin_pull), hue,
                    pin.weight, 0f, 3f, "\u00d7",
                    v -> { pin.weight = v; host.onRigStructureChanged(); });
        }

        switch (pin.type) {
            case DANGLE:
                // The tape has nothing to show for a dangling pin and must say so rather than
                // drawing an empty track, which reads as "your keys are gone".
                root.addView(hint(ctx, d, ctx.getString(R.string.lane_b_puppet_hint_dangle)));
                slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_pin_spring),
                        hue, pin.spring, false, v -> pin.spring = v);
                slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_pin_settle),
                        hue, pin.settle, false, v -> pin.settle = v);
                slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_pin_mass),
                        hue, pin.mass, false, v -> pin.mass = v);
                slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_pin_max_stretch),
                        hue, pin.maxStretch, false, v -> pin.maxStretch = v);
                break;
            case STIFF:
                slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_pin_stiff_area),
                        hue, pin.stiffArea, true, v -> pin.stiffArea = v);
                slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_pin_strength),
                        hue, pin.stiffStrength, false, v -> pin.stiffStrength = v);
                break;
            case FREE:
                // "Scale at this point" WAS here. PuppetPin.scale is persisted and read by
                // nothing: PuppetTopology.handleComponents() is 2, so a pin is x and y and has no
                // scale of its own for the deformer to apply. Exactly the defect that took the
                // rotate arc and the scale square with it on 2026-09-16, and removed for exactly
                // the same reason — a control that does nothing teaches the user the feature is
                // broken, which is worse than it plainly being absent.
                root.addView(hint(ctx, d, ctx.getString(R.string.lane_b_puppet_hint_free)));
                break;
            default:
                // An anchor has no settings of its OWN. It still has a depth, because every part
                // of a character is on some side of it.
                root.addView(hint(ctx, d, ctx.getString(R.string.lane_b_puppet_hint_anchor)));
                break;
        }

        // WHOSE KEYS THE TAPE IS SHOWING. One pin at a time was JoyRaptor’s own ruling — a
        // ghost strip of every other pin’s live take is "completely incomprehensible" — and the
        // price of that ruling is that the tape must say which pin it belongs to, or a user who
        // selects a second pin reads the change as their first pin’s keys vanishing.
        if (rig.pinCount() > 1) {
            root.addView(hint(ctx, d, ctx.getString(R.string.lane_b_puppet_hint_tape, pin.name)));
        }
    }

    /**
     * The one destructive control on this tab, dressed as one.
     *
     * <p>Red-edged and sunk rather than a filled red button: a filled one reads as the primary
     * action of the panel, which is the opposite of what this is. It says WHAT it will destroy in
     * its own subtitle, because "Start over" alone gives no sense of scale — and the confirmation
     * that follows is the host's.
     */
    @NonNull
    private static View dangerRow(@NonNull Context ctx, @NonNull Host host, float d,
                                  @NonNull String title, @NonNull String detail) {
        // ONE line, not two: a two-line tap target reads as two controls. Title in the destroy
        // colour, what it destroys beside it, cut with an ellipsis before it ever wraps — the
        // spoken/hover label always carries the whole sentence.
        LinearLayout cell = row(ctx);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setMinimumHeight(pad(d, 44));
        cell.setBackground(pill(ctx, WELL, Studio.alpha(DESTROY, 0x99)));
        cell.setPadding(pad(d, 14), 0, pad(d, 14), 0);

        TextView t = text(ctx, d, 11.5f, DESTROY);
        com.fadcam.ui.type.Type.body(t, com.fadcam.ui.type.Type.SEMIBOLD);
        t.setText(title);
        t.setSingleLine(true);
        cell.addView(t);

        String what = ctx.getString(R.string.lane_b_puppet_reset_desc, detail);
        TextView sub = text(ctx, d, 10f, TXT_DIM);
        sub.setText(what);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sublp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sublp.leftMargin = pad(d, 8);
        cell.addView(sub, sublp);

        hoverLabel(cell, ctx.getString(R.string.lane_b_puppet_reset_hover, title, what));
        press(cell);
        cell.setOnClickListener(v -> host.confirmResetRig());
        return cell;
    }

    // ── the chips ────────────────────────────────────────

    /**
     * One chip per pin, each in its own type colour, with a DOT when that pin holds animation.
     *
     * <p>Horizontally scrollable, because a character can have a dozen pins and the alternative
     * — wrapping to three lines — pushes the tools off the screen, which is the one thing row 1
     * was designed not to do.
     */
    @NonNull
    private static View chipRow(@NonNull Context ctx, @NonNull Host host, float d) {
        PuppetRig rig = host.rig();
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = row(ctx);

        int sel = host.selectedPin();
        for (int i = 0; i < rig.pinCount(); i++) {
            final int index = i;
            PuppetPin p = rig.pin(i);
            boolean hot = i == sel;
            int hue = PuppetPalette.of(p.type, rig.locked);

            // Record 06 .dchip: glass pill with a ring; the pin being edited is filled with its
            // own type colour at 70% and loses the ring. 30dp visible inside a 40dp target.
            LinearLayout chip = row(ctx);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            int inset = pad(d, 5);
            chip.setBackground(new android.graphics.drawable.InsetDrawable(
                    pill(ctx, hot ? Studio.alpha(hue, ON_ALPHA) : CTL_FILL, hot ? 0 : CTL_RING),
                    0, inset, 0, inset));
            chip.setPadding(pad(d, 13), pad(d, 7) + inset, pad(d, 13), pad(d, 7) + inset);
            chip.setMinimumHeight(pad(d, 40));

            TextView t = text(ctx, d, 11f, hot ? ON_FILL_INK : Studio.DRAWER_DIM);
            com.fadcam.ui.type.Type.body(t, hot ? com.fadcam.ui.type.Type.BOLD
                    : com.fadcam.ui.type.Type.SEMIBOLD);
            t.setText(p.name);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(t);

            // THE DOT. Only when there is something to see — an always-present dot would be
            // decoration rather than information.
            boolean simulated = p.type == PuppetPin.Type.DANGLE;
            if (simulated || host.keyCountOf(i) > 0) {
                View dot = new View(ctx);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                // On the filled chip the dot takes the chip's dark ink — a hue dot on its own
                // hue fill would vanish, and it is information, not decoration.
                int dotInk = hot ? ON_FILL_INK : hue;
                // A dangle pin is SIMULATED, so its dot is hollow: it has motion but no keys,
                // and a solid dot beside a pin whose tape is empty would be a straight lie.
                if (simulated) {
                    g.setColor(CLEAR);
                    g.setStroke(Math.max(1, Math.round(d)), dotInk);
                } else {
                    g.setColor(dotInk);
                }
                dot.setBackground(g);
                LinearLayout.LayoutParams dlp =
                        new LinearLayout.LayoutParams(pad(d, 6), pad(d, 6));
                dlp.leftMargin = pad(d, 6);
                chip.addView(dot, dlp);
            }

            hoverLabel(chip, ctx.getString(hot ? R.string.lane_b_puppet_chip_on
                    : R.string.lane_b_puppet_chip, p.name));
            press(chip);
            chip.setOnClickListener(v -> {
                host.setSelectedPin(index);
                host.setSelectedBone(-1);
                host.setScope(Scope.SELECTED);
                host.rebuildRows();
                host.onChanged();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = pad(d, 6);
            strip.addView(chip, lp);
        }
        scroller.addView(strip);
        return scroller;
    }

    // ── scope: SELECTED, when the selected thing is a BONE ──────────────

    /**
     * A bone's settings, which replace a pin's the way a Dangle's replace a Free's.
     *
     * <p>Every control here drives something {@code PuppetRigSolver} already consumes. There is
     * deliberately no key control and no record button: <b>a bone carries no keyframes</b> — it
     * writes to the pins it joins, which is the whole reason dragging a wrist does not author a
     * second source of truth for the same motion (SPEC §0).
     */
    private static void boneRows(@NonNull Context ctx, @NonNull Host host,
                                 @NonNull LinearLayout root, float d, int index) {
        PuppetRig rig = host.rig();
        final PuppetRig.Bone bone = rig.bone(index);
        int hue = PuppetPalette.BONE;

        boolean okRoot = bone.rootPin >= 0 && bone.rootPin < rig.pinCount();
        boolean okTip = bone.tipPin >= 0 && bone.tipPin < rig.pinCount();
        String rootName = okRoot ? rig.pin(bone.rootPin).name : "?";
        String tipName = okTip ? rig.pin(bone.tipPin).name : "?";

        // WHAT IT JOINS. Read-only, and two to a line, because this is how you tell which bone
        // you have got hold of when several cross in a shoulder.
        // The bone’s own name first — PuppetRig keeps them unique, and without it on screen the
        // only way to tell two bones apart was which pins they happened to join.
        LinearLayout title = row(ctx);
        String boneWord = ctx.getString(R.string.lane_b_puppet_bone);
        addReadout(ctx, title, d, boneWord, bone.name == null ? boneWord : bone.name);
        root.addView(title);
        gap(ctx, root, d, 7);

        LinearLayout ends = row(ctx);
        addReadout(ctx, ends, d, ctx.getString(R.string.lane_b_puppet_bone_from), rootName);
        addReadout(ctx, ends, d, ctx.getString(R.string.lane_b_puppet_bone_to), tipName);
        root.addView(ends);
        gap(ctx, root, d, 7);

        // LENGTH, as a share of the length the artwork was drawn at. Stored in unit space, where
        // 0 means "measure it from the rest pose" — so the slider shows 100% for an untouched
        // bone and writes an absolute length the moment it is moved.
        float measured = 0f;
        if (okRoot && okTip) {
            measured = (float) Math.hypot(rig.pin(bone.tipPin).restX - rig.pin(bone.rootPin).restX,
                    rig.pin(bone.tipPin).restY - rig.pin(bone.rootPin).restY);
        }
        final float rest = measured > 1e-5f ? measured : 0.2f;
        float shown = bone.restLength > 0f ? bone.restLength / rest : 1f;
        rangeSlider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_bone_length), hue,
                shown, 0.25f, 2f, "×",
                v -> bone.restLength = Math.abs(v - 1f) < 0.005f ? 0f : v * rest);

        // REST ANGLE is derived from where the two pins were drawn, so it is a readout rather
        // than a control — the way to change it is to move a pin, which is the honest gesture.
        float deg = 0f;
        if (okRoot && okTip) {
            deg = (float) Math.toDegrees(Math.atan2(
                    rig.pin(bone.tipPin).restY - rig.pin(bone.rootPin).restY,
                    rig.pin(bone.tipPin).restX - rig.pin(bone.rootPin).restX));
        }
        LinearLayout facts = row(ctx);
        addReadout(ctx, facts, d, ctx.getString(R.string.lane_b_puppet_bone_rest_angle),
                Math.round(deg) + "°");
        addReadout(ctx, facts, d, ctx.getString(R.string.lane_b_puppet_bone_rest_length),
                Math.round(rest * 100f) + "%");
        root.addView(facts);
        gap(ctx, root, d, 7);

        // STRETCHY and FLIP ELBOW, two to a line.
        LinearLayout bits = row(ctx);
        addToggle(ctx, host, bits, d, ctx.getString(R.string.lane_b_puppet_bone_stretchy),
                bone.stretchy, v -> bone.stretchy = v);
        // FLIP ELBOW. One bit instead of a pole-target object to position — SPEC §3. It was
        // stored, saved and honoured by FabrikSolver long before anything set it.
        addToggle(ctx, host, bits, d, ctx.getString(R.string.lane_b_puppet_bone_flip_elbow),
                bone.bendSign < 0, v -> bone.bendSign = v ? -1 : 1);
        root.addView(bits);
        gap(ctx, root, d, 7);

        // REACH — how far past its own length a stretchy bone may go. SPEC section 2 lists it in
        // the Bone scope and it was the one row never built; the value was authored nowhere and
        // FabrikSolver fell back to a hard-coded 0.35 for every bone. Only shown when Stretchy is
        // on, because a rigid bone has no reach beyond its length and a slider that does nothing
        // is worse than no slider.
        if (bone.stretchy) {
            slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_bone_reach),
                    PuppetPalette.BONE, bone.maxStretch, false, v -> bone.maxStretch = v);
        }

        if (bone.stretchy) {
            slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_bone_stretch), hue,
                    bone.maxStretch, false, v -> bone.maxStretch = v);
        } else {
            root.addView(hint(ctx, d, ctx.getString(R.string.lane_b_puppet_hint_rigid)));
        }

        LinearLayout limits = row(ctx);
        addToggle(ctx, host, limits, d, ctx.getString(R.string.lane_b_puppet_bone_joint_limits),
                bone.jointLimits, v -> bone.jointLimits = v);
        root.addView(limits);
        gap(ctx, root, d, 7);

        if (bone.jointLimits) {
            // The limit belongs to the pin this bone ARRIVES at — the knee, not the thigh —
            // which is exactly how puppetSolveChain reads it back out.
            rangeSlider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_bone_fold_back),
                    hue, bone.minAngleDeg, -180f, 0f, "°", v -> bone.minAngleDeg = v);
            rangeSlider(ctx, root, host, d,
                    ctx.getString(R.string.lane_b_puppet_bone_fold_forward),
                    hue, bone.maxAngleDeg, 0f, 180f, "°", v -> bone.maxAngleDeg = v);
        } else {
            root.addView(hint(ctx, d, ctx.getString(R.string.lane_b_puppet_hint_limits_off)));
        }

        root.addView(hint(ctx, d,
                ctx.getString(R.string.lane_b_puppet_hint_bone_keys, rootName, tipName)));
    }

    // ── scope: CHARACTER ─────────────────────────────────────────────────

    private static void characterRows(@NonNull Context ctx, @NonNull Host host,
                                      @NonNull LinearLayout root, float d) {
        PuppetRig rig = host.rig();
        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_rig_softness),
                host.accent(), rig.softness, true, v -> rig.softness = v);
        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_rig_mesh_detail),
                host.accent(), rig.meshDetail, false, v -> rig.meshDetail = v);
        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_rig_gravity),
                PuppetPalette.DANGLE, rig.gravity, false, v -> rig.gravity = v);
        // WIND. PuppetRigSolver.paramsFor has taken both of these since it was written and
        // PuppetDangleBake passes them straight through; only the two controls were missing.
        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_rig_wind),
                PuppetPalette.DANGLE, rig.wind, false, v -> rig.wind = v);
        if (rig.wind > 0.001f) {
            // Shown only when there IS wind, because a direction for no wind is a row that
            // cannot do anything — and rows that cannot do anything are the thing being removed.
            angleSlider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_rig_wind_dir),
                    PuppetPalette.DANGLE, rig.windDirDeg, 0f, 360f, v -> rig.windDirDeg = v);
        }

        // Three toggles, ONE line.
        LinearLayout showRow = row(ctx);
        addToggle(ctx, host, showRow, d, ctx.getString(R.string.lane_b_puppet_rig_show_pins),
                rig.showPins, v -> rig.showPins = v);
        addToggle(ctx, host, showRow, d, ctx.getString(R.string.lane_b_puppet_rig_show_bones),
                rig.showBones, v -> rig.showBones = v);
        addToggle(ctx, host, showRow, d, ctx.getString(R.string.lane_b_puppet_rig_show_mesh),
                rig.showMesh, v -> rig.showMesh = v);
        root.addView(showRow);
        gap(ctx, root, d, 7);

        root.addView(fold(ctx, d, ctx.getString(R.string.lane_b_puppet_rig_rarely)));
        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_rig_edge_threshold),
                host.accent(), rig.edgeThreshold, false, v -> rig.edgeThreshold = v);
        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_rig_edge_expansion),
                host.accent(), rig.edgeExpansion, false, v -> rig.edgeExpansion = v);

        // The LOCK is deliberately NOT here. It lives on the puppet badge in the corner of the
        // preview, because the accident it prevents — knocking a pin on a sprite that happens to
        // have them — happens while this drawer is SHUT.
        root.addView(hint(ctx, d, ctx.getString(R.string.lane_b_puppet_hint_put_away)));

        // START OVER. JoyRaptor asked for this in as many words: somebody who gets overwhelmed
        // needs a way back to a blank picture without deleting the picture.
        //
        // LAST, and only once there is something to destroy. It is the one control here that
        // cannot be undone by doing the opposite of it, so it does not belong anywhere a thumb
        // travels past on the way to something else — and a Start over on a rig with no pins
        // would be a button that does nothing, dressed up in a warning.
        if (rig.pinCount() > 0) {
            gap(ctx, root, d, 10);
            android.content.res.Resources res = ctx.getResources();
            String pins = res.getQuantityString(R.plurals.lane_b_puppet_count_pins,
                    rig.pinCount(), rig.pinCount());
            String what = rig.boneCount() > 0
                    ? ctx.getString(R.string.lane_b_puppet_count_join, pins,
                            res.getQuantityString(R.plurals.lane_b_puppet_count_bones,
                                    rig.boneCount(), rig.boneCount()))
                    : pins;
            root.addView(dangerRow(ctx, host, d,
                    ctx.getString(R.string.lane_b_puppet_reset), what));
        }
    }

    // ── scope: RECORDING ─────────────────────────────────────────────────

    private static void recordingRows(@NonNull Context ctx, @NonNull Host host,
                                      @NonNull LinearLayout root, float d) {
        PuppetRig rig = host.rig();

        LinearLayout pair = row(ctx);
        addToggle(ctx, host, pair, d, ctx.getString(R.string.lane_b_puppet_record_on_touch),
                rig.recordOnTouch, v -> rig.recordOnTouch = v);
        addToggle(ctx, host, pair, d, ctx.getString(R.string.lane_b_puppet_record_snap),
                rig.snapToKeys, v -> rig.snapToKeys = v);
        root.addView(pair);
        gap(ctx, root, d, 7);

        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_record_detail),
                host.accent(), rig.detail, false, v -> rig.detail = v);
        // Stored in ms, shown as a fraction of half a second — a number nobody wants to type.
        slider(ctx, root, host, d, ctx.getString(R.string.lane_b_puppet_record_blend_out),
                host.accent(), rig.blendOutMs / 500f, false,
                v -> rig.blendOutMs = Math.round(v * 500f));
        root.addView(hint(ctx, d, ctx.getString(R.string.lane_b_puppet_hint_punch_in)));
    }

    // ── controls ─────────────────────────────────────────────────────────

    private interface FloatSink { void accept(float v); }

    /**
     * One compact slider: the reach EYE on the left, then label and value on one line with the
     * slider under them, stopping short of the right edge.
     *
     * <p>The eye is not decoration. On it means the pin's reach ring stays drawn on the picture;
     * off means it appears only while you are dragging this slider. JoyRaptor asked for exactly
     * that, and it is why a slider that has no reach to show ({@code eye == false}) leaves the
     * space blank rather than showing a dead eye.
     */
    private static void slider(@NonNull Context ctx, @NonNull LinearLayout root,
                               @NonNull Host host, float d, @NonNull String label, int hue,
                               float value, boolean eye, @NonNull FloatSink sink) {
        sliderRaw(ctx, root, host, d, label, hue, value, eye, sink, PuppetDrawerTabs::percent);
    }

    /** What a slider's number says. Per cent for a 0..1 knob, degrees or multiples otherwise. */
    private interface Readout { String of(float norm); }

    private static void sliderRaw(@NonNull Context ctx, @NonNull LinearLayout root,
                                  @NonNull Host host, float d, @NonNull String label, int hue,
                                  float value, boolean eye, @NonNull FloatSink sink,
                                  @NonNull Readout readout) {
        // Record 06 .dr: a bare 46dp line on the scrim — no box behind it. The label is the
        // record's section label (.dsec), the number its mono value, the bar its .dsl.
        LinearLayout row = row(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(pad(d, 46));
        row.setPadding(0, pad(d, 2), pad(d, 4), pad(d, 2));

        if (eye) {
            // THE REACH RING, which is what this eye has always claimed to control. It used to
            // toggle showPins — a duplicate of the Character row's own switch, while the ring it
            // named was drawn nowhere. Now: on = the ring stays on the picture; off = it appears
            // only while this slider is being dragged, which is the behaviour JoyRaptor asked for.
            ImageView eyeView = new ImageView(ctx);
            boolean showing = host.rig().showReach;
            eyeView.setImageDrawable(PuppetIcons.of(PuppetIcons.EYE,
                    showing ? hue : TXT_DIM, pad(d, 16)));
            // A 16dp eye in a 32dp target: grow the hit area, not the glyph.
            eyeView.setPadding(pad(d, 8), pad(d, 8), pad(d, 8), pad(d, 8));
            hoverLabel(eyeView, ctx.getString(showing ? R.string.lane_b_puppet_eye_on
                    : R.string.lane_b_puppet_eye_off));
            eyeView.setOnClickListener(v -> {
                host.rig().showReach = !host.rig().showReach;
                host.rebuildRows();
                host.onChanged();
            });
            LinearLayout.LayoutParams elp =
                    new LinearLayout.LayoutParams(pad(d, 32), pad(d, 32));
            elp.rightMargin = pad(d, 3);
            row.addView(eyeView, elp);
        } else {
            View spacer = new View(ctx);
            LinearLayout.LayoutParams slp =
                    new LinearLayout.LayoutParams(pad(d, 32), pad(d, 1));
            slp.rightMargin = pad(d, 3);
            row.addView(spacer, slp);
        }

        LinearLayout body = column(ctx);

        LinearLayout top = row(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = sectionLabel(ctx, d, label);
        lab.setPadding(0, 0, pad(d, 6), 0);
        top.addView(lab, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = valueText(ctx, d);
        val.setText(readout.of(value));
        top.addView(val);
        body.addView(top);

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(SLIDER_STEPS);
        bar.setProgress(Math.round(clamp01(value) * SLIDER_STEPS));
        // The Kit's slider face, filled with THIS slider's colour at 70% (a pin type's hue, or
        // the rig's) — it says which thing the number belongs to (drawer audit 2026-09-24, U1).
        ObjectDrawer.Kit.styleSlider(bar, ObjectDrawer.Kit.onFill(hue));
        bar.setContentDescription(label);
        bar.setPadding(pad(d, 8), pad(d, 4), pad(d, 8), pad(d, 4));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /** The value the drag started from — the ONE thing undo has to put back. */
            private float before = value;

            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                float v = p / (float) SLIDER_STEPS;
                sink.accept(v);
                val.setText(readout.of(v));
                host.onChanged();
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {
                before = sb.getProgress() / (float) SLIDER_STEPS;
                if (eye) host.setReachPreview(true);
            }

            /**
             * ONE undo step for the WHOLE drag, not one per pixel — the standing ruling. The
             * step is recorded here rather than in onProgressChanged for exactly that reason,
             * and it is skipped entirely when the drag ended where it started, so a stray touch
             * does not leave an undo press that appears to do nothing.
             */
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (eye) host.setReachPreview(false);
                final float after = sb.getProgress() / (float) SLIDER_STEPS;
                final float from = before;
                if (Math.abs(after - from) < 1e-4f) return;
                host.recordUndo(label,
                        () -> { sink.accept(after); sb.setProgress(Math.round(after * SLIDER_STEPS));
                                val.setText(readout.of(after)); host.onChanged(); },
                        () -> { sink.accept(from); sb.setProgress(Math.round(from * SLIDER_STEPS));
                                val.setText(readout.of(from)); host.onChanged(); });
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = pad(d, 6);
        body.addView(bar, blp);

        row.addView(body, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        gap(ctx, root, d, 3);
    }

    /**
     * A slider over an arbitrary range, shown in its own units.
     *
     * <p>{@link #slider} only speaks 0..1 as a percentage, which is right for softness and wrong
     * for a fold limit in degrees or a length as a multiple. Rather than teach one method two
     * jobs, this one converts at the edges and shares everything else.
     */
    private static void rangeSlider(@NonNull Context ctx, @NonNull LinearLayout root,
                                    @NonNull Host host, float d, @NonNull String label, int hue,
                                    float value, float min, float max, @NonNull String unit,
                                    @NonNull FloatSink sink) {
        final float span = max - min;
        float norm = span <= 0f ? 0f : (value - min) / span;
        sliderRaw(ctx, root, host, d, label, hue, clamp01(norm), false,
                v -> sink.accept(min + v * span),
                v -> fmt(min + v * span) + unit);
    }

    /** Degrees, wrapped rather than clamped — 359° and 1° are neighbours, not opposites. */
    private static void angleSlider(@NonNull Context ctx, @NonNull LinearLayout root,
                                    @NonNull Host host, float d, @NonNull String label, int hue,
                                    float value, float min, float max, @NonNull FloatSink sink) {
        rangeSlider(ctx, root, host, d, label, hue, value, min, max, "°", sink);
    }

    @NonNull
    private static String fmt(float v) {
        return Math.abs(v - Math.round(v)) < 0.05f
                ? String.valueOf(Math.round(v))
                : String.format(java.util.Locale.US, "%.2f", v);
    }

    /**
     * A number you can read and cannot change. Shares its line, like everything else here.
     *
     * <p>Record 06's scrub typography (.dscrub: small label, mono value) with NO pill and no
     * ring: the pill is what says "this is a control", and a readout is not one.
     */
    private static void addReadout(@NonNull Context ctx, @NonNull LinearLayout parent, float d,
                                   @NonNull String label, @NonNull String value) {
        LinearLayout cell = row(ctx);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setMinimumHeight(pad(d, 32));
        cell.setPadding(pad(d, 2), 0, pad(d, 8), 0);

        TextView l = text(ctx, d, 9.5f, TXT_DIM);
        l.setText(label);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(l, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = valueText(ctx, d);
        v.setText(value);
        v.setSingleLine(true);
        cell.addView(v);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = pad(d, 6);
        parent.addView(cell, lp);
    }

    private interface BoolSink { void accept(boolean v); }

    /**
     * A toggle that shares its line. Never gets one of its own — that is the whole point.
     *
     * <p>Record 06's drawer checkbox (.dcb): an 18dp box, then the word; the whole 44dp cell is
     * the target. On = the box filled with the accent at 70% and a dark tick, word in drawer
     * ink. It replaces a boxed cell with a GO-green pip — GO is the colour of pressing to make
     * something happen, and a toggle's state is not that.
     */
    private static void addToggle(@NonNull Context ctx, @NonNull Host host,
                                  @NonNull LinearLayout parent, float d,
                                  @NonNull String label, boolean on, @NonNull BoolSink sink) {
        LinearLayout cell = row(ctx);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setMinimumHeight(pad(d, 44));
        cell.setPadding(pad(d, 2), 0, pad(d, 4), 0);

        View box = new View(ctx);
        // The shared .dcb face (drawer audit 2026-09-24, U1/U5): a drawn box rather than a
        // CheckBox because the whole 44dp CELL is the control, not the box.
        box.setBackground(TextOverlayDrawer.Kit.checkbox(ctx, on,
                ObjectDrawer.Kit.onFill(host.accent()), TXT_DIM));
        cell.addView(box, new LinearLayout.LayoutParams(pad(d, 18), pad(d, 18)));

        TextView t = text(ctx, d, 11.5f, on ? TXT : Studio.DRAWER_DIM);
        com.fadcam.ui.type.Type.body(t, com.fadcam.ui.type.Type.MEDIUM);
        t.setText(label);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = pad(d, 8);
        cell.addView(t, tlp);

        hoverLabel(cell, ctx.getString(on ? R.string.lane_b_puppet_toggle_on
                : R.string.lane_b_puppet_toggle_off, label));
        press(cell);
        cell.setOnClickListener(v -> {
            sink.accept(!on);
            host.rebuildRows();
            host.onChanged();
        });

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = pad(d, 6);
        parent.addView(cell, lp);
    }

    // ── small pieces ─────────────────────────────────────────────────────

    /** "Rarely needed" — the record's section label, like every other heading on the tab. */
    @NonNull
    private static View fold(@NonNull Context ctx, float d, @NonNull String text) {
        TextView t = sectionLabel(ctx, d, text);
        t.setPadding(pad(d, 2), pad(d, 7), pad(d, 2), pad(d, 3));
        return t;
    }

    @NonNull
    private static View hint(@NonNull Context ctx, float d, @NonNull String text) {
        TextView t = text(ctx, d, 10.5f, TXT_DIM);
        t.setText(text);
        t.setPadding(pad(d, 2), pad(d, 5), pad(d, 2), pad(d, 3));
        return t;
    }

    @NonNull
    private static LinearLayout column(@NonNull Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    @NonNull
    private static LinearLayout row(@NonNull Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private static void gap(@NonNull Context ctx, @NonNull LinearLayout parent, float d, int dp) {
        View v = new View(ctx);
        parent.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, pad(d, dp)));
    }

    // ── the drawer kit — ObjectDrawer.Kit, asked for by this file's old names ──────────
    // These used to be a private copy of the kit (drawer audit 2026-09-24, U1): its own slider,
    // checkbox face, pill, press and hover label, and a drop shadow on every word. They are thin
    // now: each says which shared piece a control is, plus the one local fact it needs.

    /** Plain drawer text: body face in {@code colour}. No drop shadow — the Kit drawers have none. */
    @NonNull
    private static TextView text(@NonNull Context ctx, float d, float sp, int colour) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(colour);
        com.fadcam.ui.type.Type.body(t, com.fadcam.ui.type.Type.REGULAR);
        return t;
    }

    /** Record 06 {@code .dsec}, from the Kit; ellipsised, because a slider label shares its line. */
    @NonNull
    private static TextView sectionLabel(@NonNull Context ctx, float d, @NonNull String label) {
        TextView t = ObjectDrawer.Kit.sectionLabel(ctx, label);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** Record 06 {@code .dscrub b}: the shared mono, tabular readout. */
    @NonNull
    private static TextView valueText(@NonNull Context ctx, float d) {
        return TextOverlayDrawer.Kit.valueText(ctx);
    }

    /** A fully round shape — "Fill=Pill controls" — from the Kit. {@code ring} 0 = none. */
    @NonNull
    private static GradientDrawable pill(@NonNull Context ctx, int fill, int ring) {
        return ObjectDrawer.Kit.pill(ctx, fill, ring);
    }

    /**
     * Press feedback, from the Kit: 140ms to 0.97 on the pressed state. Not used on the record
     * button or the ‹♦› — record 06 never animates keying.
     */
    private static void press(@NonNull View v) {
        ObjectDrawer.Kit.pressable(v);
    }

    /** Spoken name AND hover tooltip, through the Kit. */
    private static void hoverLabel(@NonNull View v, @NonNull CharSequence label) {
        ObjectDrawer.Kit.describe(v, label);
    }

    @NonNull
    private static String percent(float v) {
        return Math.round(clamp01(v) * 100f) + "%";
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private static float density(@NonNull Context ctx) {
        return ctx.getResources().getDisplayMetrics().density;
    }

    private static int pad(float d, int dp) { return Math.round(dp * d); }
}
