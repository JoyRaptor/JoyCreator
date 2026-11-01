package com.fadcam.ui.faditor.export;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One project snapshot shared by a batch of region exports, however many regions there are.
 *
 * <p>The snapshot file is written once and never changed, which is what keeps every job immune to
 * later edits. Each job holds a small ticket file beside it, and the snapshot goes when the last
 * ticket does. Tickets are files, not a count, because the editor and the {@code :export} process
 * both give them up, and because a job that never started simply leaves its ticket for the sweep.</p>
 */
public final class SharedExportSnapshot {
    private static final String TICKET = ".job-";

    private SharedExportSnapshot() {}

    /** One new ticket per job, in order. Tickets already made are removed if one cannot be. */
    public static List<File> issue(File snapshot, int jobs) throws IOException {
        List<File> tickets = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            File ticket = new File(snapshot.getPath() + TICKET + i);
            if (!ticket.createNewFile()) {
                for (File made : tickets) made.delete();
                throw new IOException("Could not start a shared snapshot");
            }
            tickets.add(ticket);
        }
        return tickets;
    }

    /** A job is done with the snapshot. Whoever gives up the last ticket removes the snapshot. */
    public static void release(File snapshot, File ticket) {
        ticket.delete();
        final String prefix = snapshot.getName() + TICKET;
        File[] left = snapshot.getParentFile().listFiles((dir, name) -> name.startsWith(prefix));
        if (left != null && left.length == 0) snapshot.delete();
    }

    /** Remove a day-old snapshot's leftovers: any ticket whose snapshot is gone. */
    public static void sweepOrphans(File dir) {
        File[] tickets = dir.listFiles((d, name) -> name.contains(TICKET));
        if (tickets == null) return;
        for (File ticket : tickets) {
            String name = ticket.getName();
            if (!new File(dir, name.substring(0, name.lastIndexOf(TICKET))).exists()) ticket.delete();
        }
    }
}
