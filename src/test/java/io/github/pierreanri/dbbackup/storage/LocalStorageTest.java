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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStorageTest {

    @TempDir
    Path tmp;

    @Test
    void uploadsListsDownloadsAndDeletes() throws IOException {
        LocalStorage storage = new LocalStorage("local", tmp.resolve("backups"));
        Path source = Files.writeString(tmp.resolve("src.sql.gz"), "dump");

        storage.upload(source, "app/app-1.sql.gz");
        storage.write("app/app-1.manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
        storage.upload(source, "other/other-1.sql.gz");

        assertThat(storage.exists("app/app-1.sql.gz")).isTrue();
        assertThat(storage.exists("app/missing")).isFalse();
        assertThat(storage.list("app/")).extracting(StoredObject::key)
                .containsExactly("app/app-1.manifest.json", "app/app-1.sql.gz");
        assertThat(storage.list("")).hasSize(3);
        assertThat(storage.list("app/").get(1).size()).isEqualTo(4);
        assertThat(storage.location("app/app-1.sql.gz")).isEqualTo(tmp.resolve("backups/app/app-1.sql.gz").toString());

        Path downloaded = tmp.resolve("dl");
        storage.download("app/app-1.sql.gz", downloaded);
        assertThat(downloaded).hasContent("dump");
        assertThat(new String(storage.read("app/app-1.manifest.json"), StandardCharsets.UTF_8)).isEqualTo("{}");

        storage.delete("other/other-1.sql.gz");
        assertThat(tmp.resolve("backups/other")).doesNotExist();
        assertThat(tmp.resolve("backups")).exists();
        storage.delete("other/does-not-exist");
    }

    @Test
    void listingAMissingDirectoryIsEmpty() {
        assertThat(new LocalStorage("x", tmp.resolve("nothing")).list("")).isEmpty();
    }

    @Test
    void ignoresPartialUploads() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("b/app"));
        Files.writeString(root.resolve(".app-1.sql.part"), "partial");
        assertThat(new LocalStorage("x", tmp.resolve("b")).list("")).isEmpty();
    }

    @Test
    void rejectsKeysOutsideTheRoot() {
        LocalStorage storage = new LocalStorage("local", tmp.resolve("backups"));
        assertThatThrownBy(() -> storage.exists("../secret")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.exists("/etc/passwd")).isInstanceOf(StorageException.class);
    }

    @Test
    void reportsMissingObjects() {
        LocalStorage storage = new LocalStorage("local", tmp);
        assertThatThrownBy(() -> storage.download("nope", tmp.resolve("x")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Not found");
    }

    @Test
    void normalizesPrefixes() {
        assertThat(Keys.normalizePrefix(null)).isEmpty();
        assertThat(Keys.normalizePrefix("/")).isEmpty();
        assertThat(Keys.normalizePrefix("/prod/db/")).isEqualTo("prod/db/");
        assertThat(Keys.normalizePrefix("prod")).isEqualTo("prod/");
    }
}
