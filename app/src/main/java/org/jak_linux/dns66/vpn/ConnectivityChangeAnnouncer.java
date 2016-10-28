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

    ConnectivityChangeAnnouncer(Callback connectivityCallback) {
        this.connectivityCallback = connectivityCallback;
    }

    public void startObserveConnectivtyStateChanges(Context context) {
        context.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    public void stopObservingConnectivityChanges(Context context) {
        context.unregisterReceiver(this);
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
