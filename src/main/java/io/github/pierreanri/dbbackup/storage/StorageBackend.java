/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A place where backup files are kept: a local directory or a cloud object store. Keys are
 * relative to the backend's root directory or prefix and use {@code /} as separator.
 */
public interface StorageBackend extends AutoCloseable {

    /** Name of the storage target in the configuration. */
    String name();

    /** Short type identifier: local, s3, gcs or azure. */
    String type();

    /** Root location of the backend, e.g. {@code s3://bucket/prefix/}. */
    String description();

    /** Full location of a key, e.g. {@code s3://bucket/prefix/key}. */
    String location(String key);

    /** Uploads a local file, replacing any existing object with the same key. */
    void upload(Path source, String key);

    /** Downloads an object into a local file, replacing it if it exists. */
    void download(String key, Path target);

    /** Lists objects whose key starts with {@code prefix} (empty for everything). */
    List<StoredObject> list(String prefix);

    /** Deletes an object; does nothing when it does not exist. */
    void delete(String key);

    boolean exists(String key);

    /** Reads a small object fully into memory. */
    default byte[] read(String key) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("dbbackup-read-", ".tmp");
            download(key, tmp);
            return Files.readAllBytes(tmp);
        } catch (IOException e) {
            throw new StorageException("Cannot read " + location(key) + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** Writes a small object from memory. */
    default void write(String key, byte[] content) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("dbbackup-write-", ".tmp");
            Files.write(tmp, content);
            upload(tmp, key);
        } catch (IOException e) {
            throw new StorageException("Cannot write " + location(key) + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    @Override
    default void close() {
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // temporary file
            }
        }
    }
}
