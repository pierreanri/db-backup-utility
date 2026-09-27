package io.github.pierreanri.dbbackup.util;

import java.util.regex.Pattern;

/**
 * Helpers keeping secrets out of logs and console output.
 */
public final class Secrets {

    private static final Pattern URI_USERINFO = Pattern.compile("(://[^:/@]+):([^@]*)@");
    private static final Pattern SECRET_PARAMS = Pattern.compile(
            "(?i)([?&;](?:password|pwd|sig|secret|token|accountkey|sharedaccesssignature)=)[^&;]*");

    private Secrets() {
    }

    /** Replaces the password of {@code scheme://user:password@host} URIs and secret query parameters. */
    public static String maskUri(String uri) {
        if (uri == null) {
            return null;
        }
        String masked = URI_USERINFO.matcher(uri).replaceAll("$1:****@");
        return SECRET_PARAMS.matcher(masked).replaceAll("$1****");
    }

    /** Masks every occurrence of the given secret values in a text (e.g. process error output). */
    public static String redact(String text, String... secrets) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 3) {
                result = result.replace(secret, "****");
            }
        }
        return result;
    }
}
