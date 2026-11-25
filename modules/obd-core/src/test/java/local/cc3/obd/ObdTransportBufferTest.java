package local.cc3.obd;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Core receive-buffer bounds, cancellation and interruption regression. */
public final class ObdTransportBufferTest {
    private static int checks;
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        ObdByteQueue queue = new ObdByteQueue(8);
        byte[] target = new byte[8];
        require(queue.read(target, 0, 8, 0) == 0, "nonblocking timeout");
        long started = System.nanoTime();
        require(queue.read(target, 0, 8, 25) == 0, "bounded timeout");
        require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "read did not hang");
        require(queue.offer(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6), "first packet");
        require(queue.read(target, 0, 4, 0) == 4, "partial packet read");
        require(queue.offer(new byte[]{7, 8, 9, 10}, 0, 4), "wrapped packet");
        require(queue.read(target, 1, 6, 0) == 6, "wrapped partial read");
        require(Arrays.equals(Arrays.copyOfRange(target, 1, 7), new byte[]{5, 6, 7, 8, 9, 10}), "packet byte order");

        ObdByteQueue cancel = new ObdByteQueue(8);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            waiting.countDown();
            try { result.set(cancel.read(new byte[1], 0, 1, 20000)); }
            catch (Exception e) { result.set(e); }
        });
        reader.start(); waiting.await(); cancel.finish(); reader.join(1000);
        require(!reader.isAlive() && Integer.valueOf(-1).equals(result.get()), "close cancels waiting read");
        require(!cancel.offer(new byte[]{1}, 0, 1), "closed queue rejects callbacks");

        ObdByteQueue failed = new ObdByteQueue(4);
        require(!failed.offer(new byte[5], 0, 5), "bounded receive memory");
        try { failed.read(target, 0, 1, 0); throw new AssertionError("overflow must fail"); }
        catch (IOException e) { require(e.getMessage().contains("overflow"), "overflow preserved"); }
        ObdByteQueue interrupted = new ObdByteQueue(1);
        AtomicReference<Boolean> interruptedResult = new AtomicReference<>(false);
        Thread interruptReader = new Thread(() -> {
            try { interrupted.read(new byte[1], 0, 1, 20000); }
            catch (IOException expected) { interruptedResult.set(Thread.currentThread().isInterrupted()); }
        });
        interruptReader.start(); interruptReader.interrupt(); interruptReader.join(1000);
        require(!interruptReader.isAlive() && interruptedResult.get(), "interruption cancels read and preserves flag");
        queue.finish();
        require(queue.read(target, 0, 1, 0) == -1, "EOF after drain");
        require(queue.read(target, 0, 0, 0) == 0, "empty read");
        try { queue.read(target, 7, 2, 0); throw new AssertionError("bounds"); }
        catch (IndexOutOfBoundsException expected) { checks++; }
        System.out.println("OBD transport receive buffer: " + checks + " checks passed.");
    }
}
