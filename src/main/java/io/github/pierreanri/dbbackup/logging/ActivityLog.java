/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.logging;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.pierreanri.dbbackup.util.Mappers;

/**
 * Append-only history of backup activities stored as JSON lines. Writes are serialized within the
 * process and protected by a file lock across processes (e.g. cron jobs running concurrently).
 */
public class ActivityLog {

    public static final String FILE_NAME = "history.jsonl";

    private static final Logger LOG = LoggerFactory.getLogger(ActivityLog.class);

    private final Path file;

    public ActivityLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Appends an entry. Failures are logged but never interrupt the calling operation. */
    @SuppressWarnings("try")
    public synchronized void append(ActivityEntry entry) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] line = (Mappers.json().writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
                    FileLock ignored = channel.lock()) {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        } catch (IOException e) {
            LOG.warn("Cannot write activity history {}: {}", file, e.getMessage());
        }
    }

    /** Reads all entries, oldest first. Malformed lines are skipped. */
    public List<ActivityEntry> readAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Cannot read activity history {}: {}", file, e.getMessage());
            return List.of();
        }
        List<ActivityEntry> entries = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(Mappers.json().readValue(line, ActivityEntry.class));
            } catch (JsonProcessingException e) {
                LOG.debug("Skipping malformed history line: {}", line);
            }
        }
        return entries;
    }

    /** Returns at most {@code limit} most recent entries, oldest first. */
    public List<ActivityEntry> readLast(int limit) {
        List<ActivityEntry> all = readAll();
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }
}
