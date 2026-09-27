package io.github.pierreanri.dbbackup.config;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of loading the configuration.
 *
 * @param config   the parsed configuration (empty when no file was found)
 * @param source   file the configuration was read from, {@code null} when none was found
 * @param warnings non fatal problems, e.g. environment variables that are not set
 */
public record LoadedConfig(AppConfig config, Path source, List<String> warnings) {

    public LoadedConfig {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
