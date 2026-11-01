package com.fadcam.ui.faditor.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** One snapshot for a whole region batch: it lasts exactly as long as some job still needs it. */
public final class SharedExportSnapshotTest {
    private static int passes, fails;

    private static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    private static File snapshot(File dir, String name) throws IOException {
        File file = new File(dir, name);
        Files.write(file.toPath(), "{\"name\":\"project\"}".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static long snapshotFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("shared-snapshot").toFile();

        // Many regions, one file, and every job can still read it until the last has gone.
        File batch = snapshot(dir, "export_snapshot_a.json");
        List<File> tickets = SharedExportSnapshot.issue(batch, 50);
        check(tickets.size() == 50 && snapshotFiles(dir) == 1, "50 regions are one snapshot");
        byte[] before = Files.readAllBytes(batch.toPath());
        for (int i = 0; i < 49; i++) {
            SharedExportSnapshot.release(batch, tickets.get(i));
            check(batch.isFile(), "the snapshot stays while a job still needs it");
        }
        check(java.util.Arrays.equals(before, Files.readAllBytes(batch.toPath())), "jobs never change it");
        SharedExportSnapshot.release(batch, tickets.get(49));
        check(!batch.exists(), "the last job takes the snapshot with it");

        // Out of order, and released twice by the editor and the service racing each other.
        File twice = snapshot(dir, "export_snapshot_b.json");
        List<File> pair = SharedExportSnapshot.issue(twice, 2);
        SharedExportSnapshot.release(twice, pair.get(1));
        SharedExportSnapshot.release(twice, pair.get(1));
        check(twice.isFile(), "giving up one ticket twice leaves the other job its snapshot");
        SharedExportSnapshot.release(twice, pair.get(0));
        check(!twice.exists(), "and the other job still ends it");

        // Two batches side by side: names that start alike must not count as each other's jobs.
        File one = snapshot(dir, "export_snapshot_c.json");
        File other = snapshot(dir, "export_snapshot_cc.json");
        List<File> oneTickets = SharedExportSnapshot.issue(one, 1);
        List<File> otherTickets = SharedExportSnapshot.issue(other, 1);
        SharedExportSnapshot.release(one, oneTickets.get(0));
        check(!one.exists() && other.isFile(), "batches do not hold each other up or take each other down");

        // A job that never started (partial dispatch, process death): the sweep's half.
        other.delete();
        SharedExportSnapshot.sweepOrphans(dir);
        check(!otherTickets.get(0).exists(), "a ticket whose snapshot is gone is swept");
        File alive = snapshot(dir, "export_snapshot_d.json");
        List<File> aliveTickets = SharedExportSnapshot.issue(alive, 1);
        SharedExportSnapshot.sweepOrphans(dir);
        check(aliveTickets.get(0).isFile(), "a ticket of a live batch is left alone");
        SharedExportSnapshot.release(alive, aliveTickets.get(0));

        // Tickets that cannot all be made: none are left, and nothing was dispatched.
        File clash = snapshot(dir, "export_snapshot_e.json");
        Files.write(new File(clash.getPath() + ".job-1").toPath(), new byte[0]);
        boolean threw = false;
        try {
            SharedExportSnapshot.issue(clash, 3);
        } catch (IOException expected) {
            threw = true;
        }
        check(threw, "a ticket that cannot be made stops the batch before anything starts");
        check(!new File(clash.getPath() + ".job-0").exists(), "no partial set of tickets is left");

        System.out.println("SharedExportSnapshotTest: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
