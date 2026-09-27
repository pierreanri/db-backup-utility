package io.github.pierreanri.dbbackup.storage;

import io.github.pierreanri.dbbackup.config.AzureStorageConfig;
import io.github.pierreanri.dbbackup.config.GcsStorageConfig;
import io.github.pierreanri.dbbackup.config.LocalStorageConfig;
import io.github.pierreanri.dbbackup.config.S3StorageConfig;
import io.github.pierreanri.dbbackup.config.StorageConfig;
import io.github.pierreanri.dbbackup.util.PathUtils;

/**
 * Creates storage backends from their configuration. Cloud clients are only created on first use.
 */
public class StorageFactory {

    public StorageBackend create(String name, StorageConfig config) {
        return switch (config) {
            case LocalStorageConfig local -> new LocalStorage(name, PathUtils.expand(local.path()));
            case S3StorageConfig s3 -> new S3Storage(name, s3);
            case GcsStorageConfig gcs -> new GcsStorage(name, gcs);
            case AzureStorageConfig azure -> new AzureBlobStorage(name, azure);
        };
    }
}
