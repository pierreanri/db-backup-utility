/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.pierreanri.dbbackup.config.S3StorageConfig;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

/**
 * Runs the storage contract against an S3-compatible endpoint (MinIO, moto...). Enabled when
 * {@code DBBACKUP_IT_S3_ENDPOINT} is set; see the README for the other variables.
 */
@EnabledIfEnvironmentVariable(named = "DBBACKUP_IT_S3_ENDPOINT", matches = ".+")
class S3StorageIntegrationTest extends StorageContractTest {

    @Override
    protected StorageBackend create(String prefix) {
        S3StorageConfig config = new S3StorageConfig(
                env("DBBACKUP_IT_S3_BUCKET", "dbbackup-it"), prefix, env("DBBACKUP_IT_S3_REGION", "us-east-1"),
                System.getenv("DBBACKUP_IT_S3_ENDPOINT"), true,
                env("DBBACKUP_IT_S3_ACCESS_KEY", "test"), env("DBBACKUP_IT_S3_SECRET_KEY", "test"),
                null, null, null, null);
        try (S3Client client = S3Storage.buildClient(config)) {
            client.createBucket(b -> b.bucket(config.bucket()));
        } catch (BucketAlreadyOwnedByYouException ignored) {
            // bucket exists
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() != 409) {
                throw e;
            }
        }
        // low threshold so that the large file test exercises multipart uploads (5 MiB is the S3 minimum)
        return new S3Storage("s3", config, () -> S3Storage.buildClient(config), 5L * 1024 * 1024, 5L * 1024 * 1024);
    }
}
