package io.github.pierreanri.dbbackup.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.common.StorageSharedKeyCredential;

import io.github.pierreanri.dbbackup.config.AzureStorageConfig;

/**
 * Azure Blob Storage. Large files are uploaded as blocks in parallel by the Azure SDK.
 */
public class AzureBlobStorage implements StorageBackend {

    private final String name;
    private final AzureStorageConfig config;
    private final String prefix;
    private final Supplier<BlobContainerClient> clientFactory;
    private BlobContainerClient client;

    public AzureBlobStorage(String name, AzureStorageConfig config) {
        this(name, config, () -> buildClient(config));
    }

    AzureBlobStorage(String name, AzureStorageConfig config, Supplier<BlobContainerClient> clientFactory) {
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
        return "azure";
    }

    @Override
    public String description() {
        String account = config.accountName() != null ? config.accountName() + "/" : "";
        return "azure://" + account + config.container() + "/" + prefix;
    }

    @Override
    public String location(String key) {
        return description() + Keys.check(key);
    }

    @Override
    public void upload(Path source, String key) {
        try {
            blob(key).uploadFromFile(source.toString(), true);
        } catch (RuntimeException e) {
            throw new StorageException("Upload to " + location(key) + " failed: " + describe(e), e);
        }
    }

    @Override
    public void download(String key, Path target) {
        try {
            blob(key).downloadToFile(target.toString(), true);
        } catch (BlobStorageException e) {
            throw new StorageException(e.getStatusCode() == 404 ? "Not found: " + location(key)
                    : "Download of " + location(key) + " failed: " + describe(e), e);
        } catch (RuntimeException e) {
            throw new StorageException("Download of " + location(key) + " failed: " + describe(e), e);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return blob(key).downloadContent().toBytes();
        } catch (BlobStorageException e) {
            throw new StorageException(e.getStatusCode() == 404 ? "Not found: " + location(key)
                    : "Download of " + location(key) + " failed: " + describe(e), e);
        }
    }

    @Override
    public void write(String key, byte[] content) {
        try {
            blob(key).upload(BinaryData.fromBytes(content), true);
        } catch (RuntimeException e) {
            throw new StorageException("Upload to " + location(key) + " failed: " + describe(e), e);
        }
    }

    @Override
    public List<StoredObject> list(String keyPrefix) {
        String fullPrefix = prefix + (keyPrefix == null ? "" : keyPrefix);
        List<StoredObject> objects = new ArrayList<>();
        try {
            for (BlobItem item : client().listBlobs(new ListBlobsOptions().setPrefix(fullPrefix), null)) {
                objects.add(new StoredObject(item.getName().substring(prefix.length()),
                        item.getProperties() == null || item.getProperties().getContentLength() == null ? 0
                                : item.getProperties().getContentLength(),
                        item.getProperties() == null || item.getProperties().getLastModified() == null ? null
                                : item.getProperties().getLastModified().toInstant()));
            }
        } catch (RuntimeException e) {
            throw new StorageException("Listing " + config.container() + "/" + fullPrefix + " failed: "
                    + describe(e), e);
        }
        return objects;
    }

    @Override
    public void delete(String key) {
        try {
            blob(key).deleteIfExists();
        } catch (RuntimeException e) {
            throw new StorageException("Deleting " + location(key) + " failed: " + describe(e), e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return blob(key).exists();
        } catch (RuntimeException e) {
            throw new StorageException("Checking " + location(key) + " failed: " + describe(e), e);
        }
    }

    /** Azure error messages embed the whole XML response; keep the error code instead. */
    static String describe(RuntimeException e) {
        if (e instanceof BlobStorageException blobError) {
            String code = blobError.getErrorCode() != null ? blobError.getErrorCode().toString() : "error";
            return code + " (HTTP " + blobError.getStatusCode() + ")";
        }
        return e.getMessage();
    }

    private BlobClient blob(String key) {
        return client().getBlobClient(fullKey(key));
    }

    private synchronized BlobContainerClient client() {
        if (client == null) {
            try {
                client = clientFactory.get();
            } catch (RuntimeException e) {
                throw new StorageException("Cannot create Azure Blob client for storage '" + name + "': "
                        + e.getMessage(), e);
            }
        }
        return client;
    }

    private String fullKey(String key) {
        return prefix + Keys.check(key);
    }

    static BlobContainerClient buildClient(AzureStorageConfig config) {
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (config.connectionString() != null && !config.connectionString().isBlank()) {
            builder.connectionString(config.connectionString());
        } else {
            String endpoint = config.endpoint() != null && !config.endpoint().isBlank()
                    ? config.endpoint()
                    : "https://" + config.accountName() + ".blob.core.windows.net";
            builder.endpoint(endpoint);
            if (config.accountKey() != null && !config.accountKey().isBlank()) {
                builder.credential(new StorageSharedKeyCredential(config.accountName(), config.accountKey()));
            } else if (config.sasToken() != null && !config.sasToken().isBlank()) {
                builder.sasToken(config.sasToken());
            }
        }
        return builder.buildClient().getBlobContainerClient(config.container());
    }
}
