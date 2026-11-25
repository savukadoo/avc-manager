package local.cc3.manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public final class FirewallService extends VpnService {
    static final String START = "local.cc3.manager.START";
    static final String STOP = "local.cc3.manager.STOP";
    static final String MODES = "modes";
    static final int BLOCKING = 1;
    static final int MONITORING = 2;
    private static final String CHANNEL = "ip_firewall";
    private static final int NOTIFICATION = 41;
    private static final int MTU = 1400;
    private static volatile String status = "";
    private static volatile Session active;
    private static volatile boolean stopping;
    private static TrafficLog traffic;
    private Session current;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean revoked;
    private int retryAttempt;
    private static volatile boolean systemAlwaysOn, systemLockdown;
    static boolean alwaysOn(){return systemAlwaysOn;}
    static boolean lockdown(){return systemLockdown;}
    static boolean persistent(Context context){return context.getSharedPreferences(Screen.STORAGE_NAME,MODE_PRIVATE).getBoolean("firewall_persistent",false);}
    static boolean wanted(Context context){return context.getSharedPreferences(Screen.STORAGE_NAME,MODE_PRIVATE).getBoolean("firewall_wanted",false);}
    private void remember(boolean enabled){getSharedPreferences(Screen.STORAGE_NAME,MODE_PRIVATE).edit().putBoolean("firewall_wanted",enabled).apply();}
    private boolean keepProtection(){return FirewallRestartPolicy.keep(isAlwaysOn(),persistent(this),wanted(this),revoked);}
    private int restartMode(){return keepProtection()?START_STICKY:START_NOT_STICKY;}


    static synchronized TrafficLog traffic(Context context) {
        if (traffic == null) traffic = new TrafficLog(context.getApplicationContext());
        return traffic;
    }

    static String status() {
        return status;
    }

    static int modes() {
        Session session = active;
        return session == null ? 0 : session.modes;
    }

    static boolean stopping() {
        return stopping;
    }

    @Override public int onStartCommand(Intent intent,int flags,int id) {
        systemAlwaysOn=isAlwaysOn();systemLockdown=isLockdownEnabled();revoked=false;
        boolean explicitStart=intent!=null && START.equals(intent.getAction());
        boolean explicitStop=intent!=null && STOP.equals(intent.getAction());
        int requested=FirewallRestartPolicy.requested(explicitStart,explicitStop,systemAlwaysOn,persistent(this),wanted(this),intent==null?0:intent.getIntExtra(MODES,0));
        if(explicitStart || explicitStop)remember((requested&BLOCKING)!=0);
        if(requested==0){main.removeCallbacksAndMessages(null);closeTunnel("",true);stopSelf();return START_NOT_STICKY;}
        if(stopping)return restartMode();
        if(current!=null && current.forwarding){
            if((requested&MONITORING)!=0 && (current.modes&MONITORING)==0)current.log.reset();
            current.modes=requested;showNotification(requested);return restartMode();
        }
        establishTunnel(requested);return restartMode();
    }

    private void establishTunnel(int requested) {
        showNotification(requested);
        Session previous=current;
        try {
            System.loadLibrary("cc3firewall");
            Builder builder=new Builder().setSession("AVC Firewall").setBlocking(false)
                    .setMtu(MTU).addAddress("198.18.0.1",32).addAddress("fd74:cc3::1",128)
                    .addRoute("2000::",3).addRoute("64:ff9b::",96);
            TrafficLog.addIpv4Routes(builder::addRoute);
            ParcelFileDescriptor tunnel=builder.establish();
            if(tunnel==null)throw new IOException("VPN approval required");
            Session session=new Session(tunnel,traffic(this),requested);
            int descriptor;
            try(ParcelFileDescriptor duplicate=ParcelFileDescriptor.dup(tunnel.getFileDescriptor())) {
                descriptor=duplicate.detachFd();
            }catch(IOException error){tunnel.close();throw error;}
            if((requested&MONITORING)!=0)session.log.reset();
            current=session;active=session;status="";retryAttempt=0;
            if(previous!=null){previous.closed=true;try{previous.tunnel.close();}catch(IOException ignored){}}
            new Thread(()->run(session,descriptor),"cc3-vpn").start();
        }catch(IOException|RuntimeException|LinkageError error){
            Diagnostics.event("Firewall","StartFailure",error);
            status="Protection interrupted · retrying. Android lockdown blocks traffic during restarts.";
            if(keepProtection())retry();else{closeTunnel("Could not start the local VPN.",false);stopSelf();}
        }
    }

    private void retry(){
        if(!keepProtection())return;
        showNotification(BLOCKING);
        long delay=Math.min(30000L,1000L<<Math.min(retryAttempt++,5));
        main.postDelayed(()->{if(keepProtection() && (current==null || !current.forwarding))establishTunnel(BLOCKING);},delay);
    }

    private void run(Session session, int descriptor) {
        try {
            int result = session.runTunnel(descriptor, MTU);
            if (result != 0 && session.running()) {
                Log.w("CC3Firewall", "Forwarding stopped with errno " + result);
            }
        } catch (RuntimeException | LinkageError error) {
            Log.w("CC3Firewall", "Forwarding stopped", error);
        } finally {
            getMainExecutor().execute(() -> {
                if (session.closed && stopping) {
                    stopping = false;
                    status = "";
                }
                session.forwarding=false;
                if(current==session){
                    active=null;
                    if(keepProtection()){
                        // Keep the existing TUN until replacement so its routes do not disappear during a worker retry.
                        status="Protection interrupted · reconnecting";retry();
                    }else{closeTunnel("Protection lost · local VPN disconnected.",false);stopSelf();}
                }
            });
        }
    }

    private void showNotification(int modes) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Firewall",
                NotificationManager.IMPORTANCE_LOW));
        int flag = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FirewallService.class).setAction(STOP), flag);
        PendingIntent show = PendingIntent.getActivity(this, 2,
                Screen.managerIntent(this, "Traffic"), flag);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_manager).setContentTitle("AVC Network")
                .setContentText(!status.isEmpty()?status:modes == (BLOCKING | MONITORING) ? "Blocking rules and logging connections"
                        : modes == BLOCKING ? "Enforcing block rules" : "Logging connections")
                .setContentIntent(show).setOngoing(true);
        if(!isAlwaysOn())notification.addAction(new Notification.Action.Builder(null,"Stop",stop).build());
        startForeground(NOTIFICATION,notification.build());
    }

    private void closeTunnel(String message, boolean waitForWorker) {
        Session old = current;
        current = null;
        if (waitForWorker && stopping) return;
        if (old != null) {
            stopping = waitForWorker;
            old.closed = true;
            active = null;
            try {
                old.tunnel.close();
            } catch (IOException ignored) {
                // The system may already have revoked the VPN.
            }
        }
        status = message;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onRevoke() {
        getMainExecutor().execute(() -> {
            revoked=true;remember(false);main.removeCallbacksAndMessages(null);
            closeTunnel("Android ended the local VPN.", false);
            stopSelf();
        });
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (current != null) closeTunnel("", true);
        super.onDestroy();
    }

    private final class Session {
        final ParcelFileDescriptor tunnel;
        final TrafficLog log;
        final ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        volatile boolean closed;
        volatile boolean forwarding=true;
        volatile int modes;

        Session(ParcelFileDescriptor tunnel, TrafficLog log, int modes) {
            this.tunnel = tunnel;
            this.log = log;
            this.modes = modes;
        }

        native int runTunnel(int descriptor, int mtu);

        boolean running() {
            return !closed;
        }

        boolean protectSocket(int socket) {
            return !closed && protect(socket);
        }

        int revision() {
            return (log.revision() << 1) | (modes & BLOCKING);
        }

        boolean blocked(TrafficLog.Flow flow) {
            return closed || ((modes & BLOCKING) != 0 && log.blocked(flow));
        }

        void record(TrafficLog.Flow flow, int size, boolean outgoing, boolean denied) {
            if (!closed && (modes & MONITORING) != 0) log.record(flow, size, outgoing, denied);
        }

        TrafficLog.Flow openFlow(byte[] source, int sourcePort, byte[] destination,
                int destinationPort, int protocol) {
            try {
                InetAddress remote = InetAddress.getByAddress(destination);
                int uid = -1;
                if (protocol == 6 || protocol == 17) {
                    try {
                        uid = connectivity.getConnectionOwnerUid(protocol,
                                new InetSocketAddress(InetAddress.getByAddress(source), sourcePort),
                                new InetSocketAddress(remote, destinationPort));
                    } catch (SecurityException | IllegalStateException ignored) {
                        // Android may revoke ownership lookup while stopping the service.
                    }
                }
                return new TrafficLog.Flow(remote.getHostAddress(), destinationPort, protocol, uid, log.owner(uid));
            } catch (UnknownHostException invalid) {
                return null;
            }
        }
    }
}
