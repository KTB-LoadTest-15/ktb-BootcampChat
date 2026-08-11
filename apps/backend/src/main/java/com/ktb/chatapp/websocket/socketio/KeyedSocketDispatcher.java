package com.ktb.chatapp.websocket.socketio;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link SocketDispatcher}의 키드 단일스레드 레인 구현.
 *
 * <p>N개의 레인(각각 단일 스레드 + 바운드 큐)을 두고 {@code orderingKey}의 해시로 레인을 고른다.
 * 같은 key는 항상 같은 레인 → 단일 스레드에서 제출 순서대로 실행되므로 <b>key 단위 FIFO</b>가 보장된다.
 * 서로 다른 key는 서로 다른 레인에서 병렬 처리될 수 있어 event-loop을 블로킹 없이 비운다.
 *
 * <p>포화 시 {@code CallerRunsPolicy}(event-loop 인라인 실행)를 쓰지 않는다 — 인라인 실행은 (1) event-loop을
 * 다시 블로킹하고 (2) 큐에 밀린 앞선 작업과 순서가 뒤집힐 수 있다. 대신 {@code AbortPolicy}로 거부하고
 * 호출측 {@code onReject}(클라이언트 혼잡 통지)로 넘겨 <b>순서 보장과 명시적 백프레셔</b>를 동시에 지킨다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class KeyedSocketDispatcher implements SocketDispatcher {

    private final ThreadPoolExecutor[] lanes;

    public KeyedSocketDispatcher(
            @Value("${socketio.worker.lanes:0}") int laneCount,
            @Value("${socketio.worker.queue-capacity:1000}") int queueCapacity,
            MeterRegistry meterRegistry) {
        int lanes = laneCount > 0
                ? laneCount
                : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        this.lanes = new ThreadPoolExecutor[lanes];
        for (int i = 0; i < lanes; i++) {
            final int laneId = i;
            AtomicInteger threadIdx = new AtomicInteger();
            this.lanes[i] = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(queueCapacity),
                    r -> {
                        Thread t = new Thread(r, "socket-worker-" + laneId + "-" + threadIdx.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }

        // 관측: 전체 레인에 밀린 작업 수(포화 감지용)
        Gauge.builder("socketio.worker.queued", this, KeyedSocketDispatcher::totalQueued)
                .description("Total tasks queued across Socket.IO worker lanes")
                .register(meterRegistry);

        log.info("KeyedSocketDispatcher initialized with {} lanes, queueCapacity={}", lanes, queueCapacity);
    }

    @Override
    public void dispatch(String orderingKey, Runnable task, Runnable onReject) {
        ThreadPoolExecutor lane = lanes[Math.floorMod(orderingKey.hashCode(), lanes.length)];
        try {
            lane.execute(task);
        } catch (RejectedExecutionException e) {
            // 큐 포화: 순서를 지키기 위해 인라인 실행하지 않고 거부 처리로 넘긴다.
            onReject.run();
        }
    }

    private int totalQueued() {
        int sum = 0;
        for (ThreadPoolExecutor lane : lanes) {
            sum += lane.getQueue().size();
        }
        return sum;
    }

    @PreDestroy
    public void shutdown() {
        for (ThreadPoolExecutor lane : lanes) {
            lane.shutdownNow();
        }
    }
}
