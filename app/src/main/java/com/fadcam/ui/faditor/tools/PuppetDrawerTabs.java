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

        LinearLayout row = row(ctx);
        row.setBackground(rowBg(d, ROW_BG));
        row.setPadding(pad(d, 9), pad(d, 4), pad(d, 5), pad(d, 4));

        // THE TYPE, as the pin's own silhouette — and it CHANGES the type rather than only
        // reporting it. The drawer could show a pin's type and not set it, so the only way to
        // change one was the helper strip's swatch, which is on the picture and not in the place
        // a user goes to edit a pin. Tap cycles; long-press opens the same four-way ring the
        // strip has. Same grammar in both places, deliberately.
        ImageView swatch = new ImageView(ctx);
        swatch.setImageDrawable(PuppetIcons.of(PuppetIcons.forType(pin.type), hue, pad(d, 17)));
        swatch.setContentDescription(pin.typeLabel() + " pin. Tap to change type.");
        swatch.setOnClickListener(v -> {
            PuppetPin.Type[] all = PuppetPin.Type.values();
            PuppetPin.Type was = pin.type;
            PuppetPin.Type now = all[(was.ordinal() + 1) % all.length];
            host.recordUndo("Pin type",
                    () -> { pin.type = now; host.onRigStructureChanged(); host.rebuildRows(); },
                    () -> { pin.type = was; host.onRigStructureChanged(); host.rebuildRows(); });
            pin.type = now;
            // A type change changes the WEIGHTS — a stiff patch is a different table — so the
            // mesh has to be rebuilt, not merely repainted.
            host.onRigStructureChanged();
            host.rebuildRows();
            host.onChanged();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(pad(d, 22), pad(d, 22));
        slp.rightMargin = pad(d, 7);
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
        int b = host.selectedBone();
        if (b >= 0 && b < rig.boneCount()) { boneRows(ctx, host, root, d, b); return; }
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

        // MUTE. The mesh builder has honoured it and the overlay has drawn a muted pin hollow
        // since both were written; there was simply never a way to set it. It is the answer to
        // JoyRaptor's condition on Dangle — "so long as it only disables keys and not erases or
        // overwrites them" — because muting takes a pin out of the weights while every key it
        // owns stays exactly where it is, and unmuting brings the whole performance back.
        LinearLayout pinToggles = row(ctx);
        addToggle(ctx, host, pinToggles, d, pin.muted ? "Muted" : "Active", !pin.muted,
                v -> { pin.muted = !v; host.onRigStructureChanged(); });
        addToggle(ctx, host, pinToggles, d, "Reach ring", rig.showReach,
                v -> rig.showReach = v);
        root.addView(pinToggles);
        gap(ctx, root, d, 7);

        switch (pin.type) {
            case DANGLE:
                // The tape has nothing to show for a dangling pin and must say so rather than
                // drawing an empty track, which reads as "your keys are gone".
                root.addView(hint(ctx, d,
                        "Simulated — no keys to edit. Any keys it already has are kept, "
                        + "untouched, and come back the moment it stops dangling."));
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
                // "Scale at this point" WAS here. PuppetPin.scale is persisted and read by
                // nothing: PuppetTopology.handleComponents() is 2, so a pin is x and y and has no
                // scale of its own for the deformer to apply. Exactly the defect that took the
                // rotate arc and the scale square with it on 2026-09-16, and removed for exactly
                // the same reason — a control that does nothing teaches the user the feature is
                // broken, which is worse than it plainly being absent.
                root.addView(hint(ctx, d,
                        "A free pin moves in any direction. Turn and scale need per-pin "
                        + "rotation in the engine — see the spec."));
                break;
            default:
                // An anchor has no settings of its OWN. It still has a depth, because every part
                // of a character is on some side of it.
                root.addView(hint(ctx, d, "An anchor has nothing else to set — that is the point"));
                break;
        }

        // WHOSE KEYS THE TAPE IS SHOWING. One pin at a time was JoyRaptor’s own ruling — a
        // ghost strip of every other pin’s live take is "completely incomprehensible" — and the
        // price of that ruling is that the tape must say which pin it belongs to, or a user who
        // selects a second pin reads the change as their first pin’s keys vanishing.
        if (rig.pinCount() > 1) {
            root.addView(hint(ctx, d, "The tape below shows " + pin.name
                    + "’s keys. Tap another pin, or a chip above, to see its keys."));
        }
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

            LinearLayout chip = row(ctx);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(rowBg(d, hot ? mix(hue, 0x33) : SUNK_BG,
                    hot ? hue : ROW_LINE, d));
            chip.setPadding(pad(d, 9), pad(d, 5), pad(d, 9), pad(d, 5));

            TextView t = new TextView(ctx);
            t.setText(p.name);
            t.setTextSize(10.5f);
            t.setTextColor(hot ? TXT : TXT_DIM);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setSingleLine(true);
            chip.addView(t);

            // THE DOT. Only when there is something to see — an always-present dot would be
            // decoration rather than information.
            boolean simulated = p.type == PuppetPin.Type.DANGLE;
            if (simulated || host.keyCountOf(i) > 0) {
                View dot = new View(ctx);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                // A dangle pin is SIMULATED, so its dot is hollow: it has motion but no keys,
                // and a solid dot beside a pin whose tape is empty would be a straight lie.
                if (simulated) {
                    g.setColor(0x00000000);
                    g.setStroke(Math.max(1, Math.round(d)), hue);
                } else {
                    g.setColor(hue);
                }
                dot.setBackground(g);
                LinearLayout.LayoutParams dlp =
                        new LinearLayout.LayoutParams(pad(d, 6), pad(d, 6));
                dlp.leftMargin = pad(d, 6);
                chip.addView(dot, dlp);
            }

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
        LinearLayout ends = row(ctx);
        addReadout(ctx, ends, d, "From", rootName);
        addReadout(ctx, ends, d, "To", tipName);
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
        rangeSlider(ctx, root, host, d, "Length", hue, shown, 0.25f, 2f, "×",
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
        addReadout(ctx, facts, d, "Rest angle", Math.round(deg) + "°");
        addReadout(ctx, facts, d, "Rest length", Math.round(rest * 100f) + "%");
        root.addView(facts);
        gap(ctx, root, d, 7);

        // STRETCHY and FLIP ELBOW, two to a line.
        LinearLayout bits = row(ctx);
        addToggle(ctx, host, bits, d, "Stretchy", bone.stretchy, v -> bone.stretchy = v);
        // FLIP ELBOW. One bit instead of a pole-target object to position — SPEC §3. It was
        // stored, saved and honoured by FabrikSolver long before anything set it.
        addToggle(ctx, host, bits, d, "Flip elbow", bone.bendSign < 0,
                v -> bone.bendSign = v ? -1 : 1);
        root.addView(bits);
        gap(ctx, root, d, 7);

        if (bone.stretchy) {
            slider(ctx, root, host, d, "How far it may stretch", hue, bone.maxStretch, false,
                    v -> bone.maxStretch = v);
        } else {
            root.addView(hint(ctx, d,
                    "A rigid bone stops the hand short and snaps the shoulder. "
                    + "Cartoons usually want stretchy on."));
        }

        LinearLayout limits = row(ctx);
        addToggle(ctx, host, limits, d, "Joint limits", bone.jointLimits,
                v -> bone.jointLimits = v);
        root.addView(limits);
        gap(ctx, root, d, 7);

        if (bone.jointLimits) {
            // The limit belongs to the pin this bone ARRIVES at — the knee, not the thigh —
            // which is exactly how puppetSolveChain reads it back out.
            rangeSlider(ctx, root, host, d, "Fold no further back than", hue,
                    bone.minAngleDeg, -180f, 0f, "°", v -> bone.minAngleDeg = v);
            rangeSlider(ctx, root, host, d, "Fold no further forward than", hue,
                    bone.maxAngleDeg, 0f, 180f, "°", v -> bone.maxAngleDeg = v);
        } else {
            root.addView(hint(ctx, d, "Off by default — most cartoon rigs never want them."));
        }

        root.addView(hint(ctx, d,
                "A bone holds no keys of its own. Moving it writes to " + rootName
                + " and " + tipName + "."));
    }

    // ── scope: CHARACTER ─────────────────────────────────────────────────

    private static void characterRows(@NonNull Context ctx, @NonNull Host host,
                                      @NonNull LinearLayout root, float d) {
        PuppetRig rig = host.rig();
        slider(ctx, root, host, d, "Softness", GO, rig.softness, true, v -> rig.softness = v);
        slider(ctx, root, host, d, "Mesh detail", GO, rig.meshDetail, false, v -> rig.meshDetail = v);
        slider(ctx, root, host, d, "Gravity", PuppetPalette.DANGLE, rig.gravity, false,
                v -> rig.gravity = v);
        // WIND. PuppetRigSolver.paramsFor has taken both of these since it was written and
        // PuppetDangleBake passes them straight through; only the two controls were missing.
        slider(ctx, root, host, d, "Wind", PuppetPalette.DANGLE, rig.wind, false,
                v -> rig.wind = v);
        if (rig.wind > 0.001f) {
            // Shown only when there IS wind, because a direction for no wind is a row that
            // cannot do anything — and rows that cannot do anything are the thing being removed.
            angleSlider(ctx, root, host, d, "Wind direction", PuppetPalette.DANGLE,
                    rig.windDirDeg, 0f, 360f, v -> rig.windDirDeg = v);
        }

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
        sliderRaw(ctx, root, host, d, label, hue, value, eye, sink, PuppetDrawerTabs::percent);
    }

    /** What a slider's number says. Per cent for a 0..1 knob, degrees or multiples otherwise. */
    private interface Readout { String of(float norm); }

    private static void sliderRaw(@NonNull Context ctx, @NonNull LinearLayout root,
                                  @NonNull Host host, float d, @NonNull String label, int hue,
                                  float value, boolean eye, @NonNull FloatSink sink,
                                  @NonNull Readout readout) {
        LinearLayout row = row(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rowBg(d, ROW_BG));
        row.setPadding(pad(d, 6), pad(d, 5), pad(d, 10), pad(d, 5));

        if (eye) {
            // THE REACH RING, which is what this eye has always claimed to control. It used to
            // toggle showPins — a duplicate of the Character row's own switch, while the ring it
            // named was drawn nowhere. Now: on = the ring stays on the picture; off = it appears
            // only while this slider is being dragged, which is the behaviour JoyRaptor asked for.
            ImageView eyeView = new ImageView(ctx);
            boolean showing = host.rig().showReach;
            eyeView.setImageDrawable(PuppetIcons.of(PuppetIcons.EYE,
                    showing ? hue : OFF, pad(d, 16)));
            eyeView.setOnClickListener(v -> {
                host.rig().showReach = !host.rig().showReach;
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
        val.setText(readout.of(value));
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
        gap(ctx, root, d, 7);
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

    /** A number you can read and cannot change. Shares its line, like everything else here. */
    private static void addReadout(@NonNull Context ctx, @NonNull LinearLayout parent, float d,
                                   @NonNull String label, @NonNull String value) {
        LinearLayout cell = row(ctx);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setBackground(rowBg(d, SUNK_BG, ROW_LINE, d));
        cell.setPadding(pad(d, 8), pad(d, 6), pad(d, 8), pad(d, 6));

        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(10.5f);
        l.setTextColor(TXT_FAINT);
        l.setSingleLine(true);
        cell.addView(l, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(10.5f);
        v.setTextColor(TXT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setSingleLine(true);
        cell.addView(v);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = pad(d, 6);
        parent.addView(cell, lp);
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
