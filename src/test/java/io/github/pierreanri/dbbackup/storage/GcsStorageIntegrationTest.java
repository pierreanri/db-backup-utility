package io.github.pierreanri.dbbackup.storage;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;

import io.github.pierreanri.dbbackup.config.GcsStorageConfig;

/**
 * Runs the storage contract against Google Cloud Storage or fake-gcs-server. Enabled when
 * {@code DBBACKUP_IT_GCS_ENDPOINT} is set.
 */
@EnabledIfEnvironmentVariable(named = "DBBACKUP_IT_GCS_ENDPOINT", matches = ".+")
class GcsStorageIntegrationTest extends StorageContractTest {

    @Override
    protected StorageBackend create(String prefix) {
        GcsStorageConfig config = new GcsStorageConfig(env("DBBACKUP_IT_GCS_BUCKET", "dbbackup-it"), prefix,
                env("DBBACKUP_IT_GCS_PROJECT", "test-project"), System.getenv("DBBACKUP_IT_GCS_CREDENTIALS"),
                System.getenv("DBBACKUP_IT_GCS_ENDPOINT"), null);
        Storage client = GcsStorage.buildClient(config);
        try {
            if (client.get(config.bucket()) == null) {
                client.create(BucketInfo.of(config.bucket()));
            }
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
                // test setup only
            }
        }
        return new GcsStorage("gcs", config);
    }
}
