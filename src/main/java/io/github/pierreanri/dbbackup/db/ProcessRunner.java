/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.util.Secrets;

/**
 * Runs external client tools (mysqldump, pg_dump, mongodump...) with redirected input/output,
 * bounded capture of their output and an optional timeout.
 */
public class ProcessRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int MAX_CAPTURE = 1024 * 1024;
    private static final int MAX_STDERR = 16 * 1024;

    /**
     * Runs the command and waits for it.
     *
     * @throws DbBackupException when the program is missing, times out or exits with a non-zero code
     */
    public ProcessResult run(ProcessSpec spec) {
        ProcessResult result = execute(spec);
        if (result.exitCode() != 0) {
            throw failure(spec, result);
        }
        return result;
    }

    /** The error reported for a failed command. */
    public static DbBackupException failure(ProcessSpec spec, ProcessResult result) {
        String detail = result.stderr().isBlank() ? "" : ": " + result.stderr().strip();
        return new DbBackupException("'" + spec.program() + "' failed with exit code " + result.exitCode() + detail);
    }

    /**
     * Runs the command and waits for it, returning its result whatever the exit code.
     *
     * @throws DbBackupException when the program is missing or times out
     */
    public ProcessResult execute(ProcessSpec spec) {
        ProcessBuilder builder = new ProcessBuilder(spec.command());
        builder.environment().putAll(spec.environment());
        if (spec.stdin() != null) {
            builder.redirectInput(spec.stdin().toFile());
        } else {
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        }
        if (spec.stdout() != null) {
            builder.redirectOutput(spec.stdout().toFile());
        }
        LOG.debug("Running {}", String.join(" ", spec.command()));

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            String hint = spec.missingHint() != null ? " " + spec.missingHint() : "";
            throw new DbBackupException("Cannot run '" + spec.program() + "': " + e.getMessage() + "." + hint, e);
        }

        StreamCollector stderr = new StreamCollector(process.getErrorStream(), MAX_STDERR, spec.program(), true);
        StreamCollector stdout = spec.stdout() == null
                ? new StreamCollector(process.getInputStream(), MAX_CAPTURE, spec.program(), false)
                : null;
        stderr.start();
        if (stdout != null) {
            stdout.start();
        }

        int exitCode;
        try {
            if (spec.timeout() != null) {
                if (!process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    kill(process);
                    throw new DbBackupException("'" + spec.program() + "' timed out after " + spec.timeout().toMinutes()
                            + " minute(s)");
                }
            }
            exitCode = process.waitFor();
            stderr.join(5000);
            if (stdout != null) {
                stdout.join(5000);
            }
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new DbBackupException("'" + spec.program() + "' was interrupted", e);
        }

        String errorOutput = Secrets.redact(stderr.text(), spec.secrets().toArray(String[]::new));
        return new ProcessResult(exitCode, stdout == null ? "" : stdout.text(), errorOutput);
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").toLowerCase().startsWith("windows") ? "NUL" : "/dev/null");
    }

    /** Drains a stream in a background thread, keeping at most {@code limit} trailing characters. */
    private static final class StreamCollector extends Thread {
        private final InputStream stream;
        private final int limit;
        private final String program;
        private final boolean logLines;
        private final StringBuilder buffer = new StringBuilder();

        StreamCollector(InputStream stream, int limit, String program, boolean logLines) {
            super(program + "-output");
            setDaemon(true);
            this.stream = stream;
            this.limit = limit;
            this.program = program;
            this.logLines = logLines;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (logLines) {
                        LOG.debug("[{}] {}", program, line);
                    }
                    synchronized (buffer) {
                        buffer.append(line).append('\n');
                        if (buffer.length() > limit) {
                            buffer.delete(0, buffer.length() - limit);
                        }
                    }
                }
            } catch (IOException e) {
                LOG.debug("Error reading output of {}: {}", program, e.getMessage());
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.toString();
            }
        }
    }
}
