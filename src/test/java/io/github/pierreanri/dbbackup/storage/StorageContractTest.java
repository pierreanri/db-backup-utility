/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behaviour every storage backend must have, run against real services or local emulators
 * (MinIO/moto, fake-gcs-server, Azurite) by the subclasses.
 */
@Tag("integration")
abstract class StorageContractTest {

    @TempDir
    Path tmp;

    protected String prefix;
    private StorageBackend storage;

    /** Creates a backend whose keys live under {@code prefix}. */
    protected abstract StorageBackend create(String prefix);

    @BeforeEach
    void setUp() {
        prefix = "it-" + UUID.randomUUID().toString().substring(0, 8);
        storage = create(prefix);
    }

    @AfterEach
    void tearDown() {
        for (StoredObject object : storage.list("")) {
            storage.delete(object.key());
        }
        storage.close();
    }

    @Test
    void roundTripsFilesAndMetadata() throws IOException {
        byte[] content = randomBytes(300_000);
        Path file = Files.write(tmp.resolve("backup.sql.gz"), content);

        storage.upload(file, "app/app-1.sql.gz");
        storage.write("app/app-1.manifest.json", "{\"id\":1}".getBytes(StandardCharsets.UTF_8));
        storage.upload(file, "other/other-1.sql.gz");

        assertThat(storage.exists("app/app-1.sql.gz")).isTrue();
        assertThat(storage.exists("app/nope")).isFalse();
        assertThat(storage.list("app/")).extracting(StoredObject::key)
                .containsExactlyInAnyOrder("app/app-1.sql.gz", "app/app-1.manifest.json");
        assertThat(storage.list("app/")).filteredOn(o -> o.key().endsWith(".gz")).singleElement()
                .satisfies(o -> {
                    assertThat(o.size()).isEqualTo(content.length);
                    assertThat(o.lastModified()).isNotNull();
                });
        assertThat(new String(storage.read("app/app-1.manifest.json"), StandardCharsets.UTF_8)).isEqualTo("{\"id\":1}");

        Path downloaded = tmp.resolve("downloaded");
        storage.download("app/app-1.sql.gz", downloaded);
        assertThat(Files.readAllBytes(downloaded)).isEqualTo(content);

        storage.delete("app/app-1.sql.gz");
        assertThat(storage.exists("app/app-1.sql.gz")).isFalse();
        assertThat(storage.list("app/")).extracting(StoredObject::key).containsExactly("app/app-1.manifest.json");
    }

    @Test
    void handlesLargeFiles() throws IOException {
        byte[] content = randomBytes(12 * 1024 * 1024 + 123);
        Path file = Files.write(tmp.resolve("big.dump"), content);

        storage.upload(file, "db/big.dump");
        Path downloaded = tmp.resolve("big.out");
        storage.download("db/big.dump", downloaded);

        assertThat(Files.size(downloaded)).isEqualTo(content.length);
        assertThat(Files.readAllBytes(downloaded)).isEqualTo(content);
    }

    @Test
    void reportsMissingObjects() {
        assertThatThrownBy(() -> storage.download("missing/key", tmp.resolve("x")))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.read("missing/key"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Not found");
        storage.delete("missing/key");
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
