package io.github.pierreanri.dbbackup.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.google.api.gax.paging.Page;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import io.github.pierreanri.dbbackup.config.GcsStorageConfig;
import io.github.pierreanri.dbbackup.util.PathUtils;

/**
 * Google Cloud Storage. Uploads use resumable uploads, so large backups are sent in chunks.
 * Authenticates with a service account key file or Application Default Credentials.
 */
public class GcsStorage implements StorageBackend {

    private final String name;
    private final GcsStorageConfig config;
    private final String prefix;
    private final Supplier<Storage> clientFactory;
    private Storage client;

    public GcsStorage(String name, GcsStorageConfig config) {
        this(name, config, () -> buildClient(config));
    }

    GcsStorage(String name, GcsStorageConfig config, Supplier<Storage> clientFactory) {
        this.name = name;
        this.config = config;
        this.prefix = Keys.normalizePrefix(config.prefix());
        this.clientFactory = clientFactory;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "gcs";
    }

    @Override
    public String description() {
        return "gs://" + config.bucket() + "/" + prefix;
    }

    @Override
    public String location(String key) {
        return "gs://" + config.bucket() + "/" + fullKey(key);
    }

    @Override
    public void upload(Path source, String key) {
        try {
            client().createFrom(BlobInfo.newBuilder(config.bucket(), fullKey(key)).build(), source);
        } catch (IOException | RuntimeException e) {
            throw new StorageException("Upload to " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void download(String key, Path target) {
        try {
            client().downloadTo(blobId(key), target);
        } catch (com.google.cloud.storage.StorageException e) {
            throw new StorageException((e.getCode() == 404 ? "Not found: " + location(key)
                    : "Download of " + location(key) + " failed: " + e.getMessage()), e);
        } catch (RuntimeException e) {
            throw new StorageException("Download of " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return client().readAllBytes(blobId(key));
        } catch (com.google.cloud.storage.StorageException e) {
            throw new StorageException((e.getCode() == 404 ? "Not found: " + location(key)
                    : "Download of " + location(key) + " failed: " + e.getMessage()), e);
        }
    }

    @Override
    public void write(String key, byte[] content) {
        try {
            client().create(BlobInfo.newBuilder(config.bucket(), fullKey(key)).build(), content);
        } catch (RuntimeException e) {
            throw new StorageException("Upload to " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredObject> list(String keyPrefix) {
        String fullPrefix = prefix + (keyPrefix == null ? "" : keyPrefix);
        List<StoredObject> objects = new ArrayList<>();
        try {
            Page<Blob> page = client().list(config.bucket(), Storage.BlobListOption.prefix(fullPrefix));
            for (Blob blob : page.iterateAll()) {
                if (blob.getName().endsWith("/")) {
                    continue;
                }
                objects.add(new StoredObject(blob.getName().substring(prefix.length()),
                        blob.getSize() == null ? 0 : blob.getSize(),
                        blob.getUpdateTimeOffsetDateTime() == null ? null
                                : blob.getUpdateTimeOffsetDateTime().toInstant()));
            }
        } catch (RuntimeException e) {
            throw new StorageException("Listing gs://" + config.bucket() + "/" + fullPrefix + " failed: "
                    + e.getMessage(), e);
        }
        return objects;
    }

    @Override
    public void delete(String key) {
        try {
            client().delete(blobId(key));
        } catch (RuntimeException e) {
            throw new StorageException("Deleting " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return client().get(blobId(key)) != null;
        } catch (RuntimeException e) {
            throw new StorageException("Checking " + location(key) + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
            client = null;
        }
    }

    private synchronized Storage client() {
        if (client == null) {
            try {
                client = clientFactory.get();
            } catch (RuntimeException e) {
                throw new StorageException("Cannot create Google Cloud Storage client for storage '" + name + "': "
                        + e.getMessage(), e);
            }
        }
        return client;
    }

    private BlobId blobId(String key) {
        return BlobId.of(config.bucket(), fullKey(key));
    }

    private String fullKey(String key) {
        return prefix + Keys.check(key);
    }

    static Storage buildClient(GcsStorageConfig config) {
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        if (config.projectId() != null && !config.projectId().isBlank()) {
            builder.setProjectId(config.projectId());
        }
        boolean customEndpoint = config.endpoint() != null && !config.endpoint().isBlank();
        if (customEndpoint) {
            builder.setHost(config.endpoint());
        }
        if (config.credentialsFile() != null && !config.credentialsFile().isBlank()) {
            Path file = PathUtils.expand(config.credentialsFile());
            try (InputStream in = Files.newInputStream(file)) {
                builder.setCredentials(ServiceAccountCredentials.fromStream(in));
            } catch (IOException e) {
                throw new StorageException("Cannot read GCS credentials file " + file + ": " + e.getMessage(), e);
            }
        } else if (customEndpoint) {
            builder.setCredentials(NoCredentials.getInstance());
        }
        return builder.build().getService();
    }
}
