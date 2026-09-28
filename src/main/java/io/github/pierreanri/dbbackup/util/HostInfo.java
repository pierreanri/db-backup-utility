package io.github.pierreanri.dbbackup.util;

import java.net.InetAddress;

/**
 * Information about the machine running the utility.
 */
public final class HostInfo {

    private static volatile String hostname;

    private HostInfo() {
    }

    public static String hostname() {
        String cached = hostname;
        if (cached == null) {
            cached = lookup();
            hostname = cached;
        }
        return cached;
    }

    private static String lookup() {
        for (String variable : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
