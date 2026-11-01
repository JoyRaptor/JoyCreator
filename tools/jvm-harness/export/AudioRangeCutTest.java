package com.fadcam.ui.faditor.export;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Where an audio range falls on the sound pass's clock, and the real cut through the desktop ffmpeg:
 * the same command line the phone runs, on a 20 s tone, checked by decoding the result.
 */
public final class AudioRangeCutTest {
    private static int passes, fails;

    private static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    private static String run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(out);
        return out;
    }

    /** Split the way the phone's ffmpeg session does: on spaces, quotes keep a path whole. */
    private static List<String> split(String arguments) {
        List<String> parts = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        for (char c : arguments.toCharArray()) {
            if (c == '"') quoted = !quoted;
            else if (c == ' ' && !quoted) { parts.add(word.toString()); word.setLength(0); }
            else word.append(c);
        }
        parts.add(word.toString());
        return parts;
    }

    private static double durationOf(File file) throws Exception {
        List<String> probe = new ArrayList<>(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.getPath()));
        return Double.parseDouble(run(probe).trim());
    }

    private static void mapping() {
        // Two parts; the second has a 1 s crossfade, so its composition runs 1 s behind the editor.
        long[] compBases = {0, 9_000}, compDurs = {9_000, 6_000};
        long[] editorBases = {0, 10_000}, editorDurs = {10_000, 7_000};
        check(AudioRangeCut.compOf(0, compBases, compDurs, editorBases, editorDurs) == 0, "the start is the start");
        check(AudioRangeCut.compOf(5_000, compBases, compDurs, editorBases, editorDurs) == 5_000, "inside part one");
        check(AudioRangeCut.compOf(9_500, compBases, compDurs, editorBases, editorDurs) == 9_000,
                "a part never runs past its own composed length");
        check(AudioRangeCut.compOf(12_000, compBases, compDurs, editorBases, editorDurs) == 11_000, "inside part two");
        check(AudioRangeCut.compOf(16_900, compBases, compDurs, editorBases, editorDurs) == 15_000, "the end of the last part");
        check(AudioRangeCut.compOf(19_000, compBases, compDurs, editorBases, editorDurs) == 17_000,
                "a song running on past the picture carries on one to one");
    }

    private static void command() {
        String c = AudioRangeCut.command("C:/a b/sound.m4a", 1_500, 3_250, "C:/out put.m4a.exporting");
        check(c.startsWith("-ss 1.500 -t 1.750 -i \"C:/a b/sound.m4a\""), "the cut comes before the input: " + c);
        check(c.contains("-c:a copy") && !c.contains("aac"), "the audio is copied, never re-encoded");
        check(c.contains("-f ipod") && c.endsWith("-y \"C:/out put.m4a.exporting\""),
                "M4A by format, since the staging name has no audio extension");
    }

    private static void realCut() throws Exception {
        File dir = Files.createTempDirectory("audio-range").toFile();
        File sound = new File(dir, "sound pass.m4a"), out = new File(dir, "cut.m4a.exporting");
        run(List.of("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=20",
                "-c:a", "aac", "-b:a", "128k", "-y", sound.getPath()));
        List<String> cut = new ArrayList<>(List.of("ffmpeg", "-v", "error"));
        cut.addAll(split(AudioRangeCut.command(sound.getPath(), 3_000, 8_000, out.getPath())));
        run(cut);
        check(out.length() > 0, "the cut wrote a file");
        check(sound.isFile() && durationOf(sound) > 19.9, "the sound pass stays, as the render cache");
        double seconds = durationOf(out);
        check(Math.abs(seconds - 5.0) < 0.05, "a 5 s range is 5 s long (got " + seconds + ")");
        out.delete();
        sound.delete();
        dir.delete();
    }

    public static void main(String[] args) throws Exception {
        mapping();
        command();
        realCut();
        System.out.println("AudioRangeCutTest: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
