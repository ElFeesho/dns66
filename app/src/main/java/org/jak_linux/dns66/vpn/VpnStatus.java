package org.jak_linux.dns66.vpn;

import android.support.annotation.StringRes;

import org.jak_linux.dns66.R;

public class VpnStatus {

    private static final int VPN_STATUS_STARTING = 0;
    private static final int VPN_STATUS_RUNNING = 1;
    private static final int VPN_STATUS_STOPPING = 2;
    private static final int VPN_STATUS_WAITING_FOR_NETWORK = 3;
    private static final int VPN_STATUS_RECONNECTING = 4;
    private static final int VPN_STATUS_RECONNECTING_NETWORK_ERROR = 5;
    private static final int VPN_STATUS_STOPPED = 6;

    private int currentStatus = VPN_STATUS_STOPPED;

    @StringRes
    public int statusString() {
        switch (currentStatus) {
            case VPN_STATUS_STARTING:
                return R.string.notification_starting;
            case VPN_STATUS_RUNNING:
                return R.string.notification_running;
            case VPN_STATUS_STOPPING:
                return R.string.notification_stopping;
            case VPN_STATUS_WAITING_FOR_NETWORK:
                return R.string.notification_waiting_for_net;
            case VPN_STATUS_RECONNECTING:
                return R.string.notification_reconnecting;
            case VPN_STATUS_RECONNECTING_NETWORK_ERROR:
                return R.string.notification_reconnecting_error;
            case VPN_STATUS_STOPPED:
                return R.string.notification_stopped;
            default:
                throw new IllegalArgumentException("Invalid vpnStatus value (" + currentStatus + ")");
        }
    }

    void starting() {
        setStatus(VPN_STATUS_STARTING);
    }

    void waitingForNetwork() {
        setStatus(VPN_STATUS_WAITING_FOR_NETWORK);
    }

    void reconnecting() {
        setStatus(VPN_STATUS_RECONNECTING);
    }

    void stopped() {
        setStatus(VPN_STATUS_STOPPED);
    }

    void running() {
        setStatus(VPN_STATUS_RUNNING);
    }

    void stopping() {
        setStatus(VPN_STATUS_STOPPING);
    }

    void reconnectingAfterNetworkError() {
        setStatus(VPN_STATUS_RECONNECTING_NETWORK_ERROR);
    }

    private void setStatus(int status) {
        currentStatus = status;
    }

    public boolean isStopped() {
        return currentStatus == VPN_STATUS_STOPPED;
    }
}
