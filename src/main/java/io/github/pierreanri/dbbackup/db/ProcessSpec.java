/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Description of an external command to run. Secrets must never be part of {@link #command()};
 * pass them through {@link #environment()} or a private temporary file instead.
 *
 * @param command     program and arguments
 * @param environment extra environment variables
 * @param stdin       file sent to the standard input, or {@code null}
 * @param stdout      file receiving the standard output, or {@code null} to capture it
 * @param timeout     maximum duration, or {@code null} for no limit
 * @param secrets     values redacted from error messages
 * @param missingHint message shown when the program cannot be found
 */
public record ProcessSpec(
        List<String> command,
        Map<String, String> environment,
        Path stdin,
        Path stdout,
        Duration timeout,
        List<String> secrets,
        String missingHint) {

    public ProcessSpec {
        command = List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        secrets = secrets == null ? List.of() : secrets.stream().filter(s -> s != null && !s.isEmpty()).toList();
    }

    public String program() {
        return Path.of(command.get(0)).getFileName().toString();
    }

    public static Builder builder(List<String> command) {
        return new Builder(command);
    }

    /** Fluent builder of {@link ProcessSpec}. */
    public static final class Builder {
        private final List<String> command;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private final List<String> secrets = new ArrayList<>();
        private Path stdin;
        private Path stdout;
        private Duration timeout;
        private String missingHint;

        private Builder(List<String> command) {
            this.command = new ArrayList<>(command);
        }

        public Builder env(String name, String value) {
            if (value != null) {
                environment.put(name, value);
            }
            return this;
        }

        public Builder stdin(Path file) {
            this.stdin = file;
            return this;
        }

        public Builder stdout(Path file) {
            this.stdout = file;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder timeoutMinutes(Integer minutes) {
            this.timeout = minutes == null ? null : Duration.ofMinutes(minutes);
            return this;
        }

        public Builder secret(String value) {
            if (value != null && !value.isEmpty()) {
                secrets.add(value);
            }
            return this;
        }

        public Builder missingHint(String hint) {
            this.missingHint = hint;
            return this;
        }

        public ProcessSpec build() {
            return new ProcessSpec(command, environment, stdin, stdout, timeout, secrets, missingHint);
        }
    }
}
