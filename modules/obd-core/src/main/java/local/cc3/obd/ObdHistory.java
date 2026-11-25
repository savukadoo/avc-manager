package local.cc3.obd;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Ten minutes of successful samples in memory. Empty minutes are explicitly unknown. */
public final class ObdHistory {
    public static final long WINDOW_MS = 600000, MINUTE_MS = 60000;
    private static final int MAX_SAMPLES = 4096;
    public static final class Minute {
        public final long startMillis, endMillis;
        public final int samples;
        public final double average;
        private Minute(long start, long end, int count, double total) {
            startMillis = start;
            endMillis = end;
            samples = count;
            average = count == 0 ? Double.NaN : total / count;
        }
    }
    private static final class Sample {
        final long time;
        final double value;
        Sample(long time, double value) {
            this.time = time;
            this.value = value;
        }
    }
    private final Deque<Sample> samples = new ArrayDeque<>();
    public void record(long timeMillis, double value) {
        if (timeMillis < 0 || !Double.isFinite(value))
            return;
        if (!samples.isEmpty() && timeMillis < samples.peekLast().time)
            samples.clear();
        samples.addLast(new Sample(timeMillis, value));
        trim(timeMillis);
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();
    }
    public List<Minute> minutes(long nowMillis) {
        trim(nowMillis);
        long start = nowMillis - WINDOW_MS;
        double[] totals = new double[10];
        int[] counts = new int[10];
        for (Sample sample : samples) {
            if (sample.time < start || sample.time > nowMillis)
                continue;
            int index = Math.min(9, (int) ((sample.time - start) / MINUTE_MS));
            totals[index] += sample.value;
            counts[index]++;
        }
        List<Minute> result = new ArrayList<>();
        for (int index = 0; index < 10; index++)
            result.add(new Minute(start + index * MINUTE_MS, start + (index + 1) * MINUTE_MS, counts[index],
                    totals[index]));
        return Collections.unmodifiableList(result);
    }
    private void trim(long nowMillis) {
        while (!samples.isEmpty() && samples.peekFirst().time < nowMillis - WINDOW_MS) samples.removeFirst();
    }
}
