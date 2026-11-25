package local.cc3.manager;

/** Lifecycle decisions separated from Android/native tunnel operations. */
final class FirewallRestartPolicy {
    static boolean keep(boolean alwaysOn,boolean persistent,boolean wanted,boolean revoked){
        return !revoked && (alwaysOn || persistent && wanted);
    }
    static int requested(boolean start,boolean stop,boolean alwaysOn,boolean persistent,boolean wanted,int modes){
        int requested=start?modes&3:(alwaysOn || persistent&&wanted)?1:0;
        if(stop && !alwaysOn)requested=0;
        return alwaysOn?requested|1:requested;
    }
    private FirewallRestartPolicy(){ }
}
