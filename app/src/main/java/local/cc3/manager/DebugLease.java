package local.cc3.manager;

/** Monotonic lease; reboot, deadline, clock reset or network replacement ends access. */
final class DebugLease {
    static final long DURATION_MILLIS=15*60*1000;
    static boolean valid(long now,long deadline,int currentBoot,int leaseBoot,String current,String trusted) {
        return currentBoot==leaseBoot && now>=0 && deadline>now
                && deadline-now<=DURATION_MILLIS && current!=null && !current.isEmpty()
                && current.equals(trusted);
    }
    private DebugLease() { }
}
