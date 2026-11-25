package local.cc3.obd;

import java.io.Closeable;
import java.io.IOException;

/** A single-use, local adapter byte channel. Never sends diagnostic commands itself. */
public interface ObdLink extends Closeable {
    /** Open on a worker thread. Connection attempts have a finite deadline. */
    void open() throws IOException;

    /** Send all bytes on a worker thread. The caller owns protocol framing. */
    void write(byte[] data) throws IOException;

    /** Read on a worker thread: zero means timeout; minus one means end of stream. */
    int read(byte[] target, int offset, int length, long timeoutMs) throws IOException;

    /** A human-readable transport/profile label, without a Bluetooth address. */
    String description();

    /** Safe from any thread; cancels connection, write and read waits. */
    @Override void close();
}
