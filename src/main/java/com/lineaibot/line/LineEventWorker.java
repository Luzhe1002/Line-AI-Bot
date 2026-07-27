package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LineEventWorker {

    private final LineRepository repository;
    private final LineEventProcessor processor;
    private final AppProperties properties;
    private final ExecutorService executor;
    private final Semaphore permits;

    public LineEventWorker(
            LineRepository repository,
            LineEventProcessor processor,
            AppProperties properties,
            @Qualifier("lineEventExecutor") ExecutorService executor,
            @Qualifier("lineEventPermits") Semaphore permits) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties;
        this.executor = executor;
        this.permits = permits;
    }

    @Scheduled(fixedDelayString = "${app.line-worker-delay-ms:250}")
    public void runOnce() {
        if (!properties.isLineWorkerEnabled()) {
            return;
        }
        Instant now = Instant.now();
        repository.recoverStaleEvents(now.minus(2, ChronoUnit.MINUTES), now);
        for (String eventId : repository.findReadyEventIds(
                now, properties.getLineWorkerConcurrency())) {
            if (!permits.tryAcquire()) {
                return;
            }
            if (!repository.claimEvent(eventId, Instant.now())) {
                permits.release();
                continue;
            }
            try {
                executor.execute(() -> {
                    try {
                        processor.process(eventId);
                    } finally {
                        permits.release();
                    }
                });
            } catch (RejectedExecutionException exception) {
                permits.release();
                repository.releaseClaim(
                        eventId, Instant.now(), "LINE event executor rejected the task");
            }
        }
    }
}
