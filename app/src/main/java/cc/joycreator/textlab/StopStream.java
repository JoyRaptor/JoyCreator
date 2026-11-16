package cc.joycreator.textlab;

import androidx.annotation.NonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * A stream that ends with {@link ExportStopped} as soon as the person presses Stop (LAB-04): an export's last
 * step, the copy into the folder, used to run on to the end and announce a file after Stop. Pure Java: the JVM
 * harness tests it.
 */
final class StopStream extends FilterInputStream {

    private final BooleanSupplier stopped;

    StopStream(@NonNull InputStream in, @NonNull BooleanSupplier stopped) {
        super(in);
        this.stopped = stopped;
    }

    private void check() {
        if (stopped.getAsBoolean()) throw new ExportStopped();
    }

    @Override
    public int read() throws IOException {
        check();
        return super.read();
    }

    @Override
    public int read(@NonNull byte[] b, int off, int len) throws IOException {
        check();
        return super.read(b, off, len);
    }
}
