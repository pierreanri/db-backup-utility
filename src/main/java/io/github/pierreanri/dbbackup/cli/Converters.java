package io.github.pierreanri.dbbackup.cli;

import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import picocli.CommandLine;
import picocli.CommandLine.ITypeConverter;

/**
 * Type converters accepting the same aliases as the configuration file.
 */
final class Converters {

    private Converters() {
    }

    static void register(CommandLine commandLine) {
        commandLine.registerConverter(DatabaseType.class, wrap(DatabaseType::fromString));
        commandLine.registerConverter(Compression.class, wrap(Compression::fromName));
    }

    private static <T> ITypeConverter<T> wrap(java.util.function.Function<String, T> parser) {
        return value -> {
            try {
                return parser.apply(value);
            } catch (IllegalArgumentException e) {
                throw new CommandLine.TypeConversionException(e.getMessage());
            }
        };
    }
}
