/* Copyright (C) 2016 Julian Andres Klode <jak@jak-linux.org>
 *
 * Derived from AdBuster:
 * Copyright (C) 2016 Daniel Brodie <dbrodie@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package org.jak_linux.dns66.vpn;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;
import org.jak_linux.dns66.MainActivity;
import org.jak_linux.dns66.R;

public class AdVpnService extends VpnService {

    public static class VpnStatus
    {
        static final int VPN_STATUS_STARTING = 0;
        static final int VPN_STATUS_RUNNING = 1;
        static final int VPN_STATUS_STOPPING = 2;
        static final int VPN_STATUS_WAITING_FOR_NETWORK = 3;
        static final int VPN_STATUS_RECONNECTING = 4;
        static final int VPN_STATUS_RECONNECTING_NETWORK_ERROR = 5;
        static final int VPN_STATUS_STOPPED = 6;

        private int currentStatus = VPN_STATUS_STOPPED;

        static int vpnStatusToTextId(int status) {
            switch (status) {
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
                    throw new IllegalArgumentException("Invalid vpnStatus value (" + status + ")");
            }
        }

        @StringRes
        public int statusString()
        {
            return vpnStatusToTextId(currentStatus);
        }

        void starting() {
            currentStatus = VPN_STATUS_STARTING;
        }

        void waitingForNetwork() {
            currentStatus = VPN_STATUS_WAITING_FOR_NETWORK;
        }

        void reconnecting() {
            currentStatus = VPN_STATUS_RECONNECTING;
        }

        void stopped() {
            currentStatus = VPN_STATUS_STOPPED;
        }

        public boolean isStopped() {
            return currentStatus == VPN_STATUS_STOPPED;
        }
    }

    public static final String VPN_UPDATE_STATUS_INTENT = "org.jak_linux.dns66.VPN_UPDATE_STATUS";
    public static final String VPN_UPDATE_STATUS_EXTRA = "VPN_STATUS";
    public static final int FOREGROUND_NOTIFICATION_ID = 10;

    private static final String TAG = "VpnService";

    public static VpnStatus status = new VpnStatus();

    private final Handler handler = new Handler();

    private final AdVpnThread vpnThread = new AdVpnThread(this, new AdVpnThread.Notify() {
        @Override
        public void run(final int value) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    updateVpnStatus();
                }
            });
        }
    });

    private final NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
            .setSmallIcon(R.drawable.ic_menu_info) // TODO: Notification icon
            .setPriority(Notification.PRIORITY_MIN);

    private final ConnectivityChangeAnnouncer connectivityChangedReceiver = new ConnectivityChangeAnnouncer(new ConnectivityChangeAnnouncer.Callback() {
        @Override
        public void connectivityChanged() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    reconnect();
                }
            });
        }

        @Override
        public void noNetworkDetected() {
            handler.post(new Runnable() {
                public void run() {
                    waitForNetVpn();
                }
            });
        }
    });

    public static void checkStartVpnOnBoot(Context context) {
        Log.i("BOOT", "Checking whether to start ad buster on boot");
        Configuration config = FileHelper.loadCurrentSettings(context);
        if (config == null || !config.autoStart) {
            return;
        }

        if (VpnService.prepare(context) != null) {
            Log.i("BOOT", "VPN preparation not confirmed by user, changing enabled to false");
        }

        Log.i("BOOT", "Starting ad buster from boot");

        Intent intent = new Intent(context, AdVpnService.class);
        intent.putExtra("COMMAND", Command.START.ordinal());
        intent.putExtra("NOTIFICATION_INTENT",
                PendingIntent.getActivity(context, 0,
                        new Intent(context, MainActivity.class), 0));
        context.startService(intent);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        switch (intent == null ? Command.START : Command.values()[intent.getIntExtra("COMMAND", Command.START.ordinal())]) {
            case START:
                startVpn(intent == null ? null : (PendingIntent) intent.getParcelableExtra("NOTIFICATION_INTENT"));
                break;
            case STOP:
                stopVpn();
                break;
        }

        return Service.START_STICKY;
    }

    private void updateVpnStatus() {
        notificationBuilder.setContentText(getString(status.statusString()));

        startForeground(FOREGROUND_NOTIFICATION_ID, notificationBuilder.build());

        Intent intent = new Intent(VPN_UPDATE_STATUS_INTENT);
        intent.putExtra(VPN_UPDATE_STATUS_EXTRA, status.currentStatus);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void startVpn(PendingIntent notificationIntent) {
        notificationBuilder.setContentTitle(getString(R.string.notification_title));
        if (notificationIntent != null) {
            notificationBuilder.setContentIntent(notificationIntent);
        }

        status.starting();
        updateVpnStatus();

        connectivityChangedReceiver.startObserveConnectivtyStateChanges(this);

        restartVpnThread();
    }

    private void restartVpnThread() {
        vpnThread.stopThread();
        vpnThread.startThread();
    }

    private void stopVpnThread() {
        vpnThread.stopThread();
    }

    private void waitForNetVpn() {
        stopVpnThread();
        status.waitingForNetwork();
        updateVpnStatus();
    }

    private void reconnect() {
        status.reconnecting();
        updateVpnStatus();
        restartVpnThread();
    }

    private void stopVpn() {
        Log.i(TAG, "Stopping Service");
        stopVpnThread();
        try {
            connectivityChangedReceiver.stopObservingConnectivityChanges(this);
        } catch (IllegalArgumentException e) {
            Log.i(TAG, "Ignoring exception on unregistering receiver");
        }
        status.stopped();
        updateVpnStatus();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroyed, shutting down");
        stopVpn();
    }
}