/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.pierreanri.dbbackup.config.AzureStorageConfig;

/**
 * Runs the storage contract against Azure Blob Storage or Azurite. Enabled when
 * {@code DBBACKUP_IT_AZURE_CONNECTION_STRING} is set.
 */
@EnabledIfEnvironmentVariable(named = "DBBACKUP_IT_AZURE_CONNECTION_STRING", matches = ".+")
class AzureBlobStorageIntegrationTest extends StorageContractTest {

    @Override
    protected StorageBackend create(String prefix) {
        AzureStorageConfig config = new AzureStorageConfig(env("DBBACKUP_IT_AZURE_CONTAINER", "dbbackup-it"), prefix,
                System.getenv("DBBACKUP_IT_AZURE_CONNECTION_STRING"), null, null, null, null, null);
        AzureBlobStorage.buildClient(config).createIfNotExists();
        return new AzureBlobStorage("azure", config);
    }
}
