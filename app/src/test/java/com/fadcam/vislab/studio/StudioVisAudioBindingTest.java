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
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

public class StudioVisAudioBindingTest {
    @Test public void unlinkRebindAndSeekUpdateBothFeaturesAndHitCounter() throws Exception {
        Context app = mock(Context.class);
        when(app.getApplicationContext()).thenReturn(app);
        Handler worker = mock(Handler.class);
        when(worker.post(any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        });
        VisRenderer renderer = mock(VisRenderer.class);
        try (MockedStatic<Looper> looper = mockStatic(Looper.class);
             MockedStatic<SystemClock> wall = mockStatic(SystemClock.class);
             MockedStatic<Bitmap> pixels = mockStatic(Bitmap.class);
             MockedConstruction<Handler> ui = mockConstruction(Handler.class);
             MockedConstruction<OffscreenGl> gl = mockConstruction(OffscreenGl.class, (m,c) -> {
                 when(m.ready()).thenReturn(true);
                 when(m.handler()).thenReturn(worker);
             });
             MockedConstruction<SoundClock> clocks = mockConstruction(SoundClock.class, (m,c) ->
                     when(m.at(anyDouble())).thenReturn(new Features()))) {
            Constructor<StudioVis> constructor = StudioVis.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            StudioVis bridge = constructor.newInstance(app);
            Class<?> type = Class.forName(StudioVis.class.getName() + "$Item");
            Constructor<?> newItem = type.getDeclaredConstructor(VisRenderer.class);
            newItem.setAccessible(true);
            Object item = newItem.newInstance(renderer);
            field(type, "lookJson").set(item, "{}");
            ((Map)field(StudioVis.class, "items").get(bridge)).put("export:item", item);
            Uri a = mock(Uri.class), b = mock(Uri.class);
            frame(bridge, a, 1000);
            SoundClock first = clocks.constructed().get(0);
            verify(renderer).setHitCounter(first);
            verify(first).at(1);
            frame(bridge, null, 800);
            verify(renderer).setHitCounter(null);
            verifyNoMoreInteractions(first);
            frame(bridge, b, 700);
            SoundClock second = clocks.constructed().get(1);
            verify(renderer).setHitCounter(second);
            frame(bridge, b, 200);
            verify(second).at(0.2);
            frame(bridge, a, 300);
            assertEquals(3, clocks.constructed().size());
            verify(renderer).setHitCounter(clocks.constructed().get(2));
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void frame(StudioVis bridge, Uri uri, long ms) {
        bridge.renderBlocking("export:item", "{}", uri, ms, ms, 16, 16,
                new VisControls(1, 1, 0, 1, 1), null, null);
    }
}
