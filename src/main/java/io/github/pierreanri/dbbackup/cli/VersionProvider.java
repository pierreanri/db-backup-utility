package io.github.pierreanri.dbbackup.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import picocli.CommandLine.IVersionProvider;

/**
 * Reads the application version from the filtered {@code dbbackup-version.properties} resource.
 */
public class VersionProvider implements IVersionProvider {

    public static String version() {
        try (InputStream in = VersionProvider.class.getResourceAsStream("/dbbackup-version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String version = props.getProperty("version");
                if (version != null && !version.startsWith("${")) {
                    return version;
                }
            }
        } catch (IOException ignored) {
            // fall through to the default below
        }
        return "dev";
    }

    @Override
    public String[] getVersion() {
        return new String[] {
            "dbbackup " + version(),
            "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
            "OS " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")
        };
    }
}
