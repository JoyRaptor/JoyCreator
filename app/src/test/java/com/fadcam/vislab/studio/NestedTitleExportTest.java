package com.fadcam.vislab.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.fadcam.vislab.gl.VisRenderer;
import com.fadcam.vislab.audio.Features;
import org.junit.Test;
import cc.joycreator.textlab.StudioTextLab;
import cc.joycreator.textlab.TitleLook;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import android.graphics.Paint;
import android.util.Log;
import java.util.HashMap;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

public class NestedTitleExportTest {
    @Test public void coldAndPreviouslyDrawnParentsRejectMissingChildFrame() throws Exception {
        Context app = mock(Context.class);
        when(app.getApplicationContext()).thenReturn(app);
        Handler worker = mock(Handler.class);
        when(worker.post(any())).thenAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return true; });
        VisRenderer renderer = mock(VisRenderer.class);
        StudioTextLab text = mock(StudioTextLab.class, call -> {
            if (!call.getMethod().getName().startsWith("render")) return null;
            // The child GL worker never completes this one frame; the parent renderer stays responsive.
            LookTicket<Object> delayed = new LookTicket<>(ignored -> {});
            assertEquals(LookTicket.Outcome.TIMED_OUT, delayed.await(5, null));
            if (call.getMethod().getName().equals("renderBlocking")) return null;
            Constructor<StudioTextLab.LookFailed> cause = StudioTextLab.LookFailed.class
                    .getDeclaredConstructor(String.class);
            cause.setAccessible(true);
            throw cause.newInstance("child frame timed out");
        });
        String ref = VisShow.TEXT + "headline";
        TextOverlayItem title = mock(TextOverlayItem.class);
        when(title.getId()).thenReturn("headline");
        when(title.hasTextLook()).thenReturn(true);
        when(title.getTextLook()).thenReturn(mock(TitleLook.class));
        when(title.getText()).thenReturn("Caption");
        when(title.lookTime(1200)).thenReturn(300L);
        Constructor<VisShow> makeShow = VisShow.class.getDeclaredConstructor(String[].class, Map.class, Map.class, Map.class, long.class);
        makeShow.setAccessible(true);
        VisShow show = makeShow.newInstance(new String[]{"0", ref}, Map.of(ref,title), Map.of(), Map.of(), 1200L);
        try (MockedStatic<StudioTextLab> titles = mockStatic(StudioTextLab.class);
             MockedStatic<Log> log = mockStatic(Log.class);
             MockedStatic<Looper> looper = mockStatic(Looper.class);
             MockedStatic<SystemClock> wall = mockStatic(SystemClock.class);
             MockedStatic<Bitmap> pixels = mockStatic(Bitmap.class);
             MockedConstruction<Paint> paints = mockConstruction(Paint.class);
             MockedConstruction<Handler> ui = mockConstruction(Handler.class);
             MockedConstruction<OffscreenGl> gl = mockConstruction(OffscreenGl.class, (m,c) -> {
                 when(m.ready()).thenReturn(true);
                 when(m.handler()).thenReturn(worker);
             })) {
            titles.when(() -> StudioTextLab.get(app)).thenReturn(text);
            StudioPictures pictures = new StudioPictures(app);
            Constructor<StudioVis> constructor = StudioVis.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            StudioVis bridge = constructor.newInstance(app);
            Class<?> type = Class.forName(StudioVis.class.getName() + "$Item");
            Constructor<?> newItem = type.getDeclaredConstructor(VisRenderer.class);
            newItem.setAccessible(true);
            Object item = newItem.newInstance(renderer);
            field(type,"lookJson").set(item, "{}");
            field(type,"pictures").set(item, pictures);
            ((Map)field(StudioVis.class, "items").get(bridge)).put("export:item", item);
            // Simulate the texture consumer: with no new pixels it keeps a stale texture or uploads transparent.
            doAnswer(invocation -> { pictures.frame(ref, 1.2); return null; }).when(renderer)
                    .render(any(), anyDouble(), anyDouble(), anyInt(), anyInt(), anyInt());
            for (String priorLook : new String[]{null, "{}"}) {
                field(type,"drewLook").set(item, priorLook);
                StudioVis.LookFailed failure = assertThrows(StudioVis.LookFailed.class, () ->
                        bridge.renderBlocking("export:item", "{}", null, 1200, 1200, 16, 16,
                                new VisControls(1,1,0,1,1,null,show), null, null));
                assertTrue(failure.getMessage(), failure.getMessage().contains("headline"));
                assertTrue(failure.getMessage(), failure.getMessage().contains("300"));
                assertFalse(bridge.cannotDraw("export:item", "{}"));
            }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
