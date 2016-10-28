package org.jak_linux.dns66.vpn;

import android.content.Context;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

class FileBlockedHostProvider implements AdVpnThread.BlockedHostProvider {

    private final AdVpnService.ConfigProvider configProvider;
    private final Context context;

    FileBlockedHostProvider(AdVpnService.ConfigProvider configProvider, Context context) {
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
