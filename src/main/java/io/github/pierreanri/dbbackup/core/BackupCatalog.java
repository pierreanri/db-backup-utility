/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.storage.StorageBackend;
import io.github.pierreanri.dbbackup.storage.StoredObject;
import io.github.pierreanri.dbbackup.util.Mappers;

/**
 * Lists and locates the backups held by a storage backend by reading their manifests.
 */
public class BackupCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(BackupCatalog.class);

    private final StorageBackend storage;

    public BackupCatalog(StorageBackend storage) {
        this.storage = storage;
    }

    /** Backups of {@code database} (all databases when {@code null}), newest first. */
    public List<BackupManifest> list(String database) {
        String prefix = database == null ? "" : database + "/";
        List<BackupManifest> manifests = new ArrayList<>();
        for (StoredObject object : storage.list(prefix)) {
            if (!object.key().endsWith(BackupNaming.MANIFEST_SUFFIX)) {
                continue;
            }
            try {
                manifests.add(Mappers.json().readValue(storage.read(object.key()), BackupManifest.class));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Ignoring unreadable manifest {}: {}", storage.location(object.key()), e.getMessage());
            }
        }
        manifests.sort(Comparator.comparing(BackupManifest::createdAt).reversed());
        return manifests;
    }

    /**
     * Finds a backup by id. {@code latest} (with {@code database} set) returns the most recent
     * backup of that database.
     */
    public Optional<BackupManifest> find(String id, String database) {
        if ("latest".equalsIgnoreCase(id)) {
            if (database == null) {
                throw new DbBackupException("'latest' needs a database: use --db");
            }
            return list(database).stream().findFirst();
        }
        return list(database).stream().filter(m -> m.id().equals(id)).findFirst()
                .or(() -> database == null ? Optional.empty()
                        : list(null).stream().filter(m -> m.id().equals(id)).findFirst());
    }

    /**
     * Deletes a backup. The manifest goes first so that an interrupted deletion never leaves a
     * listed backup without its file.
     */
    public void delete(BackupManifest manifest) {
        storage.delete(manifest.manifestKey());
        storage.delete(manifest.backupKey());
        if (manifest.stateKey() != null) {
            storage.delete(manifest.stateKey());
        }
    }

    /**
     * The backups needed to restore {@code target}, oldest (the full backup) first.
     *
     * @throws DbBackupException when a backup of the chain is missing
     */
    public List<BackupManifest> chain(BackupManifest target) {
        return resolveChain(target, list(target.database()), storage.name());
    }

    static List<BackupManifest> resolveChain(BackupManifest target, List<BackupManifest> candidates, String where) {
        java.util.Map<String, BackupManifest> byId = new java.util.HashMap<>();
        candidates.forEach(m -> byId.put(m.id(), m));
        java.util.LinkedList<BackupManifest> chain = new java.util.LinkedList<>();
        BackupManifest current = target;
        while (true) {
            chain.addFirst(current);
            if (current.isFull()) {
                return chain;
            }
            if (chain.size() > 10_000 || current.parentId() == null) {
                throw new DbBackupException("Backup " + current.id() + " has an invalid chain");
            }
            BackupManifest parent = byId.get(current.parentId());
            if (parent == null) {
                throw new DbBackupException("Backup " + target.id() + " depends on " + current.parentId()
                        + ", which is missing from " + where + ": it cannot be restored");
            }
            current = parent;
        }
    }

    public StorageBackend storage() {
        return storage;
    }
}
