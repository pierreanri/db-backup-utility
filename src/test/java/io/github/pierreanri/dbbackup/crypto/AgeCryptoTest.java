/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.EncryptionConfig;

class AgeCryptoTest {

    @TempDir
    Path tmp;

    private Path randomFile(int size) throws IOException {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return Files.write(tmp.resolve("plain-" + size + ".bin"), bytes);
    }

    @Test
    void roundTripsWithSeveralRecipients() throws IOException {
        String[] alice = AgeCrypto.generateKeyPair();
        String[] bob = AgeCrypto.generateKeyPair();
        assertThat(alice[0]).startsWith("age1");
        assertThat(alice[1]).startsWith("AGE-SECRET-KEY-1");
        Path plain = randomFile(3 * 1024 * 1024 + 17);
        Path encrypted = tmp.resolve("x.age");

        AgeCrypto.encrypt(plain, encrypted, new EncryptionConfig(List.of(alice[0], bob[0]), null, null, null, null));
        assertThat(AgeCrypto.isEncrypted(encrypted)).isTrue();
        assertThat(AgeCrypto.isEncrypted(plain)).isFalse();

        for (String identity : List.of(alice[1], bob[1])) {
            Path out = tmp.resolve("out");
            AgeCrypto.decrypt(encrypted, out, List.of(identity), null);
            assertThat(Files.readAllBytes(out)).isEqualTo(Files.readAllBytes(plain));
        }
    }

    @Test
    void roundTripsWithAPassphrase() throws IOException {
        Path plain = randomFile(1000);
        Path encrypted = tmp.resolve("x.age");
        AgeCrypto.encrypt(plain, encrypted, new EncryptionConfig(null, null, "s3cret phrase", null, 10));

        Path out = tmp.resolve("out");
        AgeCrypto.decrypt(encrypted, out, List.of(), "s3cret phrase");
        assertThat(Files.readAllBytes(out)).isEqualTo(Files.readAllBytes(plain));

        assertThatThrownBy(() -> AgeCrypto.decrypt(encrypted, tmp.resolve("bad"), List.of(), "wrong"))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("wrong key or passphrase");
    }

    @Test
    void readsKeyFilesAndRejectsBadKeys() throws IOException {
        String[] pair = AgeCrypto.generateKeyPair();
        Path identity = Files.writeString(tmp.resolve("key.txt"), "# created: now\n# public key: " + pair[0]
                + "\n" + pair[1] + "\n");
        Path recipients = Files.writeString(tmp.resolve("recipients.txt"), "# team\n" + pair[0] + "\n\n");

        assertThat(AgeCrypto.readIdentities(identity)).containsExactly(pair[1]);
        assertThat(AgeCrypto.readRecipients(recipients)).containsExactly(pair[0]);
        assertThat(AgeCrypto.identities(new EncryptionConfig(null, null, null, List.of(identity.toString()), null),
                List.of(identity))).hasSize(2);

        Path plain = randomFile(10);
        AgeCrypto.encrypt(plain, tmp.resolve("r.age"),
                new EncryptionConfig(null, recipients.toString(), null, null, null));
        AgeCrypto.decrypt(tmp.resolve("r.age"), tmp.resolve("r.out"), List.of(pair[1]), null);

        assertThatThrownBy(() -> AgeCrypto.validateRecipient("age1notakey"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgeCrypto.readIdentities(recipients))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("No age identity found");
        assertThatThrownBy(() -> AgeCrypto.decrypt(tmp.resolve("r.age"), tmp.resolve("x"), List.of(), null))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("is encrypted");
    }

    static boolean ageInstalled() {
        try {
            return new ProcessBuilder("age", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    @EnabledIf("ageInstalled")
    void interoperatesWithTheAgeCommandLineTool() throws Exception {
        String[] pair = AgeCrypto.generateKeyPair();
        Path identity = Files.writeString(tmp.resolve("key.txt"), pair[1] + "\n");
        Path plain = Files.writeString(tmp.resolve("plain.txt"), "hello from dbbackup");

        AgeCrypto.encrypt(plain, tmp.resolve("ours.age"), new EncryptionConfig(List.of(pair[0]), null, null, null, null));
        Process decrypt = new ProcessBuilder("age", "-d", "-i", identity.toString(), "-o",
                tmp.resolve("by-age.txt").toString(), tmp.resolve("ours.age").toString()).inheritIO().start();
        assertThat(decrypt.waitFor()).isZero();
        assertThat(tmp.resolve("by-age.txt")).hasContent("hello from dbbackup");

        Process encrypt = new ProcessBuilder("age", "-r", pair[0], "-o", tmp.resolve("theirs.age").toString(),
                plain.toString()).inheritIO().start();
        assertThat(encrypt.waitFor()).isZero();
        AgeCrypto.decrypt(tmp.resolve("theirs.age"), tmp.resolve("by-us.txt"), List.of(pair[1]), null);
        assertThat(Files.readString(tmp.resolve("by-us.txt"), StandardCharsets.UTF_8)).isEqualTo("hello from dbbackup");
    }
}
