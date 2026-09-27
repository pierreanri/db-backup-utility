/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.github.pierreanri.dbbackup.config.S3StorageConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

class S3StorageTest {

    @TempDir
    Path tmp;

    private final S3Client client = mock(S3Client.class);
    private final S3StorageConfig config = new S3StorageConfig("bucket", "/prod/", null, null, null, null, null, null,
            null, "STANDARD_IA", null);

    private S3Storage storage(long threshold, long partSize) {
        return new S3Storage("s3", config, () -> client, threshold, partSize);
    }

    @Test
    void smallFilesUseASinglePut() throws IOException {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        Path file = Files.writeString(tmp.resolve("f"), "hello");

        storage(1024, 16).upload(file, "app/app-1.sql.gz");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("bucket");
        assertThat(request.getValue().key()).isEqualTo("prod/app/app-1.sql.gz");
        assertThat(request.getValue().contentLength()).isEqualTo(5);
        assertThat(request.getValue().storageClass()).isEqualTo(StorageClass.STANDARD_IA);
        verify(client, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    void largeFilesUseMultipartUploads() throws IOException {
        byte[] content = new byte[2500];
        new Random(42).nextBytes(content);
        Path file = Files.write(tmp.resolve("big"), content);
        List<UploadPartRequest> parts = new ArrayList<>();
        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();

        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up-1").build());
        when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            UploadPartRequest part = inv.getArgument(0);
            RequestBody body = inv.getArgument(1);
            parts.add(part);
            try (InputStream in = body.contentStreamProvider().newStream()) {
                in.transferTo(uploaded);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return UploadPartResponse.builder().eTag("etag-" + part.partNumber()).build();
        });
        when(client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());

        storage(1000, 1000).upload(file, "db/big.dump");

        assertThat(parts).extracting(UploadPartRequest::partNumber).containsExactly(1, 2, 3);
        assertThat(parts).extracting(UploadPartRequest::contentLength).containsExactly(1000L, 1000L, 500L);
        assertThat(parts).allSatisfy(p -> assertThat(p.uploadId()).isEqualTo("up-1"));
        assertThat(uploaded.toByteArray()).isEqualTo(content);

        ArgumentCaptor<CompleteMultipartUploadRequest> complete =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(client).completeMultipartUpload(complete.capture());
        assertThat(complete.getValue().multipartUpload().parts())
                .extracting(p -> p.eTag()).containsExactly("etag-1", "etag-2", "etag-3");
    }

    @Test
    void failedMultipartUploadsAreAborted() throws IOException {
        Path file = Files.write(tmp.resolve("big"), new byte[3000]);
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up-2").build());
        when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("boom").statusCode(500).build());

        assertThatThrownBy(() -> storage(1000, 1000).upload(file, "db/big.dump"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("s3://bucket/prod/db/big.dump");
        verify(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void listsAllPagesWithRelativeKeys() {
        Instant now = Instant.parse("2026-05-01T10:00:00Z");
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(inv -> {
            ListObjectsV2Request request = inv.getArgument(0);
            assertThat(request.prefix()).isEqualTo("prod/app/");
            if (request.continuationToken() == null) {
                return ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("t2")
                        .contents(S3Object.builder().key("prod/app/a").size(1L).lastModified(now).build()).build();
            }
            return ListObjectsV2Response.builder().isTruncated(false)
                    .contents(S3Object.builder().key("prod/app/b").size(2L).lastModified(now).build()).build();
        });

        List<StoredObject> objects = storage(1000, 1000).list("app/");

        assertThat(objects).containsExactly(new StoredObject("app/a", 1, now), new StoredObject("app/b", 2, now));
    }

    @Test
    void existsHandlesMissingKeys() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThat(storage(1, 1).exists("x")).isFalse();
    }

    @Test
    void locationIncludesPrefix() {
        assertThat(storage(1, 1).location("a/b")).isEqualTo("s3://bucket/prod/a/b");
    }

    @Test
    void buildsClientForCustomEndpointWithoutNetworkAccess() {
        S3StorageConfig minio = new S3StorageConfig("b", null, null, "http://localhost:9000", true, "key", "secret",
                null, null, null, null);
        try (S3Client built = S3Storage.buildClient(minio)) {
            assertThat(built).isNotNull();
        }
    }
}
