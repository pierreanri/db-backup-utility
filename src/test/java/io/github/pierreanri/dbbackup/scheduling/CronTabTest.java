package io.github.pierreanri.dbbackup.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pierreanri.dbbackup.config.ScheduleConfig;

class CronTabTest {

    private static ScheduledJob job(String name, String cron) {
        return ScheduledJob.of(new ScheduleConfig(name, "db", cron, null, null, null, null, null, null, null));
    }

    @Test
    void generatesOneLinePerJob() {
        String block = CronTab.block(List.of(job("nightly", "0 2 * * *"), job("hourly", "@hourly")),
                "'/usr/bin/java' -jar /opt/dbbackup.jar --config /etc/dbbackup.yml", "/var/log/dbbackup cron.log");

        assertThat(block).startsWith(CronTab.BEGIN).endsWith(CronTab.END + "\n")
                .contains("0 2 * * * '/usr/bin/java' -jar /opt/dbbackup.jar --config /etc/dbbackup.yml --quiet "
                        + "schedule run nightly >> '/var/log/dbbackup cron.log' 2>&1\n")
                .contains("@hourly '/usr/bin/java'");
    }

    @Test
    void mergesIntoAnExistingCrontab() {
        String block = CronTab.BEGIN + "\n0 2 * * * new\n" + CronTab.END + "\n";
        String existing = "MAILTO=me\n*/5 * * * * other-job\n\n" + CronTab.BEGIN + "\n0 1 * * * old\n" + CronTab.END + "\n";

        String merged = CronTab.merge(existing, block);
        assertThat(merged).isEqualTo("MAILTO=me\n*/5 * * * * other-job\n\n" + CronTab.BEGIN + "\n0 2 * * * new\n"
                + CronTab.END + "\n");
        assertThat(CronTab.merge(merged, block)).isEqualTo(merged);
        assertThat(CronTab.merge(merged, null)).isEqualTo("MAILTO=me\n*/5 * * * * other-job\n");
        assertThat(CronTab.merge("", block)).isEqualTo(block);
        assertThat(CronTab.merge(block, null)).isEmpty();
    }

    @Test
    void quotesShellArguments() {
        assertThat(CronTab.shellQuote("/opt/dbbackup.jar")).isEqualTo("/opt/dbbackup.jar");
        assertThat(CronTab.shellQuote("/my dir/it's")).isEqualTo("'/my dir/it'\\''s'");
    }
}
