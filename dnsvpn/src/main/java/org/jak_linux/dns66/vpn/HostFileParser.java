package org.jak_linux.dns66.vpn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

class HostFileParser {
    public Set<String> parse(InputStream inputStream) {
        HashSet<String> hosts = new HashSet<>();
        try {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String s = line.trim();
                    s = s.substring(0, s.contains("#") ? s.indexOf("#") : s.length());

                    if (!s.isEmpty()) {
                        String[] split = s.split("[ \t]+");

                        if (split.length == 2 && isDeviceLocalIp(split[0])) {
                            hosts.add(split[1].toLowerCase(Locale.ENGLISH));
                        } else if (split.length == 1) {
                            hosts.add(split[0].toLowerCase(Locale.ENGLISH));
                        }
                    }
                }
            }
        } catch (IOException ignored) {

        }
        return hosts;
    }

    private boolean isDeviceLocalIp(String s) {
        return s.equals("127.0.0.1") || s.equals("0.0.0.0");
    }
}
