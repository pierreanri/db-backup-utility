/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretsTest {

    @Test
    void masksUriPasswords() {
        assertThat(Secrets.maskUri("mongodb://admin:hunter2@db:27017/app?authSource=admin"))
                .isEqualTo("mongodb://admin:****@db:27017/app?authSource=admin");
        assertThat(Secrets.maskUri("mongodb+srv://cluster0.example.net/app")).isEqualTo("mongodb+srv://cluster0.example.net/app");
        assertThat(Secrets.maskUri("https://acct.blob.core.windows.net/c?sv=1&sig=abcdef"))
                .isEqualTo("https://acct.blob.core.windows.net/c?sv=1&sig=****");
    }

    @Test
    void redactsSecretsInText() {
        assertThat(Secrets.redact("auth failed for password hunter2", "hunter2", null)).isEqualTo("auth failed for password ****");
    }
}
