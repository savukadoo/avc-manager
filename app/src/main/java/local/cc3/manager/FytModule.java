package local.cc3.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

final class FytModule implements ServiceConnection {
    private static final String REMOTE = "com.syu.ipc.IRemoteModule";
    private static final String CALLBACK = "com.syu.ipc.IModuleCallback";
    private final Context context;
    private final String action;
    private final int[] updates;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder remote;
    private boolean bound;

    interface Listener {
        void update(int field, int[] values);
        void disconnected();
    }

    FytModule(Context context, String action, int[] updates, Listener listener) {
        this.context = context;
        this.action = action;
        this.updates = updates;
        this.listener = listener;
    }

    private final Binder callback = new Binder() {
        {
            attachInterface(null, CALLBACK);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CALLBACK);
            int field = data.readInt();
            int[] values = data.createIntArray();
            if (values != null && values.length > 0) {
                main.post(() -> {
                    if (remote != null) listener.update(field, values);
                });
            }
            return true;
        }
    };

    void start() {
        if (bound) return;
        try {
            Intent intent = new Intent(action).setClassName("com.syu.ms", "app.ModuleService");
            bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (SecurityException error) {
            Log.w("CC3FytModule", "FYT service rejected binding", error);
        }
        if (!bound) listener.disconnected();
    }

    void stop() {
        if (remote != null) {
            for (int update : updates) subscribe(update, false);
            remote = null;
        }
        if (bound) {
            bound = false;
            context.unbindService(this);
        }
        main.removeCallbacksAndMessages(null);
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!bound) return;
        try {
            if (REMOTE.equals(binder.getInterfaceDescriptor())) {
                remote = binder;
                for (int update : updates) {
                    if (!subscribe(update, true)) {
                        stop();
                        listener.disconnected();
                        break;
                    }
                }
                return;
            }
        } catch (RemoteException error) {
            Log.w("CC3FytModule", "FYT service disconnected", error);
        }
        stop();
        listener.disconnected();
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        remote = null;
        listener.disconnected();
    }

    @Override public void onNullBinding(ComponentName name) {
        stop();
        listener.disconnected();
    }

    @Override public void onBindingDied(ComponentName name) {
        stop();
        listener.disconnected();
    }

    private boolean subscribe(int field, boolean register) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(REMOTE);
            data.writeStrongBinder(callback);
            data.writeInt(field);
            if (register) data.writeInt(1);
            return remote.transact(register ? 3 : 4, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException | SecurityException error) {
            return false;
        } finally {
            data.recycle();
        }
    }

    boolean send(int command, int... values) {
        if (remote == null) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(REMOTE);
            data.writeInt(command);
            data.writeIntArray(values);
            data.writeFloatArray(null);
            data.writeStringArray(null);
            return remote.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException | SecurityException error) {
            return false;
        } finally {
            data.recycle();
        }
    }
}
