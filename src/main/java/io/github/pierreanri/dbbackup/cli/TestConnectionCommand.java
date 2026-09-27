package io.github.pierreanri.dbbackup.cli;

import java.util.ArrayList;
import java.util.List;

import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "test-connection", aliases = "test",
        description = "Check that databases are reachable with the configured credentials.%n"
                + "Tests every configured database when none is named.")
class TestConnectionCommand extends BaseCommand {

    @Parameters(paramLabel = "DATABASE", arity = "0..*", description = "Database profile(s) to test.")
    List<String> databases = new ArrayList<>();

    @Mixin
    DatabaseOptions dbOptions;

    @Override
    public Integer call() {
        AppConfig config = ctx().config();
        List<DatabaseConfig> targets = new ArrayList<>();
        if (!databases.isEmpty()) {
            databases.forEach(name -> targets.add(dbOptions.resolve(config, name)));
        } else if (dbOptions.isAdHoc()) {
            targets.add(dbOptions.resolve(config, null));
        } else if (!config.databases().isEmpty()) {
            config.databases().keySet().forEach(name -> targets.add(dbOptions.resolve(config, name)));
        } else {
            targets.add(dbOptions.resolve(config, null));
        }

        int failures = 0;
        for (DatabaseConfig db : targets) {
            try {
                String info = ctx().adapters().forType(db.type()).testConnection(db);
                out().printf("OK    %-20s %s  (%s)%n", db.name(), db.describe(), info);
            } catch (RuntimeException e) {
                failures++;
                out().printf("FAIL  %-20s %s%n      %s%n", db.name(), db.describe(), e.getMessage());
                if (db.type() != DatabaseType.SQLITE && e.getMessage() != null && e.getMessage().contains("Cannot run")) {
                    out().println("      (the client tools must be installed on this machine)");
                }
            }
        }
        out().flush();
        return failures == 0 ? OK : FAILED;
    }
}
