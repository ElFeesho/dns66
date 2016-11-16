package org.jak_linux.dns66.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;

class ConnectivityChangeAnnouncer extends BroadcastReceiver {

    interface Callback {
        void connectivityChanged();

        void noNetworkDetected();
    }

    private final Callback connectivityCallback;
    private boolean registered = false;

    ConnectivityChangeAnnouncer(Callback connectivityCallback) {
        this.connectivityCallback = connectivityCallback;
    }

    public void startObserveConnectivtyStateChanges(Context context) {
        if (!registered) {
            context.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            registered = true;
        }
    }

    public void stopObservingConnectivityChanges(Context context) {
        if (registered) {
            context.unregisterReceiver(this);
            registered = false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, 0) != ConnectivityManager.TYPE_VPN) {
            if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
                connectivityCallback.noNetworkDetected();
            } else {
                connectivityCallback.connectivityChanged();
            }
        }
    }
}
