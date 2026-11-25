package local.cc3.obd;

import java.util.List;

public final class ObdHistoryTest {
    private static int checks;
    public static void main(String[] args) {
        long now = 1000000;
        ObdHistory history = new ObdHistory();
        List<ObdHistory.Minute> points = history.minutes(now);
        check(points.size() == 10, "ten one-minute windows");
        check(points.stream().allMatch(p -> p.samples == 0 && Double.isNaN(p.average)),
                "empty history is unknown, not zero");
        history.record(now - 600000, 0);
        history.record(now - 550000, 20);
        history.record(now - 540000, 30);
        history.record(now, 100);
        points = history.minutes(now);
        check(points.get(0).samples == 2 && points.get(0).average == 10,
                "first minute mean includes real zero");
        check(points.get(1).samples == 1 && points.get(1).average == 30,
                "exact minute boundary goes to next bin");
        check(points.get(9).samples == 1 && points.get(9).average == 100, "current endpoint included");
        check(points.get(2).samples == 0 && Double.isNaN(points.get(2).average), "missing minutes are gaps");
        for (int i = 0; i < 10; i++)
            check(points.get(i).endMillis - points.get(i).startMillis == 60000, "one-minute bin width " + i);
        boolean immutable = false;
        try {
            points.clear();
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        check(immutable, "history view is immutable");
        history.record(now + 1, Double.NaN);
        history.record(now + 2, Double.POSITIVE_INFINITY);
        check(history.minutes(now).stream().mapToInt(p -> p.samples).sum() == 4,
                "invalid readings are not averaged");
        check(history.minutes(now + 600001).stream().allMatch(p -> p.samples == 0),
                "expired samples removed");
        history = new ObdHistory();
        history.record(now, 20);
        history.record(now - 10000, -10);
        points = history.minutes(now);
        check(points.stream().mapToInt(p -> p.samples).sum() == 1 && points.get(9).average == -10,
                "backward clock resets incompatible history");
        check(history.minutes(now - 20000).stream().allMatch(p -> p.samples == 0),
                "future samples cannot appear as past history");
        history = new ObdHistory();
        for (int i = 0; i < 5000; i++) history.record(now + i, i);
        points = history.minutes(now + 5000);
        check(points.stream().mapToInt(p -> p.samples).sum() == 4096, "sample buffer is bounded");
        check(Math.abs(points.get(9).average - 2951.5) < .000001, "bounded buffer keeps the newest samples");
        System.out.println("OBD history tests passed: " + checks + " assertions; synthetic samples only");
    }
    private static void check(boolean result, String label) {
        if (!result)
            throw new AssertionError(label);
        checks++;
    }
}
