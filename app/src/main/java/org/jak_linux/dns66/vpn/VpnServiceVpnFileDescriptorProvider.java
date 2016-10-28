package org.jak_linux.dns66.vpn;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.MainActivity;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collection;

class VpnServiceVpnFileDescriptorProvider implements AdVpnThread.VpnFileDescriptorProvider {
    private static final String[] NOUGAT_APP_WHITELIST = {"org.jak_linux.dns66", "com.android.vending"};
    private final VpnService vpnService;
    private final AdVpnService.DnsServerListProvider dnsServerListProvider;
    private final AdVpnService.ConfigProvider configProvider;

    VpnServiceVpnFileDescriptorProvider(VpnService vpnService, AdVpnService.DnsServerListProvider dnsServerListProvider, AdVpnService.ConfigProvider configProvider) {
        this.vpnService = vpnService;
        this.dnsServerListProvider = dnsServerListProvider;
        this.configProvider = configProvider;
    }

    @Override
    public ParcelFileDescriptor retrieve() {
        // Get the current DNS servers before starting the VPN
        Collection<InetAddress> dnsServers;
        try {
            dnsServers = dnsServerListProvider.retrieveDnsServers();
        } catch (AdVpnService.DnsServerListProvider.NoDnsServersException e) {
            e.printStackTrace();
            //throw new AdVpnThread.VpnNetworkException("No DNS servers");
            return null;
        }

        // Configure a builder while parsing the parameters.
        VpnService.Builder builder = vpnService.new Builder();
        builder.addAddress("192.168.50.1", 24);

        // Add configured DNS servers
        Configuration config = configProvider.retrieveConfig();
        if (config.dnsServers.enabled) {
            for (Configuration.Item item : config.dnsServers.items) {
                if (item.state == Configuration.Item.STATE_ALLOW) {
                    try {
                        builder.addDnsServer(item.location);
                        builder.addRoute(item.location, 32);
                    } catch (Exception dangerous) {
                        dangerous.printStackTrace();
                    }
                }
            }
        }
        // Add all knows DNS servers
        for (InetAddress addr : dnsServers) {
            if (addr instanceof Inet4Address) {
                builder.addDnsServer(addr);
                builder.addRoute(addr, 32);
            }
        }

        builder.setBlocking(true);

        // Work around DownloadManager bug on Nougat - It cannot resolve DNS
        // names while a VPN service is active.
        for (String app : NOUGAT_APP_WHITELIST) {
            try {
                builder.addDisallowedApplication(app);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Create a new interface using the builder and save the parameters.
        return builder.setSession("DNS66").setConfigureIntent(PendingIntent.getActivity(vpnService, 1, new Intent(vpnService, MainActivity.class), PendingIntent.FLAG_CANCEL_CURRENT)).establish();
    }
}
