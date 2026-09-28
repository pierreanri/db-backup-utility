package io.github.pierreanri.dbbackup.cli;

import java.nio.file.Path;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.ConfigLoader;
import io.github.pierreanri.dbbackup.config.LoadedConfig;
import io.github.pierreanri.dbbackup.core.BackupService;
import io.github.pierreanri.dbbackup.core.RestoreService;
import io.github.pierreanri.dbbackup.core.StorageRegistry;
import io.github.pierreanri.dbbackup.db.DatabaseAdapters;
import io.github.pierreanri.dbbackup.logging.ActivityLog;
import io.github.pierreanri.dbbackup.logging.LoggingConfigurator;
import io.github.pierreanri.dbbackup.logging.Verbosity;
import io.github.pierreanri.dbbackup.notify.Notifier;
import io.github.pierreanri.dbbackup.notify.Notifiers;
import io.github.pierreanri.dbbackup.storage.StorageFactory;
import io.github.pierreanri.dbbackup.util.PathUtils;

/**
 * Everything a command needs: the loaded configuration, logging, services and storage backends.
 * Created once per command execution and closed afterwards.
 */
public final class AppContext implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private final LoadedConfig loaded;
    private final Path logFile;
    private final ActivityLog activityLog;
    private final Notifier notifier;
    private final DatabaseAdapters adapters;
    private final StorageRegistry storages;
    private final Path workDir;
    private final Clock clock;

    private AppContext(LoadedConfig loaded, Path logFile, Path logDir) {
        this.loaded = loaded;
        this.logFile = logFile;
        this.activityLog = new ActivityLog(logDir.resolve(ActivityLog.FILE_NAME));
        this.notifier = Notifiers.fromConfig(loaded.config().notifications());
        this.adapters = new DatabaseAdapters();
        this.storages = new StorageRegistry(loaded.config(), new StorageFactory());
        String configuredWorkDir = loaded.config().defaults().workDir();
        this.workDir = configuredWorkDir != null && !configuredWorkDir.isBlank()
                ? PathUtils.expand(configuredWorkDir)
                : Path.of(System.getProperty("java.io.tmpdir"), "dbbackup");
        this.clock = Clock.systemUTC();
    }

    /** Loads the configuration and configures logging. */
    public static AppContext create(Path configPath, Path logDirOverride, Verbosity verbosity) {
        LoadedConfig loaded = new ConfigLoader().load(configPath);
        Path logDir = LoggingConfigurator.logDirectory(loaded.config().logging(), logDirOverride);
        Path logFile = LoggingConfigurator.configure(loaded.config().logging(), logDir, verbosity);
        if (loaded.source() != null) {
            LOG.debug("Using configuration {}", loaded.source());
        }
        loaded.warnings().forEach(LOG::warn);
        return new AppContext(loaded, logFile, logDir);
    }

    public AppConfig config() {
        return loaded.config();
    }

    public Path configSource() {
        return loaded.source();
    }

    public Path logFile() {
        return logFile;
    }

    public ActivityLog activityLog() {
        return activityLog;
    }

    public Notifier notifier() {
        return notifier;
    }

    public DatabaseAdapters adapters() {
        return adapters;
    }

    public StorageRegistry storages() {
        return storages;
    }

    public Clock clock() {
        return clock;
    }

    public BackupService backupService() {
        return new BackupService(config(), adapters, storages, activityLog, notifier, workDir, clock);
    }

    public RestoreService restoreService() {
        return new RestoreService(adapters, storages, config().encryption(), activityLog, notifier, workDir, clock);
    }

    @Override
    public void close() {
        storages.close();
    }
}
