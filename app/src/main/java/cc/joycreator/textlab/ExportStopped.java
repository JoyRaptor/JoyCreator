package cc.joycreator.textlab;

/** Thrown inside a TextLab export when the person stopped it (LookExport, LookVideo, StopStream). */
final class ExportStopped extends RuntimeException {
    ExportStopped() {
        super("stopped");
    }
}
