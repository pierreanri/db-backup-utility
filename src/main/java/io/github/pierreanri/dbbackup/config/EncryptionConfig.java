/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.config;

import java.util.List;

/**
 * Encryption of backup files with <a href="https://age-encryption.org">age</a>. Backups are
 * encrypted when {@code recipients}, {@code recipientsFile} or {@code passphrase} is set.
 *
 * @param recipients        age public keys ({@code age1...}) backups are encrypted to
 * @param recipientsFile    file listing age public keys, one per line
 * @param passphrase        passphrase (scrypt) used instead of public keys
 * @param identityFiles     files holding the private keys ({@code AGE-SECRET-KEY-1...}) used to
 *                          decrypt backups when restoring
 * @param scryptWorkFactor  log2 of the scrypt work factor for passphrases (default 16)
 */
public record EncryptionConfig(
        List<String> recipients,
        String recipientsFile,
        String passphrase,
        List<String> identityFiles,
        Integer scryptWorkFactor) {

    public static final EncryptionConfig NONE = new EncryptionConfig(null, null, null, null, null);
    public static final int DEFAULT_SCRYPT_WORK_FACTOR = 16;

    public EncryptionConfig {
        recipients = recipients == null ? List.of()
                : recipients.stream().filter(r -> r != null && !r.isBlank()).map(String::trim).toList();
        identityFiles = identityFiles == null ? List.of()
                : identityFiles.stream().filter(f -> f != null && !f.isBlank()).toList();
    }

    /** Whether new backups are encrypted. */
    public boolean enabled() {
        return !recipients.isEmpty() || notBlank(recipientsFile) || notBlank(passphrase);
    }

    public boolean usesPassphrase() {
        return notBlank(passphrase);
    }

    public int effectiveScryptWorkFactor() {
        return scryptWorkFactor == null ? DEFAULT_SCRYPT_WORK_FACTOR : scryptWorkFactor;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
