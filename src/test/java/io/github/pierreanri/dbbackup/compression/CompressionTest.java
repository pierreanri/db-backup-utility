/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.compression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.pierreanri.dbbackup.DbBackupException;

class CompressionTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @EnumSource(Compression.class)
    void roundTrips(Compression compression) throws IOException {
        String content = "INSERT INTO t VALUES (1, 'hello');\n".repeat(20_000);
        Path source = Files.writeString(tmp.resolve("dump.sql"), content);
        Path compressed = tmp.resolve("dump.sql" + compression.extension() + ".out");
        Path restored = tmp.resolve("restored.sql");

        String sha = Compressor.compress(source, compressed, compression);
        Compressor.decompress(compressed, restored, compression);

        assertThat(sha).isEqualTo(Checksums.sha256(compressed)).hasSize(64);
        assertThat(Files.readString(restored)).isEqualTo(content);
        if (compression != Compression.NONE) {
            assertThat(Files.size(compressed)).isLessThan(Files.size(source) / 10);
        }
    }

    @Test
    void parsesNamesAndAliases() {
        assertThat(Compression.fromName("gz")).isEqualTo(Compression.GZIP);
        assertThat(Compression.fromName("BZIP2")).isEqualTo(Compression.BZIP2);
        assertThat(Compression.fromName("xz")).isEqualTo(Compression.XZ);
        assertThat(Compression.fromName("none")).isEqualTo(Compression.NONE);
        assertThat(Compression.fromName(null)).isEqualTo(Compression.GZIP);
        assertThatThrownBy(() -> Compression.fromName("zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported: none, gzip, bzip2, xz");
    }

    @Test
    void detectsCompressionFromFileName() {
        assertThat(Compression.fromFileName("app-20260101T000000Z.dump.gz")).isEqualTo(Compression.GZIP);
        assertThat(Compression.fromFileName("x.sql.BZ2")).isEqualTo(Compression.BZIP2);
        assertThat(Compression.fromFileName("x.archive.xz")).isEqualTo(Compression.XZ);
        assertThat(Compression.fromFileName("x.db")).isEqualTo(Compression.NONE);
        assertThat(Compression.GZIP.stripExtension("x.sql.gz")).isEqualTo("x.sql");
        assertThat(Compression.NONE.stripExtension("x.sql")).isEqualTo("x.sql");
    }

    @Test
    void knownSha256() throws IOException {
        Path file = Files.write(tmp.resolve("abc"), "abc".getBytes(StandardCharsets.US_ASCII));
        assertThat(Checksums.sha256(file)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void reportsCorruptInput() throws IOException {
        Path garbage = Files.writeString(tmp.resolve("bad.gz"), "not gzip at all");
        assertThatThrownBy(() -> Compressor.decompress(garbage, tmp.resolve("out"), Compression.GZIP))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("Cannot decompress bad.gz");
    }
}
