/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs scheduled jobs in the foreground. A timer checks every second which jobs are due and hands
 * them to a small worker pool. A job never runs twice at the same time: when its previous run is
 * still in progress, the new occurrence is skipped. After a pause (e.g. the machine slept), a
 * missed job runs once and then follows its schedule again.
 */
public class SchedulerDaemon {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulerDaemon.class);

    private final List<ScheduledJob> jobs;
    private final Consumer<ScheduledJob> runner;
    private final Clock clock;
    private final Map<String, Instant> nextRuns = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private ScheduledExecutorService timer;

    public SchedulerDaemon(List<ScheduledJob> jobs, Consumer<ScheduledJob> runner, Clock clock, int maxConcurrent) {
        this.jobs = List.copyOf(jobs);
        this.runner = runner;
        this.clock = clock;
        this.workers = Executors.newFixedThreadPool(Math.max(1, maxConcurrent), r -> {
            Thread thread = new Thread(r, "dbbackup-job");
            thread.setDaemon(false);
            return thread;
        });
        Instant now = clock.instant();
        for (ScheduledJob job : this.jobs) {
            running.put(job.name(), new AtomicBoolean());
            computeNext(job, now);
        }
    }

    /** Starts the timer; returns immediately. */
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "dbbackup-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleAtFixedRate(this::tickSafely, 1, 1, TimeUnit.SECONDS);
        LOG.info("Scheduler started with {} job(s)", jobs.size());
    }

    /** Blocks until {@link #stop(Duration)} has been called. */
    public void awaitTermination() throws InterruptedException {
        stopped.await();
    }

    /** Stops scheduling new runs and waits up to {@code grace} for running jobs to finish. */
    public synchronized void stop(Duration grace) {
        if (stopped.getCount() == 0) {
            return;
        }
        LOG.info("Stopping scheduler");
        if (timer != null) {
            timer.shutdownNow();
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Jobs still running after {}; interrupting them", grace);
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        stopped.countDown();
    }

    /** Next planned run of every job. */
    public Map<String, Instant> nextRuns() {
        Map<String, Instant> ordered = new LinkedHashMap<>();
        for (ScheduledJob job : jobs) {
            ordered.put(job.name(), nextRuns.get(job.name()));
        }
        return ordered;
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.error("Scheduler error: {}", e.getMessage(), e);
        }
    }

    /** Starts every job that is due. */
    void tick() {
        Instant now = clock.instant();
        for (ScheduledJob job : jobs) {
            Instant next = nextRuns.get(job.name());
            if (next != null && !now.isBefore(next)) {
                computeNext(job, now);
                trigger(job);
            }
        }
    }

    private void trigger(ScheduledJob job) {
        AtomicBoolean flag = running.get(job.name());
        if (!flag.compareAndSet(false, true)) {
            LOG.warn("Skipping scheduled run of '{}': the previous run is still in progress", job.name());
            return;
        }
        LOG.info("Starting scheduled job '{}'", job.name());
        try {
            workers.submit(() -> {
                try {
                    runner.accept(job);
                } catch (RuntimeException e) {
                    LOG.error("Scheduled job '{}' failed: {}", job.name(), e.getMessage());
                } finally {
                    flag.set(false);
                    LOG.info("Next run of '{}': {}", job.name(), nextRuns.get(job.name()));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            flag.set(false);
        }
    }

    private void computeNext(ScheduledJob job, Instant after) {
        ZonedDateTime from = after.atZone(job.schedule().zone());
        job.schedule().next(from).ifPresentOrElse(
                next -> nextRuns.put(job.name(), next.toInstant()),
                () -> nextRuns.remove(job.name()));
    }

    boolean isRunning(String name) {
        return running.get(name).get();
    }
}
