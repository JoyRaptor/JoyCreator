package cc.joycreator.avatar.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * The face's settings in a sheet behind the Face chip (the owner, 11-28: "random blank spots, terrible layout"; the lead: a
 * sheet styled like Studio's, no half-width column in Perform). Everything the face camera brings: the private preview,
 * Re-centre, Mirror, Check directions; Shoulders and their sensitivity; which camera reads the face and which the body; walking
 * with your face. Built from {@link SheetKit} as Studio's sheets are. The stage stays visible behind it (no dim), so a
 * change shows on the character at once.
 */
final class FaceSheet {

    private final Activity a;
    private final PerformPanel.Host host;
    @Nullable private BottomSheetDialog dialog;
    @Nullable private LinearLayout body;
    @Nullable TextView mirrorChip;                                   // (read by the panel's paint, and by the emulator tests)
    private final List<TextView> cameraChips = new ArrayList<>();
    private final List<String[]> cameraKeys = new ArrayList<>();      // {key, "b" for the body's row}
    private boolean opening;

    FaceSheet(@NonNull Activity a, @NonNull PerformPanel.Host host) {
        this.a = a;
        this.host = host;
    }

    boolean isOpen() {
        return dialog != null && dialog.isShowing();
    }

    /** Opens the sheet (or brings it up to date when open). */
    void open() {
        if (dialog == null) {
            BottomSheetDialog d = new BottomSheetDialog(a);
            ScrollView scroll = new ScrollView(a);
            LinearLayout b = new LinearLayout(a);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setPadding(0, 0, 0, SheetKit.dp(a, 12));
            scroll.addView(b);
            d.setContentView(SheetKit.fitNavBar(scroll));
            SheetKit.install(d, null);
            d.getBehavior().setSkipCollapsed(true);
            d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            if (d.getWindow() != null) d.getWindow().setDimAmount(0f);   // (the character stays in sight while you change things)
            d.setOnDismissListener(x -> {
                View p = host.facePreview();                            // the preview goes back to no one: it is the camera's
                if (p != null && p.getParent() instanceof ViewGroup) ((ViewGroup) p.getParent()).removeView(p);
                View w = host.faceWalkDrawer();
                if (w != null && w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
                dialog = null;
                body = null;
                mirrorChip = null;
            });
            dialog = d;
            body = b;
        }
        opening = true;
        rebuild();
        opening = false;
        if (!dialog.isShowing()) dialog.show();
    }

    void close() {
        if (dialog != null) dialog.dismiss();
    }

    /** Lays the sheet out again from what is true now (a tick, a camera chosen, an undo). */
    void rebuild() {
        LinearLayout b = body;
        if (b == null) return;
        b.removeAllViews();
        cameraChips.clear();
        cameraKeys.clear();
        int inset = SheetKit.dp(a, SheetKit.ROW_INSET_DP);
        b.addView(SheetKit.header(a, a.getString(R.string.avatar_face_sheet), null).view);

        // the preview and the three things you do with your face before performing
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(inset, SheetKit.dp(a, 4), inset, SheetKit.dp(a, 4));
        View preview = host.facePreview();
        if (preview != null) {
            if (preview.getParent() instanceof ViewGroup) ((ViewGroup) preview.getParent()).removeView(preview);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(SheetKit.dp(a, 96), SheetKit.dp(a, 72));
            pl.setMarginEnd(SheetKit.dp(a, 10));
            top.addView(preview, pl);
        }
        LinearLayout acts = new LinearLayout(a);
        acts.setOrientation(LinearLayout.VERTICAL);
        acts.addView(chip(a.getString(R.string.avatar_recentre), a.getString(R.string.avatar_hover_recentre), v -> host.recentreFace()));
        mirrorChip = chip(a.getString(R.string.avatar_mirror), a.getString(R.string.avatar_hover_mirror), v -> {
            host.setFaceMirror(!host.faceMirror());
            paint();
        });
        acts.addView(mirrorChip);
        acts.addView(chip(a.getString(R.string.avatar_face_dirs), a.getString(R.string.avatar_hover_face_dirs), v -> host.checkDirections()));
        top.addView(acts);
        b.addView(top);

        // SHRUG-1: Shoulders and, while on, how little shrug makes a full one
        if (host.shouldersShown()) {
            b.addView(SheetKit.sectionLabel(a, a.getString(R.string.avatar_shrug), 0));   // (not "Shoulders": the tick box below says that)
            LinearLayout sh = inset();
            boolean shOn = host.shouldersOn();
            sh.addView(WalkSections.tick(a, a.getString(R.string.avatar_shoulders), shOn, a.getString(R.string.avatar_hover_shoulders),
                    a.getResources().getDisplayMetrics().density, v -> {
                        host.setShouldersOn(v);
                        rebuild();
                    }));
            if (shOn && host instanceof ShrugSections.Host)
                sh.addView(ShrugSections.sensitivity(a, (ShrugSections.Host) host, a.getResources().getDisplayMetrics().density));
            b.addView(sh);
        }

        // cameras per tracker: only where there is a choice
        List<String> cams = host.cameraChoices();
        if (cams != null && cams.size() > 1) {
            b.addView(SheetKit.sectionLabel(a, a.getString(R.string.avatar_face_camera), 0));
            b.addView(cameraRow(false, cams));
            if (host.shouldersShown() && host.shouldersOn()) {
                b.addView(SheetKit.sectionLabel(a, a.getString(R.string.avatar_body_camera), 0));
                b.addView(cameraRow(true, cams));
            }
            if (host.camerasRefused()) {
                LinearLayout r = inset();
                TextView why = new TextView(a);
                why.setText(R.string.avatar_cams_refused);
                why.setTextSize(SheetKit.DESC_SP);
                why.setTextColor(SheetKit.ROW_DESC);
                r.addView(why);
                LinearLayout one = new LinearLayout(a);
                one.setPadding(0, SheetKit.dp(a, 6), 0, 0);
                one.addView(chip(a.getString(R.string.avatar_cams_one), a.getString(R.string.avatar_hover_cams_one),
                        v -> host.setCamera(true, host.faceCamera())));
                r.addView(one);
                b.addView(r);
                // said where it is seen: after a change the line and its chip come into view (they appear below the chips
                // just tapped); a sheet that opens starts at its top
                if (!opening) r.post(() -> r.requestRectangleOnScreen(new android.graphics.Rect(0, 0, r.getWidth(), r.getHeight()), false));
            }
        }

        // walking with your face
        View walk = host.faceWalkDrawer();
        if (walk != null) {
            if (walk.getParent() instanceof ViewGroup) ((ViewGroup) walk.getParent()).removeView(walk);
            b.addView(SheetKit.sectionLabel(a, a.getString(R.string.avatar_face_sheet_walk), 0));
            LinearLayout w = inset();
            w.addView(walk);
            b.addView(w);
        }
        paint();
    }

    /** What is on now: Mirror, the chosen cameras. */
    void paint() {
        if (mirrorChip != null) SheetKit.setChipSelected(mirrorChip, host.faceMirror());
        String f = host.faceCamera(), bc = host.bodyCamera();
        for (int i = 0; i < cameraChips.size(); i++) {
            String[] k = cameraKeys.get(i);
            SheetKit.setChipSelected(cameraChips.get(i), k[0].equals("b".equals(k[1]) ? bc : f));
        }
    }

    private View cameraRow(boolean body, List<String> cams) {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = inset();
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String key : cams) {
            int word = "back-wide".equals(key) ? R.string.avatar_cam_back_wide : "back".equals(key) ? R.string.avatar_cam_back : R.string.avatar_cam_front;
            int what = "back-wide".equals(key) ? R.string.avatar_hover_cam_back_wide : "back".equals(key) ? R.string.avatar_hover_cam_back : R.string.avatar_hover_cam_front;
            TextView t = chip(a.getString(word), a.getString(body ? R.string.avatar_hover_body_camera : R.string.avatar_hover_face_camera, a.getString(what)),
                    v -> host.setCamera(body, key));
            cameraChips.add(t);
            cameraKeys.add(new String[]{key, body ? "b" : "f"});
            row.addView(t);
        }
        hs.addView(row);
        return hs;
    }

    private LinearLayout inset() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int inset = SheetKit.dp(a, SheetKit.ROW_INSET_DP);
        l.setPadding(inset, 0, inset, SheetKit.dp(a, 4));
        return l;
    }

    /** A Studio chip with its hover label (one tap, one thing). */
    private TextView chip(String text, String hover, View.OnClickListener tap) {
        TextView t = SheetKit.chip(a, text);
        SheetKit.setChipSelected(t, false);
        SheetKit.label(t, hover);
        SheetKit.press(t);
        t.setOnClickListener(tap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(SheetKit.dp(a, 6));
        lp.topMargin = SheetKit.dp(a, 3);
        lp.bottomMargin = SheetKit.dp(a, 3);
        t.setLayoutParams(lp);
        return t;
    }
}
