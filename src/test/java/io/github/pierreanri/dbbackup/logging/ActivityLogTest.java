package io.github.pierreanri.dbbackup.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.logging.ActivityEntry.Operation;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Status;

class ActivityLogTest {

    @TempDir
    Path tmp;

    private static ActivityEntry entry(String db, Status status) {
        return new ActivityEntry(Instant.parse("2026-01-02T03:04:05Z"), Operation.BACKUP, status, db, "sqlite",
                db + "-20260102T030405Z", 1234L, 56L, List.of("local:/backups/x"), "cli", null, "host");
    }

    @Test
    void appendsAndReadsEntries() {
        ActivityLog log = new ActivityLog(tmp.resolve("logs/history.jsonl"));
        assertThat(log.readAll()).isEmpty();

        log.append(entry("a", Status.SUCCESS));
        log.append(entry("b", Status.FAILED));
        log.append(entry("c", Status.SUCCESS));

        List<ActivityEntry> all = log.readAll();
        assertThat(all).extracting(ActivityEntry::database).containsExactly("a", "b", "c");
        assertThat(all.get(0)).isEqualTo(entry("a", Status.SUCCESS));
        assertThat(log.readLast(2)).extracting(ActivityEntry::database).containsExactly("b", "c");
    }

    @Test
    void skipsMalformedLines() throws IOException {
        ActivityLog log = new ActivityLog(tmp.resolve("history.jsonl"));
        log.append(entry("a", Status.SUCCESS));
        Files.writeString(log.file(), "not json\n\n", StandardOpenOption.APPEND);
        log.append(entry("b", Status.SUCCESS));

        assertThat(log.readAll()).extracting(ActivityEntry::database).containsExactly("a", "b");
    }

    @Test
    void concurrentAppendsDoNotInterleave() throws InterruptedException {
        ActivityLog log = new ActivityLog(tmp.resolve("history.jsonl"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 200; i++) {
            String db = "db" + i;
            pool.submit(() -> log.append(entry(db, Status.SUCCESS)));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(log.readAll()).hasSize(200);
    }
}
