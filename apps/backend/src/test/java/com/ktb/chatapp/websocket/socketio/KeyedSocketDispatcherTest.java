package com.ktb.chatapp.websocket.socketio;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KeyedSocketDispatcher의 오프로드/순서 보장/포화 거부를 검증한다(이벤트 루프 오프로드, #1).
 */
class KeyedSocketDispatcherTest {

    private KeyedSocketDispatcher dispatcher(int lanes, int queueCapacity) {
        return new KeyedSocketDispatcher(lanes, queueCapacity, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("호출 스레드가 아니라 전용 socket-worker 스레드에서 실행된다(오프로드)")
    void dispatch_runsOnWorkerThread_notCaller() throws InterruptedException {
        KeyedSocketDispatcher dispatcher = dispatcher(2, 100);
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> runThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        dispatcher.dispatch("room-1", () -> {
            runThread.set(Thread.currentThread().getName());
            done.countDown();
        }, () -> {});

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runThread.get()).isNotEqualTo(callerThread);
        assertThat(runThread.get()).startsWith("socket-worker-");
    }

    @Test
    @DisplayName("같은 key의 작업은 제출 순서대로(FIFO) 실행된다")
    void dispatch_sameKey_preservesOrder() throws InterruptedException {
        KeyedSocketDispatcher dispatcher = dispatcher(4, 1000);
        int n = 200;
        List<Integer> executed = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final int seq = i;
            dispatcher.dispatch("same-room", () -> {
                executed.add(seq);
                done.countDown();
            }, () -> done.countDown());
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        // 같은 key는 단일 레인 → 제출 순서 그대로
        assertThat(executed).containsExactlyElementsOf(IntStream.range(0, n).boxed().toList());
    }

    @Test
    @DisplayName("레인 큐가 가득 차면 task 대신 onReject를 호출한다(순서 보장 위해 인라인 실행 안 함)")
    void dispatch_whenLaneSaturated_invokesOnReject() throws InterruptedException {
        // 레인 1개 + 큐 용량 1. 첫 작업으로 워커를 붙잡고, 큐를 채운 뒤 추가 제출은 거부되어야 한다.
        KeyedSocketDispatcher dispatcher = dispatcher(1, 1);
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch firstRunning = new CountDownLatch(1);

        // 1) 워커 스레드를 점유(실행 중)
        dispatcher.dispatch("k", () -> {
            firstRunning.countDown();
            try {
                block.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> {});
        assertThat(firstRunning.await(5, TimeUnit.SECONDS)).isTrue();

        // 2) 큐(용량 1)를 채운다 — 아직 거부 아님
        AtomicBoolean secondRejected = new AtomicBoolean(false);
        dispatcher.dispatch("k", () -> {}, () -> secondRejected.set(true));
        assertThat(secondRejected.get()).isFalse();

        // 3) 워커 점유 + 큐 가득 → 다음 제출은 거부(onReject 호출)
        AtomicBoolean thirdRejected = new AtomicBoolean(false);
        dispatcher.dispatch("k", () -> {}, () -> thirdRejected.set(true));
        assertThat(thirdRejected.get()).isTrue();

        block.countDown();
        dispatcher.shutdown();
    }
}
