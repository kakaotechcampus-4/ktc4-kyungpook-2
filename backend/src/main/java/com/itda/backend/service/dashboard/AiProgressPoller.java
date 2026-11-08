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
 * 알아서 비운다. 어떤 예외도 다음 차례를 막지 않는다.
 */
@Slf4j
@Component
public class AiProgressPoller {

    private final AiProgressProperties properties;
    private final AiProgressClient client;
    private final AiProgressTranslator translator;
    private final AiProgressStore store;
    private ScheduledExecutorService executor;
    /** 실패가 이어지는 동안 1초마다 경고를 쌓지 않으려고 둔다. 폴러 스레드만 읽고 쓴다. */
    private boolean failing;

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

    /**
     * 한 차례. {@link Throwable} 까지 잡는다 — 예약 작업은 무엇이든 한 번 새면 조용히 멈추고, 그러면 화면은 영영 빈 값이다.
     * AI 를 못 물은 것(클라이언트가 이미 debug 로 남김)은 여기서 실패로 세지 않는다. 번역(DB) 실패는 이어지는 동안
     * 처음 한 번만 warn 으로 남긴다.
     */
    void tick() {
        try {
            if (!store.askedRecently()) {
                return;
            }
            client.fetch().ifPresent(items -> store.replace(translator.translate(items)));
            if (failing) {
                failing = false;
                log.info("ai progress tick recovered");
            }
        } catch (Throwable e) {
            if (failing) {
                log.debug("ai progress tick still failing: {}", e.toString());
            } else {
                failing = true;
                log.warn("ai progress tick failed: {}", e.toString(), e);
            }
        }
    }
}
