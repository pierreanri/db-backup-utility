/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Compression algorithms available for backup files.
 */
public enum Compression {
    NONE("none", "", List.of("none", "off", "false", "no")),
    GZIP("gzip", ".gz", List.of("gzip", "gz")),
    BZIP2("bzip2", ".bz2", List.of("bzip2", "bz2")),
    XZ("xz", ".xz", List.of("xz", "lzma"));

    private static final int BUFFER = 64 * 1024;

    private final String id;
    private final String extension;
    private final List<String> aliases;

    Compression(String id, String extension, List<String> aliases) {
        this.id = id;
        this.extension = extension;
        this.aliases = aliases;
    }

    @JsonValue
    public String id() {
        return id;
    }

    /** File name suffix including the dot, empty for {@link #NONE}. */
    public String extension() {
        return extension;
    }

    public OutputStream compress(OutputStream out) throws IOException {
        return switch (this) {
            case NONE -> out;
            case GZIP -> new GZIPOutputStream(out, BUFFER);
            case BZIP2 -> new BZip2CompressorOutputStream(out);
            case XZ -> new XZOutputStream(out, new LZMA2Options(LZMA2Options.PRESET_DEFAULT));
        };
    }

    public InputStream decompress(InputStream in) throws IOException {
        return switch (this) {
            case NONE -> in;
            case GZIP -> new GZIPInputStream(in, BUFFER);
            case BZIP2 -> new BZip2CompressorInputStream(in, true);
            case XZ -> new XZInputStream(in);
        };
    }

    @JsonCreator
    public static Compression fromName(String name) {
        if (name == null || name.isBlank()) {
            return GZIP;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (Compression compression : values()) {
            if (compression.aliases.contains(normalized)) {
                return compression;
            }
        }
        throw new IllegalArgumentException("unsupported compression '" + name + "' (supported: " + supported() + ")");
    }

    /** Detects the compression of a file from its name, {@link #NONE} when no known suffix is present. */
    public static Compression fromFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (Compression compression : values()) {
            if (compression != NONE && lower.endsWith(compression.extension)) {
                return compression;
            }
        }
        return NONE;
    }

    /** Removes this compression's suffix from a file name. */
    public String stripExtension(String fileName) {
        if (this != NONE && fileName.toLowerCase(Locale.ROOT).endsWith(extension)) {
            return fileName.substring(0, fileName.length() - extension.length());
        }
        return fileName;
    }

    public static String supported() {
        return Arrays.stream(values()).map(Compression::id).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return id;
    }
}
