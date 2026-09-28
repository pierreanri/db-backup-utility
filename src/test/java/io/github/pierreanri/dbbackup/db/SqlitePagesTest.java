package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;

class SqlitePagesTest {

    @TempDir
    Path tmp;

    private static void sql(Path db, String... statements) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement stmt = conn.createStatement()) {
            for (String statement : statements) {
                stmt.execute(statement);
            }
        }
    }

    @Test
    void changesRebuildTheExactFileWhenGrowingAndShrinking() throws Exception {
        Path db = tmp.resolve("a.db");
        sql(db, "CREATE TABLE t (x TEXT)", "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 3000) "
                + "INSERT INTO t SELECT printf('%0500d', i) FROM n");
        Path base = Files.copy(db, tmp.resolve("base.db"));
        SqlitePages.PageState baseState = SqlitePages.computeState(base);
        Path stateFile = tmp.resolve("base.state");
        SqlitePages.writeState(baseState, stateFile);
        assertThat(SqlitePages.readState(stateFile).pageCount()).isEqualTo(baseState.pageCount());

        sql(db, "UPDATE t SET x = 'changed' WHERE rowid % 100 = 0", "INSERT INTO t VALUES ('new row')");
        Path grown = Files.copy(db, tmp.resolve("grown.db"));
        SqlitePages.ChangeSummary growth = SqlitePages.writeChanges(grown, SqlitePages.readState(stateFile),
                tmp.resolve("1.pages"));
        assertThat(growth.changedPages()).isPositive().isLessThan(baseState.pageCount());

        sql(db, "DELETE FROM t WHERE rowid > 100", "VACUUM");
        Path shrunk = Files.copy(db, tmp.resolve("shrunk.db"));
        SqlitePages.writeChanges(shrunk, growth.state(), tmp.resolve("2.pages"));
        assertThat(Files.size(shrunk)).isLessThan(Files.size(base));

        Path rebuilt = Files.copy(base, tmp.resolve("rebuilt.db"));
        SqlitePages.applyChanges(rebuilt, tmp.resolve("1.pages"));
        assertThat(Files.readAllBytes(rebuilt)).isEqualTo(Files.readAllBytes(grown));
        SqlitePages.applyChanges(rebuilt, tmp.resolve("2.pages"));
        assertThat(Files.readAllBytes(rebuilt)).isEqualTo(Files.readAllBytes(shrunk));
    }

    @Test
    void rejectsPageSizeChangesAndForeignFiles() throws Exception {
        Path db = tmp.resolve("a.db");
        sql(db, "CREATE TABLE t (x)");
        SqlitePages.PageState state = SqlitePages.computeState(db);
        sql(db, "PRAGMA page_size = 8192", "VACUUM");

        assertThatThrownBy(() -> SqlitePages.writeChanges(db, state, tmp.resolve("x.pages")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("page size of the SQLite database changed");
        Path text = Files.writeString(tmp.resolve("x.txt"), "hello");
        assertThatThrownBy(() -> SqlitePages.pageSize(text)).isInstanceOf(DbBackupException.class);
        assertThatThrownBy(() -> SqlitePages.readState(text)).isInstanceOf(DbBackupException.class);
    }
}
