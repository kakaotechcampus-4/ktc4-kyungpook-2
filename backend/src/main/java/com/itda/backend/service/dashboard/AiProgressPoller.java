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
 * <p>최근 5초 안에 진행 API 를 부른 화면이 있을 때만 묻는다. 실패하면 보관함을 그대로 두고, 오래 이어지면 보관함이
 * 알아서 비운다 ({@link AiProgressStore}).
 */
@Slf4j
@Component
public class AiProgressPoller {

    private final AiProgressProperties properties;
    private final AiProgressClient client;
    private final AiProgressTranslator translator;
    private final AiProgressStore store;
    private ScheduledExecutorService executor;
    /** 지금 이어지고 있는 실패의 종류. 실패가 아니면 {@code null}. 폴러 스레드만 읽고 쓴다. */
    private String failure;

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

    boolean isFailing() {
        return failure != null;
    }

    /**
     * 한 차례. AI 를 못 물은 것과 번역(DB)이 실패한 것을 똑같이 실패로 센다 — 어느 쪽이든 보관함이 새로 채워지지 않는다.
     *
     * <p>{@link Exception} 과 함께 {@link LinkageError} · {@link StackOverflowError} 까지 잡는다. 예약 작업은 무엇이든 한 번
     * 새면 조용히 멈추고 그러면 화면은 영영 빈 값이다. 메모리 부족 같은 다른 {@link Error} 는 잡지 않는다.
     */
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
                return;                     // 서버를 내리는 중에 끊긴 것이다
            }
            failed(e.getClass().getSimpleName(), e);
        }
    }

    /** 실패가 시작되거나 종류가 바뀔 때만 warn 을 남긴다. 같은 실패가 이어지는 동안은 1초마다 쌓지 않는다. */
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
