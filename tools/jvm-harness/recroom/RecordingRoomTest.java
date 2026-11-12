import com.fadcam.media.FragmentFileWriter;
import com.fadcam.utils.RecordingRoom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Work-loss audit F5 (2026-11-12). */
public class RecordingRoomTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    /** A "disk" of fixed size: a write that does not fit puts in what fits, then fails (a half-written piece). */
    static final class Disk implements FragmentFileWriter.Target {
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        final long room;
        boolean truncateFails;

        Disk(long room) { this.room = room; }

        @Override public void write(byte[] data) throws IOException {
            long fit = Math.min(data.length, room - file.size());
            if (fit > 0) file.write(data, 0, (int) fit);
            if (fit < data.length) throw new IOException("ENOSPC: No space left on device");
        }

        @Override public void flush() {}

        @Override public void truncate(long size) throws IOException {
            if (truncateFails) throw new IOException("cannot truncate");
            byte[] all = file.toByteArray();
            file.reset();
            file.write(all, 0, (int) Math.min(size, all.length));
        }
    }

    public static void main(String[] args) {
        // ---- the room arithmetic
        long mb = 1024 * 1024;
        long perSec = RecordingRoom.bytesPerSecond(20_000_000L, 192_000L);   // 20 Mbit/s video
        check(perSec == (20_000_000L + 192_000L) / 8, "bytes per second");
        long need = RecordingRoom.neededToStart(perSec);
        check(need == 64 * mb + 60 * perSec, "a minute plus the margin is needed to start");
        check(!RecordingRoom.enoughToStart(100 * mb, perSec), "100 MB free is not enough for 20 Mbit/s: refused");
        check(RecordingRoom.enoughToStart(need, perSec), "exactly enough is enough");
        check(RecordingRoom.enoughToStart(-1, perSec), "unknown free space never refuses");
        check(!RecordingRoom.enoughToStart(10 * mb, 0), "even a tiny bitrate needs the margin");
        check(RecordingRoom.mustStop(RecordingRoom.STOP_MARGIN_BYTES - 1), "below the margin: stop");
        check(!RecordingRoom.mustStop(RecordingRoom.STOP_MARGIN_BYTES), "at the margin: carry on");
        check(!RecordingRoom.mustStop(-1), "unknown free space never stops a recording");
        check(RecordingRoom.mustStop(0), "a full disk stops it");

        // ---- a recording that hits a full disk
        byte[] piece = new byte[1000];
        for (int i = 0; i < piece.length; i++) piece[i] = (byte) i;
        Disk disk = new Disk(3500);   // three whole pieces fit, the fourth is cut off half way
        AtomicInteger told = new AtomicInteger();
        FragmentFileWriter w = new FragmentFileWriter(disk, told::incrementAndGet);
        check(w.write(piece) && w.write(piece) && w.write(piece), "three pieces go in");
        check(!w.write(piece), "the fourth does not fit and reports it");
        check(w.hasFailed(), "the writer knows it failed");
        check(disk.file.size() == 3000, "the file was cut back to the last whole piece: " + disk.file.size());
        check(told.get() == 1, "the listener was told once");
        check(!w.write(piece) && !w.write(piece), "nothing more is written afterwards");
        check(disk.file.size() == 3000 && told.get() == 1, "and the file and the news do not change");
        check(w.goodBytes() == 3000, "good bytes counted");

        // ---- a failure that also cannot cut the file back still ends the writing
        Disk stuck = new Disk(1500);
        stuck.truncateFails = true;
        FragmentFileWriter w2 = new FragmentFileWriter(stuck, null);
        w2.write(piece);
        check(!w2.write(piece) && w2.hasFailed(), "a failed cut-back still stops the writing");
        check(!w2.write(piece), "and no further piece follows a half piece");

        // ---- a listener that throws does not break the writer
        FragmentFileWriter w3 = new FragmentFileWriter(new Disk(10), () -> { throw new IllegalStateException("x"); });
        check(!w3.write(piece) && w3.hasFailed(), "a listener that fails is ignored");

        System.out.println("RecordingRoomTest: " + passes + " passed, " + fails + " failed");
        System.exit(fails > 0 ? 1 : 0);
    }
}
