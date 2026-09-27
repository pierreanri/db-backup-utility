/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.exceptionfactory.jagged.RecipientStanzaReader;
import com.exceptionfactory.jagged.RecipientStanzaWriter;
import com.exceptionfactory.jagged.framework.stream.StandardDecryptingChannelFactory;
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory;
import com.exceptionfactory.jagged.scrypt.ScryptRecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.scrypt.ScryptRecipientStanzaWriterFactory;
import com.exceptionfactory.jagged.x25519.X25519KeyPairGenerator;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory;
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.EncryptionConfig;
import io.github.pierreanri.dbbackup.util.PathUtils;

/**
 * Streams files through <a href="https://age-encryption.org/v1">age</a> encryption. Files
 * produced here can be decrypted with the standard {@code age} tool and vice versa.
 */
public final class AgeCrypto {

    /** Suffix of encrypted files. */
    public static final String EXTENSION = ".age";
    /** Value of {@code encryption} in backup manifests. */
    public static final String ALGORITHM = "age";

    private static final byte[] MAGIC = "age-encryption.org/v1".getBytes(StandardCharsets.US_ASCII);
    private static final int BUFFER = 256 * 1024;

    private AgeCrypto() {
    }

    /** Encrypts {@code source} into {@code target} for the recipients (or passphrase) of the configuration. */
    public static void encrypt(Path source, Path target, EncryptionConfig config) {
        List<RecipientStanzaWriter> writers = recipientWriters(config);
        try (ReadableByteChannel in = FileChannel.open(source, StandardOpenOption.READ);
                WritableByteChannel file = FileChannel.open(target, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                WritableByteChannel out = new StandardEncryptingChannelFactory().newEncryptingChannel(file, writers)) {
            copy(in, out);
        } catch (IOException | GeneralSecurityException e) {
            throw new DbBackupException("Encryption of " + source.getFileName() + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts {@code source} into {@code target}.
     *
     * @param identities age private keys ({@code AGE-SECRET-KEY-1...})
     * @param passphrase passphrase, may be {@code null}
     */
    public static void decrypt(Path source, Path target, List<String> identities, String passphrase) {
        List<RecipientStanzaReader> readers = new ArrayList<>();
        try {
            for (String identity : identities) {
                readers.add(X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(identity));
            }
            if (passphrase != null && !passphrase.isEmpty()) {
                readers.add(ScryptRecipientStanzaReaderFactory.newRecipientStanzaReader(
                        passphrase.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DbBackupException("Invalid age identity: " + e.getMessage(), e);
        }
        if (readers.isEmpty()) {
            throw new DbBackupException(source.getFileName() + " is encrypted: configure encryption.identityFiles "
                    + "(or encryption.passphrase), or pass --identity FILE");
        }
        try (ReadableByteChannel file = FileChannel.open(source, StandardOpenOption.READ);
                ReadableByteChannel in = new StandardDecryptingChannelFactory().newDecryptingChannel(file, readers);
                WritableByteChannel out = FileChannel.open(target, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            copy(in, out);
        } catch (IOException | GeneralSecurityException e) {
            throw new DbBackupException("Decryption of " + source.getFileName() + " failed (wrong key or passphrase?): "
                    + e.getMessage(), e);
        }
    }

    /** Whether a file starts with the age header. */
    public static boolean isEncrypted(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(MAGIC.length), MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    /** Generates a new X25519 key pair: {@code [public key, private key]}. */
    public static String[] generateKeyPair() {
        try {
            KeyPair pair = new X25519KeyPairGenerator().generateKeyPair();
            return new String[] {pair.getPublic().toString(), pair.getPrivate().toString()};
        } catch (GeneralSecurityException e) {
            throw new DbBackupException("Cannot generate an age key: " + e.getMessage(), e);
        }
    }

    /** Reads the identities ({@code AGE-SECRET-KEY-1...} lines) of an identity file. */
    public static List<String> readIdentities(Path file) {
        return readKeys(file, "AGE-SECRET-KEY-1", "identity");
    }

    /** Reads the recipients ({@code age1...} lines) of a recipients file. */
    public static List<String> readRecipients(Path file) {
        return readKeys(file, "age1", "recipient");
    }

    /** Checks that a string is a valid age recipient. */
    public static void validateRecipient(String recipient) {
        try {
            X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(recipient);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("invalid age recipient '" + recipient + "'");
        }
    }

    /** Identities from the configuration plus extra identity files. */
    public static List<String> identities(EncryptionConfig config, List<Path> extraFiles) {
        List<String> identities = new ArrayList<>();
        for (String file : config.identityFiles()) {
            identities.addAll(readIdentities(PathUtils.expand(file)));
        }
        for (Path file : extraFiles) {
            identities.addAll(readIdentities(file));
        }
        return identities;
    }

    private static List<RecipientStanzaWriter> recipientWriters(EncryptionConfig config) {
        List<RecipientStanzaWriter> writers = new ArrayList<>();
        if (config.usesPassphrase()) {
            writers.add(ScryptRecipientStanzaWriterFactory.newRecipientStanzaWriter(
                    config.passphrase().getBytes(StandardCharsets.UTF_8), config.effectiveScryptWorkFactor()));
            return writers;
        }
        List<String> recipients = new ArrayList<>(config.recipients());
        if (config.recipientsFile() != null && !config.recipientsFile().isBlank()) {
            recipients.addAll(readRecipients(PathUtils.expand(config.recipientsFile())));
        }
        if (recipients.isEmpty()) {
            throw new DbBackupException("Encryption is enabled but no age recipient is configured");
        }
        for (String recipient : recipients) {
            try {
                writers.add(X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(recipient));
            } catch (GeneralSecurityException | RuntimeException e) {
                throw new DbBackupException("Invalid age recipient '" + recipient + "': " + e.getMessage(), e);
            }
        }
        return writers;
    }

    private static List<String> readKeys(Path file, String prefix, String what) {
        List<String> keys = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(prefix)) {
                    keys.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new DbBackupException("Cannot read age " + what + " file " + file + ": " + e.getMessage(), e);
        }
        if (keys.isEmpty()) {
            throw new DbBackupException("No age " + what + " found in " + file);
        }
        return keys;
    }

    private static void copy(ReadableByteChannel in, WritableByteChannel out) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER);
        while (in.read(buffer) != -1) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                out.write(buffer);
            }
            buffer.clear();
        }
    }
}
