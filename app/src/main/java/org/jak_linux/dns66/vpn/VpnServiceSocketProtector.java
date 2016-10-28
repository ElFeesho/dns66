package org.jak_linux.dns66.vpn;

import android.net.VpnService;

import java.net.DatagramSocket;

class VpnServiceSocketProtector implements AdVpnThread.SocketProtector {

    private final VpnService vpnService;

    VpnServiceSocketProtector(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public void protect(DatagramSocket socket) {
        vpnService.protect(socket);
    }
}
