package com.fadcam.ui.faditor.sprite;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.CanvasPickerBottomSheet;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Internal receiver: make the existing SpriteLab/Studio model durable before opening either editor. */
public final class JoyBrushBoardImportActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String bundle=getIntent().getStringExtra("joybrush_board_bundle");
        final boolean studio=getIntent().getBooleanExtra("joybrush_board_studio",false);
        Toast.makeText(this,"Opening board…",Toast.LENGTH_SHORT).show();
        new Thread(() -> receive(bundle,studio),"joybrush-board-import").start();
    }
    private void receive(String path,boolean studio) {
        File stage=null;
        File ownProject=null;
        boolean ownsStage=false;
        try {
            if(path == null) throw new IllegalArgumentException("No board files were supplied");
            stage=new File(path).getCanonicalFile();
            if(!stage.getParentFile().equals(getCacheDir().getCanonicalFile()) || !stage.getName().startsWith("jb-board-export-"))
                throw new IllegalArgumentException("Invalid board bundle");
            ownsStage=true;
            File[] files=stage.listFiles();
            if(files == null || files.length != 2) throw new IllegalArgumentException("Incomplete board bundle");
            File png=null,sidecar=null;
            for(File f: files) {
                if(f.getName().endsWith(".sprite.json")) sidecar=f;
                else if(f.getName().endsWith(".png")) png=f;
            }
            if(png == null || sidecar == null || sidecar.length() > 4*1024*1024) throw new IllegalArgumentException("Incomplete board bundle");
            byte[] json;
            try(FileInputStream in=new FileInputStream(sidecar);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer)) != -1) out.write(buffer,0,n); json=out.toByteArray();
            }
            JsonObject metadata=JsonParser.parseString(new String(json,StandardCharsets.UTF_8)).getAsJsonObject();
            SpriteSheet sheet=SpriteSheet.fromJson(metadata);
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds=true; android.graphics.BitmapFactory.decodeFile(png.getPath(),bounds);
            if(bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth % sheet.getCols() != 0 || bounds.outHeight % sheet.getRows() != 0)
                throw new IllegalArgumentException("The sheet image does not match its grid");
            FaditorProject project=new FaditorProject(sheet.getName());
            ProjectStorage storage=new ProjectStorage(this);
            ownProject=storage.projectDir(project.getId());
            File assets=new File(ownProject,"assets");
            if(!assets.isDirectory() && !assets.mkdirs()) throw new IllegalStateException("Could not create board assets");
            File image=new File(assets,"sheet-"+UUID.randomUUID()+".png");
            try(FileInputStream in=new FileInputStream(png);FileOutputStream out=new FileOutputStream(image)) {
                byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer)) != -1) out.write(buffer,0,n);
                out.getFD().sync();
            }
            sheet.setSheetUri(Uri.fromFile(image).toString());
            project.getSpriteSheets().add(sheet);
            if(studio) {
                SpriteOverlayItem item=SpriteOverlayItem.create(sheet.getId());
                item.setLayerId(Timeline.spriteLayerIdFor(item)); item.setCenter(.5f,.5f); item.setSizeFraction(1f);
                long ticks=1;
                if(!sheet.getPresets().isEmpty()) {
                    SpriteSheet.Preset preset=sheet.getPresets().get(0);
                    item.getFrameTrack().put(FrameTrack.Key.ofPreset(0,preset.id));
                    ticks=preset.weights.isEmpty() ? preset.frames.size() : preset.weights.stream().mapToLong(Integer::longValue).sum();
                } else item.getFrameTrack().put(FrameTrack.Key.ofCell(0,0));
                item.setTimeRange(0,Math.max(1,Math.round(ticks*1000.0/sheet.getFps())));
                // Studio's existing image timeline supplies playback and video-export timing.
                // A durable blank canvas makes this a complete animation project on arrival.
                File canvasFile=new File(assets,"Canvas.png");
                android.graphics.Bitmap canvas=android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888);
                try(FileOutputStream out=new FileOutputStream(canvasFile)) {
                    canvas.eraseColor(android.graphics.Color.BLACK);
                    if(!canvas.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)) throw new IllegalStateException("Could not create the Studio canvas");
                    out.getFD().sync();
                } finally { canvas.recycle(); }
                Clip canvasClip=new Clip(Uri.fromFile(canvasFile),item.getEndMs());
                canvasClip.setImageClip(true); canvasClip.setAudioMuted(true); canvasClip.setDisplayName("Canvas");
                project.getTimeline().addClip(canvasClip);
                project.setCanvasPreset(CanvasPickerBottomSheet.customPresetKey(bounds.outWidth/sheet.getCols(),bounds.outHeight/sheet.getRows()));
                project.getTimeline().addSpriteOverlay(item);
            }
            if(!storage.save(project)) throw new IllegalStateException("The board project could not be saved");
            // Loading the saved model proves IDs and asset registration before launching the editor.
            FaditorProject saved=storage.load(project.getId());
            if(saved == null || saved.spriteSheetById(sheet.getId()) == null) throw new IllegalStateException("The saved board could not be reopened");
            final Intent intent=studio ? new Intent(this,FaditorEditorActivity.class)
                .putExtra(FaditorEditorActivity.EXTRA_PROJECT_ID,project.getId()) : new Intent(this,SpriteSheetEditorActivity.class)
                .putExtra(SpriteSheetEditorActivity.EXTRA_PROJECT_ID,project.getId()).putExtra(SpriteSheetEditorActivity.EXTRA_SHEET_ID,sheet.getId());
            ownProject=null;
            runOnUiThread(() -> { if(!isFinishing() && !isDestroyed()) startActivity(intent); finish(); });
        } catch(Exception e) {
            final String message=e.getMessage();
            runOnUiThread(() -> { Toast.makeText(this,"Could not open board: "+message,Toast.LENGTH_LONG).show(); finish(); });
        } finally {
            if(ownsStage) deleteOwned(stage);
            if(ownProject != null) deleteOwned(ownProject);
        }
    }
    private static void deleteOwned(File file) {
        File[] children=file.listFiles(); if(children != null) for(File child:children) deleteOwned(child);
        file.delete();
    }
}
