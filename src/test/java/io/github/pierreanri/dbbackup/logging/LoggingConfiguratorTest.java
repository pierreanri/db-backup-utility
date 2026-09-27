/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import io.github.pierreanri.dbbackup.config.LoggingConfig;

class LoggingConfiguratorTest {

    @TempDir
    Path tmp;

    @AfterEach
    void restoreTestLogging() throws JoranException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        new ContextInitializer(context).autoConfig();
    }

    @Test
    void writesRollingLogFile() throws IOException {
        Path file = LoggingConfigurator.configure(new LoggingConfig(null, "debug", "1MB", 7, null), tmp,
                Verbosity.QUIET);

        LoggerFactory.getLogger("io.github.pierreanri.dbbackup.Test").debug("hello from test");
        LoggerFactory.getLogger("software.amazon.awssdk.Noise").info("hidden");

        assertThat(file).isEqualTo(tmp.resolve("dbbackup.log"));
        String content = Files.readString(file);
        assertThat(content).contains("hello from test").doesNotContain("hidden");
    }

    @Test
    void fileLoggingCanBeDisabled() {
        Path file = LoggingConfigurator.configure(new LoggingConfig(null, null, null, null, false), tmp,
                Verbosity.NORMAL);
        assertThat(file).isNull();
        assertThat(tmp.resolve("dbbackup.log")).doesNotExist();
    }

    @Test
    void resolvesLogDirectory() {
        assertThat(LoggingConfigurator.logDirectory(null, tmp)).isEqualTo(tmp);
        assertThat(LoggingConfigurator.logDirectory(new LoggingConfig("/var/log/x", null, null, null, null), null))
                .isEqualTo(Path.of("/var/log/x"));
        assertThat(LoggingConfigurator.logDirectory(LoggingConfig.DEFAULT, null).toString()).endsWith(".dbbackup/logs");
    }
}
