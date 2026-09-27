/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;

import io.github.pierreanri.dbbackup.config.GcsStorageConfig;

class GcsStorageTest {

    @TempDir
    Path tmp;

    private final Storage client = mock(Storage.class);
    private final GcsStorage storage = new GcsStorage("gcs",
            new GcsStorageConfig("my-bucket", "backups/prod", null, null, null, null), () -> client);

    @Test
    void uploadsWithPrefixedName() throws IOException {
        Path file = Files.writeString(tmp.resolve("f"), "data");
        storage.upload(file, "app/app-1.sql.gz");

        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(client).createFrom(info.capture(), eq(file));
        assertThat(info.getValue().getBucket()).isEqualTo("my-bucket");
        assertThat(info.getValue().getName()).isEqualTo("backups/prod/app/app-1.sql.gz");
        assertThat(storage.location("app/app-1.sql.gz")).isEqualTo("gs://my-bucket/backups/prod/app/app-1.sql.gz");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsBlobsRelativeToPrefix() {
        OffsetDateTime updated = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC);
        Blob blob = mock(Blob.class);
        when(blob.getName()).thenReturn("backups/prod/app/a.gz");
        when(blob.getSize()).thenReturn(42L);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(updated);
        Page<Blob> page = mock(Page.class);
        when(page.iterateAll()).thenReturn(List.of(blob));
        when(client.list(eq("my-bucket"), any(Storage.BlobListOption[].class))).thenReturn(page);

        assertThat(storage.list("app/"))
                .containsExactly(new StoredObject("app/a.gz", 42, Instant.parse("2026-03-04T05:06:07Z")));
    }

    @Test
    void downloadsReadsDeletesAndChecksExistence() {
        BlobId id = BlobId.of("my-bucket", "backups/prod/k");
        when(client.readAllBytes(id)).thenReturn(new byte[] {1, 2});
        when(client.get(id)).thenReturn(null);

        storage.download("k", tmp.resolve("out"));
        verify(client).downloadTo(id, tmp.resolve("out"));
        assertThat(storage.read("k")).containsExactly(1, 2);
        assertThat(storage.exists("k")).isFalse();
        storage.delete("k");
        verify(client).delete(id);
    }

    @Test
    void mapsMissingObjects() {
        BlobId id = BlobId.of("my-bucket", "backups/prod/missing");
        when(client.readAllBytes(id)).thenThrow(new com.google.cloud.storage.StorageException(404, "No such object"));
        assertThatThrownBy(() -> storage.read("missing"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Not found: gs://my-bucket/backups/prod/missing");
    }

    @Test
    void buildsClientForEmulatorWithoutCredentials() throws Exception {
        Storage built = GcsStorage.buildClient(
                new GcsStorageConfig("b", null, "test-project", null, "http://localhost:4443", null));
        try {
            assertThat(built.getOptions().getHost()).isEqualTo("http://localhost:4443");
        } finally {
            built.close();
        }
    }
}
