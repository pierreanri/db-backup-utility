package io.github.pierreanri.dbbackup.core;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.storage.StorageBackend;
import io.github.pierreanri.dbbackup.storage.StorageFactory;

/**
 * Creates storage backends on demand and closes them at the end of the command.
 */
public class StorageRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StorageRegistry.class);

    private final AppConfig config;
    private final StorageFactory factory;
    private final Map<String, StorageBackend> backends = new LinkedHashMap<>();

    public StorageRegistry(AppConfig config, StorageFactory factory) {
        this.config = config;
        this.factory = factory;
    }

    public synchronized StorageBackend get(String name) {
        return backends.computeIfAbsent(name, n -> factory.create(n, config.storage(n)));
    }

    /** Registers a backend that is not part of the configuration (e.g. {@code --output-dir}). */
    public synchronized void register(StorageBackend backend) {
        backends.put(backend.name(), backend);
    }

    @Override
    public synchronized void close() {
        for (StorageBackend backend : backends.values()) {
            try {
                backend.close();
            } catch (RuntimeException e) {
                LOG.debug("Error closing storage {}: {}", backend.name(), e.getMessage());
            }
        }
        backends.clear();
    }
}
