/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Kind of backup in a backup chain.
 */
public enum BackupType {
    /** A complete, self-contained backup; the root of a chain. */
    FULL("full", "full"),
    /** The changes since the previous backup of the chain (full, incremental or differential). */
    INCREMENTAL("incremental", "incr"),
    /** The changes since the full backup of the chain. */
    DIFFERENTIAL("differential", "diff");

    private final String id;
    private final String shortName;

    BackupType(String id, String shortName) {
        this.id = id;
        this.shortName = shortName;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public String shortName() {
        return shortName;
    }

    @JsonCreator
    public static BackupType fromString(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BackupType type : values()) {
            if (type.id.equals(normalized) || type.shortName.equals(normalized)
                    || (type == INCREMENTAL && normalized.equals("inc"))) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported backup type '" + value
                + "' (supported: full, incremental, differential)");
    }

    @Override
    public String toString() {
        return id;
    }
}
