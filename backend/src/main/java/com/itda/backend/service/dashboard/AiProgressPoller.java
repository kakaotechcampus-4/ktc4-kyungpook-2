package com.itda.backend.service.dashboard;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.itda.backend.service.agent.AiProgressClient;
import com.itda.backend.service.agent.AiProgressProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiProgressPoller {

    private final AiProgressProperties properties;
    private final AiProgressClient client;
    private final AiProgressTranslator translator;
    private final AiProgressStore store;
    private ScheduledExecutorService executor;
    private String failure;

    @PostConstruct
    public void start() {
        if (!properties.enabled()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-progress-poller");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMs = properties.interval().toMillis();
        executor.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("ai progress poller started interval={}ms timeout={}ms", intervalMs, properties.timeout().toMillis());
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    boolean isFailing() {
        return failure != null;
    }

    void tick() {
        try {
            if (!store.askedRecently()) {
                return;
            }
            var items = client.fetch();
            if (items.isEmpty()) {
                failed("ai unreachable", null);
                return;
            }
            store.replace(translator.translate(items.get()));
            if (failure != null) {
                log.info("ai progress recovered after {}", failure);
                failure = null;
            }
        } catch (Exception | LinkageError | StackOverflowError e) {
            if (executor != null && executor.isShutdown()) {
                return;
            }
            failed(e.getClass().getSimpleName(), e);
        }
    }

    private void failed(String kind, Throwable cause) {
        if (kind.equals(failure)) {
            log.debug("ai progress still failing: {}", kind);
            return;
        }
        failure = kind;
        if (cause == null) {
            log.warn("ai progress failed: {} (screens fall back to fixed text)", kind);
        } else {
            log.warn("ai progress failed: {}", kind, cause);
        }
    }
}
