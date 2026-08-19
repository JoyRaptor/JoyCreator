package com.fadcam.ui.faditor.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.TextOverlayItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent layer placed over the video preview that renders editable text
 * overlays. Each overlay is a {@link TextView} the user can drag to move, pinch
 * to scale, and tap to edit. Empty areas pass touches through to the player.
 */
public class TextOverlayLayer extends FrameLayout {

    public interface Callback {
        /** Pixel rect of the visible video content inside this layer's bounds. */
        @NonNull RectF getVideoContentRect();
        /** An overlay's position/size/text changed — persist it. */
        void onOverlayChanged();
        /**
         * SPEC_TIMER_OBJECT: total project duration, for a timer overlay's ABSOLUTE basis
         * and as the fallback out-point of an untrimmed tape. Deliberately NOT a default
         * method: export takes the same value as a required constructor argument, and a
         * silently-zero duration here would make the preview disagree with the export.
         */
        long getProjectDurationMs();
        /**
         * User DOUBLE-TAPPED an overlay — open its type editor (program-wide
         * gesture grammar, JoyRaptor 2026-07-17: tap = select, double-tap = type
         * editor, hold = general drawer).
         */
        void onEditRequested(@NonNull TextOverlayItem item);
        /** Single tap (no drag) — select the overlay (timeline row + handles). */
        default void onOverlaySelected(@NonNull TextOverlayItem item) { }
        /** Hold (~long-press, no movement) — open the general properties drawer. */
        default void onOverlayHeld(@NonNull TextOverlayItem item) { }
        /**
         * A drag/pinch gesture on {@code item} finished, mutating its transform
         * (and possibly adding a keyframe). {@code before} is the snapshot taken
         * when the gesture started — record a single undo step from it. Default
         * no-op so existing callers need not implement it.
         */
        default void onOverlayManipulated(@NonNull TextOverlayItem item,
                                          @NonNull TextOverlayItem.TransformSnapshot before) { }
    }

    // ── WYSIWYG in-canvas text editing (2026-08-09 reframe) ───────────────────────────────────
    // "The preview IS the textbox": while the text drawer is open, a transparent EditText lays
    // over the edited box (a child of TextBoxView — see its class doc). The layer owns it, the
    // box hosts it, and the activity's session feeds it through this host: the user types and
    // drags selection handles WHERE THE GLYPHS ARE, and the renderer below keeps painting both
    // the styled text and (via setEditingSelection) the purple range highlight.

    /** The activity-side editing session — forwards the editor's events verbatim. */
    public interface TextEditingHost {
        /** beforeTextChanged — the session aligns its spans to the edit. */
        void onBeforeTextChanged(int start, int before, int count);
        /** afterTextChanged — the session writes the authored string to the model. */
        void onAfterTextChanged(@NonNull String text);
        /** The editor's selection moved — the session clamps + repaints chips/highlight. */
        void onSelectionChanged(int selStart, int selEnd);
    }

    @Nullable private String editingItemId;
    @Nullable private TextEditingHost textHost;
    @Nullable private EditText textEditor;

    /**
     * Begin in-canvas WYSIWYG editing of {@code itemId}: attach (or re-attach) the transparent
     * editor over its box with {@code initialText}, select all (the every-day "base style" state,
     * exactly like the old drawer field's select-on-focus), focus and raise the IME.
     */
    public void startTextEditing(@NonNull String itemId, @NonNull String initialText,
                                 @NonNull TextEditingHost host) {
        if (editingItemId != null && editingItemId.equals(itemId) && textEditor != null) {
            // Same item, same session — the box was rebuilt (every keystroke does); re-host.
            attachToBox();
            return;
        }
        endTextEditing();
        editingItemId = itemId;
        textHost = host;
        // Themed context, so the caret and the two selection handles are drawn in the app's
        // selection accent rather than the platform default — see FaditorTextEditorTheme. They
        // are the ONLY native chrome this transparent editor shows, and they sit over arbitrary
        // video, so their colour is the whole of their legibility.
        android.content.Context editorCtx = new android.view.ContextThemeWrapper(
                getContext(), com.fadcam.R.style.FaditorTextEditorTheme);
        textEditor = new EditText(editorCtx) {
            @Override protected void onSelectionChanged(int selStart, int selEnd) {
                super.onSelectionChanged(selStart, selEnd);
                if (textHost != null) textHost.onSelectionChanged(selStart, selEnd);
            }
        };
        // Invisible ink + invisible native highlight: the renderer below draws BOTH the glyphs
        // and the W5-2 purple selection — what stays native is the caret, the selection handles
        // and the IME connection, which is precisely the point of the reframe.
        textEditor.setTextColor(0x00000000);
        textEditor.setHighlightColor(0x00000000);
        textEditor.setBackgroundColor(0x00000000);
        textEditor.setPadding(0, 0, 0, 0);
        textEditor.setIncludeFontPadding(false);
        textEditor.setSingleLine(false);
        mirrorItemForMetrics(itemId);
        textEditor.setText(initialText);
        textEditor.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                if (textHost != null) textHost.onBeforeTextChanged(a, b, c);
            }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (textHost != null) textHost.onAfterTextChanged(s.toString());
            }
        });
        attachToBox();
        final EditText fresh = textEditor;
        fresh.post(() -> {
            fresh.requestFocus();
            // CARET AT THE END, NOTHING SELECTED (JoyRaptor, 2026-08-14).
            //
            // This used to selectAll(), on the reasoning that "open state = whole text = base
            // style editing". That reasoning is load-bearing in the WRONG direction:
            // TextStyleSession.hasSelection() treats a WHOLE-text selection as no selection at
            // all, so every style tap while it stands is applied to the item's base — to all the
            // text. Any moment a range selection collapses back to select-all, "bold this word"
            // silently becomes "bold everything", which is exactly what was reported: "I could
            // select a word and underline or italic it without changing the surrounding text.
            // Now every option changes ALL text."
            //
            // Opening with a caret is also simply what every text editor does with existing
            // content. The cost is that typing no longer instantly replaces the whole string —
            // the drawer's own "Select all" chip is the deliberate way to ask for that now.
            fresh.setSelection(fresh.getText().length());
            showIme(fresh);
        });
    }

    /** End in-canvas editing: detach the editor, hide the IME, forget the session link. */
    public void endTextEditing() {
        if (textEditor != null) {
            hideIme(textEditor);
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                Object tag = v.getTag();
                if (tag instanceof TextOverlayItem && v instanceof TextBoxView
                        && editingItemId != null
                        && editingItemId.equals(((TextOverlayItem) tag).getId())) {
                    ((TextBoxView) v).detachEditor();
                    break;
                }
            }
            textEditor = null;
        }
        editingItemId = null;
        textHost = null;
    }

    public boolean isEditingText() { return editingItemId != null; }

    /** True only when THIS item currently hosts the in-canvas editor. */
    public boolean isEditingItem(@NonNull String itemId) {
        return itemId.equals(editingItemId);
    }

    /** Re-host the editor on this item's box (fresh box after a rebuild). */
    private void attachToBox() {
        if (editingItemId == null || textEditor == null) return;
        int boxes = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            Object tag = v.getTag();
            if (v instanceof TextBoxView) boxes++;
            if (tag instanceof TextOverlayItem && v instanceof TextBoxView
                    && editingItemId.equals(((TextOverlayItem) tag).getId())) {
                final TextBoxView tb = (TextBoxView) v;
                tb.attachEditor(textEditor);
                final EditText ed = textEditor;
                tb.post(() -> {
                    restoreEditorSelection();
                    ed.requestFocus();
                    showIme(ed);
                });
                return;
            }
        }
        // SILENT UNTIL NOW, and that was the whole trap. A box on the OTHER paint surface (Z3
        // splits overlays around the PiP plane) leaves this loop having done nothing, so the
        // drawer opens over a preview with no editor in it and the user gets a text object they
        // cannot type into — with no clue why. Say so.
        //
        // ONCE PER ITEM, not once per call. attachToBox re-hosts on every rebuild and the layer
        // rebuilds on every text sync, so an unqualified warning here would write thousands of
        // lines a minute in precisely the broken state someone would be reading the log to
        // diagnose — and FLog is not gated on BuildConfig.DEBUG (see LayerGestureController's
        // ROWGESTURE note: every call writes two lines and runs a redaction pass). Same
        // one-shot discipline as buildPlan's loggedNullPip.
        if (!editingItemId.equals(loggedNoBoxFor)) {
            loggedNoBoxFor = editingItemId;
            com.fadcam.FLog.w("TextOverlayLayer", "attachToBox: no box for " + editingItemId
                    + " on this surface (" + boxes + " text boxes here) — no editor, no keyboard");
        }
    }

    /** @see #attachToBox — the item whose missing box has already been reported. */
    @Nullable private String loggedNoBoxFor;

    /**
     * Re-apply the remembered drawer selection to the editor after a re-host. The layer rebuilds
     * the box on every {@link #rebuild()} (every style toggle repaints through {@code setData}),
     * and the detach/re-attach collapses the editor's selection to a caret — so a user who
     * selected a range and then tapped "B" would lose the range and be stuck re-selecting for
     * every toggle. Only a real range (start {@code <} end) is restored; a caret is left as the
     * user put it.
     */
    private void restoreEditorSelection() {
        if (textEditor == null) return;
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            int len = textEditor.getText().length();
            if (selectionStart <= len && selectionEnd <= len) {
                textEditor.setSelection(selectionStart, selectionEnd);
            }
        }
    }

    /** The editor's caret/handles measure against the item's BASE metrics — mirror them. */
    private void mirrorItemForMetrics(@Nullable String itemId) {
        if (textEditor == null || itemId == null) return;
        for (TextOverlayItem o : overlays) {
            if (!itemId.equals(o.getId())) continue;
            int style = android.graphics.Typeface.NORMAL;
            if (o.isBold()) style |= android.graphics.Typeface.BOLD;
            if (o.isItalic()) style |= android.graphics.Typeface.ITALIC;
            textEditor.setTypeface(android.graphics.Typeface.create(
                    (android.graphics.Typeface) null, style));
            String align = o.getTextAlign();
            int g = TextOverlayItem.ALIGN_CENTER.equals(align) ? Gravity.CENTER_HORIZONTAL
                    : TextOverlayItem.ALIGN_RIGHT.equals(align) ? Gravity.RIGHT
                    : Gravity.LEFT;
            textEditor.setGravity(g);
            return;
        }
    }

    /**
     * Raise the keyboard for {@code e} — at most once per genuine open.
     *
     * <p><b>DO NOT ADD A RETRY HERE.</b> A bare {@code showSoftInput} straight after
     * {@code requestFocus} can lose a race (the IMM's served view is updated by the focus-change
     * pass, not synchronously), and the obvious repair is to re-post the show until it takes.
     * That was tried on 2026-08-14 and it was much worse than the problem: this method is called
     * from {@code attachToBox}, which re-hosts on every rebuild, and every successful show moves
     * the window insets, which lays out, which rebuilds. Retry chains stacked into a closed loop
     * — 49,692 logcat lines in 45 seconds, the keyboard opening and shutting several times a
     * second, the editor unusable.</p>
     *
     * <p>The race was never the real defect. {@code rebuild()} was DESTROYING the served view;
     * see the note there. With the view kept attached there is nothing to retry, and the guard
     * below keeps this quiet on the many calls that have nothing new to ask for.</p>
     */
    private void showIme(@NonNull EditText e) {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        // ALREADY UP FOR THIS EDITOR? THEN SAY NOTHING.
        //
        // This guard is the whole lesson of the 2026-08-14 storm. attachToBox re-hosts the
        // editor on EVERY rebuild, and the layer rebuilds on every text sync — so this method is
        // called many times per second during ordinary editing. Each call used to be a fresh
        // showSoftInput, and each show changes the window insets, which lays out, which rebuilds,
        // which calls this again. On the Note 20 that closed into a visible loop: the keyboard
        // opened and shut several times a second and the editor was unusable.
        //
        // A show that is already true is not worth asking for. Asking only when the manager is
        // NOT already serving this editor breaks the feedback path at its narrowest point.
        if (imm.isActive(e) && imeShown) return;
        imeShown = imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
    }

    /**
     * Whether we believe the keyboard is up for the current editor.
     *
     * <p>Cleared by {@link #hideIme} and by {@link #endTextEditing}, so the next genuine open
     * asks again. Deliberately a belief rather than a query: {@code isActive} answers "is this
     * view the served one", which stays true while the keyboard is dismissed by the system back
     * gesture — and re-asking on every rebuild is exactly what caused the storm.</p>
     */
    private boolean imeShown;

    private void hideIme(@NonNull EditText e) {
        imeShown = false;
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && e.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
        }
    }

    private final List<TextOverlayItem> overlays = new ArrayList<>();
    @Nullable private Callback callback;
    /** Current timeline time (ms) used to evaluate overlay time-ranges + keyframes. */
    private long currentTimeMs = 0;
    /** Overlay being actively dragged/scaled — shown at its static transform. */
    @Nullable private TextOverlayItem manipulating;
    /** Double-tap pairing state (type-editor express lane, layer-level). */
    @Nullable private TextOverlayItem lastTapOverlay;
    private long lastTapUpMs;
    private boolean snapEnabled = true;
    private static final float SNAP_THRESHOLD = 0.045f;
    private static final long TIME_SNAP_MS = 250L;

    public void setSnapEnabled(boolean enabled) {
        this.snapEnabled = enabled;
    }

    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    public TextOverlayLayer(Context context) { super(context); }
    public TextOverlayLayer(Context context, AttributeSet attrs) { super(context, attrs); }
    public TextOverlayLayer(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /** Z3: false = draw-only (the below-video instance). See SPEC_CROSSTYPE_Z. */
    private boolean interactive = true;

    /** Z3: make this instance draw-only, so it never competes for touch. */
    public void setInteractive(boolean value) { this.interactive = value; }

    /**
     * Preview-only live-area fade. A hugely scaled overlay extends past the video
     * frame; per the user (2026-08-09) everything INSIDE the live area stays
     * regular and ONLY the overhang beyond the boundary turns half-transparent.
     * The fade hits the overlay's OWN pixels — nothing behind it ever tints.
     *
     * <p>Implemented as two drawing passes over the children: pass 1 clips the
     * live rect and draws at full opacity; pass 2 clips the complement (four
     * strips around the rect — no Region.Op, no complex Path, HW-accelerator
     * friendly) into {@link #saveLayerAlpha} and draws at half opacity. A child
     * fully inside the rect is clipped away in pass 2, so only real overhang
     * costs the second draw. Never exported — the export path has no canvas to
     * overhang, exactly as the rendered frame has no area outside the canvas.</p>
     */
    private static final int OVERHANG_ALPHA = 128; // half-transparent overhang

    @Override
    protected void dispatchDraw(@NonNull android.graphics.Canvas canvas) {
        if (callback == null || getChildCount() == 0) {
            super.dispatchDraw(canvas);
            return;
        }
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        if (!anyOverhang(r)) {
            super.dispatchDraw(canvas);
            return;
        }

        // Pass 1 — the live area at full opacity.
        canvas.save();
        canvas.clipRect(r.left, r.top, r.right, r.bottom);
        super.dispatchDraw(canvas);
        canvas.restore();

        // Pass 2 — everything outside the live rect, at half opacity. The four
        // strips mean only the child pixels that actually overhang are affected.
        float w = getWidth();
        float h = getHeight();
        canvas.save();
        canvas.clipRect(0f, 0f, w, r.top);
        canvas.clipRect(0f, r.bottom, w, h);
        canvas.clipRect(0f, r.top, r.left, r.bottom);
        canvas.clipRect(r.right, r.top, w, r.bottom);
        canvas.saveLayerAlpha(0f, 0f, w, h, OVERHANG_ALPHA);
        super.dispatchDraw(canvas);
        canvas.restore();
        canvas.restore();
    }

    /**
     * True when any visible child reaches past the live-area rect, using the
     * view's laid-out bounds in this layer's space (a text box keeps its excursion
     * margin inside those bounds, so the fade starts at the edge the user sees).
     */
    private boolean anyOverhang(@NonNull RectF r) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v.getLeft() < r.left - 0.5f || v.getTop() < r.top - 0.5f
                    || v.getRight() > r.right + 0.5f || v.getBottom() > r.bottom + 0.5f) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decoded preview bitmaps for image overlays, keyed by source URI.
     *
     * <p><b>Why this exists.</b> The image branch used to call {@code setImageURI}, which decodes
     * the source on the calling thread at its FULL stored resolution, with no cache anywhere. A
     * 12-megapixel photo therefore paid a full decode on every {@link #rebuild()} — and rebuild
     * ran on every drag frame and every slider tick — then handed the compositor a bitmap tens of
     * times larger than the pixels it would occupy. That is the bulk of the "lots of lag, can't
     * really see what I'm doing" report (JoyRaptor, 2026-08-12).</p>
     *
     * <p>Bounded by the SCREEN, not by the overlay's current scale, on purpose: the scale is
     * animated, so sizing the decode to it would re-decode every time the zoom changed — trading
     * one stall for many. A screen-sized bitmap is the largest the preview can ever show, and
     * FIT_XY stretches it for anything larger, exactly as the ImageView did before.</p>
     *
     * <p>Preview only. The EXPORT decodes its own copy bounded by the OUTPUT frame
     * ({@code ImageOverlayDraw.decode}), which is the right bound there and usually a different
     * one — a 4K export must not be limited to what a 1080p phone screen can show.</p>
     */
    private final java.util.Map<String, android.graphics.Bitmap> imageCache =
            new java.util.HashMap<>();

    @Nullable
    private android.graphics.Bitmap imageBitmap(@NonNull TextOverlayItem o) {
        String uri = o.getImageUri();
        if (uri == null) return null;
        // containsKey, not get() != null: a null VALUE is a cached FAILURE, and re-attempting a
        // broken URI on every rebuild is the cost this whole field exists to avoid.
        if (imageCache.containsKey(uri)) {
            android.graphics.Bitmap cached = imageCache.get(uri);
            return (cached != null && !cached.isRecycled()) ? cached : null;
        }
        android.graphics.Bitmap out = null;
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxEdge = Math.max(640, Math.max(dm.widthPixels, dm.heightPixels));
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in =
                         getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outHeight / (sample * 2) >= maxEdge
                    && bounds.outWidth / (sample * 2) >= 1) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (java.io.InputStream in =
                         getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                out = android.graphics.BitmapFactory.decodeStream(in, null, opts);
            }
            // EXIF ORIENTATION, applied on BOTH renderers or neither. BitmapFactory ignores the
            // tag, so a phone photo stored 3:4 with a "rotate 90" flag draws sideways here and in
            // the export while every other viewer shows it upright. ImageBaseStillCache states the
            // rule for image CLIPS — "EXIF is not optional: media3's own bitmap loader applies it
            // on export" — and this is the same tag on the same kind of file. Fixing only the
            // export would have swapped one wrong picture for a preview that disagrees with it,
            // which is the WYSIWYG rule broken in the other direction.
            int rot = exifRotation(Uri.parse(uri));
            if (out != null && rot != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rot);
                android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(
                        out, 0, 0, out.getWidth(), out.getHeight(), m, true);
                // The un-rotated bitmap was never published — only this cache will hold the result.
                if (rotated != out) out = rotated;
            }
        } catch (Throwable ignored) {
            // Unreadable source: cache the failure so the next rebuild does not try again.
        }
        imageCache.put(uri, out);
        return out;
    }

    /**
     * Degrees this image must be rotated by to be seen upright, from its EXIF tag.
     *
     * <p>A SECOND stream, because {@code ExifInterface} consumes what it reads. Same four cases and
     * the same silent fallback as the export's decoder and {@code ImageBaseStillCache}: an
     * unreadable tag costs the image its rotation, never its appearance.</p>
     */
    private int exifRotation(@NonNull Uri uri) {
        try (java.io.InputStream is = getContext().getContentResolver().openInputStream(uri)) {
            if (is == null) return 0;
            int o = new androidx.exifinterface.media.ExifInterface(is).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            if (o == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) { }
        return 0;
    }

    /**
     * Drop cached preview bitmaps. Call when the set of image overlays changes enough that holding
     * their pixels is waste — not on a rebuild, which is exactly when the cache earns its keep.
     *
     * <p>Bitmaps are NOT recycled here: an {@code ImageView} created by a previous rebuild may
     * still be attached and drawing one, and recycling underneath it draws nothing at best. They
     * are released to the collector instead, which is the same trade the still-frame paths make.
     * </p>
     */
    public void clearImageCache() {
        imageCache.clear();
    }

    public void setData(@NonNull List<TextOverlayItem> overlays, @NonNull Callback cb) {
        // SAME ITEMS, SAME ORDER → REPOSITION, DO NOT REBUILD.
        //
        // setData is called from syncTimelineOverlays, which every edit path funnels through —
        // including the per-TICK slider writes in the object drawers. Rebuilding there was the
        // remaining half of the image-drag jank (JoyRaptor, 2026-08-12: the flicker is "doing the
        // slider movement", and "there's no updating happening during the slider movement until
        // the hand is off"). Both symptoms are one cause: rebuild() destroys every view and the
        // fresh one is positioned from a POSTED callback, so for at least a frame the object is
        // unpositioned — it reads as a transparent flicker — and during a continuous drag the
        // posted pass is always describing a value the finger has already left behind.
        //
        // An earlier pass removed the explicit rebuild() from the drawer's own setter but missed
        // this one, which is reached indirectly and rebuilds the BELOW-z surface on every tick.
        // Guarding it here fixes the class of bug rather than the two call sites I can see.
        // The comparison is on the ITEMS ONLY, deliberately. overlayLayerCallback() builds a fresh
        // anonymous instance on every call, so including the callback in this test made it fail
        // every time and the guard never once fired — the first version of this fix was inert, and
        // the per-tick rebuild it was written to stop carried straight on happening.
        boolean same = overlays.size() == this.overlays.size() && callback != null;
        if (same) {
            for (int i = 0; i < overlays.size(); i++) {
                // Identity, not equals: these ARE the model objects, and a new instance at the
                // same index is a different overlay that needs its own view.
                if (this.overlays.get(i) != overlays.get(i)) { same = false; break; }
            }
        }
        // The callback is swapped in either way: it closes over nothing that outlives a call, and
        // the views hold no reference to it, so replacing it costs nothing and keeping a stale one
        // would be the actual hazard.
        this.callback = cb;
        if (same) {
            // Views already exist for exactly these items — re-read their transforms in place.
            setPlayheadMs(currentTimeMs);
            return;
        }
        this.overlays.clear();
        this.overlays.addAll(overlays);
        rebuild();
    }

    /** Recreate all overlay views from the model (call after data or size changes). */
    public void rebuild() {
        // The editor is a child of one of these boxes, and removeAllViews detaches it — which
        // collapses its selection to a caret and (via the host) makes the drawer forget the
        // range being styled. Snapshot before the teardown, restore after the re-attach.
        int ss = -1;
        int se = -1;
        if (textEditor != null) {
            ss = textEditor.getSelectionStart();
            se = textEditor.getSelectionEnd();
        }
        // THE BOX BEING TYPED INTO STAYS ATTACHED THROUGH THE REBUILD.
        //
        // removeAllViews() detaches the transparent EditText along with its host box, and a
        // detached view stops being the input manager's SERVED view. The IMM's answer to that is
        // not to re-serve it — it CANCELS the pending show and closes the input outright. Note 20
        // logcat, one tap:
        //
        //   ssi() SHOW_SOFT_INPUT                    <- the tap opens the editor
        //   Resizing frame [0,90][1440,3088]         <- the keyboard's own insets resize us
        //   closeCurrentInput: mService.hideSoftInput
        //   ssi() - cancel : servedView != view, servedView=null
        //
        // So the keyboard appearing caused the layout that destroyed the view the keyboard was
        // for. It flashed up and vanished in about 125 ms, every time (JoyRaptor, 2026-08-14: "it
        // brings up the keyboard for maybe a tenth of a second or less"). The re-host in
        // createOverlayView was written to survive rebuilds and does survive them — but only as
        // far as the VIEW is concerned; the IME connection does not come back with it.
        //
        // Detaching everything EXCEPT that one box keeps the served view attached, so there is
        // nothing for the IMM to cancel. The rest of the layer rebuilds exactly as before.
        View editing = null;
        // textEditor != null, NOT just editingItemId != null. The two can disagree, and when
        // they do this method silently rewrites the project's z-order.
        //
        // Below, the edited box is bringChildToFront()ed -- correct while you are typing, and
        // the ONLY thing that overrides lane order on this surface. But editingItemId is cleared
        // in exactly one place, endTextStyleSession, and only when the style drawer closes
        // cleanly AND the id still matches. Any other exit leaks it, and a leaked id pins that
        // one box above everything for the rest of the session while every other box still obeys
        // its lane -- which reads as "z-order is randomly wrong for one object".
        //
        // Device-diagnosed 2026-08-18 from JoyRaptor's project: a 7-candle text box on laneZ=0, the
        // BOTTOM lane, painting over images 8 lanes above it, while other text on higher lanes
        // sat correctly under those same images. It was the box he had edited last.
        //
        // Tying the hoist to a LIVE editor makes the override last exactly as long as the thing
        // that justifies it. endTextEditing() nulls textEditor, so the next rebuild puts the box
        // back in lane order on its own -- the state self-heals instead of needing every exit
        // path to remember to clean up.
        if (editingItemId != null && textEditor != null) {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                Object tag = v.getTag();
                if (tag instanceof TextOverlayItem && v instanceof TextBoxView
                        && editingItemId.equals(((TextOverlayItem) tag).getId())) {
                    editing = v;
                    break;
                }
            }
        }
        for (int i = getChildCount() - 1; i >= 0; i--) {
            if (getChildAt(i) != editing) removeViewAt(i);
        }
        if (callback == null) {
            if (editing == null) removeAllViews();
            return;
        }
        // BY ID, NOT BY IDENTITY — and this distinction is the whole reason the branch works.
        //
        // setData only calls rebuild() when the incoming model objects are DIFFERENT INSTANCES
        // from the ones held ("Identity, not equals: these ARE the model objects, and a new
        // instance at the same index is a different overlay that needs its own view"). So an
        // identity test here is false in exactly the case that got us here: undo/redo, or any
        // path that restores a snapshot and re-emits fresh items, would build a SECOND view for
        // the item already on screen, leaving the keyboard attached to the stale one — typing
        // into an object no longer in the timeline while the visible box never changed.
        //
        // Matching on id and ADOPTING the fresh instance keeps one view per item and re-points
        // the retained box at the model everything else is now using.
        boolean editingSurvives = false;
        for (TextOverlayItem o : overlays) {
            if (editing != null && sameItem(editing.getTag(), o)) {
                editing.setTag(o);
                editingSurvives = true;
                continue;
            }
            View v = createOverlayView(o);
            v.setTag(o);
            addView(v);
        }
        if (editing != null && !editingSurvives) {
            // The edited item is no longer on this surface at all — deleted, or moved across
            // the Z3 plane. Keeping its box would strand a view for something that does not
            // exist here, so it goes; the editor session is torn down by its own drawer close.
            removeView(editing);
            editing = null;
        }
        // ZDIAG (temporary, 2026-08-18): JoyRaptor reports a recurring wrong-depth object -- an image
        // on the BOTTOM lane painting above text on higher lanes -- and suspects the consolidate
        // lanes tool leaves stale z information. Static reading has cleared every refresh path,
        // so this prints what the layer ACTUALLY stacked, in order, against what it was handed.
        // Compare with ZDIAG-MODEL in the activity: if the two agree, the model order is wrong
        // and the bug is upstream in lane ordering; if they disagree, the view order is stale or
        // something reordered the children after the build. REMOVE once identified.
        {
            StringBuilder sb = new StringBuilder("ZDIAG-VIEW n=").append(getChildCount())
                    .append(" hoist=").append(editing != null ? "YES" : "no")
                    .append(" editingId=").append(editingItemId)
                    .append(" textEditor=").append(textEditor != null)
                    .append(" order=");
            for (int i = 0; i < getChildCount(); i++) {
                Object tg = getChildAt(i).getTag();
                if (tg instanceof TextOverlayItem) {
                    TextOverlayItem ti = (TextOverlayItem) tg;
                    sb.append(ti.getId().substring(0, 8))
                      .append(ti.isImage() ? "(img)" : "(txt)").append(' ');
                }
            }
            com.fadcam.FLog.d("TextOverlayLayer", sb.toString());
        }
        if (editing != null) {
            // It was left at the bottom of the stack by the detach loop above. bringChildToFront
            // reorders the child array WITHOUT detaching from the window, so the served view
            // survives this too. Putting the box you are typing in on top is also simply right.
            bringChildToFront(editing);
            position(editing, (TextOverlayItem) editing.getTag());
        }
        if (textEditor != null && ss >= 0 && se > ss) {
            int len = textEditor.getText().length();
            if (ss <= len && se <= len) {
                textEditor.setSelection(ss, se);
            }
        }
    }

    /** Update the timeline time and re-evaluate every overlay's time-range,
     * keyframed position/size/rotation, and opacity — without rebuilding views.
     */
    public void setPlayheadMs(long timelineMs) {
        currentTimeMs = timelineMs;
        refreshPositions();
    }

    /**
     * Whether a child's tag and a model item are the SAME overlay — by id, because a fresh
     * instance of the same overlay is still that overlay. See the note in {@link #rebuild}.
     */
    private static boolean sameItem(@Nullable Object tag, @NonNull TextOverlayItem o) {
        return tag instanceof TextOverlayItem
                && ((TextOverlayItem) tag).getId().equals(o.getId());
    }

    /** Re-run {@link #position} for every child, without rebuilding any view. */
    private void refreshPositions() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof TextOverlayItem) {
                position(v, (TextOverlayItem) tag);
            }
        }
    }

    /**
     * Route the type-drawer's live selection (W5-2 §3.8) to the text view that
     * draws {@code itemId}, so the preview highlights exactly the characters
     * the drawer's controls will format. NOTE: indices are DISPLAY-string
     * indices (what the user sees while editing, case transformations
     * applied) — the drawer maps them to authored-text indices itself before
     * touching the model. Both negative means draw no selection.
     *
     * <p>The drawer's selection is also remembered HERE, so a
     * {@link #rebuild()} (every {@code refreshOverlayPreview} recreates the
     * views but reuses the same item objects) re-applies it to the fresh
     * view instead of leaving the highlight to silently disappear.
     */
    public void setEditingSelection(@NonNull String itemId, int selStart, int selEnd) {
        selectionItemId = itemId;
        selectionStart = selStart;
        selectionEnd = selEnd;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof TextOverlayItem) {
                TextOverlayItem o = (TextOverlayItem) tag;
                if (itemId.equals(o.getId()) && v instanceof TextBoxView) {
                    ((TextBoxView) v).setSelection(selStart, selEnd);
                    return;
                }
            }
        }
    }

    @Nullable private String selectionItemId;
    private int selectionStart = -1;
    private int selectionEnd = -1;

    /** Re-apply the remembered drawer selection to a freshly created view. */
    private void applyRememberedSelection(@NonNull TextOverlayItem o, @NonNull View view) {
        if (selectionItemId == null || !selectionItemId.equals(o.getId())) return;
        if (view instanceof TextBoxView) {
            ((TextBoxView) view).setSelection(selectionStart, selectionEnd);
        }
    }

    private View createOverlayView(@NonNull TextOverlayItem o) {
        View view;
        if (o.isGeneratedSlide()) {
            // AI-authored transparent overlay slide (spec Phase 4): a live,
            // scrubbable WebView fed by the playhead, same as fullscreen slides.
            com.fadcam.ui.faditor.slides.GeneratedSlideView gsv =
                    new com.fadcam.ui.faditor.slides.GeneratedSlideView(getContext());
            com.fadcam.ui.faditor.model.GeneratedSource gs = o.getGeneratedSource();
            if (gs != null && gs.htmlUri != null) {
                String p = android.net.Uri.parse(gs.htmlUri).getPath();
                if (p != null) {
                    java.io.File f = new java.io.File(p);
                    if (f.isFile()) gsv.loadSlide(f);
                }
            }
            view = gsv;
            view.setLayoutParams(new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            attachGestures(view, o);
            final View fv = view;
            fv.post(() -> position(fv, o));
            return view;
        }
        if (o.isImage()) {
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            android.graphics.Bitmap bmp = imageBitmap(o);
            if (bmp != null) iv.setImageBitmap(bmp);
            view = iv;
        } else {
            // A TextBoxView, not a TextView: one view holding one string cannot move individual
            // characters, which is the whole reason text boxes were BLOCK-only. Every visual
            // property that used to be set here now lives in TextBoxRenderer, which the EXPORT
            // calls too — so "the preview styles it slightly differently" is no longer possible.
            // It previously was: this branch set no glow and no background pill at all, and set
            // the FILL colour to the stroke colour instead of stroking.
            view = new TextBoxView(getContext(), o);
        }
        view.setLayoutParams(new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        applyRememberedSelection(o, view);
        attachGestures(view, o);
        // The box was rebuilt under a live editing session — re-host the editor on the fresh
        // box. Keystrokes rebuild every frame (refreshOverlayPreview → setData), so the editor
        // must SURVIVE the rebuild: same instance, new parent, focus + IME restored.
        if (editingItemId != null && editingItemId.equals(o.getId())
                && textEditor != null && view instanceof TextBoxView) {
            ((TextBoxView) view).attachEditor(textEditor);
            final View fv = view;
            fv.post(() -> {
                if (textEditor != null) {
                    restoreEditorSelection();
                    textEditor.requestFocus();
                    showIme(textEditor);
                }
            });
        }
        // Position once the view has a measured size.
        final View fv = view;
        fv.post(() -> position(fv, o));
        return view;
    }

    private void position(@NonNull View view, @NonNull TextOverlayItem o) {
        if (callback == null) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;

        // Hide the overlay outside its time range.
        if (!o.isVisibleAt(currentTimeMs)) {
            view.setVisibility(GONE);
            return;
        }
        view.setVisibility(VISIBLE);
        // While the user is dragging/scaling this overlay, follow the finger
        // (static transform) rather than the keyframed value at the playhead.
        // The WYSIWYG-edited item is static too: a box mid-entrance-animation
        // would slide its glyphs away from under the editor the user is typing in.
        boolean live = o == manipulating
                || (editingItemId != null && editingItemId.equals(o.getId()));
        float sizeFraction = live ? o.getSizeFraction() : o.animatedSizeFraction(currentTimeMs);

        // ── TEXT: the whole animation happens INSIDE the view ────────────────────────────────
        // A TextBoxView draws per unit through the shared TextBoxRenderer, so it applies the
        // preset's geometry, alpha, substitution and reveal itself, per glyph. The view-level
        // anim transform that used to live here would therefore DOUBLE-APPLY — at BLOCK it is
        // the same transform twice, and at LETTER it is a whole-body motion layered on top of a
        // per-glyph one. So for text the view keeps only the object's own keyframed opacity and
        // rotation, and the renderer owns everything the tape drives.
        boolean isTextBox = view instanceof TextBoxView;
        // Images and slides have no glyphs, so their entrance is still a whole-body view
        // transform, composed OVER the keyframed values rather than replacing them — alpha
        // multiplies, scale multiplies, translation adds ("compose, don't replace").
        com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform anim = isTextBox
                ? new com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform()
                : presetTransformAt(o, sizeFraction, r.height(), live);

        // A text box composites BOTH its keyframed opacity and the preset's per-unit alpha inside
        // TextBoxRenderer, because the export has no view to set alpha on. Setting it here too
        // would apply the object's opacity twice and darken every semi-transparent text box.
        view.setAlpha(isTextBox ? 1f
                : (live ? 1f
                        : Math.max(0f, Math.min(1f,
                                o.animatedOpacity(currentTimeMs) * anim.alpha))));
        // The GL composite is drawing this image, effects and all — see setGlOwnedImageIds. Set
        // AFTER the line above rather than instead of it, so the one expression for an overlay's
        // opacity stays in one place and this reads as what it is: a handover, not a second
        // opinion about how opaque the picture should be.
        if (glOwnedImageIds.contains(o.getId())) view.setAlpha(0f);

        if (o.isGeneratedSlide()
                && view instanceof com.fadcam.ui.faditor.slides.GeneratedSlideView) {
            // Full-canvas placement — the HTML owns its own layout — and a
            // playhead-driven seek with the same stretch mapping export bakes.
            LayoutParams glp = (LayoutParams) view.getLayoutParams();
            glp.width = Math.max(1, Math.round(r.width()));
            glp.height = Math.max(1, Math.round(r.height()));
            glp.leftMargin = Math.round(r.left);
            glp.topMargin = Math.round(r.top);
            view.setLayoutParams(glp);
            ((com.fadcam.ui.faditor.slides.GeneratedSlideView) view).seekTo(
                    com.fadcam.ui.faditor.slides.SlideRenderer
                            .mapOverlayToAnimMs(o, currentTimeMs));
            return;
        }

        int w, h;
        // The box's own centre, before the view's excursion margin is added around it. A text
        // box's VIEW is deliberately larger than its box (see TextBoxView.EXCURSION_EM), so the
        // two are not the same rectangle and the layout below must place the BOX's centre.
        float boxInset = 0f;
        if (view instanceof TextBoxView) {
            TextBoxView tb = (TextBoxView) view;
            float fontPx = Math.max(1f, sizeFraction * r.height());
            String shown = TextBoxRenderer.textAt(o, currentTimeMs,
                    callback.getProjectDurationMs());
            // One call, so the time and the string cannot be updated independently — a timer or
            // MATRIX would otherwise draw this frame's clock with last frame's text.
            tb.bind(o, shown, fontPx, currentTimeMs, callback.getProjectDurationMs(), !live,
                    live ? 1f : o.animatedOpacity(currentTimeMs));
            float[] size = new float[2];
            tb.measureView(size);
            w = Math.max(1, Math.round(size[0]));
            h = Math.max(1, Math.round(size[1]));
            boxInset = tb.boxInsetPx();
        } else if (o.isImage() && view instanceof ImageView) {
            float aspect = 1f;
            android.graphics.drawable.Drawable d = ((ImageView) view).getDrawable();
            if (d != null && d.getIntrinsicHeight() > 0) {
                aspect = d.getIntrinsicWidth() / (float) d.getIntrinsicHeight();
            }
            h = Math.round(imageHeightPx(o, sizeFraction, r.height(), live));
            w = Math.round(imageWidthPx(o, sizeFraction, r.height(), aspect, live));
        } else {
            // Neither a text box nor an image with a drawable — keep whatever it measured to
            // rather than collapsing it to nothing.
            w = Math.max(1, view.getWidth());
            h = Math.max(1, view.getHeight());
        }

        // Travel limit proportional to the object's OWN size: the centre may move
        // up to one half-extent beyond each edge, so at any scale the object can
        // be pushed until it is just fully off-frame (user, 2026-08-09). Clamped
        // at half a frame inside setCenterTravelLimit so a tiny object can still
        // be moved completely off-canvas too.
        o.setCenterTravelLimit((w / 2f) / Math.max(1f, r.width()),
                (h / 2f) / Math.max(1f, r.height()));

        float cx = r.left + (live ? o.getCenterX() : o.animatedCenterX(currentTimeMs)) * r.width();
        float cy = r.top + (live ? o.getCenterY() : o.animatedCenterY(currentTimeMs)) * r.height();

        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        // ONLY WRITE THE PARAMS WHEN THEY CHANGED. setLayoutParams calls requestLayout, and this
        // method runs on every playhead tick and every rebuild — including from inside a layout
        // pass, which is what produced the Note 20's "requestLayout() improperly called by ...
        // fx_below_group / PlayerView / WaveformOverlayView" storm while the keyboard's insets
        // were animating. Each of those forces another layout, which calls this again. Comparing
        // first turns the steady state into no work at all.
        int newW = Math.max(1, w);
        int newH = Math.max(1, h);
        // Centring the VIEW would centre the box plus its excursion margin — which is the same
        // point only because the margin is symmetric. Written as an explicit subtraction of the
        // inset from a box-sized centring so it stays correct if the margin ever becomes
        // asymmetric, and so the intent is legible: it is the BOX the user positioned.
        int newLeft = Math.round(cx - (w - boxInset * 2f) / 2f - boxInset);
        int newTop = Math.round(cy - (h - boxInset * 2f) / 2f - boxInset);
        if (lp.width != newW || lp.height != newH
                || lp.leftMargin != newLeft || lp.topMargin != newTop) {
            lp.width = newW;
            lp.height = newH;
            lp.leftMargin = newLeft;
            lp.topMargin = newTop;
            view.setLayoutParams(lp);
        }
        view.setRotation(live ? o.getRotationDeg() : o.animatedRotation(currentTimeMs));
        // Always written, never skipped when the animation is off: these are VIEW properties on
        // a recycled view, so leaving them alone would strand the last frame's scale/offset on
        // an overlay whose preset was just set back to NONE. Identity is 1/1/0/0.
        view.setScaleX(anim.scaleX);
        view.setScaleY(anim.scaleY);
        view.setTranslationX(anim.dx);
        view.setTranslationY(anim.dy);
        // Text boxes clip per unit inside TextBoxRenderer, so a view-level clip would be a second,
        // coarser mask over the top — identical at BLOCK and simply wrong at LETTER.
        if (!isTextBox) applyReveal(view, anim.revealFrac, w, h);
    }

    // ── The three expressions the GL composite has to agree with, extracted ────────────────────
    //
    // An image overlay that carries effects, a chroma key or a blend mode is drawn by the GL
    // chain rather than by its ImageView (see fxPipFor), and the two must land in the SAME place
    // to the pixel. These are the pieces both of them read. Extracted rather than transcribed:
    // ImageOverlayDraw already says the export's copy of this arithmetic is "MIRRORED from
    // TextOverlayLayer.position", and a THIRD transcription is how the editor and the file start
    // disagreeing about where a picture is.

    /**
     * The entrance/exit preset's whole-body transform at the playhead, or identity while the
     * finger is down — a box mid-animation would slide away from under the gesture.
     */
    @NonNull
    private com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform presetTransformAt(
            @NonNull TextOverlayItem o, float sizeFraction, float contentHeightPx, boolean live) {
        if (live || callback == null) {
            return new com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform();
        }
        return com.fadcam.ui.faditor.transcript.CaptionAnimator.textBoxTransformAt(
                com.fadcam.ui.faditor.transcript.CaptionAnimator.parsePreset(o.getTextAnimPreset()),
                currentTimeMs, o.motionRangeStartMs(),
                o.motionSpanMs(callback.getProjectDurationMs()),
                o.getTextAnimInPct(), o.getTextAnimOutPct(), sizeFraction * contentHeightPx);
    }

    /**
     * An image overlay's drawn HEIGHT and WIDTH, each derived from the SAME unscaled base and
     * scaled by its OWN axis. The per-axis multipliers fold in here rather than into
     * {@code sizeFraction}, so a split (unlinked) Scale X/Y pair stretches the picture along one
     * axis without moving the other — the whole point of the chain toggle.
     *
     * <p><b>Width no longer derives from the SCALED height, and that was a real distortion.</b>
     * It used to read {@code h = sizeFraction * sy * H} and then {@code w = h * aspect * sx},
     * which puts {@code sy} into the WIDTH as well: width scaled by {@code sx * sy} while height
     * scaled by {@code sy}. Setting both axes to the same number — a plain uniform enlargement,
     * the least surprising thing a user can ask for — therefore stretched the picture instead of
     * enlarging it. At 4x it came out four times too wide, which is how JoyRaptor spotted it in a
     * screenshot (2026-08-13).</p>
     *
     * <p><b>Provably inert wherever the scale was never split.</b> The two formulas differ by
     * exactly a factor of {@code sy}, so at {@code scaleY == 1} — every project that only ever
     * used the linked control — they are the same number. Only a project that deliberately
     * unlinked the pair renders differently, and it renders correctly instead of stretched.</p>
     *
     * <p>{@code ImageOverlayDraw} carries the identical pair for the export, and was fixed in
     * the same change: it mirrors this method by design, so a fix here alone would have traded a
     * distorted preview for a preview that disagrees with the file.</p>
     */
    /**
     * A text box with nothing in it — the state a freshly created overlay sits in until the
     * first character is typed. Blank rather than the "Enter text" hint string: the drawer maps
     * the hint to an empty editor on open, and the editor writes that back, so by the time a box
     * is on screen waiting for input its text is genuinely empty.
     *
     * <p>Images are excluded. An image with no text is not an empty box, it is a picture, and
     * one tap on it must keep meaning select.</p>
     */
    private boolean isEmptyTextBox(@NonNull TextOverlayItem o) {
        if (o.isImage() || o.isGeneratedSlide()) return false;
        String t = o.getText();
        if (t == null || t.trim().isEmpty()) return true;
        // The HINT counts as empty too. A box abandoned before typing carries the prompt string
        // rather than "", so testing only for blank would leave exactly the box this is for —
        // the one sitting on screen saying "Enter text" — needing a double-tap.
        return t.equals(getContext().getString(com.fadcam.R.string.faditor_text_hint));
    }

    private float imageHeightPx(@NonNull TextOverlayItem o, float sizeFraction,
                                float contentHeightPx, boolean live) {
        float sy = live ? o.getScaleY() : o.animatedScaleY(currentTimeMs);
        return sizeFraction * contentHeightPx * sy;
    }

    /** @see #imageHeightPx — the same base, through the source aspect, times Scale X. */
    private float imageWidthPx(@NonNull TextOverlayItem o, float sizeFraction,
                               float contentHeightPx, float aspect, boolean live) {
        float sx = live ? o.getScaleX() : o.animatedScaleX(currentTimeMs);
        return sizeFraction * contentHeightPx * aspect * sx;
    }

    /**
     * The decoded picture behind an image overlay, EXIF applied — the same cached bitmap the
     * preview's {@code ImageView} shows.
     *
     * <p>Exposed so the GL composite textures the identical pixels rather than decoding its own
     * copy: two decoders is two sample sizes and two EXIF opinions, which is precisely the class
     * of divergence that made an image export squashed while the preview looked right.</p>
     */
    @Nullable
    public android.graphics.Bitmap decodedImageFor(@NonNull TextOverlayItem o) {
        return imageBitmap(o);
    }

    /**
     * This image overlay's placement for the GL composite, or null when it cannot be drawn there
     * yet (no content rect, no decoded picture).
     *
     * <p><b>Why images enter the GL chain at all.</b> A Canvas cannot run a fragment shader, so an
     * image carrying effects, a chroma key or a blend mode leaves the Canvas path on EXPORT
     * ({@code TextOverlayItem.wantsGlExport}) — and until now the editor had no matching path, so
     * the drawer had to say "export only" for all three. This is the matching path.</p>
     *
     * <p><b>The preset folds into the geometry here, where the View gets it as separate
     * properties.</b> A View is scaled about its pivot, then rotated, then translated in its
     * parent; the export's Canvas reaches the same place with {@code translate}, {@code rotate},
     * {@code scale} in that order. Folding the preset's scale into the half-extents and its
     * translation into the centre is that same composition written once — the reveal is the one
     * channel that cannot fold, because narrowing the box would STRETCH the picture rather than
     * uncover it, so it rides as a uniform.</p>
     *
     * <p>Y AND ROTATION ARE FLIPPED into GL's frame, exactly as {@code OverlayVideoPreviewView}
     * flips a PiP's: this layer works in view space, whose origin is top-left and whose positive
     * rotation is clockwise on screen, while the shader works in a bottom-up uv.</p>
     */
    @Nullable
    public com.fadcam.ui.faditor.compositor.FxPreviewTextureView.Pip fxPipFor(
            @NonNull TextOverlayItem o, int frameW, int frameH) {
        if (callback == null || !o.isImage()) return null;
        if (!o.isVisibleAt(currentTimeMs)) return null;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return null;
        android.graphics.Bitmap bmp = imageBitmap(o);
        if (bmp == null || bmp.isRecycled() || bmp.getHeight() <= 0) return null;

        boolean live = o == manipulating
                || (editingItemId != null && editingItemId.equals(o.getId()));
        float sizeFraction = live ? o.getSizeFraction() : o.animatedSizeFraction(currentTimeMs);
        com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform anim =
                presetTransformAt(o, sizeFraction, r.height(), live);
        float aspect = bmp.getWidth() / (float) bmp.getHeight();
        float hPx = imageHeightPx(o, sizeFraction, r.height(), live);
        float wPx = imageWidthPx(o, sizeFraction, r.height(), aspect, live);

        float cx = (live ? o.getCenterX() : o.animatedCenterX(currentTimeMs))
                + anim.dx / r.width();
        float cy = (live ? o.getCenterY() : o.animatedCenterY(currentTimeMs))
                + anim.dy / r.height();
        float halfW = (wPx * anim.scaleX) / r.width() * 0.5f;
        float halfH = (hPx * anim.scaleY) / r.height() * 0.5f;
        float alpha = live ? 1f
                : Math.max(0f, Math.min(1f, o.animatedOpacity(currentTimeMs) * anim.alpha));
        float rot = live ? o.getRotationDeg() : o.animatedRotation(currentTimeMs);

        return com.fadcam.ui.faditor.compositor.FxPreviewTextureView.Pip.ofImage(
                cx, 1f - cy, halfW, halfH, -rot, alpha,
                o.getFx(), currentTimeMs, o.getCompositing(),
                com.fadcam.ui.faditor.model.BlendModes.modeCode(o.getOverlayBlendMode()),
                frameW, frameH, o.getId(), bmp, anim.revealFrac);
    }

    /**
     * The ids of the image overlays the GL composite is currently drawing, whose own
     * {@code ImageView} must therefore be invisible — otherwise the RAW picture sits on top of
     * the effected one and the whole feature looks like it did nothing. The same trap
     * {@code glOwnsImagePreview} exists for on the image-CLIP path.
     *
     * <p>ALPHA, not {@code GONE}: the view still measures, still lays out and still takes the
     * touches that drag, scale and rotate the image. Hiding it would take the object's own
     * gestures away as the price of showing its effect.</p>
     */
    public void setGlOwnedImageIds(@NonNull java.util.Set<String> ids) {
        if (glOwnedImageIds.equals(ids)) return;
        glOwnedImageIds.clear();
        glOwnedImageIds.addAll(ids);
        refreshPositions();
    }

    @NonNull private final java.util.Set<String> glOwnedImageIds = new java.util.HashSet<>();

    /**
     * The THIRD animated channel on the text-box path: MASK_WIPE's reveal, as a clip on the view.
     *
     * <p><b>This is why MASK_WIPE reaches text boxes when ODOMETER cannot.</b> The recorded wall
     * for the text-box preview is that it draws an overlay as one {@code TextView} holding one
     * string, so it cannot draw two clipped glyph rows the way the export's {@code canvas.drawText}
     * could — that is a divergence, and it is what keeps text boxes BLOCK-only and blocks ODOMETER
     * here. MASK_WIPE asks for something different: not two things drawn, but one thing shown in
     * part. {@code View.setClipBounds} does exactly that, in the view's own coordinate space and
     * therefore BEFORE its scale/translation are applied — which is the same order
     * {@code CompositeExportOverlay} gets by clipping after its matrix, so the two agree. No canvas
     * renderer, no per-glyph layout, and no preview/export divergence.</p>
     *
     * <p>The bounds are the view's full measured box: a text box is one unit (BLOCK), so the wipe
     * runs across the whole body rather than per word. The preview's box is the text's own measured
     * extent while the export's bitmap carries a 0.35em pad, so the export insets by that pad to
     * wipe across the same ink — see {@code TextOverlayRenderer.padPxFor}.</p>
     *
     * <p>Written on EVERY call, never skipped when the animation is off, for exactly the reason the
     * scale and translation above are: these are properties on a RECYCLED view, so a preset set
     * back to NONE would otherwise strand the last frame's mask and leave the box permanently
     * half-drawn. {@code null} is the identity.</p>
     */
    private void applyReveal(@NonNull View view, float revealFrac, int w, int h) {
        if (revealFrac >= 1f) {
            view.setClipBounds(null);
            return;
        }
        float f = Math.max(0f, revealFrac);
        clipTmp.set(0, 0, Math.round(Math.max(1, w) * f), Math.max(1, h));
        view.setClipBounds(clipTmp);
    }

    /**
     * Scratch for {@link #applyReveal}. Safe to reuse: {@code setClipBounds} copies into the
     * view's own rect rather than retaining this one.
     */
    private final android.graphics.Rect clipTmp = new android.graphics.Rect();

    private void attachGestures(@NonNull View tv, @NonNull TextOverlayItem o) {
        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        o.setSizeFraction(o.getSizeFraction() * detector.getScaleFactor());
                        position(tv, o);
                        return true;
                    }
                });

        tv.setOnTouchListener(new OnTouchListener() {
            float downRawX, downRawY, startCenterX, startCenterY;
            boolean moved;
            /** The hold (long-press) fired — this gesture is fully consumed. */
            boolean heldFired;
            @Nullable TextOverlayItem.TransformSnapshot beforeGesture;
            final Runnable holdRunnable = () -> {
                // Hold with no movement = general properties drawer (gesture
                // grammar 2026-07-17). The overlay hasn't moved, so just end
                // the manipulation cleanly and hand off.
                heldFired = true;
                manipulating = null;
                tv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                position(tv, o);
                if (callback != null) callback.onOverlayHeld(o);
            };

            void cancelHold() { tv.removeCallbacks(holdRunnable); }

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // Z3: an INERT instance (the below-video surface) draws but never grabs
                // touch — two hit-testing text layers would have the top one silently eat
                // taps meant for the bottom. See SPEC_CROSSTYPE_Z's Z3 note.
                if (!interactive) return false;
                // WYSIWYG reframe: while this item is being edited ON the box, the transparent
                // editor child owns every touch in the box — and the excursion ring counts too,
                // or dragging the margin would yank the box out from under the caret. The item
                // becomes inert until the drawer closes: move/scale/rotate return on exit.
                if (editingItemId != null && editingItemId.equals(o.getId())) return false;
                scaleDetector.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startCenterX = o.getCenterX();
                        startCenterY = o.getCenterY();
                        moved = false;
                        heldFired = false;
                        manipulating = o;
                        beforeGesture = o.snapshotTransform();
                        tv.postDelayed(holdRunnable,
                                android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        cancelHold(); // pinch incoming — not a hold
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (heldFired) return true;
                        if (scaleDetector.isInProgress() || callback == null) {
                            cancelHold();
                            return true;
                        }
                        RectF r = callback.getVideoContentRect();
                        if (r.width() <= 0 || r.height() <= 0) return true;
                        // Screen-pixel delta over a local-pixel rect: correct only while nothing
                        // above is scaled, and player_container shrinks to clear a drawer.
                        float ui = UiScale.of(TextOverlayLayer.this);
                        float dx = (e.getRawX() - downRawX) / ui / r.width();
                        float dy = (e.getRawY() - downRawY) / ui / r.height();
                        if (Math.abs(e.getRawX() - downRawX) > 8
                                || Math.abs(e.getRawY() - downRawY) > 8) {
                            moved = true;
                            cancelHold();
                        }
                        if (snapEnabled) {
                            float snappedX = snapX(startCenterX + dx);
                            float snappedY = snapY(startCenterY + dy);
                            o.setCenter(snappedX, snappedY);
                        } else {
                            o.setCenter(startCenterX + dx, startCenterY + dy);
                        }
                        position(tv, o);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelHold();
                        manipulating = null;
                        beforeGesture = null;
                        position(tv, o);
                        return true;
                    case MotionEvent.ACTION_UP:
                        cancelHold();
                        if (heldFired) { // drawer already opened; swallow the UP
                            beforeGesture = null;
                            return true;
                        }
                        manipulating = null;
                        if (callback != null) {
                            if (!moved) {
                                long now = android.os.SystemClock.uptimeMillis();
                                if (o == lastTapOverlay && now - lastTapUpMs <= 320) {
                                    lastTapOverlay = null;
                                    callback.onEditRequested(o); // double-tap = type editor
                                } else if (isEmptyTextBox(o)) {
                                    // ONE TAP OPENS AN EMPTY BOX. Selecting a box with nothing
                                    // in it accomplishes nothing the user wants — there is no
                                    // content to style, and the only sensible next move is to
                                    // type. Requiring a double-tap to reach the one useful
                                    // action is what "I can't actually get the text dialog box
                                    // up ... it should be easy for me to tap on the text box to
                                    // edit it" describes (JoyRaptor, 2026-08-13).
                                    //
                                    // Only for an EMPTY box: a filled one keeps tap-to-select,
                                    // because selecting is how you reach its style controls and
                                    // stealing that would trade one complaint for another.
                                    lastTapOverlay = null;
                                    // POSTED, not called inline. Opening the editor rebuilds
                                    // this layer's views and then posts focus + showIme onto the
                                    // fresh box; doing that while this ACTION_UP is still being
                                    // dispatched leaves the drawer open with no keyboard, which
                                    // is the very state this shortcut exists to avoid. The
                                    // double-tap path escapes it only because its first tap has
                                    // already let the touch stream finish.
                                    final TextOverlayItem target = o;
                                    tv.post(() -> {
                                        if (callback != null) callback.onEditRequested(target);
                                    });
                                } else {
                                    lastTapOverlay = o;
                                    lastTapUpMs = now;
                                    callback.onOverlaySelected(o); // tap = select
                                }
                            } else {
                                // Auto-keyframe: once an overlay is armed (has a
                                // keyframe), moving it at the playhead records a
                                // keyframe there (After Effects "stopwatch on"
                                // behaviour). Un-armed overlays just move.
                                if (o.isArmed()) {
                                    long snappedTime = snapEnabled ? snapTimeMs(currentTimeMs) : currentTimeMs;
                                    if (snappedTime != currentTimeMs) currentTimeMs = snappedTime;
                                    o.addKeyframeAt(currentTimeMs);
                                }
                                if (beforeGesture != null) {
                                    callback.onOverlayManipulated(o, beforeGesture);
                                }
                                callback.onOverlayChanged();
                            }
                        }
                        beforeGesture = null;
                        position(tv, o);
                        return true;
                }
                return false;
            }
        });
    }

    private float snapX(float x) {
        float limit = manipulating != null ? manipulating.getCenterLimitX() : 1f;
        float best = clamp(x, limit);
        float bestDistance = SNAP_THRESHOLD;
        float[] targets = new float[]{0.5f};
        for (TextOverlayItem other : overlays) {
            if (other != manipulating) targets = append(targets, other.getCenterX());
        }
        for (float target : targets) {
            float d = Math.abs(x - target);
            if (d < bestDistance) {
                bestDistance = d;
                best = target;
            }
        }
        return clamp(best, limit);
    }

    private float snapY(float y) {
        float limit = manipulating != null ? manipulating.getCenterLimitY() : 1f;
        float best = clamp(y, limit);
        float bestDistance = SNAP_THRESHOLD;
        float[] targets = new float[]{0.5f};
        for (TextOverlayItem other : overlays) {
            if (other != manipulating) targets = append(targets, other.getCenterY());
        }
        for (float target : targets) {
            float d = Math.abs(y - target);
            if (d < bestDistance) {
                bestDistance = d;
                best = target;
            }
        }
        return clamp(best, limit);
    }

    private long snapTimeMs(long timeMs) {
        long best = timeMs;
        long bestDistance = TIME_SNAP_MS;
        for (TextOverlayItem other : overlays) {
            if (other == manipulating) continue;
            best = snapToOneEdge(timeMs, best, bestDistance, other.getStartMs());
            if (other.getEndMs() != Long.MAX_VALUE) {
                best = snapToOneEdge(timeMs, best, bestDistance, other.getEndMs());
            }
        }
        return best;
    }

    private long snapToOneEdge(long timeMs, long best, long bestDistance, long edge) {
        long d = Math.abs(timeMs - edge);
        if (d < bestDistance) {
            bestDistance = d;
            best = edge;
        }
        return best;
    }

    private float[] append(float[] values, float value) {
        float[] out = java.util.Arrays.copyOf(values, values.length + 1);
        out[out.length - 1] = value;
        return out;
    }

    private float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(1f + limit, value));
    }
}
