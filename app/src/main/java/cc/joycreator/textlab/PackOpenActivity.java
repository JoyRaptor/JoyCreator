package cc.joycreator.textlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyPack;
import com.fadcam.joyfolder.android.JoyFolderHome;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opening a {@code .joypack} (job PACK-1): from a file manager, a download, or a share. Shows what the pack is and
 * what it holds, and installs it on one tap into {@code Supplies/Packs/<pack name>/}. Nothing is written before the
 * person says Install. Plain words only: a pack that cannot be used says why, never an error code.
 */
public final class PackOpenActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Uri uri = uriOf(getIntent());
        if (uri == null) {
            finish();
            return;
        }
        JoyFolderHome.io().execute(() -> {
            PackInstaller.Read r = null;
            String problem = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException(getString(R.string.textlab_pack_file_not_opened));
                r = PackInstaller.read(in);
            } catch (Exception e) {
                problem = e.getMessage() != null ? e.getMessage() : getString(R.string.textlab_pack_not_readable);
            }
            PackInstaller.Read got = r;
            String why = problem;
            runOnUiThread(() -> {
                if (got == null) say(getString(R.string.textlab_pack_not_openable, why));
                else ask(got);
            });
        });
    }

    @Nullable
    private static Uri uriOf(Intent in) {
        if (in == null) return null;
        if (Intent.ACTION_SEND.equals(in.getAction())) return in.getParcelableExtra(Intent.EXTRA_STREAM);
        return in.getData();
    }

    private void ask(PackInstaller.Read r) {
        JoyPack p = r.pack;
        if (!p.usable()) {
            say(getString(R.string.textlab_pack_needs_newer, p.name));
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JoyPack.Entry e : p.contents) counts.merge(e.known() ? plain(this, e.socket) : "later", 1, Integer::sum);
        StringBuilder what = new StringBuilder();
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            if (c.getKey().equals("later")) continue;
            if (what.length() > 0) what.append(", ");
            what.append(c.getValue()).append(' ').append(c.getKey());
        }
        StringBuilder msg = new StringBuilder();
        if (!p.author.isEmpty()) msg.append(p.version.isEmpty() ? getString(R.string.textlab_pack_by, p.author)
                : getString(R.string.textlab_pack_by_version, p.author, p.version)).append("\n\n");
        if (!p.about.isEmpty()) msg.append(p.about).append("\n\n");
        msg.append(what.length() > 0 ? what : getString(R.string.textlab_pack_nothing_usable)).append("  ·  ")
                .append(Formatter.formatShortFileSize(this, r.bytes));
        Integer later = counts.get("later");
        if (later != null) msg.append("\n").append(getString(later == 1 ? R.string.textlab_pack_waits_one : R.string.textlab_pack_waits_many, later));
        msg.append("\n\n").append(getString(R.string.textlab_pack_goes_to));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p.name)
                .setMessage(msg)
                .setPositiveButton(R.string.textlab_pack_install, (d, w) -> install(r))
                .setNegativeButton(R.string.textlab_pack_cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void install(PackInstaller.Read r) {
        JoyFolderHome.io().execute(() -> {
            String done, problem = null;
            try {
                JoyFolder jf = JoyFolderHome.open(this);
                if (jf == null) throw new java.io.IOException(getString(R.string.textlab_pack_choose_folder));
                done = PackInstaller.install(jf, r);
                // Every wing that reads packs sees this one now (PACK-1): Studio's fonts, grades and caption
                // styles are copied into the app; VisLab offers its looks and parts.
                com.fadcam.joyfolder.studio.PackSuppliesSync.sync(this);
                com.fadcam.vislab.room.VisLabSession.packsChanged(this);
            } catch (Exception e) {
                done = null;
                problem = e.getMessage();
            }
            String where = done, why = problem;
            runOnUiThread(() -> {
                Toast.makeText(this, where != null ? getString(R.string.textlab_pack_installed, r.pack.name, where)
                        : getString(R.string.textlab_pack_not_installed, why), Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }

    private void say(String text) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage(text).setPositiveButton(R.string.textlab_pack_ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish()).show();
    }

    /** A socket in the person's words. */
    private static String plain(android.content.Context c, String socket) {
        switch (socket) {
            case "text-look": return c.getString(R.string.textlab_pack_kind_text_look);
            case "vis-look": return c.getString(R.string.textlab_pack_kind_vis_look);
            case "vis-part": return c.getString(R.string.textlab_pack_kind_vis_part);
            case "brush": return c.getString(R.string.textlab_pack_kind_brush);
            case "paper": return c.getString(R.string.textlab_pack_kind_paper);
            case "palette": return c.getString(R.string.textlab_pack_kind_palette);
            case "font": return c.getString(R.string.textlab_pack_kind_font);
            case "grade": return c.getString(R.string.textlab_pack_kind_grade);
            case "caption-style": return c.getString(R.string.textlab_pack_kind_caption_style);
            default: return socket;
        }
    }
}
