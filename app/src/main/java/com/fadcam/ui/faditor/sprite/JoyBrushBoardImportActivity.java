package com.fadcam.ui.faditor.sprite;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Internal receiver: make the existing SpriteLab/Studio model durable before opening either editor.
 *
 * Sent to Studio, the board can start a NEW project or go INTO one that already exists (as one more sprite layer over what
 * is there, see {@link BoardAttachPlan}); with no project yet there is nothing to ask.
 */
public final class JoyBrushBoardImportActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String bundle=getIntent().getStringExtra("joybrush_board_bundle");
        final boolean studio=getIntent().getBooleanExtra("joybrush_board_studio",false);
        Toast.makeText(this,"Opening board…",Toast.LENGTH_SHORT).show();
        new Thread(() -> choose(bundle,studio),"joybrush-board-import").start();
    }
    /** Studio with projects on the phone asks where the board goes; everything else goes straight in. */
    private void choose(String path,boolean studio) {
        List<ProjectStorage.ProjectSummary> projects=null;
        if(studio) {
            try { projects=new ProjectStorage(this).listProjects(); } catch(Exception ignored) { projects=null; }
        }
        if(projects == null || projects.isEmpty()) { receive(path,studio,null); return; }
        final List<ProjectStorage.ProjectSummary> list=projects;
        runOnUiThread(() -> {
            if(isFinishing() || isDestroyed()) { discardStage(path); return; }
            final String[] labels=new String[list.size()+1];
            labels[0]="New project";
            for(int i=0;i<list.size();i++) labels[i+1]=list.get(i).name.isEmpty() ? "Untitled" : list.get(i).name;
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Send to Studio")
                .setItems(labels,(d,which) -> {
                    final String target=which == 0 ? null : list.get(which-1).id;
                    new Thread(() -> receive(path,true,target),"joybrush-board-import").start();
                })
                .setOnCancelListener(d -> { new Thread(() -> discardStage(path),"joybrush-board-discard").start(); finish(); })
                .create();
            dialog.show();
        });
    }
    /** The staged bundle belongs to this receiver; a board given up (back out of the chooser) leaves nothing behind. */
    private void discardStage(String path) {
        try {
            if(path == null) return;
            File stage=new File(path).getCanonicalFile();
            if(stage.getParentFile().equals(getCacheDir().getCanonicalFile()) && stage.getName().startsWith("jb-board-export-")) deleteOwned(stage);
        } catch(Exception ignored) { }
    }
    private void receive(String path,boolean studio,String targetId) {
        File stage=null;
        File ownProject=null;
        File unsavedImage=null;
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
            ProjectStorage storage=new ProjectStorage(this);
            // Into an existing project: open it first (a project that cannot be opened stops here, nothing written), and
            // give the sheet an id the project does not already use, so a board sent twice never replaces its earlier self.
            final FaditorProject existing=targetId == null ? null : storage.load(targetId);
            if(targetId != null && existing == null) throw new IllegalStateException("That project could not be opened");
            if(existing != null) {
                Set<String> taken=new HashSet<>();
                for(SpriteSheet s: existing.getSpriteSheets()) taken.add(s.getId());
                String wanted=metadata.has("id") ? metadata.get("id").getAsString() : null;
                metadata.addProperty("id",BoardAttachPlan.sheetIdFor(wanted,taken,() -> UUID.randomUUID().toString()));
            }
            SpriteSheet sheet=SpriteSheet.fromJson(metadata);
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds=true; android.graphics.BitmapFactory.decodeFile(png.getPath(),bounds);
            if(bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth % sheet.getCols() != 0 || bounds.outHeight % sheet.getRows() != 0)
                throw new IllegalArgumentException("The sheet image does not match its grid");
            FaditorProject project=existing != null ? existing : new FaditorProject(sheet.getName());
            // Only a project made here is deleted if the import fails; an existing one is never ours to remove.
            File projectDir=storage.projectDir(project.getId());
            if(existing == null) ownProject=projectDir;
            File assets=new File(projectDir,"assets");
            if(!assets.isDirectory() && !assets.mkdirs()) throw new IllegalStateException("Could not create board assets");
            File image=new File(assets,"sheet-"+UUID.randomUUID()+".png");
            try(FileInputStream in=new FileInputStream(png);FileOutputStream out=new FileOutputStream(image)) {
                byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer)) != -1) out.write(buffer,0,n);
                out.getFD().sync();
            }
            unsavedImage=image;
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
                final long animMs=Math.max(1,Math.round(ticks*1000.0/sheet.getFps()));
                if(existing != null) {
                    // One more layer over what is already there: the whole project long, looping, half the canvas.
                    BoardAttachPlan.Placement at=BoardAttachPlan.onExisting(project.getTimeline().getTotalDurationMs(),animMs);
                    item.setTimeRange(at.startMs,at.endMs);
                    item.setEndBehavior(at.endBehavior);
                    item.setSizeFraction(at.sizeFraction);
                } else {
                    item.setTimeRange(0,animMs);
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
                }
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
            unsavedImage=null;
            runOnUiThread(() -> { if(!isFinishing() && !isDestroyed()) startActivity(intent); finish(); });
        } catch(Exception e) {
            final String message=e.getMessage();
            runOnUiThread(() -> { Toast.makeText(this,"Could not open board: "+message,Toast.LENGTH_LONG).show(); finish(); });
        } finally {
            if(ownsStage) deleteOwned(stage);
            if(ownProject != null) deleteOwned(ownProject);
            // The picture copied into an EXISTING project stays only if the project was saved with it.
            if(unsavedImage != null && ownProject == null) unsavedImage.delete();
        }
    }
    private static void deleteOwned(File file) {
        File[] children=file.listFiles(); if(children != null) for(File child:children) deleteOwned(child);
        file.delete();
    }
}
