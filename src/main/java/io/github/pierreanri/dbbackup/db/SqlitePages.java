/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import io.github.pierreanri.dbbackup.DbBackupException;

/**
 * Page level change tracking for SQLite database files, used for incremental backups.
 *
 * <p>A SQLite database is an array of fixed size pages. The state of a backup is the list of the
 * fingerprints (truncated SHA-256) of its pages. An incremental backup stores only the pages whose
 * fingerprint changed, plus the new page count; applying it to the previous database file
 * reproduces the new one exactly.
 */
final class SqlitePages {

    static final int HASH_LENGTH = 16;
    private static final byte[] HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATE_MAGIC = "DBBKSQS1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHANGES_MAGIC = "DBBKSQI1".getBytes(StandardCharsets.US_ASCII);

    private SqlitePages() {
    }

    /** Fingerprints of all pages of a database file. */
    record PageState(int pageSize, long pageCount, byte[] hashes) {

        boolean sameAs(long page, byte[] allHashes, int offset) {
            if (page >= pageCount) {
                return false;
            }
            int from = (int) (page * HASH_LENGTH);
            return Arrays.equals(hashes, from, from + HASH_LENGTH, allHashes, offset, offset + HASH_LENGTH);
        }
    }

    /** Result of {@link #writeChanges}. */
    record ChangeSummary(PageState state, long changedPages) {
    }

    static int pageSize(Path databaseFile) throws IOException {
        try (InputStream in = Files.newInputStream(databaseFile)) {
            byte[] header = in.readNBytes(100);
            if (header.length < 100 || !Arrays.equals(header, 0, HEADER.length, HEADER, 0, HEADER.length)) {
                throw new DbBackupException(databaseFile.getFileName() + " is not a SQLite database");
            }
            int size = ((header[16] & 0xff) << 8) | (header[17] & 0xff);
            return size == 1 ? 65536 : size;
        }
    }

    static PageState computeState(Path databaseFile) throws IOException {
        int pageSize = pageSize(databaseFile);
        long pageCount = pageCount(databaseFile, pageSize);
        byte[] hashes = new byte[Math.toIntExact(pageCount * HASH_LENGTH)];
        MessageDigest digest = sha256();
        byte[] page = new byte[pageSize];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(databaseFile), 1 << 20)) {
            for (long i = 0; i < pageCount; i++) {
                readPage(in, page);
                System.arraycopy(digest.digest(page), 0, hashes, (int) (i * HASH_LENGTH), HASH_LENGTH);
            }
        }
        return new PageState(pageSize, pageCount, hashes);
    }

    static void writeState(PageState state, Path file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.write(STATE_MAGIC);
            out.writeInt(state.pageSize());
            out.writeLong(state.pageCount());
            out.write(state.hashes());
        }
    }

    static PageState readState(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            checkMagic(in, STATE_MAGIC, file);
            int pageSize = in.readInt();
            long pageCount = in.readLong();
            byte[] hashes = new byte[Math.toIntExact(pageCount * HASH_LENGTH)];
            in.readFully(hashes);
            return new PageState(pageSize, pageCount, hashes);
        }
    }

    /**
     * Writes the pages of {@code databaseFile} that differ from {@code previous} into
     * {@code changesFile}.
     */
    static ChangeSummary writeChanges(Path databaseFile, PageState previous, Path changesFile) throws IOException {
        PageState current = computeState(databaseFile);
        if (current.pageSize() != previous.pageSize()) {
            throw new DbBackupException("The page size of the SQLite database changed (" + previous.pageSize() + " -> "
                    + current.pageSize() + "): take a full backup");
        }
        int pageSize = current.pageSize();
        long changed = 0;
        byte[] page = new byte[pageSize];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(databaseFile), 1 << 20);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(changesFile), 1 << 20))) {
            out.write(CHANGES_MAGIC);
            out.writeInt(pageSize);
            out.writeLong(current.pageCount());
            for (long i = 0; i < current.pageCount(); i++) {
                readPage(in, page);
                if (!previous.sameAs(i, current.hashes(), (int) (i * HASH_LENGTH))) {
                    out.writeLong(i);
                    out.write(page);
                    changed++;
                }
            }
            out.writeLong(-1);
        }
        return new ChangeSummary(current, changed);
    }

    /** Applies a changes file produced by {@link #writeChanges} to a database file, in place. */
    static void applyChanges(Path databaseFile, Path changesFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(changesFile), 1 << 20));
                RandomAccessFile db = new RandomAccessFile(databaseFile.toFile(), "rw")) {
            checkMagic(in, CHANGES_MAGIC, changesFile);
            int pageSize = in.readInt();
            long pageCount = in.readLong();
            if (pageSize(databaseFile) != pageSize) {
                throw new DbBackupException("Page size mismatch between " + databaseFile.getFileName() + " and "
                        + changesFile.getFileName());
            }
            db.setLength(pageCount * pageSize);
            byte[] page = new byte[pageSize];
            long pageNumber;
            while ((pageNumber = in.readLong()) >= 0) {
                in.readFully(page);
                db.seek(pageNumber * pageSize);
                db.write(page);
            }
        } catch (EOFException e) {
            throw new DbBackupException("Truncated SQLite changes file " + changesFile.getFileName(), e);
        }
    }

    private static long pageCount(Path databaseFile, int pageSize) throws IOException {
        long size = Files.size(databaseFile);
        if (size % pageSize != 0) {
            throw new DbBackupException(databaseFile.getFileName() + " is not a whole number of pages");
        }
        return size / pageSize;
    }

    private static void readPage(InputStream in, byte[] page) throws IOException {
        if (in.readNBytes(page, 0, page.length) != page.length) {
            throw new EOFException("unexpected end of database file");
        }
    }

    private static void checkMagic(DataInputStream in, byte[] magic, Path file) throws IOException {
        byte[] actual = in.readNBytes(magic.length);
        if (!Arrays.equals(actual, magic)) {
            throw new DbBackupException(file.getFileName() + " is not a dbbackup SQLite state/changes file");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
