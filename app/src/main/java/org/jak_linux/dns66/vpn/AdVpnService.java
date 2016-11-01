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
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.R;

import java.net.InetAddress;
import java.util.Collection;

public class AdVpnService extends VpnService {

    interface DnsServerListProvider {
        class NoDnsServersException extends Throwable {
        }

        Collection<InetAddress> retrieveDnsServers() throws NoDnsServersException;
    }

    interface ConfigProvider {
        Configuration retrieveConfig();
    }

    private static final String TAG = "VpnService";
    private static final int FOREGROUND_NOTIFICATION_ID = 10;

    public static final String VPN_UPDATE_STATUS_INTENT = "org.jak_linux.dns66.VPN_UPDATE_STATUS";
    public static final String KEY_NOTIFICATION_INTENT = "NOTIFICATION_INTENT";
    public static VpnStatus status = new VpnStatus();

    private final Handler handler = new Handler();

    private final NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this).setSmallIcon(R.drawable.ic_menu_info).setPriority(Notification.PRIORITY_MIN);

    private AdVpnThread vpnThread;

    private final ConnectivityChangeAnnouncer connectivityChangedReceiver = new ConnectivityChangeAnnouncer(new ConnectivityChangeAnnouncer.Callback() {
        @Override
        public void connectivityChanged() {
            handler.post(() -> reconnect());
        }

        @Override
        public void noNetworkDetected() {
            handler.post(() -> waitForNetVpn());
        }
    });

    @Override
    public void onCreate() {
        super.onCreate();
        ContextConfigProvider configProvider = new ContextConfigProvider(this);
        vpnThread = new AdVpnThread(
                new AdVpnThread.StatusObserver() {
                    @Override
                    public void running() {
                        status.running();
                        handler.post(() -> updateVpnStatus());
                    }

                    @Override
                    public void starting() {
                        status.starting();
                        handler.post(() -> updateVpnStatus());
                    }

                    @Override
                    public void reconnectingAfterNetworkError() {
                        status.reconnectingAfterNetworkError();
                        handler.post(() -> updateVpnStatus());
                    }

                    @Override
                    public void stopping() {
                        status.stopping();
                        handler.post(() -> updateVpnStatus());
                    }
                },
                new VpnServiceSocketProtector(AdVpnService.this),
                new VpnServiceVpnFileDescriptorProvider(this, new ConnectivityManagerDnsServerListProvider((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)), configProvider),
                new FileBlockedHostProvider(configProvider, this));
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Command command = extractCommandFromIntent(intent);
        if (command == Command.START) {
            if (intent != null) {
                PendingIntent notificationIntent = intent.getParcelableExtra(KEY_NOTIFICATION_INTENT);
                if (notificationIntent != null) {
                    updateNotificationIntent(notificationIntent);
                }
            }
            startVpn();
        } else if (command == Command.STOP) {
            stopVpn();
        }

        return Service.START_STICKY;
    }

    private Command extractCommandFromIntent(@Nullable Intent intent) {
        return intent == null ? Command.START : Command.values()[intent.getIntExtra("COMMAND", Command.START.ordinal())];
    }

    private void updateNotificationIntent(PendingIntent notificationIntent) {
        notificationBuilder.setContentIntent(notificationIntent);
    }

    private void updateVpnStatus() {
        notificationBuilder.setContentText(getString(status.statusString()));

        startForeground(FOREGROUND_NOTIFICATION_ID, notificationBuilder.build());

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(VPN_UPDATE_STATUS_INTENT));
    }

    private void startVpn() {
        notificationBuilder.setContentTitle(getString(R.string.notification_title));

        status.starting();
        updateVpnStatus();

        connectivityChangedReceiver.startObserveConnectivtyStateChanges(this);

        restartVpnThread();
    }

    private void restartVpnThread() {
        vpnThread.restartThread();
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
        stopVpnThread();
        connectivityChangedReceiver.stopObservingConnectivityChanges(this);
        status.stopped();
        updateVpnStatus();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
    }
}