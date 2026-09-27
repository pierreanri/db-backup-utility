package io.github.pierreanri.dbbackup.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores backups in a local directory. Uploads are written to a temporary file first and then
 * atomically renamed, so a partially written backup never looks complete.
 */
public class LocalStorage implements StorageBackend {

    private static final String PART_SUFFIX = ".part";

    private final String name;
    private final Path root;

    public LocalStorage(String name, Path root) {
        this.name = name;
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "local";
    }

    public Path root() {
        return root;
    }

    @Override
    public String location(String key) {
        return resolve(key).toString();
    }

    @Override
    public void upload(Path source, String key) {
        Path target = resolve(key);
        Path part = target.resolveSibling("." + target.getFileName() + PART_SUFFIX);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // best effort
            }
            throw new StorageException("Cannot store " + target + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void download(String key, Path target) {
        Path source = resolve(key);
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException e) {
            throw new StorageException("Not found: " + source, e);
        } catch (IOException e) {
            throw new StorageException("Cannot read " + source + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredObject> list(String prefix) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        String normalizedPrefix = prefix == null ? "" : prefix.replace('\\', '/');
        List<StoredObject> objects = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file) || file.getFileName().toString().endsWith(PART_SUFFIX)) {
                    continue;
                }
                String key = root.relativize(file).toString().replace('\\', '/');
                if (key.startsWith(normalizedPrefix)) {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    objects.add(new StoredObject(key, attrs.size(), attrs.lastModifiedTime().toInstant()));
                }
            }
        } catch (IOException e) {
            throw new StorageException("Cannot list " + root + ": " + e.getMessage(), e);
        }
        objects.sort(Comparator.comparing(StoredObject::key));
        return objects;
    }

    @Override
    public void delete(String key) {
        Path file = resolve(key);
        try {
            Files.deleteIfExists(file);
            Path dir = file.getParent();
            while (dir != null && !dir.equals(root) && dir.startsWith(root) && Files.isDirectory(dir)
                    && isEmptyDirectory(dir)) {
                Files.delete(dir);
                dir = dir.getParent();
            }
        } catch (IOException e) {
            throw new StorageException("Cannot delete " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public byte[] read(String key) {
        Path file = resolve(key);
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new StorageException("Not found: " + file, e);
        } catch (IOException e) {
            throw new StorageException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(Keys.check(key)).normalize();
        if (!path.startsWith(root)) {
            throw new StorageException("Key escapes the storage directory: " + key);
        }
        return path;
    }

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }
}
