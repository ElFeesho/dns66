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
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;
import org.jak_linux.dns66.MainActivity;
import org.jak_linux.dns66.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AdVpnService extends VpnService {

    private static class VpnServiceVpnFileDescriptorProvider implements AdVpnThread.VpnFileDescriptorProvider {
        private static final String[] NOUGAT_APP_WHITELIST = {"org.jak_linux.dns66", "com.android.vending"};
        private final VpnService vpnService;
        private final AdVpnThread.DnsServerListProvider dnsServerListProvider;
        private final AdVpnThread.ConfigProvider configProvider;

        private VpnServiceVpnFileDescriptorProvider(VpnService vpnService, AdVpnThread.DnsServerListProvider dnsServerListProvider, AdVpnThread.ConfigProvider configProvider) {
            this.vpnService = vpnService;
            this.dnsServerListProvider = dnsServerListProvider;
            this.configProvider = configProvider;
        }

        @Override
        public ParcelFileDescriptor retrieve() {
            Log.i(TAG, "Configuring");

            // Get the current DNS servers before starting the VPN
            Collection<InetAddress> dnsServers;
            try {
                dnsServers = dnsServerListProvider.retrieveDnsServers();
            } catch (AdVpnThread.DnsServerListProvider.NoDnsServersException e) {
                e.printStackTrace();
                //throw new AdVpnThread.VpnNetworkException("No DNS servers");
                return null;
            }
            Log.i(TAG, "Got DNS servers = " + dnsServers);

            // Configure a builder while parsing the parameters.
            Builder builder = vpnService.new Builder();
            builder.addAddress("192.168.50.1", 24);

            // Add configured DNS servers
            Configuration config = configProvider.retrieveConfig();
            if (config.dnsServers.enabled) {
                for (Configuration.Item item : config.dnsServers.items) {
                    if (item.state == Configuration.Item.STATE_ALLOW) {
                        Log.i(TAG, "configure: Adding DNS Server " + item.location);
                        try {
                            builder.addDnsServer(item.location);
                            builder.addRoute(item.location, 32);
                        } catch (Exception e) {
                            Log.e(TAG, "configure: Cannot add custom DNS server", e);
                        }
                    }
                }
            }
            // Add all knows DNS servers
            for (InetAddress addr : dnsServers) {
                if (addr instanceof Inet4Address) {
                    Log.i(TAG, "configure: Adding DNS Server " + addr);
                    builder.addDnsServer(addr);
                    builder.addRoute(addr, 32);
                }
            }

            builder.setBlocking(true);

            // Work around DownloadManager bug on Nougat - It cannot resolve DNS
            // names while a VPN service is active.
            for (String app : NOUGAT_APP_WHITELIST) {
                try {
                    Log.d(TAG, "configure: Disallowing " + app + " from using the DNS VPN");
                    builder.addDisallowedApplication(app);
                } catch (Exception e) {
                    Log.w(TAG, "configure: Cannot disallow", e);
                }
            }

            // Create a new interface using the builder and save the parameters.
            return builder.setSession("DNS66").setConfigureIntent(PendingIntent.getActivity(vpnService, 1, new Intent(vpnService, MainActivity.class), PendingIntent.FLAG_CANCEL_CURRENT)).establish();
        }
    }

    private static class ContextConfigProvider implements AdVpnThread.ConfigProvider {

        private final Context context;

        public ContextConfigProvider(Context context) {
            this.context = context;
        }

        @Override
        public Configuration retrieveConfig() {
            return FileHelper.loadCurrentSettings(context);
        }
    }

    static class FileBlockedHostProvider implements AdVpnThread.BlockedHostProvider {

        private final AdVpnThread.ConfigProvider configProvider;
        private final Context context;

        public FileBlockedHostProvider(AdVpnThread.ConfigProvider configProvider, Context context) {
            this.configProvider = configProvider;
            this.context = context;
        }

        @Override
        public Set<String> retrieveBlockedHosts() throws InterruptedException {
            Configuration config = configProvider.retrieveConfig();

            HashSet<String> blockedHosts = new HashSet<>();


            if (config.hosts.enabled) {
                for (Configuration.Item item : config.hosts.items) {

                    File file = FileHelper.getItemFile(context, item);

                    if (file == null && !item.location.contains("/")) {
                        // Single address to block
                        if (item.state == Configuration.Item.STATE_ALLOW) {
                            blockedHosts.remove(item.location);
                        } else if (item.state == Configuration.Item.STATE_DENY) {
                            blockedHosts.add(item.location);
                        }

                        continue;
                    }

                    FileReader reader;
                    if (file == null || item.state == Configuration.Item.STATE_IGNORE)
                        continue;
                    try {
                        reader = new FileReader(file);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        continue;
                    }

                    try {
                        try (BufferedReader br = new BufferedReader(reader)) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                if (Thread.interrupted())
                                    throw new InterruptedException("Interrupted");
                                String s = line.trim();

                                if (s.length() != 0) {
                                    String[] ss = s.split("#");
                                    s = ss.length > 0 ? ss[0].trim() : "";
                                }
                                if (s.length() != 0) {
                                    String[] split = s.split("[ \t]+");
                                    String host = null;
                                    if (split.length == 2 && (split[0].equals("127.0.0.1") || split[0].equals("0.0.0.0"))) {
                                        host = split[1].toLowerCase(Locale.ENGLISH);
                                    } else if (split.length == 1) {
                                        host = split[0].toLowerCase(Locale.ENGLISH);
                                    }
                                    if (host != null) {
                                        if (item.state == 0)
                                            blockedHosts.add(host);
                                        else if (item.state == 1)
                                            blockedHosts.remove(host);
                                    }
                                }

                            }
                        }

                    } catch (IOException ignored) {

                    } finally {
                        try {
                            reader.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return blockedHosts;
        }
    }

    public static final String VPN_UPDATE_STATUS_INTENT = "org.jak_linux.dns66.VPN_UPDATE_STATUS";
    public static final int FOREGROUND_NOTIFICATION_ID = 10;

    private static final String TAG = "VpnService";

    public static VpnStatus status = new VpnStatus();

    private final Handler handler = new Handler();
    private final NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
            .setSmallIcon(R.drawable.ic_menu_info) // TODO: Notification icon
            .setPriority(Notification.PRIORITY_MIN);
    private final AdVpnThread vpnThread = new AdVpnThread(new AdVpnThread.Notify() {
        @Override
        public void run(final int value) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    updateVpnStatus();
                }
            });
        }
    }, new AdVpnThread.SocketProtector() {
        @Override
        public void protect(DatagramSocket socket) {
            protect(socket);
        }
    }, new VpnServiceVpnFileDescriptorProvider(this, new ConnectivityManagerDnsServerListProvider((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)), new ContextConfigProvider(this)), new AdVpnService.FileBlockedHostProvider(new ContextConfigProvider(this), this));
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
        intent.putExtra("NOTIFICATION_INTENT", PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), 0));
        context.startService(intent);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        Command command = intent == null ? Command.START : Command.values()[intent.getIntExtra("COMMAND", Command.START.ordinal())];
        if (command == Command.START) {
            startVpn(intent == null ? null : (PendingIntent) intent.getParcelableExtra("NOTIFICATION_INTENT"));
        } else if (command == Command.STOP) {
            stopVpn();
        }

        return Service.START_STICKY;
    }

    private void updateVpnStatus() {
        notificationBuilder.setContentText(getString(status.statusString()));

        startForeground(FOREGROUND_NOTIFICATION_ID, notificationBuilder.build());

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(VPN_UPDATE_STATUS_INTENT));
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