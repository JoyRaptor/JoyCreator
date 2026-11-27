package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;
import android.util.Size;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.ui.faditor.export.CompositeExportOverlay;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteOverlayView;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.studio.CharacterLoader;
import cc.joycreator.avatar.studio.CharacterPosers;
import cc.joycreator.avatar.studio.RestPoser;
import cc.joycreator.avatar.studio.SolverPoser;
import cc.joycreator.avatar.studio.StudioCharacter;

/**
 * A character in Video Studio moves (AVA-1 slice 2, through X2 + X3): Topps and Pip placed as Studio places them
 * (StudioCharacter.prepareNow, a one-cell sheet of the standing picture, the take on the item), then drawn by Studio's
 * own preview ({@link SpriteOverlayView}) and its own Canvas export ({@link CompositeExportOverlay}), eight frames in
 * time order as playback and export run them.
 *
 * <p>Checks: the character is drawn (not an empty box); a take that turns the head and opens the mouth draws a
 * different picture from a still take (the character moves, not its standing picture); the export draws the same
 * picture as the preview. {@code -e fault rest} plants Studio's old standing-only drawing and the test must fail.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AvatarStudioDrawTest {
    private static final int W = 720, H = 720, FRAMES = 8;

    @After
    public void putBack() {
        CharacterPosers.install(SolverPoser::new);
    }

    @Test
    public void aCharacterMovesInStudioPreviewAndExport() throws Exception {
        if ("rest".equals(Emu.fault())) CharacterPosers.install(RestPoser::new);
        Context app = Emu.ins().getTargetContext();
        File out = Emu.dir("studio");
        File assets = new File(app.getCacheDir(), "avatar-emu-assets");
        assets.mkdirs();
        StringBuilder table = new StringBuilder("who      drawn%  moved%  export-vs-preview%  verdict\n");
        List<String> failed = new ArrayList<>();
        for (String asset : new String[] {CharacterAssets.TOPPS, CharacterAssets.PIP}) {
            StudioCharacter.Prepared p = StudioCharacter.prepareNow(app, CharacterLoader.assetRef(asset), assets);
            String who = p.name.toLowerCase(Locale.ROOT);
            SpriteSheet sheet = new SpriteSheet(p.sheetId(), p.name, Uri.fromFile(p.picture).toString());
            sheet.setGrid(1, 1);
            Bitmap still = preview(app, item(p, sheet, take(false)), sheet);
            SpriteOverlayItem moving = item(p, sheet, take(true));
            Bitmap turned = preview(app, moving, sheet);
            Bitmap exported = export(app, item(p, sheet, take(true)), sheet);
            Emu.save(still, new File(out, who + "-preview-still.png"));
            Emu.save(turned, new File(out, who + "-preview-turned.png"));
            Emu.save(exported, new File(out, who + "-export-turned.png"));
            double drawn = share(turned, null, 0), moved = share(turned, still, 32), apart = share(exported, turned, 32);
            boolean ok = drawn > 0.05 && moved > 0.01 && apart < 0.005;
            if (!ok) failed.add(who);
            table.append(String.format(Locale.ROOT, "%-8s %6.1f  %6.2f  %8.3f            %s\n", who, drawn * 100, moved * 100, apart * 100,
                ok ? "ok" : "FAULT"));
            still.recycle();
            turned.recycle();
            exported.recycle();
        }
        Log.i(Emu.TAG, "studio draw\n" + table);
        try (FileOutputStream f = new FileOutputStream(new File(out, "studio.txt"))) { f.write(table.toString().getBytes("UTF-8")); }
        assertTrue("a character in Studio is drawn, moves with its take, and exports as it previews: " + failed + "\n" + table,
            failed.isEmpty());
    }

    /** Two seconds at 30 a second: a turned head and an open mouth, or nothing (a still take). */
    static Take take(boolean moving) {
        Take t = new Take();
        t.n = 60;
        if (moving) {
            List<Object> turn = new ArrayList<>(), up = new ArrayList<>(), mouth = new ArrayList<>(), vis = new ArrayList<>();
            for (int i = 0; i < t.n; i++) { turn.add(0.6); up.add(-0.2); mouth.add(0.8); vis.add("Aa"); }
            t.ch.put("turnX", turn);
            t.ch.put("turnY", up);
            t.ch.put("mouth", mouth);
            t.ch.put("vis", vis);
        }
        return t;
    }

    /** The item as Studio's Add sheet and Joybot make it (CharacterActions.place). */
    static SpriteOverlayItem item(StudioCharacter.Prepared p, SpriteSheet sheet, Take take) {
        SpriteOverlayItem item = SpriteOverlayItem.create(sheet.getId());
        item.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        item.setTimeRange(0, 2000);
        item.setName(p.name);
        item.setSizeFraction(0.8f);
        item.setCenter(0.5f, 0.5f);
        item.setCharacterRef(p.ref);
        item.setCharacterUid(p.uid);
        item.setCharacterTake(StudioCharacter.json(take));
        return item;
    }

    /** Studio's preview layer, played frame by frame; the last frame's picture. */
    static Bitmap preview(Context app, SpriteOverlayItem item, SpriteSheet sheet) {
        Bitmap[] result = new Bitmap[1];
        Emu.ins().runOnMainSync(() -> {
            SpriteSheetRenderer r = SpriteSheetRenderer.load(app, sheet, 1024);
            SpriteOverlayView v = new SpriteOverlayView(app);
            v.setInteractive(false);
            v.setData(Collections.singletonList(item), new SpriteOverlayView.Callback() {
                @NonNull @Override public RectF getVideoContentRect() { return new RectF(0, 0, W, H); }
                @Nullable @Override public SpriteSheet lookupSheet(@NonNull String id) { return sheet; }
                @Nullable @Override public SpriteSheetRenderer lookupRenderer(@NonNull String id) { return r; }
                @Override public void onSpriteChanged() { }
            });
            v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, W, H);
            Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            for (int i = 0; i < FRAMES; i++) {
                b.eraseColor(Color.TRANSPARENT);
                v.setPlayheadMs(i * 1000L / 30);
                v.draw(c);
            }
            v.setData(Collections.emptyList(), new SpriteOverlayView.Callback() {
                @NonNull @Override public RectF getVideoContentRect() { return new RectF(0, 0, W, H); }
                @Nullable @Override public SpriteSheet lookupSheet(@NonNull String id) { return null; }
                @Nullable @Override public SpriteSheetRenderer lookupRenderer(@NonNull String id) { return null; }
                @Override public void onSpriteChanged() { }
            });   // releases the character's puppet, as removing the item does
            result[0] = b;
        });
        return result[0];
    }

    /** Studio's Canvas export pass over the same item, frame by frame in time order; the last frame's picture. */
    static Bitmap export(Context app, SpriteOverlayItem item, SpriteSheet sheet) throws Exception {
        Clip clip = new Clip(Uri.parse("file:///avatar-emu/none.mp4"), 2000);
        CompositeExportOverlay o = new CompositeExportOverlay(app, 0, clip, W, H, new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>(), Collections.singletonList(item), Collections.singletonList(sheet), new ArrayList<>(), 2000, 0, 0, false);
        try {
            o.configure(new Size(W, H));
            Bitmap last = null;
            for (int i = 0; i < FRAMES; i++) last = o.getBitmap(i * 1000_000L / 30);
            return last.copy(Bitmap.Config.ARGB_8888, false);
        } finally {
            o.release();
        }
    }

    /**
     * The share of the picture's pixels that are drawn (against = null) or that differ from {@code against} by more
     * than {@code bad} levels in any channel (premultiplied, as both are drawn).
     */
    static double share(Bitmap a, @Nullable Bitmap against, int bad) {
        int n = a.getWidth() * a.getHeight(), hit = 0, drawn = 0;
        int[] pa = new int[n], pb = against == null ? null : new int[n];
        a.getPixels(pa, 0, a.getWidth(), 0, 0, a.getWidth(), a.getHeight());
        if (pb != null) against.getPixels(pb, 0, a.getWidth(), 0, 0, a.getWidth(), a.getHeight());
        for (int i = 0; i < n; i++) {
            int x = pa[i];
            if ((x >>> 24) > 8) drawn++;
            if (pb == null) continue;
            int y = pb[i];
            int d = 0;
            for (int s = 0; s < 32; s += 8) d = Math.max(d, Math.abs(((x >>> s) & 255) - ((y >>> s) & 255)));
            if (d > bad) hit++;
        }
        return pb == null ? drawn / (double) n : hit / (double) Math.max(1, drawn);
    }
}
