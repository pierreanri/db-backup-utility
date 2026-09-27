package io.github.pierreanri.dbbackup.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import io.github.pierreanri.dbbackup.config.LoggingConfig;
import io.github.pierreanri.dbbackup.util.PathUtils;

/**
 * Configures Logback programmatically: a console appender on stderr and a size and time based
 * rolling log file ({@code dbbackup.log}) in the configured log directory.
 */
public final class LoggingConfigurator {

    public static final String LOG_FILE_NAME = "dbbackup.log";

    private static final List<String> NOISY_LOGGERS = List.of(
            "software.amazon.awssdk", "com.azure", "com.google", "io.grpc", "io.netty", "io.opentelemetry",
            "org.apache.http", "org.mongodb.driver", "reactor");

    private LoggingConfigurator() {
    }

    /** Log directory for the given config: {@code logging.dir} or {@code ~/.dbbackup/logs}. */
    public static Path logDirectory(LoggingConfig config, Path override) {
        if (override != null) {
            return override;
        }
        if (config != null && config.dir() != null && !config.dir().isBlank()) {
            return PathUtils.expand(config.dir());
        }
        return PathUtils.appHome().resolve("logs");
    }

    /**
     * Replaces the current Logback configuration.
     *
     * @return the log file, or {@code null} when file logging is disabled or not possible
     */
    public static Path configure(LoggingConfig config, Path logDir, Verbosity verbosity) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        Level consoleLevel = switch (verbosity) {
            case QUIET -> Level.WARN;
            case NORMAL -> Level.INFO;
            case VERBOSE -> Level.DEBUG;
        };
        Level fileLevel = Level.toLevel(config == null ? null : config.level(), Level.INFO);
        if (verbosity == Verbosity.VERBOSE && fileLevel.isGreaterOrEqual(Level.DEBUG)) {
            fileLevel = Level.DEBUG;
        }

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(consoleLevel.isGreaterOrEqual(fileLevel) ? fileLevel : consoleLevel);
        root.addAppender(consoleAppender(context, consoleLevel, verbosity));

        Path logFile = null;
        if (config == null || config.fileEnabled()) {
            try {
                Files.createDirectories(logDir);
                logFile = logDir.resolve(LOG_FILE_NAME);
                root.addAppender(fileAppender(context, logFile, fileLevel, config));
            } catch (IOException | RuntimeException e) {
                logFile = null;
                LoggerFactory.getLogger(LoggingConfigurator.class)
                        .warn("File logging disabled: cannot use log directory {} ({})", logDir, e.getMessage());
            }
        }

        Level thirdParty = verbosity == Verbosity.VERBOSE ? Level.INFO : Level.WARN;
        for (String name : NOISY_LOGGERS) {
            context.getLogger(name).setLevel(thirdParty);
        }
        return logFile;
    }

    private static ConsoleAppender<ILoggingEvent> consoleAppender(LoggerContext context, Level level,
            Verbosity verbosity) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(verbosity == Verbosity.VERBOSE
                ? "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{24} - %msg%n"
                : "%d{HH:mm:ss} %-5level %msg%n");
        encoder.start();

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setName("console");
        appender.setTarget("System.err");
        appender.setEncoder(encoder);
        appender.addFilter(threshold(context, level));
        appender.start();
        return appender;
    }

    private static RollingFileAppender<ILoggingEvent> fileAppender(LoggerContext context, Path logFile, Level level,
            LoggingConfig config) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} - %msg%n");
        encoder.start();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(context);
        appender.setName("file");
        appender.setFile(logFile.toString());
        appender.setEncoder(encoder);

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(appender);
        policy.setFileNamePattern(logFile.getParent().resolve("dbbackup.%d{yyyy-MM-dd}.%i.log.gz").toString());
        String maxFileSize = config != null && config.maxFileSize() != null ? config.maxFileSize() : "10MB";
        policy.setMaxFileSize(FileSize.valueOf(maxFileSize));
        policy.setMaxHistory(config != null && config.maxHistoryDays() != null ? config.maxHistoryDays() : 30);
        policy.setTotalSizeCap(FileSize.valueOf("1GB"));
        policy.start();

        appender.setRollingPolicy(policy);
        appender.addFilter(threshold(context, level));
        appender.start();
        return appender;
    }

    private static ThresholdFilter threshold(LoggerContext context, Level level) {
        ThresholdFilter filter = new ThresholdFilter();
        filter.setContext(context);
        filter.setLevel(level.toString());
        filter.start();
        return filter;
    }
}
