package cc.joycreator.textlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Nullable;

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
                if (in == null) throw new java.io.IOException("the file could not be opened");
                r = PackInstaller.read(in);
            } catch (Exception e) {
                problem = e.getMessage() != null ? e.getMessage() : "it could not be read";
            }
            PackInstaller.Read got = r;
            String why = problem;
            runOnUiThread(() -> {
                if (got == null) say("This is not a pack Joy Creator can open: " + why + ".");
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
            say("\"" + p.name + "\" needs a newer Joy Creator. Update the app, then open the pack again.");
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JoyPack.Entry e : p.contents) counts.merge(e.known() ? plain(e.socket) : "later", 1, Integer::sum);
        StringBuilder what = new StringBuilder();
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            if (c.getKey().equals("later")) continue;
            if (what.length() > 0) what.append(", ");
            what.append(c.getValue()).append(' ').append(c.getKey());
        }
        StringBuilder msg = new StringBuilder();
        if (!p.author.isEmpty()) msg.append("By ").append(p.author).append(p.version.isEmpty() ? "" : "  ·  version " + p.version).append("\n\n");
        if (!p.about.isEmpty()) msg.append(p.about).append("\n\n");
        msg.append(what.length() > 0 ? what : "Nothing this version can use yet").append("  ·  ")
                .append(Formatter.formatShortFileSize(this, r.bytes));
        Integer later = counts.get("later");
        if (later != null) msg.append("\n").append(later).append(later == 1 ? " thing waits" : " things wait").append(" for a newer Joy Creator.");
        msg.append("\n\nIt goes to Supplies › Packs in your Joy Creator folder.");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p.name)
                .setMessage(msg)
                .setPositiveButton("Install", (d, w) -> install(r))
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void install(PackInstaller.Read r) {
        JoyFolderHome.io().execute(() -> {
            String done, problem = null;
            try {
                JoyFolder jf = JoyFolderHome.open(this);
                if (jf == null) throw new java.io.IOException("choose your Joy Creator folder first (Setup)");
                done = PackInstaller.install(jf, r);
                // Every wing that reads packs sees this one now (PACK-1): Studio's fonts, grades and caption
                // styles are copied into the app; VisLab offers its looks and parts.
                com.fadcam.joyfolder.studio.PackSupplies.sync(this);
                com.fadcam.vislab.room.VisLabSession.packsChanged(this);
            } catch (Exception e) {
                done = null;
                problem = e.getMessage();
            }
            String where = done, why = problem;
            runOnUiThread(() -> {
                Toast.makeText(this, where != null ? "Installed \"" + r.pack.name + "\" in " + where
                        : "Not installed: " + why, Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }

    private void say(String text) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage(text).setPositiveButton("OK", (d, w) -> finish())
                .setOnCancelListener(d -> finish()).show();
    }

    /** A socket in the person's words. */
    private static String plain(String socket) {
        switch (socket) {
            case "text-look": return "title looks";
            case "vis-look": return "VisLab looks";
            case "vis-part": return "VisLab parts";
            case "brush": return "brushes";
            case "paper": return "papers";
            case "palette": return "palettes";
            case "font": return "fonts";
            case "grade": return "colour grades";
            case "caption-style": return "caption styles";
            default: return socket;
        }
    }
}
