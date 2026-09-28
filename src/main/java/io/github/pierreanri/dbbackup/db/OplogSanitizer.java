package io.github.pierreanri.dbbackup.db;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.bson.BsonArray;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;

import io.github.pierreanri.dbbackup.DbBackupException;

/**
 * Removes the storage identifiers that recent MongoDB versions (8.3+) record in the {@code o2}
 * field of collection and index creation commands. Replaying them would make the server reuse the
 * storage files of the original collections, which fails while those are still being dropped (for
 * example when a database is restored right after being dropped). Without them, the server picks
 * new identifiers, as it does for oplogs written by older versions. Document operations are left
 * untouched ({@code o2} holds their document key).
 */
final class OplogSanitizer {

    private static final List<String> IDENTIFIERS = List.of("catalogId", "ident", "idIndexIdent", "indexIdent");
    private static final BsonDocumentCodec CODEC = new BsonDocumentCodec();

    private OplogSanitizer() {
    }

    /**
     * Rewrites a {@code .bson} oplog file in place.
     *
     * @return number of entries changed
     */
    static int stripStorageIdentifiers(Path oplogFile) {
        if (!Files.isRegularFile(oplogFile)) {
            return 0;
        }
        Path rewritten = oplogFile.resolveSibling(oplogFile.getFileName() + ".rewritten");
        int changed = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(oplogFile), 1 << 20);
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(rewritten), 1 << 20)) {
            byte[] document;
            while ((document = next(in)) != null) {
                BsonDocument entry = CODEC.decode(new BsonBinaryReader(ByteBuffer.wrap(document)),
                        DecoderContext.builder().build());
                if (clean(entry)) {
                    changed++;
                    BasicOutputBuffer buffer = new BasicOutputBuffer();
                    CODEC.encode(new BsonBinaryWriter(buffer), entry, EncoderContext.builder().build());
                    buffer.pipe(out);
                } else {
                    out.write(document);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new DbBackupException("Cannot rewrite the oplog " + oplogFile.getFileName() + ": " + e.getMessage(), e);
        }
        try {
            Files.move(rewritten, oplogFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DbBackupException("Cannot rewrite the oplog " + oplogFile.getFileName() + ": " + e.getMessage(), e);
        }
        return changed;
    }

    /** Cleans one oplog entry (and the entries of a transaction); returns whether it changed. */
    static boolean clean(BsonDocument entry) {
        boolean changed = false;
        BsonValue command = entry.get("o");
        if (command != null && command.isDocument() && command.asDocument().get("applyOps") instanceof BsonArray ops) {
            for (BsonValue op : ops) {
                if (op.isDocument() && clean(op.asDocument())) {
                    changed = true;
                }
            }
        }
        if (!"c".equals(entry.getString("op", new BsonString("")).getValue())
                || !(entry.get("o2") instanceof BsonDocument o2)) {
            return changed;
        }
        for (String key : IDENTIFIERS) {
            if (o2.remove(key) != null) {
                changed = true;
            }
        }
        if (o2.get("indexes") instanceof BsonArray indexes) {
            for (BsonValue index : indexes) {
                if (index.isDocument() && index.asDocument().remove("indexIdent") != null) {
                    changed = true;
                }
            }
            if (indexes.stream().allMatch(index -> index.isDocument() && index.asDocument().isEmpty())) {
                o2.remove("indexes");
            }
        }
        if (o2.isEmpty()) {
            entry.remove("o2");
        }
        return changed;
    }

    /** Reads the next BSON document (length prefixed), or {@code null} at the end of the stream. */
    private static byte[] next(InputStream in) throws IOException {
        byte[] size = in.readNBytes(4);
        if (size.length == 0) {
            return null;
        }
        if (size.length < 4) {
            throw new IOException("truncated BSON document");
        }
        int length = ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 5) {
            throw new IOException("invalid BSON document length " + length);
        }
        byte[] document = new byte[length];
        System.arraycopy(size, 0, document, 0, 4);
        if (in.readNBytes(document, 4, length - 4) != length - 4) {
            throw new IOException("truncated BSON document");
        }
        return document;
    }
}
