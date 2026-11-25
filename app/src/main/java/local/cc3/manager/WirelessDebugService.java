package local.cc3.manager;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Foreground owner-controlled legacy ADB lease; does not claim to encrypt legacy ADB. */
public final class WirelessDebugService extends Service {
    static final String ENABLE="local.cc3.manager.DEBUG_LEASE_ENABLE", STOP="local.cc3.manager.DEBUG_LEASE_STOP";
    private static final String CHANNEL="wireless_debug_lease", PREFS="wireless_debug_lease";
    private static final int NOTICE=411;
    static final class State {
        final boolean active,busy; final String message; final long remaining;
        State(boolean active,boolean busy,String message,long remaining){this.active=active;this.busy=busy;this.message=message;this.remaining=remaining;}
    }
    private static volatile State state=new State(false,false,"",0);
    static State state(){return state;}
    static boolean hasLease(Context app){return app.getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("armed",false);}
    private final Handler main=new Handler(Looper.getMainLooper());
    private FytAdbController controller;
    private android.net.ConnectivityManager connectivity;
    private final android.net.ConnectivityManager.NetworkCallback networkChanges=new android.net.ConnectivityManager.NetworkCallback(){
        private void changed(){main.post(()->{if(!destroyed && (hasLease(WirelessDebugService.this)||closing))check();});}
        @Override public void onAvailable(android.net.Network network){changed();}
        @Override public void onLost(android.net.Network network){changed();}
        @Override public void onLinkPropertiesChanged(android.net.Network network,android.net.LinkProperties links){changed();}
    };
    private boolean closing, busy, destroyed;
    private long operationStarted;
    private long notifiedMinute=-1;
    private String stopReason="Wi-Fi debugging is off";
    private final Runnable tick=new Runnable(){@Override public void run(){check();main.postDelayed(this,1000);}};
    private SharedPreferences lease(){return getSharedPreferences(PREFS,MODE_PRIVATE);}
    private int boot(){return Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT,-1);}
    @Override public void onCreate(){
        super.onCreate();controller=new FytAdbController(this);
        connectivity=getSystemService(android.net.ConnectivityManager.class);
        connectivity.registerNetworkCallback(new android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),networkChanges);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Temporary debugging",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        startForeground(NOTICE,notification("Checking temporary debugging…"));
        main.removeCallbacks(tick);
        if(intent!=null && STOP.equals(intent.getAction())) stopAccess("Wi-Fi debugging stopped");
        else if(intent!=null && ENABLE.equals(intent.getAction())) {
            String trusted=WifiTrust.current(this);
            if(trusted.isEmpty() || boot()<0 || Settings.Global.getInt(getContentResolver(),Settings.Global.ADB_ENABLED,0)!=1) {
                state=new State(false,false,"Connect to Wi-Fi and enable USB debugging first.",0);finish();return START_NOT_STICKY;
            }
            long deadline=SystemClock.elapsedRealtime()+DebugLease.DURATION_MILLIS;
            lease().edit().putBoolean("armed",true).putLong("deadline",deadline).putInt("boot",boot()).putString("trusted",trusted).commit();
            closing=false;armAlarm(deadline);request(true);
        } else if(!hasLease(this)) {finish();return START_NOT_STICKY;}
        else {
            long deadline=lease().getLong("deadline",0);
            if(DebugLease.valid(SystemClock.elapsedRealtime(),deadline,boot(),lease().getInt("boot",-2),WifiTrust.current(this),lease().getString("trusted",""))){
                armAlarm(deadline);busy=true;operationStarted=SystemClock.elapsedRealtime();verify(true);
            }else stopAccess("Previous debugging session ended");
        }
        main.post(tick);return START_STICKY;
    }
    private void check(){
        if(busy && SystemClock.elapsedRealtime()-operationStarted>7000){
            controller.close();controller=new FytAdbController(this);busy=false;
            stopAccess("Debugging control timed out; retrying shutdown");
        }
        if(closing){if(!busy)request(false);return;}
        SharedPreferences p=lease();long now=SystemClock.elapsedRealtime(),deadline=p.getLong("deadline",0);
        if(!DebugLease.valid(now,deadline,boot(),p.getInt("boot",-2),WifiTrust.current(this),p.getString("trusted",""))) {
            stopAccess(now>=deadline?"Debugging timer ended":"Trusted Wi-Fi changed; debugging stopped");return;
        }
        if(state.active){
            long remaining=Math.max(0,deadline-now);state=new State(true,busy,"Wi-Fi debugging is on",remaining);
            long minute=(remaining+59999)/60000;
            if(minute!=notifiedMinute){notifiedMinute=minute;getSystemService(NotificationManager.class).notify(NOTICE,notification("Debugging on · "+minute+" minutes left"));}
        }
    }
    private void stopAccess(String reason){closing=true;stopReason=reason;state=new State(state.active,true,"Turning off Wi-Fi debugging…",0);if(!busy)request(false);}
    private void request(boolean enable){
        if(busy)return;busy=true;operationStarted=SystemClock.elapsedRealtime();
        state=new State(state.active,true,enable?"Enabling 15-minute debugging…":"Turning off Wi-Fi debugging…",0);
        FytAdbController.Listener listener=new FytAdbController.Listener(){
            @Override public void onRequestSent(){main.postDelayed(()->verify(enable),850);}
            @Override public void onError(String ignored){
                busy=false;state=new State(state.active,false,"Cannot verify debugging shutdown. Use Turn off or reboot the CC3.",0);
                Diagnostics.event("Wireless","ControlFailure",new IllegalStateException());
                if(enable)closing=true;
            }
        };
        if(enable)controller.enableTemporary(listener);else controller.disablePersistent(listener);
    }
    private void verify(boolean enable){
        new Thread(()->{
            boolean success;
            try {
                if(enable)success=LocalAdb.authenticationRequired();
                else {try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",FytAdbController.ADB_PORT),600);success=false;}}
            }catch(java.net.ConnectException refused){success=!enable;}
            catch(Exception unavailable){success=false;}
            final boolean verified=success;
            main.post(()->{
                if(destroyed)return;
                busy=false;
                if(enable){
                    if(verified && !closing){state=new State(true,false,"Wi-Fi debugging is on",0);check();}
                    else stopAccess("ADB authentication could not be verified; debugging stopped");
                }else if(verified){lease().edit().clear().commit();state=new State(false,false,stopReason,0);finish();}
                else {state=new State(true,false,"Cannot verify shutdown; retrying. Reboot if it persists.",0);}
            });
        },"cc3-debug-verification").start();
    }
    private PendingIntent expiry(){return PendingIntent.getForegroundService(this,NOTICE,new Intent(this,WirelessDebugService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private void armAlarm(long deadline){
        AlarmManager alarms=getSystemService(AlarmManager.class);
        try{alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,deadline,expiry());}
        catch(SecurityException unavailable){alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,deadline,expiry());}
    }
    private Notification notification(String message){
        PendingIntent open=PendingIntent.getActivity(this,NOTICE,Screen.managerIntent(this,"Access"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_manager).setContentTitle("Temporary Wi-Fi debugging")
                .setContentText(message).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null,"Turn off",expiry()).build()).build();
    }
    private void finish(){main.removeCallbacks(tick);getSystemService(AlarmManager.class).cancel(expiry());stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){destroyed=true;main.removeCallbacksAndMessages(null);controller.close();
        if(connectivity!=null)connectivity.unregisterNetworkCallback(networkChanges);super.onDestroy();}
}
