package com.fadcam.ui.faditor.audio;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.*;

/** Inspect and run the exact production trim/encode command against a known middle or late excerpt. */
public class AudioSegmentCommandTest {
    public static void main(String[] args) throws Exception {
        long start = Long.parseLong(args[2]), end = Long.parseLong(args[3]);
        BakedAudioCache.Request req = new BakedAudioCache.Request(null,start,end,BakedAudioCache.CHAIN_DENOISE);
        String cmd = BakedAudioCache.bakeCommand(args[0],req,"afftdn=nf=-25",new File(args[1]));
        if (cmd.contains("adelay=")) throw new AssertionError("encoding a silent prefix of " + start + " ms");
        List<String> run = new ArrayList<>(); run.add(args[4]); run.add("-v"); run.add("error");
        Matcher tokens = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(cmd);
        while(tokens.find()) run.add(tokens.group(1) == null ? tokens.group(2) : tokens.group(1));
        if (new ProcessBuilder(run).inheritIO().start().waitFor()!=0) throw new AssertionError("encode failed");
    }
}
