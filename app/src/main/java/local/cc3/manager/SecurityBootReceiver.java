package local.cc3.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** Restores only explicit owner protection choices; never enables debugging on boot. */
public final class SecurityBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean boot=Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        if(!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))return;
        restoreProtection(context);
    }
    static void restoreProtection(Context context){
        if(FirewallService.persistent(context) && FirewallService.wanted(context)
                && FirewallService.modes()==0 && VpnService.prepare(context)==null)
            context.startForegroundService(new Intent(context,FirewallService.class).setAction(FirewallService.START)
                    .putExtra(FirewallService.MODES,FirewallService.BLOCKING));
    }
}
