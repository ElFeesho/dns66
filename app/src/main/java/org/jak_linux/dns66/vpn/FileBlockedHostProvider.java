package org.jak_linux.dns66.vpn;

import android.content.Context;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
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
        HashSet<String> blockedHosts = new HashSet<>();

        Configuration config = configProvider.retrieveConfig();

        if (config.hosts.enabled) {
            for (Configuration.Item item : config.hosts.items) {
                if (item.state == Configuration.Item.STATE_IGNORE) {
                    continue;
                }

                File file = FileHelper.getItemFile(context, item);

                if (file == null) {
                    if (!item.location.contains("/")) {
                        if (item.state == Configuration.Item.STATE_ALLOW) {
                            blockedHosts.remove(item.location);
                        } else if (item.state == Configuration.Item.STATE_DENY) {
                            blockedHosts.add(item.location);
                        }
                    }
                    continue;
                }

                try (FileInputStream fileInputStream = new FileInputStream(file)) {
                    Set<String> hosts = new HostFileParser().parse(fileInputStream);
                    if (item.state == Configuration.Item.STATE_DENY) {
                        blockedHosts.addAll(hosts);
                    } else if (item.state == Configuration.Item.STATE_ALLOW) {
                        blockedHosts.removeAll(hosts);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return blockedHosts;
    }
}
