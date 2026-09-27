/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.config;

/**
 * Amazon S3 or any S3-compatible object storage (MinIO, Ceph, Wasabi, DigitalOcean Spaces,
 * Backblaze B2, Cloudflare R2...).
 *
 * @param bucket          bucket name
 * @param prefix          key prefix ("folder") inside the bucket
 * @param region          AWS region; falls back to the SDK default region chain
 * @param endpoint        custom endpoint URL for S3-compatible services
 * @param pathStyle       use path-style addressing (needed by most S3-compatible services)
 * @param accessKeyId     static access key; the default AWS credential chain is used when unset
 * @param secretAccessKey static secret key
 * @param sessionToken    optional session token for temporary credentials
 * @param profile         named profile of {@code ~/.aws/credentials}
 * @param storageClass    storage class of uploaded objects, e.g. {@code STANDARD_IA}
 * @param retention       optional retention override for this target
 */
public record S3StorageConfig(
        String bucket,
        String prefix,
        String region,
        String endpoint,
        Boolean pathStyle,
        String accessKeyId,
        String secretAccessKey,
        String sessionToken,
        String profile,
        String storageClass,
        RetentionConfig retention) implements StorageConfig {

    @Override
    public String typeId() {
        return "s3";
    }
}
