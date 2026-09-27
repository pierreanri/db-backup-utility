/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.compression;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;

import io.github.pierreanri.dbbackup.DbBackupException;

/**
 * Streams files through a {@link Compression} while computing the SHA-256 of the result.
 */
public final class Compressor {

    private static final int BUFFER = 256 * 1024;

    private Compressor() {
    }

    /**
     * Compresses {@code source} into {@code target}.
     *
     * @return the SHA-256 (hex) of {@code target}
     */
    public static String compress(Path source, Path target, Compression compression) {
        MessageDigest digest = Checksums.newSha256();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source), BUFFER);
                OutputStream fileOut = new DigestOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(target), BUFFER), digest);
                OutputStream out = compression.compress(fileOut)) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new DbBackupException("Cannot " + (compression == Compression.NONE ? "copy " : compression.id() + "-compress ")
                    + source.getFileName() + ": " + e.getMessage(), e);
        }
        return Checksums.toHex(digest.digest());
    }

    /** Decompresses {@code source} into {@code target}. */
    public static void decompress(Path source, Path target, Compression compression) {
        try (InputStream in = compression.decompress(new BufferedInputStream(Files.newInputStream(source), BUFFER));
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), BUFFER)) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new DbBackupException("Cannot decompress " + source.getFileName() + " (" + compression.id() + "): "
                    + e.getMessage(), e);
        }
    }
}
