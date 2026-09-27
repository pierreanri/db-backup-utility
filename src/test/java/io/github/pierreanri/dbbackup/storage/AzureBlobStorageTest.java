/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.ListBlobsOptions;

import io.github.pierreanri.dbbackup.config.AzureStorageConfig;

class AzureBlobStorageTest {

    @TempDir
    Path tmp;

    private final BlobContainerClient container = mock(BlobContainerClient.class);
    private final BlobClient blob = mock(BlobClient.class);
    private final AzureBlobStorage storage = new AzureBlobStorage("azure",
            new AzureStorageConfig("backups", "prod", null, "acct", "key", null, null, null), () -> container);

    @Test
    void uploadsAndDownloadsThroughBlobClient() {
        when(container.getBlobClient("prod/app/a.gz")).thenReturn(blob);
        Path file = tmp.resolve("a.gz");

        storage.upload(file, "app/a.gz");
        storage.download("app/a.gz", tmp.resolve("out"));
        storage.delete("app/a.gz");

        verify(blob).uploadFromFile(file.toString(), true);
        verify(blob).downloadToFile(tmp.resolve("out").toString(), true);
        verify(blob).deleteIfExists();
        assertThat(storage.location("app/a.gz")).isEqualTo("azure://acct/backups/prod/app/a.gz");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsBlobsRelativeToPrefix() {
        BlobItem item = new BlobItem().setName("prod/app/a.gz").setProperties(new BlobItemProperties()
                .setContentLength(7L).setLastModified(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
        PagedIterable<BlobItem> page = mock(PagedIterable.class);
        when(page.iterator()).thenReturn(List.of(item).iterator());
        when(container.listBlobs(any(ListBlobsOptions.class), isNull())).thenReturn(page);

        assertThat(storage.list("app/"))
                .containsExactly(new StoredObject("app/a.gz", 7, Instant.parse("2026-01-01T00:00:00Z")));

        ArgumentCaptor<ListBlobsOptions> options = ArgumentCaptor.forClass(ListBlobsOptions.class);
        verify(container).listBlobs(options.capture(), isNull());
        assertThat(options.getValue().getPrefix()).isEqualTo("prod/app/");
    }

    @Test
    void wrapsErrors() {
        when(container.getBlobClient(eq("prod/x"))).thenReturn(blob);
        when(blob.exists()).thenThrow(new IllegalStateException("network down"));
        assertThatThrownBy(() -> storage.exists("x"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("network down");
    }

    @Test
    void buildsClientFromConnectionStringWithoutNetworkAccess() {
        AzureStorageConfig config = new AzureStorageConfig("c", null, "DefaultEndpointsProtocol=http;"
                + "AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq"
                + "/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;", null, null, null,
                null, null);
        BlobContainerClient built = AzureBlobStorage.buildClient(config);
        assertThat(built.getBlobContainerName()).isEqualTo("c");
        assertThat(built.getBlobContainerUrl()).isEqualTo("http://127.0.0.1:10000/devstoreaccount1/c");
    }
}
