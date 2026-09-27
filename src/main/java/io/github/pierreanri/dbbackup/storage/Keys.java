/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

/**
 * Helpers for object keys and prefixes.
 */
final class Keys {

    private Keys() {
    }

    /** Normalizes a configured prefix to either {@code ""} or {@code "some/prefix/"}. */
    static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String trimmed = prefix.replace('\\', '/').trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }

    /** Validates a relative key: no leading slash, no {@code ..} segment. */
    static String check(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("Empty storage key");
        }
        String normalized = key.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new StorageException("Storage keys must be relative: " + key);
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new StorageException("Storage keys must not contain '..': " + key);
            }
        }
        return normalized;
    }
}
