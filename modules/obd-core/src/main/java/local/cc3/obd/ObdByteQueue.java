package local.cc3.obd;

import java.io.IOException;

/** Bounded receive buffer shared by RFCOMM and GATT. No Android dependency. */
public final class ObdByteQueue {
    private final byte[] bytes;
    private int head, size;
    private boolean ended;
    private IOException failure;

    public ObdByteQueue(int capacity) {
        if (capacity <= 0)
            throw new IllegalArgumentException("capacity");
        bytes = new byte[capacity];
    }

    public synchronized boolean offer(byte[] source, int offset, int length) {
        bounds(source, offset, length);
        if (ended || failure != null)
            return false;
        if (length > bytes.length - size) {
            fail(new IOException("OBD receive buffer overflow; reconnect the adapter."));
            return false;
        }
        for (int i = 0; i < length; i++) bytes[(head + size + i) % bytes.length] = source[offset + i];
        size += length;
        notifyAll();
        return true;
    }

    public synchronized int read(byte[] target, int offset, int length, long timeoutMs) throws IOException {
        bounds(target, offset, length);
        if (timeoutMs < 0)
            throw new IllegalArgumentException("timeoutMs");
        if (length == 0)
            return 0;
        long budget = Math.min(timeoutMs, Long.MAX_VALUE / 1000000L) * 1000000L;
        long started = System.nanoTime();
        while (size == 0 && !ended && failure == null) {
            long left = budget - (System.nanoTime() - started);
            if (left <= 0)
                return 0;
            try {
                wait(left / 1000000L, (int) (left % 1000000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("OBD read interrupted.", e);
            }
        }
        if (failure != null)
            throw failure;
        if (size == 0)
            return -1;
        int count = Math.min(length, size);
        for (int i = 0; i < count; i++) target[offset + i] = bytes[(head + i) % bytes.length];
        head = (head + count) % bytes.length;
        size -= count;
        return count;
    }

    public synchronized void finish() {
        ended = true;
        notifyAll();
    }

    public synchronized void fail(IOException error) {
        if (failure == null)
            failure = error;
        ended = true;
        size = 0;
        notifyAll();
    }

    private static void bounds(byte[] array, int offset, int length) {
        if (array == null)
            throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > array.length - length)
            throw new IndexOutOfBoundsException();
    }
}
