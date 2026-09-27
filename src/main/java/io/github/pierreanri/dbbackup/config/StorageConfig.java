/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Settings of one storage target ({@code storage.<name>} in the config file). The {@code type}
 * property selects the implementation.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LocalStorageConfig.class, names = {"local", "filesystem"}),
    @JsonSubTypes.Type(value = S3StorageConfig.class, names = {"s3", "aws", "minio"}),
    @JsonSubTypes.Type(value = GcsStorageConfig.class, names = {"gcs", "google", "google-cloud-storage"}),
    @JsonSubTypes.Type(value = AzureStorageConfig.class, names = {"azure", "azure-blob"})
})
public sealed interface StorageConfig
        permits LocalStorageConfig, S3StorageConfig, GcsStorageConfig, AzureStorageConfig {

    /** Short type identifier, e.g. {@code s3}. */
    String typeId();

    /** Optional retention override for this target. */
    RetentionConfig retention();
}
