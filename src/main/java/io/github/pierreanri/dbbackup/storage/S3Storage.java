package io.github.pierreanri.dbbackup.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.config.S3StorageConfig;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Amazon S3 and S3-compatible object storage. Files larger than the multipart threshold are
 * uploaded in parts streamed directly from disk.
 */
public class S3Storage implements StorageBackend {

    private static final Logger LOG = LoggerFactory.getLogger(S3Storage.class);

    static final long DEFAULT_MULTIPART_THRESHOLD = 64L * 1024 * 1024;
    static final long MIN_PART_SIZE = 16L * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;

    private final String name;
    private final S3StorageConfig config;
    private final String prefix;
    private final Supplier<S3Client> clientFactory;
    private final long multipartThreshold;
    private final long minPartSize;
    private S3Client client;

    public S3Storage(String name, S3StorageConfig config) {
        this(name, config, () -> buildClient(config), DEFAULT_MULTIPART_THRESHOLD, MIN_PART_SIZE);
    }

    S3Storage(String name, S3StorageConfig config, Supplier<S3Client> clientFactory, long multipartThreshold,
            long minPartSize) {
        this.name = name;
        this.config = config;
        this.prefix = Keys.normalizePrefix(config.prefix());
        this.clientFactory = clientFactory;
        this.multipartThreshold = multipartThreshold;
        this.minPartSize = minPartSize;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public String location(String key) {
        return "s3://" + config.bucket() + "/" + fullKey(key);
    }

    @Override
    public void upload(Path source, String key) {
        String fullKey = fullKey(key);
        try {
            long size = Files.size(source);
            if (size <= multipartThreshold) {
                PutObjectRequest.Builder request = PutObjectRequest.builder()
                        .bucket(config.bucket()).key(fullKey).contentLength(size);
                if (config.storageClass() != null) {
                    request.storageClass(StorageClass.fromValue(config.storageClass()));
                }
                client().putObject(request.build(), RequestBody.fromFile(source));
            } else {
                multipartUpload(source, fullKey, size);
            }
        } catch (IOException e) {
            throw new StorageException("Cannot read " + source + ": " + e.getMessage(), e);
        } catch (SdkException e) {
            throw new StorageException("Upload to " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    private void multipartUpload(Path source, String fullKey, long size) {
        long partSize = Math.max(minPartSize, (size + MAX_PARTS - 1) / MAX_PARTS);
        CreateMultipartUploadRequest.Builder create = CreateMultipartUploadRequest.builder()
                .bucket(config.bucket()).key(fullKey);
        if (config.storageClass() != null) {
            create.storageClass(StorageClass.fromValue(config.storageClass()));
        }
        String uploadId = client().createMultipartUpload(create.build()).uploadId();
        LOG.debug("Multipart upload of {} bytes to s3://{}/{} in parts of {} bytes", size, config.bucket(), fullKey,
                partSize);
        try {
            List<CompletedPart> parts = new ArrayList<>();
            int partNumber = 1;
            for (long offset = 0; offset < size; offset += partSize, partNumber++) {
                long length = Math.min(partSize, size - offset);
                long partOffset = offset;
                int number = partNumber;
                String etag = client().uploadPart(UploadPartRequest.builder().bucket(config.bucket()).key(fullKey)
                                .uploadId(uploadId).partNumber(number).contentLength(length).build(),
                        RequestBody.fromContentProvider(() -> openRange(source, partOffset, length), length,
                                "application/octet-stream")).eTag();
                parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
            }
            client().completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(config.bucket())
                    .key(fullKey).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()).build());
        } catch (RuntimeException e) {
            try {
                client().abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(config.bucket())
                        .key(fullKey).uploadId(uploadId).build());
            } catch (RuntimeException abortError) {
                LOG.warn("Could not abort multipart upload {}: {}", uploadId, abortError.getMessage());
            }
            throw e;
        }
    }

    private static FileRangeInputStream openRange(Path file, long offset, long length) {
        try {
            return new FileRangeInputStream(file, offset, length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void download(String key, Path target) {
        try {
            Files.deleteIfExists(target);
            client().getObject(getRequest(key), ResponseTransformer.toFile(target));
        } catch (NoSuchKeyException e) {
            throw new StorageException("Not found: " + location(key), e);
        } catch (IOException | SdkException e) {
            throw new StorageException("Download of " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return client().getObjectAsBytes(getRequest(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new StorageException("Not found: " + location(key), e);
        } catch (SdkException e) {
            throw new StorageException("Download of " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String key, byte[] content) {
        try {
            client().putObject(PutObjectRequest.builder().bucket(config.bucket()).key(fullKey(key))
                    .contentLength((long) content.length).build(), RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new StorageException("Upload to " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredObject> list(String keyPrefix) {
        String fullPrefix = prefix + (keyPrefix == null ? "" : keyPrefix);
        List<StoredObject> objects = new ArrayList<>();
        try {
            String token = null;
            do {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                        .bucket(config.bucket()).prefix(fullPrefix);
                if (token != null) {
                    request.continuationToken(token);
                }
                ListObjectsV2Response response = client().listObjectsV2(request.build());
                for (S3Object object : response.contents()) {
                    objects.add(new StoredObject(object.key().substring(prefix.length()), object.size(),
                            object.lastModified()));
                }
                token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
            } while (token != null);
        } catch (SdkException e) {
            throw new StorageException("Listing s3://" + config.bucket() + "/" + fullPrefix + " failed: "
                    + e.getMessage(), e);
        }
        return objects;
    }

    @Override
    public void delete(String key) {
        try {
            client().deleteObject(DeleteObjectRequest.builder().bucket(config.bucket()).key(fullKey(key)).build());
        } catch (SdkException e) {
            throw new StorageException("Deleting " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client().headObject(HeadObjectRequest.builder().bucket(config.bucket()).key(fullKey(key)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("Checking " + location(key) + " failed: " + e.getMessage(), e);
        } catch (SdkException e) {
            throw new StorageException("Checking " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private synchronized S3Client client() {
        if (client == null) {
            try {
                client = clientFactory.get();
            } catch (SdkException e) {
                throw new StorageException("Cannot create S3 client for storage '" + name + "': " + e.getMessage()
                        + " (set 'region' and credentials in the storage configuration)", e);
            }
        }
        return client;
    }

    private GetObjectRequest getRequest(String key) {
        return GetObjectRequest.builder().bucket(config.bucket()).key(fullKey(key)).build();
    }

    private String fullKey(String key) {
        return prefix + Keys.check(key);
    }

    static S3Client buildClient(S3StorageConfig config) {
        S3ClientBuilder builder = S3Client.builder().httpClientBuilder(ApacheHttpClient.builder());
        boolean customEndpoint = config.endpoint() != null && !config.endpoint().isBlank();
        if (config.region() != null && !config.region().isBlank()) {
            builder.region(Region.of(config.region()));
        } else if (customEndpoint) {
            builder.region(Region.US_EAST_1);
        }
        if (customEndpoint) {
            builder.endpointOverride(URI.create(config.endpoint()));
            // Many S3-compatible services do not support the newer default integrity checksums.
            builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
            builder.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        builder.forcePathStyle(Boolean.TRUE.equals(config.pathStyle()));
        builder.credentialsProvider(credentials(config));
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(S3StorageConfig config) {
        if (config.accessKeyId() != null && !config.accessKeyId().isBlank()) {
            if (config.sessionToken() != null && !config.sessionToken().isBlank()) {
                return StaticCredentialsProvider.create(AwsSessionCredentials.create(config.accessKeyId(),
                        config.secretAccessKey(), config.sessionToken()));
            }
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKeyId(),
                    config.secretAccessKey()));
        }
        if (config.profile() != null && !config.profile().isBlank()) {
            return ProfileCredentialsProvider.create(config.profile());
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
