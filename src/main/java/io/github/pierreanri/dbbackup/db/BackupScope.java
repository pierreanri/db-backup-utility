/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a backup contains. All backups are full logical backups; the scope only narrows whether
 * the schema, the data or both are dumped.
 */
public enum BackupScope {
    FULL("full"),
    SCHEMA_ONLY("schema-only"),
    DATA_ONLY("data-only");

    private final String id;

    BackupScope(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static BackupScope fromString(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (BackupScope scope : values()) {
            if (scope.id.equals(normalized)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("unsupported backup scope '" + value + "' (supported: full, schema-only, data-only)");
    }

    @Override
    public String toString() {
        return id;
    }
}
