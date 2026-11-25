package local.cc3.manager;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;

final class WifiTrust {
    // API29 compatibility: enumerate physical Wi-Fi even when a VPN is the active Network.
    @SuppressWarnings("deprecation")
    static String current(Context context) {
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        if(manager==null)return "";
        ArrayList<String> identities=new ArrayList<>();
        for(Network network:manager.getAllNetworks()) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            LinkProperties properties=manager.getLinkProperties(network);
            if(caps==null || properties==null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))continue;
            for(LinkAddress address:properties.getLinkAddresses()) if(address.getAddress() instanceof Inet4Address)
                identities.add(network.getNetworkHandle()+"/"+properties.getInterfaceName()+"/"+address);
        }
        Collections.sort(identities);
        return String.join(";",identities);
    }
    private WifiTrust() { }
}
