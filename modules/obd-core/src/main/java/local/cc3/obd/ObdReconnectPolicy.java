package local.cc3.obd;

/** Foreground-only reconnect budget. Manual disconnect/scan always wins for this visit. */
public final class ObdReconnectPolicy {
    private boolean active, suppressed;
    private int attempts;
    private long nextAttempt;

    public void enter() {
        if (active)
            return;
        active = true;
        reset();
    }
    public void leave() {
        active = false;
    }
    public void reset() {
        suppressed = false;
        attempts = 0;
        nextAttempt = 0;
    }
    public void suppress() {
        suppressed = true;
    }
    public boolean suppressed() {
        return suppressed;
    }
    public boolean exhausted() {
        return attempts >= 3;
    }
    public boolean begin(long now, boolean enabled, boolean selected, boolean ready, boolean busy) {
        if (!active || suppressed || !enabled || !selected || !ready || busy || attempts >= 3
                || now < nextAttempt)
            return false;
        attempts++;
        nextAttempt = now + (attempts == 1 ? 5000 : 15000);
        return true;
    }
}
