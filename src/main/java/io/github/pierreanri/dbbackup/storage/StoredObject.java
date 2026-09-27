/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import java.time.Instant;

/**
 * An object (file) held by a storage backend.
 *
 * @param key          key relative to the backend's root/prefix, using {@code /} as separator
 * @param size         size in bytes
 * @param lastModified last modification time, may be {@code null}
 */
public record StoredObject(String key, long size, Instant lastModified) {
}
