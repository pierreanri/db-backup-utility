package io.github.pierreanri.dbbackup.config;

/**
 * Google Cloud Storage.
 *
 * @param bucket          bucket name
 * @param prefix          object name prefix inside the bucket
 * @param projectId       Google Cloud project id; detected from the credentials when unset
 * @param credentialsFile service account JSON key; Application Default Credentials when unset
 * @param endpoint        custom endpoint, e.g. a local fake-gcs-server for testing
 * @param retention       optional retention override for this target
 */
public record GcsStorageConfig(
        String bucket,
        String prefix,
        String projectId,
        String credentialsFile,
        String endpoint,
        RetentionConfig retention) implements StorageConfig {

    @Override
    public String typeId() {
        return "gcs";
    }
}
