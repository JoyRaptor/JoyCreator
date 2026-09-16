package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Typeface;
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
    private static final int TXT = 0xFFE8E8E8;
    private static final int TXT_DIM = 0xFFA0A0A0;
    private static final int TXT_FAINT = 0xFF7C848F;
    private static final int ROW_BG = 0x552A3038;
    private static final int ROW_LINE = 0x33FFFFFF;
    private static final int SUNK_BG = 0x66101318;
    private static final int GO = 0xFF57B45C;
    private static final int REC = 0xFFF2727F;
    private static final int OFF = 0xFF4E5561;
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

        @NonNull Tool tool();
        void setTool(@NonNull Tool t);

        @NonNull Scope scope();
        void setScope(@NonNull Scope s);

        long playheadMs();

        /** A value changed — repaint preview and timeline, and schedule a save. */
        void onChanged();

        /** Rebuild these rows in place (the tool row and the scope body both depend on state). */
        void rebuildRows();

        /** Record ONE undo step. A chain drag is one press, so this is never called per pin. */
        void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo);

        // ── the selected pin's keys, for the ‹♦› ──────────────────────────

        /** How many keys the selected pin has. 0 when it has none or nothing is selected. */
        int keyCount();
        /** 1-based position of the key at the playhead, or 0 when the playhead is between keys. */
        int keyIndexAtPlayhead();
        boolean playheadIsOnKey();
        void dropKeyAtPlayhead();
        void deleteKeyAtPlayhead();
        void jumpToPrevKey();
        void jumpToNextKey();
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

        // 1 · identity + keys + record. Only once something exists to name.
        if (rig.pinCount() > 0 && host.selectedPin() >= 0
                && host.selectedPin() < rig.pinCount()) {
            root.addView(identityRow(ctx, host, d, refreshers));
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

        LinearLayout row = row(ctx);
        row.setBackground(rowBg(d, ROW_BG));
        row.setPadding(pad(d, 9), pad(d, 4), pad(d, 5), pad(d, 4));

        // the type, as a dot — the same hue its keyframes wear on the tape
        View swatch = new View(ctx);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(hue);
        swatch.setBackground(dot);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(pad(d, 9), pad(d, 9));
        slp.rightMargin = pad(d, 8);
        row.addView(swatch, slp);

        // the name. This is the thing the assistant reads.
        EditText name = new EditText(ctx);
        name.setText(pin.name);
        name.setTextColor(TXT);
        name.setTextSize(12.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        name.setBackground(rowBg(d, 0x22FFFFFF));
        name.setPadding(pad(d, 7), pad(d, 3), pad(d, 7), pad(d, 3));
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
        TextView pos = new TextView(ctx);
        pos.setTextColor(TXT_FAINT);
        pos.setTextSize(9.5f);
        pos.setMinWidth(pad(d, 28));
        pos.setGravity(Gravity.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = pad(d, 3);
        plp.rightMargin = pad(d, 5);
        row.addView(pos, plp);

        Runnable refreshPos = () -> {
            int n = host.keyCount();
            if (pin.isSimulated()) { pos.setText("sim"); return; }
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
        wrap.setBackground(rowBg(d, rig.recordOnTouch ? 0x33F2727F : 0x00000000,
                rig.recordOnTouch ? REC : ROW_LINE, d));

        View dotView = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(rig.recordOnTouch ? REC : OFF);
        dotView.setBackground(g);
        wrap.addView(dotView, new LinearLayout.LayoutParams(pad(d, 10), pad(d, 10)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pad(d, 30), pad(d, 30));
        wrap.setLayoutParams(lp);
        wrap.setContentDescription(rig.recordOnTouch
                ? "Recording when you touch a pin. Tap to stop."
                : "Not recording on touch. Tap to arm.");
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
        addTool(ctx, host, row, d, Tool.GRAB,   PuppetIcons.GRAB,   "Grab",   TXT);
        addTool(ctx, host, row, d, Tool.PIN,    PuppetIcons.PIN,    "Pin",    PuppetPalette.PIN);
        addTool(ctx, host, row, d, Tool.STIFF,  PuppetIcons.STIFF,  "Stiff",  PuppetPalette.STIFF);
        addTool(ctx, host, row, d, Tool.DANGLE, PuppetIcons.DANGLE, "Dangle", PuppetPalette.DANGLE);
        addTool(ctx, host, row, d, Tool.FREE,   PuppetIcons.FREE,   "Free",   PuppetPalette.FREE);
        addTool(ctx, host, row, d, Tool.BONE,   PuppetIcons.BONE,   "Bone",   PuppetPalette.BONE);
        return row;
    }

    private static void addTool(@NonNull Context ctx, @NonNull Host host,
                                @NonNull LinearLayout parent, float d,
                                @NonNull Tool tool, @NonNull String icon,
                                @NonNull String label, int hue) {
        boolean on = host.tool() == tool;

        LinearLayout cell = column(ctx);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(pad(d, 1), pad(d, 7), pad(d, 1), pad(d, 6));
        cell.setBackground(rowBg(d, on ? mix(hue, 0x26) : ROW_BG, on ? hue : ROW_LINE, d));

        ImageView glyph = new ImageView(ctx);
        // OFF is grey, never a faded colour — the rule SpriteIcons states and this set keeps.
        glyph.setImageDrawable(PuppetIcons.of(icon, on ? hue : TXT_DIM, pad(d, 18)));
        cell.addView(glyph, new LinearLayout.LayoutParams(pad(d, 18), pad(d, 18)));

        TextView text = new TextView(ctx);
        text.setText(label);
        text.setTextSize(10f);
        text.setTextColor(on ? TXT : TXT_DIM);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = pad(d, 3);
        cell.addView(text, tlp);

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
        LinearLayout row = row(ctx);
        row.setBackground(rowBg(d, SUNK_BG, ROW_LINE, d));
        row.setPadding(pad(d, 3), pad(d, 3), pad(d, 3), pad(d, 3));
        addScope(ctx, host, row, d, Scope.SELECTED, "Selected");
        addScope(ctx, host, row, d, Scope.CHARACTER, "Character");
        addScope(ctx, host, row, d, Scope.RECORDING, "Recording");
        return row;
    }

    private static void addScope(@NonNull Context ctx, @NonNull Host host,
                                 @NonNull LinearLayout parent, float d,
                                 @NonNull Scope scope, @NonNull String label) {
        boolean on = host.scope() == scope;
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setAllCaps(true);
        t.setTextSize(10f);
        t.setLetterSpacing(0.04f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(on ? TXT : TXT_FAINT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(pad(d, 3), pad(d, 6), pad(d, 3), pad(d, 6));
        if (on) t.setBackground(rowBg(d, ROW_BG));
        t.setOnClickListener(v -> { host.setScope(scope); host.rebuildRows(); });
        parent.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    // ── scope: SELECTED ──────────────────────────────────────────────────

    private static void selectedRows(@NonNull Context ctx, @NonNull Host host,
                                     @NonNull LinearLayout root, float d) {
        PuppetRig rig = host.rig();
        int i = host.selectedPin();
        if (i < 0 || i >= rig.pinCount()) {
            root.addView(hint(ctx, d, rig.pinCount() == 0
                    ? "Tap the picture to place your first pin"
                    : "Tap a pin to edit it"));
            return;
        }
        PuppetPin pin = rig.pin(i);
        int hue = PuppetPalette.of(pin.type, rig.locked);

        // DEPTH IS FIRST, and on every type. A shoulder behind the body with the hand in front of
        // it is one arm, so this cannot live on a piece or a layer — it has to be per pin, and the
        // engine blends the pins into a field so the limb hands over halfway along.
        slider(ctx, root, host, d, "Depth — behind / in front", hue, pin.depth, false,
                v -> pin.depth = v);

        switch (pin.type) {
            case DANGLE:
                slider(ctx, root, host, d, "Springiness", hue, pin.spring, false,
                        v -> pin.spring = v);
                slider(ctx, root, host, d, "Settle", hue, pin.settle, false,
                        v -> pin.settle = v);
                slider(ctx, root, host, d, "Mass", hue, pin.mass, false,
                        v -> pin.mass = v);
                slider(ctx, root, host, d, "Max stretch", hue, pin.maxStretch, false,
                        v -> pin.maxStretch = v);
                break;
            case STIFF:
                slider(ctx, root, host, d, "Stiff area", hue, pin.stiffArea, true,
                        v -> pin.stiffArea = v);
                slider(ctx, root, host, d, "Strength", hue, pin.stiffStrength, false,
                        v -> pin.stiffStrength = v);
                break;
            case FREE:
                slider(ctx, root, host, d, "Scale at this point", hue, pin.scale, true,
                        v -> pin.scale = v);
                root.addView(hint(ctx, d, "Turn and scale it on the picture — arc and square"));
                break;
            default:
                // An anchor has no settings of its OWN. It still has a depth, because every part
                // of a character is on some side of it.
                root.addView(hint(ctx, d, "An anchor has nothing else to set — that is the point"));
                break;
        }
    }

    // ── scope: CHARACTER ─────────────────────────────────────────────────

    private static void characterRows(@NonNull Context ctx, @NonNull Host host,
                                      @NonNull LinearLayout root, float d) {
        PuppetRig rig = host.rig();
        slider(ctx, root, host, d, "Softness", GO, rig.softness, true, v -> rig.softness = v);
        slider(ctx, root, host, d, "Mesh detail", GO, rig.meshDetail, false, v -> rig.meshDetail = v);
        slider(ctx, root, host, d, "Gravity", PuppetPalette.DANGLE, rig.gravity, false,
                v -> rig.gravity = v);

        // Three toggles, ONE line.
        LinearLayout showRow = row(ctx);
        addToggle(ctx, host, showRow, d, "Pins", rig.showPins, v -> rig.showPins = v);
        addToggle(ctx, host, showRow, d, "Bones", rig.showBones, v -> rig.showBones = v);
        addToggle(ctx, host, showRow, d, "Mesh", rig.showMesh, v -> rig.showMesh = v);
        root.addView(showRow);
        gap(ctx, root, d, 7);

        root.addView(fold(ctx, d, "Rarely needed"));
        slider(ctx, root, host, d, "Edge threshold", GO, rig.edgeThreshold, false,
                v -> rig.edgeThreshold = v);
        slider(ctx, root, host, d, "Edge expansion", GO, rig.edgeExpansion, false,
                v -> rig.edgeExpansion = v);

        // The LOCK is deliberately NOT here. It lives on the puppet badge in the corner of the
        // preview, because the accident it prevents — knocking a pin on a sprite that happens to
        // have them — happens while this drawer is SHUT.
        root.addView(hint(ctx, d,
                "Lock the pins from the puppet badge on the picture"));
    }

    // ── scope: RECORDING ─────────────────────────────────────────────────

    private static void recordingRows(@NonNull Context ctx, @NonNull Host host,
                                      @NonNull LinearLayout root, float d) {
        PuppetRig rig = host.rig();

        LinearLayout pair = row(ctx);
        addToggle(ctx, host, pair, d, "Record on touch", rig.recordOnTouch,
                v -> rig.recordOnTouch = v);
        addToggle(ctx, host, pair, d, "Snap to keys", rig.snapToKeys, v -> rig.snapToKeys = v);
        root.addView(pair);
        gap(ctx, root, d, 7);

        slider(ctx, root, host, d, "Detail of recorded moves", GO, rig.detail, false,
                v -> rig.detail = v);
        // Stored in ms, shown as a fraction of half a second — a number nobody wants to type.
        slider(ctx, root, host, d, "Blend out", GO, rig.blendOutMs / 500f, false,
                v -> rig.blendOutMs = Math.round(v * 500f));
        root.addView(hint(ctx, d,
                "A punch-in starts where the old move was, and eases back at the end"));
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
        LinearLayout row = row(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rowBg(d, ROW_BG));
        row.setPadding(pad(d, 6), pad(d, 5), pad(d, 10), pad(d, 5));

        if (eye) {
            ImageView eyeView = new ImageView(ctx);
            boolean showing = host.rig().showPins;
            eyeView.setImageDrawable(PuppetIcons.of(PuppetIcons.EYE,
                    showing ? hue : OFF, pad(d, 16)));
            eyeView.setOnClickListener(v -> {
                host.rig().showPins = !host.rig().showPins;
                host.rebuildRows();
                host.onChanged();
            });
            LinearLayout.LayoutParams elp =
                    new LinearLayout.LayoutParams(pad(d, 24), pad(d, 24));
            elp.rightMargin = pad(d, 7);
            row.addView(eyeView, elp);
        } else {
            View spacer = new View(ctx);
            LinearLayout.LayoutParams slp =
                    new LinearLayout.LayoutParams(pad(d, 24), pad(d, 1));
            slp.rightMargin = pad(d, 7);
            row.addView(spacer, slp);
        }

        LinearLayout body = column(ctx);

        LinearLayout top = row(ctx);
        TextView lab = new TextView(ctx);
        lab.setText(label);
        lab.setTextSize(10.5f);
        lab.setTextColor(TXT_DIM);
        lab.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(lab, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(ctx);
        val.setTextSize(10.5f);
        val.setTextColor(TXT);
        val.setText(percent(value));
        top.addView(val);
        body.addView(top);

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(SLIDER_STEPS);
        bar.setProgress(Math.round(clamp01(value) * SLIDER_STEPS));
        bar.getProgressDrawable().setTint(hue);
        bar.getThumb().setTint(hue);
        bar.setPadding(0, pad(d, 2), 0, pad(d, 2));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /** The value the drag started from — the ONE thing undo has to put back. */
            private float before = value;

            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                float v = p / (float) SLIDER_STEPS;
                sink.accept(v);
                val.setText(percent(v));
                host.onChanged();
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {
                before = sb.getProgress() / (float) SLIDER_STEPS;
            }

            /**
             * ONE undo step for the WHOLE drag, not one per pixel — the standing ruling. The
             * step is recorded here rather than in onProgressChanged for exactly that reason,
             * and it is skipped entirely when the drag ended where it started, so a stray touch
             * does not leave an undo press that appears to do nothing.
             */
            @Override public void onStopTrackingTouch(SeekBar sb) {
                final float after = sb.getProgress() / (float) SLIDER_STEPS;
                final float from = before;
                if (Math.abs(after - from) < 1e-4f) return;
                host.recordUndo(label,
                        () -> { sink.accept(after); sb.setProgress(Math.round(after * SLIDER_STEPS));
                                val.setText(percent(after)); host.onChanged(); },
                        () -> { sink.accept(from); sb.setProgress(Math.round(from * SLIDER_STEPS));
                                val.setText(percent(from)); host.onChanged(); });
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = pad(d, 6);
        body.addView(bar, blp);

        row.addView(body, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        gap(ctx, root, d, 7);
    }

    private interface BoolSink { void accept(boolean v); }

    /** A toggle that shares its line. Never gets one of its own — that is the whole point. */
    private static void addToggle(@NonNull Context ctx, @NonNull Host host,
                                  @NonNull LinearLayout parent, float d,
                                  @NonNull String label, boolean on, @NonNull BoolSink sink) {
        LinearLayout cell = row(ctx);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setBackground(rowBg(d, ROW_BG, on ? mix(GO, 0x88) : ROW_LINE, d));
        cell.setPadding(pad(d, 8), pad(d, 6), pad(d, 8), pad(d, 6));

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(10.5f);
        t.setTextColor(on ? TXT : TXT_DIM);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        cell.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View pip = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? GO : OFF);
        pip.setBackground(g);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(pad(d, 10), pad(d, 10));
        plp.leftMargin = pad(d, 6);
        cell.addView(pip, plp);

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

    @NonNull
    private static View fold(@NonNull Context ctx, float d, @NonNull String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setAllCaps(true);
        t.setTextSize(9.5f);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TXT_FAINT);
        t.setPadding(pad(d, 2), pad(d, 6), pad(d, 2), pad(d, 6));
        return t;
    }

    @NonNull
    private static View hint(@NonNull Context ctx, float d, @NonNull String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(10.5f);
        t.setTextColor(TXT_FAINT);
        t.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
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

    @NonNull
    private static GradientDrawable rowBg(float d, int fill) {
        return rowBg(d, fill, 0, d);
    }

    @NonNull
    private static GradientDrawable rowBg(float d, int fill, int stroke, float unused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(4f * d);
        if (stroke != 0) g.setStroke(Math.max(1, Math.round(d)), stroke);
        return g;
    }

    /** The hue at {@code alpha}, for a tinted background that still shows the video behind it. */
    private static int mix(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
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
