package com.fadcam.vislab.studio;

import android.content.Context;
import android.graphics.*;
import com.fadcam.ui.faditor.sprite.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import java.lang.reflect.Constructor;
import java.util.Map;

public class HeldSpriteTest {
    @Test public void heldCellsKeepTheirPixelsUntilArtOrOrientationChanges() throws Exception {
        Context app = mock(Context.class);
        when(app.getApplicationContext()).thenReturn(app);
        SpriteSheet sheet = new SpriteSheet("s","sheet","one.png");
        SpriteSheet replacement = new SpriteSheet("s","changed","two.png");
        SpriteOverlayItem item = mock(SpriteOverlayItem.class);
        when(item.getSheetId()).thenReturn("s");
        when(item.getEndMs()).thenReturn(400L);
        SpriteSheetRenderer first = mock(SpriteSheetRenderer.class), next = mock(SpriteSheetRenderer.class);
        when(first.cellAspect()).thenReturn(1f);
        when(next.cellAspect()).thenReturn(1f);
        String ref = VisShow.SPRITE + "sprite";
        try (MockedStatic<StudioArt> art = mockStatic(StudioArt.class);
             MockedStatic<SpriteSheetRenderer> sheets = mockStatic(SpriteSheetRenderer.class);
             MockedStatic<SpriteFrameResolver> cells = mockStatic(SpriteFrameResolver.class);
             MockedStatic<Bitmap> images = mockStatic(Bitmap.class);
             var paints = mockConstruction(Paint.class);
             var canvases = mockConstruction(Canvas.class);
             var rects = mockConstruction(RectF.class)) {
            images.when(() -> Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)).thenAnswer(inv -> {
                Bitmap b = mock(Bitmap.class);
                when(b.getWidth()).thenReturn(512);
                when(b.getHeight()).thenReturn(512);
                return b;
            });
            art.when(() -> StudioArt.sheet("s")).thenReturn(sheet);
            sheets.when(() -> SpriteSheetRenderer.load(app,sheet,1024)).thenReturn(first);
            sheets.when(() -> SpriteSheetRenderer.load(app,replacement,1024)).thenReturn(next);
            cells.when(() -> SpriteFrameResolver.resolveCellAt(any(),same(item),anyLong()))
                    .thenAnswer(inv -> (long)inv.getArgument(2) < 200 ? 0 : 1);
            StudioPictures one = new StudioPictures(app);
            draw(one, ref, item, -200);
            long initial = one.frameGeneration(ref);
            draw(one, ref, item, 0);
            draw(one, ref, item, 80);
            verify(first,times(1)).drawCell(any(),eq(0),any(),any());
            assertTrue(initial >= 0);
            assertEquals(initial,one.frameGeneration(ref));
            draw(one, ref, item, 401);
            draw(one, ref, item, 600);
            verify(first,times(1)).drawCell(any(),eq(1),any(),any());
            long last = one.frameGeneration(ref);
            assertNotEquals(initial,last);
            when(item.isFlipH()).thenReturn(true);
            draw(one,ref,item,600);
            assertNotEquals(last,one.frameGeneration(ref));
            StudioPictures two = new StudioPictures(app);
            draw(two,ref,item,600);
            verify(first,times(3)).drawCell(any(),eq(1),any(),any());
            art.when(() -> StudioArt.sheet("s")).thenReturn(replacement);
            draw(one,ref,item,600);
            verify(next).drawCell(any(),eq(1),any(),any());
            expectOneUpload(one, ref);
        }
    }

    private static void expectOneUpload(StudioPictures pictures, String ref) throws Exception {
        var gl = new com.fadcam.vislab.gl.VisRenderer(null, null);
        var layer = mock(com.fadcam.vislab.model.CompiledScene.Layer.class);
        var knobs = layer.getClass().getField("knobs");
        knobs.setAccessible(true);
        knobs.set(layer, mock(com.fadcam.vislab.model.KnobSet.class));
        var time = gl.getClass().getDeclaredField("time");
        time.setAccessible(true);
        var upload = gl.getClass().getDeclaredMethod("movingTexture", com.fadcam.vislab.model.CompiledScene.Layer.class,
                String.class, com.fadcam.vislab.gl.VisRenderer.ImageSource.class, int[].class);
        upload.setAccessible(true);
        try (var gpu = mockStatic(android.opengl.GLES20.class); var calls = mockStatic(android.opengl.GLUtils.class)) {
            int[] texture = (int[])upload.invoke(gl, layer, ref, pictures, null);
            time.setDouble(gl, 1);
            upload.invoke(gl, layer, ref, pictures, texture);
            calls.verify(() -> android.opengl.GLUtils.texImage2D(anyInt(),eq(0),any(Bitmap.class),eq(0)),times(1));
            calls.verifyNoMoreInteractions();
        }
    }

    private static void draw(StudioPictures pictures, String ref, SpriteOverlayItem item, long time) throws Exception {
        Constructor<VisShow> make = VisShow.class.getDeclaredConstructor(String[].class, Map.class, Map.class, Map.class, long.class);
        make.setAccessible(true);
        VisShow show = make.newInstance(new String[]{"0",ref}, Map.of(), Map.of(ref,item), Map.of(), time);
        java.lang.reflect.Field binding = StudioPictures.class.getDeclaredField("show");
        binding.setAccessible(true);
        binding.set(pictures, show);
        assertNotNull(pictures.frame(ref,time / 1000.0));
    }
}
