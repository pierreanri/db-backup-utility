/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads {@code length} bytes of a file starting at {@code offset}. Used to stream multipart upload
 * parts without buffering them in memory.
 */
final class FileRangeInputStream extends InputStream {

    private final FileChannel channel;
    private long position;
    private long remaining;

    FileRangeInputStream(Path file, long offset, long length) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        this.position = offset;
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int toRead = (int) Math.min(len, remaining);
        int n = channel.read(ByteBuffer.wrap(buffer, off, toRead), position);
        if (n <= 0) {
            return -1;
        }
        position += n;
        remaining -= n;
        return n;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
