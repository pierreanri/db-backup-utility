/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.pierreanri.dbbackup.config.ScheduleConfig;

class SchedulerDaemonTest {

    private static ScheduledJob job(String name, String cron) {
        return ScheduledJob.of(new ScheduleConfig(name, "db", cron, null, null, null, null, null, null, "UTC", null));
    }

    @Test
    void runsJobsWhenTheyAreDue() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T01:59:30Z"));
        List<String> runs = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        SchedulerDaemon daemon = new SchedulerDaemon(List.of(job("nightly", "0 2 * * *"), job("hourly", "@hourly")),
                j -> {
                    runs.add(j.name() + "@" + clock.instant());
                    done.countDown();
                }, clock, 2);

        assertThat(daemon.nextRuns()).containsEntry("nightly", Instant.parse("2026-01-01T02:00:00Z"))
                .containsEntry("hourly", Instant.parse("2026-01-01T02:00:00Z"));
        daemon.tick();
        assertThat(runs).isEmpty();

        clock.set(Instant.parse("2026-01-01T02:00:00Z"));
        daemon.tick();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runs).containsExactlyInAnyOrder("nightly@2026-01-01T02:00:00Z", "hourly@2026-01-01T02:00:00Z");
        assertThat(daemon.nextRuns()).containsEntry("nightly", Instant.parse("2026-01-02T02:00:00Z"))
                .containsEntry("hourly", Instant.parse("2026-01-01T03:00:00Z"));
        daemon.stop(Duration.ofSeconds(5));
    }

    @Test
    void skipsARunWhileThePreviousOneIsStillRunning() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        List<Instant> runs = new CopyOnWriteArrayList<>();
        SchedulerDaemon daemon = new SchedulerDaemon(List.of(job("slow", "* * * * *")), j -> {
            runs.add(clock.instant());
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, clock, 2);

        clock.set(Instant.parse("2026-01-01T00:01:00Z"));
        daemon.tick();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        clock.set(Instant.parse("2026-01-01T00:02:00Z"));
        daemon.tick();
        assertThat(runs).hasSize(1);
        assertThat(daemon.isRunning("slow")).isTrue();

        release.countDown();
        daemon.stop(Duration.ofSeconds(5));
        assertThat(daemon.isRunning("slow")).isFalse();
    }

    @Test
    void runsMissedJobsOnlyOnceAfterAPause() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:30Z"));
        List<Instant> runs = new CopyOnWriteArrayList<>();
        SchedulerDaemon daemon = new SchedulerDaemon(List.of(job("minutely", "* * * * *")), j -> runs.add(clock.instant()),
                clock, 1);

        clock.set(Instant.parse("2026-01-01T05:00:30Z"));
        daemon.tick();
        daemon.tick();
        daemon.stop(Duration.ofSeconds(5));

        assertThat(runs).hasSize(1);
        assertThat(daemon.nextRuns()).containsEntry("minutely", Instant.parse("2026-01-01T05:01:00Z"));
    }

    @Test
    void jobFailuresDoNotStopTheScheduler() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:30Z"));
        CountDownLatch attempts = new CountDownLatch(2);
        SchedulerDaemon daemon = new SchedulerDaemon(List.of(job("flaky", "* * * * *")), j -> {
            attempts.countDown();
            throw new IllegalStateException("boom");
        }, clock, 1);

        clock.set(Instant.parse("2026-01-01T00:01:00Z"));
        daemon.tick();
        Thread.sleep(200);
        clock.set(Instant.parse("2026-01-01T00:02:00Z"));
        daemon.tick();

        assertThat(attempts.await(5, TimeUnit.SECONDS)).isTrue();
        daemon.stop(Duration.ofSeconds(5));
    }

    @Test
    void startAndStopTheTimer() throws InterruptedException {
        SchedulerDaemon daemon = new SchedulerDaemon(List.of(job("x", "@yearly")), j -> {
        }, Clock.systemUTC(), 1);
        daemon.start();
        Thread stopper = new Thread(() -> daemon.stop(Duration.ofSeconds(1)));
        stopper.start();
        daemon.awaitTermination();
        stopper.join();
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
