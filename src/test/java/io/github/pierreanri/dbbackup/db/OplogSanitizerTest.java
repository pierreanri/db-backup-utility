/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OplogSanitizerTest {

    @TempDir
    Path tmp;

    private static final String CREATE = """
            {"op": "c", "ns": "shop.$cmd", "o": {"create": "audit"},
             "o2": {"catalogId": 19, "ident": "b856", "idIndexIdent": "4df3"}, "versionContext": {"OFCV": "8.3"}}""";
    private static final String INDEX = """
            {"op": "c", "ns": "shop.$cmd", "o": {"startIndexBuild": "t", "indexes": [{"name": "x_1"}]},
             "o2": {"indexes": [{"indexIdent": "b0f8"}]}}""";
    private static final String INSERT = """
            {"op": "i", "ns": "shop.orders", "o": {"_id": 1, "total": 10}, "o2": {"_id": 1}}""";
    private static final String TRANSACTION = """
            {"op": "c", "ns": "admin.$cmd", "o": {"applyOps": [
              {"op": "i", "ns": "shop.t", "o": {"_id": 2}, "o2": {"_id": 2}},
              {"op": "c", "ns": "shop.$cmd", "o": {"create": "t2"}, "o2": {"catalogId": 33, "ident": "e4b7"}}]}}""";
    private static final String COLL_MOD = """
            {"op": "c", "ns": "shop.$cmd", "o": {"collMod": "t"}, "o2": {"collectionOptions_old": {"uuid": 1}}}""";

    @Test
    void removesStorageIdentifiersFromCommandsOnly() throws IOException {
        Path oplog = tmp.resolve("oplog.bson");
        write(oplog, List.of(CREATE, INDEX, INSERT, TRANSACTION, COLL_MOD));

        assertThat(OplogSanitizer.stripStorageIdentifiers(oplog)).isEqualTo(3);

        List<BsonDocument> entries = read(oplog);
        assertThat(entries).hasSize(5);
        assertThat(entries.get(0).containsKey("o2")).isFalse();
        assertThat(entries.get(0).getDocument("o").getString("create").getValue()).isEqualTo("audit");
        assertThat(entries.get(1).containsKey("o2")).isFalse();
        assertThat(entries.get(2)).isEqualTo(BsonDocument.parse(INSERT));
        BsonDocument nestedCreate = entries.get(3).getDocument("o").getArray("applyOps").get(1).asDocument();
        assertThat(nestedCreate.containsKey("o2")).isFalse();
        assertThat(entries.get(3).getDocument("o").getArray("applyOps").get(0).asDocument().getDocument("o2"))
                .isEqualTo(BsonDocument.parse("{\"_id\": 2}"));
        assertThat(entries.get(4)).isEqualTo(BsonDocument.parse(COLL_MOD));
    }

    @Test
    void leavesOlderOplogsAndEmptyFilesAlone() throws IOException {
        Path oplog = tmp.resolve("oplog.bson");
        write(oplog, List.of(INSERT, "{\"op\": \"c\", \"ns\": \"shop.$cmd\", \"o\": {\"create\": \"audit\"}}"));
        byte[] before = Files.readAllBytes(oplog);

        assertThat(OplogSanitizer.stripStorageIdentifiers(oplog)).isZero();
        assertThat(Files.readAllBytes(oplog)).isEqualTo(before);

        Path empty = Files.createFile(tmp.resolve("empty.bson"));
        assertThat(OplogSanitizer.stripStorageIdentifiers(empty)).isZero();
        assertThat(OplogSanitizer.stripStorageIdentifiers(tmp.resolve("missing.bson"))).isZero();
    }

    private static void write(Path file, List<String> documents) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            for (String json : documents) {
                BasicOutputBuffer buffer = new BasicOutputBuffer();
                new BsonDocumentCodec().encode(new BsonBinaryWriter(buffer), BsonDocument.parse(json),
                        EncoderContext.builder().build());
                buffer.pipe(out);
            }
        }
    }

    private static List<BsonDocument> read(Path file) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        List<BsonDocument> documents = new ArrayList<>();
        while (bytes.hasRemaining()) {
            int length = bytes.getInt(bytes.position());
            try (BsonBinaryReader reader = new BsonBinaryReader(bytes.slice(bytes.position(), length))) {
                documents.add(new BsonDocumentCodec().decode(reader, DecoderContext.builder().build()));
            }
            bytes.position(bytes.position() + length);
        }
        return documents;
    }
}
