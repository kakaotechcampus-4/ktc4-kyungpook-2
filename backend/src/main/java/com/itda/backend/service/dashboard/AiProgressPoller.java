package com.itda.backend.service.dashboard;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.itda.backend.service.agent.AiProgressClient;
import com.itda.backend.service.agent.AiProgressProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 에게 에이전트가 지금 어느 단계인지 {@code app.ai-progress.interval} 마다 물어 {@link AiProgressStore} 에 담는다 (#453).
 * 화면이 몇 개든 AI 에는 이 폴러 하나만 묻는다.
 *
 * <p><b>전용 스레드 하나로 돈다.</b> 공용 스케줄러 풀(5개)은 에이전트 워커가 AI 응답을 최대 210초씩 붙잡고 알림·정리
 * 작업도 쓰므로, 거기 끼우면 정작 오래 걸릴 때 묻지 못한다.
 *
 * <p>최근 5초 안에 진행 API 를 부른 화면이 있을 때만 묻는다. 실패하면 보관함을 그대로 두고, 3초 넘게 이어지면 보관함이
 * 알아서 비운다. 어떤 예외도 다음 차례를 막지 않는다 — 예약 작업은 예외가 한 번 새면 다시 돌지 않는다.
 */
@Slf4j
@Component
public class AiProgressPoller {

    private final AiProgressProperties properties;
    private final AiProgressClient client;
    private final AiProgressTranslator translator;
    private final AiProgressStore store;
    private ScheduledExecutorService executor;

    public AiProgressPoller(AiProgressProperties properties, AiProgressClient client, AiProgressTranslator translator,
            AiProgressStore store) {
        this.properties = properties;
        this.client = client;
        this.translator = translator;
        this.store = store;
    }

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

    void tick() {
        try {
            if (!store.askedRecently()) {
                return;
            }
            client.fetch().ifPresent(items -> store.replace(translator.translate(items)));
        } catch (Exception e) {
            log.warn("ai progress tick failed: {}", e.toString());
        }
    }
}
