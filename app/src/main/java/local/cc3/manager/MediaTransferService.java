package local.cc3.manager;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Manually started, short-lived uploads on a Wi-Fi/hotspot interface. Never binds LTE. */
public final class MediaTransferService extends Service {
    public static final String START="local.cc3.manager.MEDIA_TRANSFER_START", STOP="local.cc3.manager.MEDIA_TRANSFER_STOP";
    private static final String CHANNEL="cc3_media_transfer";
    private static final int NOTICE=410;
    public static final class State {
        public final boolean active, busy;
        public final String url, code, message, file;
        public final long received,total,available;
        public final int completed;
        State(boolean a,boolean b,String u,String c,String m,String f,long r,long t,long space,int count) {
            active=a;busy=b;url=u;code=c;message=m;file=f;received=r;total=t;available=space;completed=count;
        }
    }
    private static volatile State state=new State(false,false,"","","Uploads are off","",0,0,0,0);
    public static State state(){return state;}
    private static volatile String certificateFingerprint="";
    static String fingerprint(){return certificateFingerprint;}
    public static final class Endpoint {
        final Inet4Address address; final int prefix;
        Endpoint(Inet4Address address,int prefix){this.address=address;this.prefix=prefix;}
        public String label(){return address.getHostAddress();}
    }
    // Android10 compatibility: this scoped platform path intentionally retains legacy APIs.
    @SuppressWarnings("deprecation")
    public static List<Endpoint> endpoints(Context context) {
        LinkedHashMap<String,Endpoint> choices=new LinkedHashMap<>();
        try {
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(CONNECTIVITY_SERVICE);
            for(Network network:manager.getAllNetworks()) {
                NetworkCapabilities caps=manager.getNetworkCapabilities(network);
                LinkProperties links=manager.getLinkProperties(network);
                if(caps==null || links==null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
                for(LinkAddress link:links.getLinkAddresses())add(choices,link.getAddress(),link.getPrefixLength());
            }
            // Android's hotspot is not always listed as an active Network.
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null && interfaces.hasMoreElements()) {
                NetworkInterface iface=interfaces.nextElement();String name=iface.getName().toLowerCase(Locale.ROOT);
                if(!iface.isUp() || iface.isLoopback() || !(name.startsWith("wlan") || name.startsWith("ap")
                        || name.startsWith("swlan") || name.startsWith("softap")))continue;
                for(InterfaceAddress address:iface.getInterfaceAddresses())add(choices,address.getAddress(),address.getNetworkPrefixLength());
            }
        } catch(Exception unavailable) { /* The screen offers retry without changing network settings. */ }
        return new ArrayList<>(choices.values());
    }
    private static void add(Map<String,Endpoint> choices,InetAddress address,int prefix) {
        if(address instanceof Inet4Address && address.isSiteLocalAddress() && prefix>=8 && prefix<=32)
            choices.put(address.getHostAddress(),new Endpoint((Inet4Address)address,prefix));
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private MediaUploadServer server;
    private PowerManager.WakeLock wake;
    private boolean starting;
    private volatile int generation;
    private long lastProgress;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Laptop media uploads",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || STOP.equals(intent.getAction())){stopTransfer("Uploads stopped");return START_NOT_STICKY;}
        if(!START.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(starting || server!=null && server.active())return START_NOT_STICKY;
        starting=true;state=new State(false,false,"","","Starting local uploads…","",0,0,0,0);
        startForeground(NOTICE,notice("Starting local uploads…"));
        final int token=++generation;
        final MediaUploadServer[] owner=new MediaUploadServer[1];
        final String requested=intent.getStringExtra("address");
        new Thread(()->{
            MediaUploadServer candidate=null;
            try {
                Endpoint chosen=null;
                for(Endpoint endpoint:endpoints(this))if(requested==null || requested.equals(endpoint.label())){chosen=endpoint;break;}
                if(chosen==null)throw new IOException("Connect the CC3 and laptop to the same Wi-Fi, or use the CC3 hotspot.");
                byte[] page;try(InputStream in=getAssets().open("media-upload.html");ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);page=out.toByteArray();}
                File root=new LocalMediaLibrary(this).root();MediaFileStore.cleanOldUploads(root,System.currentTimeMillis());
                UploadTlsIdentity identity=UploadTlsIdentity.load(this,chosen.address);
                certificateFingerprint=identity.fingerprint;
                candidate=new MediaUploadServer(root,chosen.address,chosen.prefix,page,LocalMediaLibrary::isMediaFileName,new MediaUploadServer.Listener(){
                    @Override public void progress(String name,long received,long total){
                        synchronized(MediaTransferService.this){if(token!=generation || server!=owner[0])return;
                        State old=state;state=new State(true,true,old.url,old.code,"Receiving file",name,received,total,old.available,old.completed);}
                        long now=SystemClock.elapsedRealtime();if(now-lastProgress>1000){lastProgress=now;main.post(()->{
                            if(token!=generation || server!=owner[0])return;
                            if(wake!=null && !wake.isHeld())wake.acquire(2*60*60*1000L);
                            getSystemService(NotificationManager.class).notify(NOTICE,notice("Receiving "+name));});}
                    }
                    @Override public void saved(File file){synchronized(MediaTransferService.this){if(token!=generation || server!=owner[0])return;State old=state;MediaUploadServer running=server;
                        state=new State(true,false,old.url,old.code,"Saved · "+file.getName(),"",0,0,running==null?old.available:running.usableBytes(),old.completed+1);
                        sendBroadcast(new Intent("local.cc3.manager.MEDIA_LIBRARY_CHANGED").setPackage(getPackageName()));}
                        main.post(()->{if(token!=generation || server!=owner[0])return;releaseWake();getSystemService(NotificationManager.class).notify(NOTICE,notice("Ready for more files"));});}
                    @Override public void interrupted(String message){synchronized(MediaTransferService.this){if(token!=generation || server!=owner[0])return;State old=state;state=new State(old.active,false,old.url,old.code,message,"",0,0,old.available,old.completed);}main.post(()->{if(token==generation && server==owner[0])releaseWake();});}
                    @Override public void stopped(){main.post(()->{if(token==generation && server==owner[0])stopTransfer("Upload session ended");});}
                },identity.context.getServerSocketFactory());
                owner[0]=candidate;
                final MediaUploadServer ready=candidate;
                main.post(()->{
                    if(token!=generation || !starting){ready.close();return;}
                    try {
                        server=ready;ready.start(8765);
                        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);wake=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"cc3:media-upload");
                        state=new State(true,false,ready.url(),ready.pairingCode(),"Ready for laptop uploads","",0,0,ready.usableBytes(),0);
                        getSystemService(NotificationManager.class).notify(NOTICE,notice("Ready for laptop uploads"));
                    }catch(IOException error){stopTransfer("Cannot start uploads: "+error.getMessage());}
                    finally{starting=false;}
                });
            }catch(Exception error){if(candidate!=null)candidate.close();final String message=error.getMessage();main.post(()->{if(token==generation)stopTransfer("Cannot start uploads: "+message);});}
        },"cc3-start-media-transfer").start();
        return START_NOT_STICKY;
    }
    private Notification notice(String line){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MediaTransferActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MediaTransferService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_manager).setContentTitle("AVC media uploads")
                .setContentText(line).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop uploads",stop).build()).build();
    }
    private void releaseWake(){if(wake!=null && wake.isHeld())wake.release();}
    private synchronized void stopTransfer(String message){generation++;starting=false;MediaUploadServer old=server;server=null;
        if(old!=null)old.close();releaseWake();State before=state;
        if(before.active || before.message.startsWith("Starting") || message.startsWith("Cannot"))
            state=new State(false,false,"","",message,"",0,0,0,before.completed);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){stopTransfer("Uploads stopped");main.removeCallbacksAndMessages(null);super.onDestroy();}
}
