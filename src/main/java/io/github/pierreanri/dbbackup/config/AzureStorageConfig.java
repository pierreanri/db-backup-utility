package io.github.pierreanri.dbbackup.config;

/**
 * Azure Blob Storage. Authenticate with a connection string, an account key or a SAS token.
 *
 * @param container        blob container name
 * @param prefix           blob name prefix inside the container
 * @param connectionString full storage account connection string
 * @param accountName      storage account name (used with {@code accountKey} or {@code sasToken})
 * @param accountKey       storage account key
 * @param sasToken         shared access signature token
 * @param endpoint         custom blob endpoint, e.g. Azurite; defaults to
 *                         {@code https://<accountName>.blob.core.windows.net}
 * @param retention        optional retention override for this target
 */
public record AzureStorageConfig(
        String container,
        String prefix,
        String connectionString,
        String accountName,
        String accountKey,
        String sasToken,
        String endpoint,
        RetentionConfig retention) implements StorageConfig {

    @Override
    public String typeId() {
        return "azure";
    }
}
