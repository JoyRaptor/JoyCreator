package cc.joycreator.avatar.studio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.StudioDialog;
import com.fadcam.ui.faditor.export.ExportSheetParts;
import com.fadcam.ui.faditor.model.SolidColour;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Avatar Studio's export sheet (slice 2, items 5 and 6; spec A11), built from Studio's own sheet parts
 * ({@link ExportSheetParts}) so the two look alike: the name, then what to make.
 * <ul>
 *   <li><b>Joy Creator file</b>: the character's own file, shared as it is (it opens in the pilot too).</li>
 *   <li><b>One frame</b>: a picture of the character as it stands, see-through when ticked.</li>
 *   <li><b>Video</b>: through Studio's exporter, never a second one: a new Studio project with the character on its
 *       key colour (Studio's Solid colour clip), and Studio's export sheet open on it (size, quality, progress, Stop).</li>
 *   <li><b>Send to Studio</b>: the character, its take and its voice into a chosen or new project.</li>
 * </ul>
 * The room opens it with {@link #show}; the room owns nothing here but the button.
 */
public final class RoomExportSheet {
    /** Studio's editor reads these: the character to drop when it opens, a key colour under it, the export sheet. */
    public static final String EXTRA_DROP_CHARACTER = "ava_drop_character";
    public static final String EXTRA_DROP_KEY_COLOUR = "ava_drop_key_colour";
    public static final String EXTRA_OPEN_EXPORT = "ava_open_export";

    private RoomExportSheet() {}

    /**
     * @param ref      the character's reference (its path in the Joy Creator folder, or a built-in one); the room saves
     *                 first so the file is current
     * @param signals  the character as it stands now (for One frame), or null for its rest
     */
    public static void show(@NonNull Activity a, @NonNull String ref, @NonNull String name,
                            @Nullable Map<String, Object> signals) {
        int pad = SheetKit.dp(a, 20);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        EditText fileName = ExportSheetParts.nameRow(a, root, name.isEmpty() ? "Character" : name, pad);
        SheetKit.label(fileName, a.getString(R.string.ava_export_name_hover));

        TextView what = new TextView(a);
        what.setText(R.string.ava_export_what);
        what.setTextColor(Studio.INK_FAINT);
        what.setTextSize(11);
        what.setPadding(0, pad / 2, 0, 0);
        root.addView(what);

        final int[] kind = {2};   // 0 file, 1 frame, 2 video, 3 Send to Studio
        int[] labels = {R.string.ava_export_file, R.string.ava_export_frame, R.string.ava_export_video, R.string.ava_export_send};
        int[] hovers = {R.string.ava_export_file_hover, R.string.ava_export_frame_hover, R.string.ava_export_video_hover,
                R.string.ava_export_send_hover};
        CheckBox[] boxes = new CheckBox[4];
        LinearLayout frameExtras = new LinearLayout(a), videoExtras = new LinearLayout(a);
        frameExtras.setOrientation(LinearLayout.VERTICAL);
        videoExtras.setOrientation(LinearLayout.VERTICAL);
        Runnable sync = () -> {
            for (int i = 0; i < 4; i++) boxes[i].setChecked(i == kind[0]);
            frameExtras.setVisibility(kind[0] == 1 ? android.view.View.VISIBLE : android.view.View.GONE);
            videoExtras.setVisibility(kind[0] == 2 ? android.view.View.VISIBLE : android.view.View.GONE);
        };
        for (int i = 0; i < 4; i++) {
            final int k = i;
            boxes[i] = ExportSheetParts.checkbox(a, root, labels[i], 0, pad);
            SheetKit.label(boxes[i], a.getString(hovers[i]));
            boxes[i].setOnClickListener(v -> {
                kind[0] = k;
                sync.run();
            });
            if (i == 1) root.addView(frameExtras);
            if (i == 2) root.addView(videoExtras);
        }

        CheckBox seeThrough = ExportSheetParts.checkbox(a, frameExtras, R.string.ava_export_see_through,
                R.string.ava_export_see_through_desc, pad);
        seeThrough.setChecked(true);
        SheetKit.label(seeThrough, a.getString(R.string.ava_export_see_through_hover));

        // the key colour behind him: none, green, blue, or any colour
        final int[] key = {SolidColour.KEY_GREEN};
        TextView keyLabel = new TextView(a);
        keyLabel.setText(R.string.ava_export_key);
        keyLabel.setTextColor(Studio.INK_FAINT);
        keyLabel.setTextSize(11);
        keyLabel.setPadding(0, pad / 2, 0, 0);
        videoExtras.addView(keyLabel);
        LinearLayout keys = new LinearLayout(a);
        TextView green = SheetKit.chip(a, a.getString(R.string.ava_export_key_green));
        TextView blue = SheetKit.chip(a, a.getString(R.string.ava_export_key_blue));
        TextView other = SheetKit.chip(a, a.getString(R.string.ava_export_key_other));
        TextView none = SheetKit.chip(a, a.getString(R.string.ava_export_key_none));
        SheetKit.label(green, a.getString(R.string.ava_export_key_green_hover));
        SheetKit.label(blue, a.getString(R.string.ava_export_key_blue_hover));
        SheetKit.label(other, a.getString(R.string.ava_export_key_other_hover));
        SheetKit.label(none, a.getString(R.string.ava_export_key_none_hover));
        TextView[] keyChips = {green, blue, other, none};
        Runnable paintKeys = () -> {
            int on = key[0] == SolidColour.KEY_GREEN ? 0 : key[0] == SolidColour.KEY_BLUE ? 1 : key[0] == 0 ? 3 : 2;
            for (int i = 0; i < 4; i++) SheetKit.setChipSelected(keyChips[i], i == on);
        };
        green.setOnClickListener(v -> { key[0] = SolidColour.KEY_GREEN; paintKeys.run(); });
        blue.setOnClickListener(v -> { key[0] = SolidColour.KEY_BLUE; paintKeys.run(); });
        none.setOnClickListener(v -> { key[0] = 0; paintKeys.run(); });
        other.setOnClickListener(v -> com.fadcam.ui.faditor.tools.ColorPickerDialog.show(a,
                a.getString(R.string.ava_export_key), key[0] == 0 ? SolidColour.KEY_GREEN : key[0], false, c -> {
                    if (c != null) key[0] = SolidColour.opaque(c);
                    paintKeys.run();
                }));
        for (TextView t : keyChips) keys.addView(t);
        videoExtras.addView(keys);
        ExportSheetParts.note(a, videoExtras, R.string.ava_export_video_note);
        paintKeys.run();
        sync.run();

        ScrollView scroll = new ScrollView(a);
        scroll.addView(root);
        AlertDialog d = StudioDialog.builder(a)
                .setTitle(R.string.ava_export_title)
                .setView(scroll)
                .setPositiveButton(R.string.ava_export_go, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.show();
        SheetKit.label(d.getButton(AlertDialog.BUTTON_POSITIVE), a.getString(R.string.ava_export_go_hover));
        SheetKit.label(d.getButton(AlertDialog.BUTTON_NEGATIVE), a.getString(R.string.ava_cancel_hover));
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String base = ExportSheetParts.sanitizeName(fileName.getText().toString(), name.isEmpty() ? "Character" : name);
            switch (kind[0]) {
                case 0: shareFile(a, ref, base); break;
                case 1: frame(a, ref, base, signals, seeThrough.isChecked()); break;
                case 2: toStudio(a, ref, null, key[0] == 0 ? SolidColour.KEY_GREEN : key[0], key[0] == 0, true); break;
                default: chooseProject(a, ref); break;
            }
            d.dismiss();
        });
    }

    /** Opens Studio on a project (new when {@code projectId} is null) and drops the character there. */
    static void toStudio(Activity a, String ref, @Nullable String projectId, int keyColour, boolean blackInstead,
                         boolean openExport) {
        Intent it = new Intent(a, com.fadcam.ui.faditor.FaditorEditorActivity.class);
        if (projectId != null) it.putExtra(com.fadcam.ui.faditor.FaditorEditorActivity.EXTRA_PROJECT_ID, projectId);
        it.putExtra(EXTRA_DROP_CHARACTER, ref);
        it.putExtra(EXTRA_DROP_KEY_COLOUR, blackInstead ? 0 : keyColour);
        it.putExtra(EXTRA_OPEN_EXPORT, openExport);
        a.startActivity(it);
    }

    private static void chooseProject(Activity a, String ref) {
        List<ProjectStorage.ProjectSummary> ps = new ProjectStorage(a).listProjects();
        String[] names = new String[ps.size() + 1];
        names[0] = a.getString(R.string.ava_export_send_new);
        for (int i = 0; i < ps.size(); i++) names[i + 1] = ps.get(i).name;
        StudioDialog.builder(a)
                .setTitle(R.string.ava_export_send_title)
                .setItems(names, (d, w) -> {
                    if (w == 0) toStudio(a, ref, null, 0, true, false);
                    else toStudio(a, ref, ps.get(w - 1).id, 0, true, false);
                })
                .show();
    }

    private static void shareFile(Activity a, String ref, String base) {
        new Thread(() -> {
            File out = null;
            try {
                CharacterLoader.forget(ref);
                CharacterLoader.Loaded l = CharacterLoader.load(a, ref);
                if (l != null) {
                    File dir = new File(a.getCacheDir(), "avatar-share");
                    if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    out = new File(dir, base + ".character.json");
                    try (FileOutputStream o = new FileOutputStream(out)) {
                        o.write(l.doc.json.toString(2).replace("\\/", "/").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                out = null;
            }
            final File f = out;
            a.runOnUiThread(() -> share(a, f, "application/json"));
        }).start();
    }

    private static void frame(Activity a, String ref, String base, @Nullable Map<String, Object> signals, boolean clear) {
        new Thread(() -> {
            File out = null;
            CharacterLoader.Loaded l = CharacterLoader.load(a, ref);
            if (l != null && l.doc.w > 0 && l.doc.h > 0) {
                float s = Math.min(2f, 2048f / Math.max(l.doc.w, l.doc.h));
                Bitmap bmp = null;
                CharacterPoser p = CharacterPosers.get().forDoc(l.doc, new CharacterPictures(l.doc, l.beside));
                try {
                    bmp = Bitmap.createBitmap(Math.round(l.doc.w * s), Math.round(l.doc.h * s), Bitmap.Config.ARGB_8888);
                    if (!clear) bmp.eraseColor(0xFFFFFFFF);
                    Canvas cv = new Canvas(bmp);
                    cv.scale(s, s);
                    p.draw(cv, signals, 0);
                    File dir = new File(a.getCacheDir(), "avatar-share");
                    if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    out = new File(dir, base + ".png");
                    try (FileOutputStream o = new FileOutputStream(out)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
                    }
                } catch (Exception | OutOfMemoryError e) {
                    out = null;
                } finally {
                    p.release();
                    if (bmp != null) bmp.recycle();
                }
            }
            final File f = out;
            a.runOnUiThread(() -> share(a, f, "image/png"));
        }).start();
    }

    private static void share(Activity a, @Nullable File f, String type) {
        if (f == null) {
            Toast.makeText(a, R.string.ava_export_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri u = androidx.core.content.FileProvider.getUriForFile(a, a.getPackageName() + ".provider", f);
            Intent send = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(send, f.getName()));
        } catch (RuntimeException e) {
            Toast.makeText(a, R.string.ava_export_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
