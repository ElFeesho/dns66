package org.jak_linux.dns66.vpn;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;

import java.net.InetAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

class ConnectivityManagerDnsServerListProvider implements AdVpnThread.DnsServerListProvider {

    private final ConnectivityManager connectivityManager;

    ConnectivityManagerDnsServerListProvider(ConnectivityManager connectivityManager) {
        this.connectivityManager = connectivityManager;
    }

    @Override
    public Collection<InetAddress> retrieveDnsServers() throws NoDnsServersException {
        return getDnsServers();
    }

    private Set<InetAddress> getDnsServers() throws NoDnsServersException {
        Set<InetAddress> out = new HashSet<>();

        // Seriously, Android? Seriously?
        NetworkInfo activeInfo = connectivityManager.getActiveNetworkInfo();
        if (activeInfo == null) {
            throw new NoDnsServersException();
        }

        for (Network nw : connectivityManager.getAllNetworks()) {
            NetworkInfo ni = connectivityManager.getNetworkInfo(nw);
            if (ni == null || !ni.isConnected() || ni.getType() != activeInfo.getType()
                    || ni.getSubtype() != activeInfo.getSubtype()) {
                continue;
            }

            for (InetAddress address : connectivityManager.getLinkProperties(nw).getDnsServers()) {
                out.add(address);
            }
        }
        return out;
    }
}
