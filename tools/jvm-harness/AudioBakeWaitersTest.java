import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.fadcam.ui.faditor.audio.BakedAudioCache;

import java.io.File;
import java.util.concurrent.*;

public class AudioBakeWaitersTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        BakedAudioCache cache = new BakedAudioCache(new Context());
        BakedAudioCache.Request req = request(dir, "original.wav"), other = request(dir, "second.wav");
        CompletableFuture<BakedAudioCache.Result> drawer = new CompletableFuture<>(), ai = new CompletableFuture<>(),
                independent = new CompletableFuture<>();
        cache.bakeAsync(dir,req,null,(v,e)->drawer.complete(v));
        Thread sync = new Thread(()-> ai.complete(cache.bakeSync(dir,req,null,new String[1])));
        sync.start();
        Thread interrupted = new Thread(()-> {
            if (cache.bakeSync(dir,req,null,new String[1]) != null) throw new AssertionError("interrupted wait returned output");
        });
        interrupted.start();
        interrupted.interrupt();
        interrupted.join(2000);
        try {
            if (interrupted.isAlive()) throw new AssertionError("wait did not detach");
            cache.bakeAsync(dir,other,null,(v,e)->independent.complete(v));
        } finally { FFmpegKit.gate.countDown(); }
        BakedAudioCache.Result d=drawer.get(10,TimeUnit.SECONDS), a=ai.get(10,TimeUnit.SECONDS),
                o=independent.get(10,TimeUnit.SECONDS);
        if(d==null || a==null || o==null || !a.bakedFile.equals(d.bakedFile) || o.bakedFile.equals(d.bakedFile)
                || !d.originalSourceUri.toString().equals(req.sourceUri.toString()) || FFmpegKit.starts.get()!=2)
            throw new AssertionError("duplicate or lost render: starts="+FFmpegKit.starts);
        System.out.println("PASS: two keys, two encodes, shared return, interrupted waiter, original retained");
    }
    private static BakedAudioCache.Request request(File dir,String name) {
        return new BakedAudioCache.Request(new Uri(new File(dir,name).getPath()),0,1000,BakedAudioCache.CHAIN_FIX);
    }
}
