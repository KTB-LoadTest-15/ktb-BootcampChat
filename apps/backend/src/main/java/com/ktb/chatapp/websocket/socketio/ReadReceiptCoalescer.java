package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MessagesReadResponse;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;

/**
 * 읽음 커서 브로드캐스트를 방 단위로 coalescing한다.
 *
 * <p>커서 재설계로 payload는 이미 상수 크기지만, 방 N명이 같은 메시지를 읽으면 여전히 N번
 * broadcast(각 N명 수신) = N² 소켓 전송이 발생한다. 짧은 창({@code socketio.read.coalesce-window-ms})
 * 동안 같은 방의 커서 갱신을 모아 {@code {userId → lastReadTs}} 맵 1건으로 방출하면, 창당 broadcast가
 * 1회로 줄어 N²를 N으로 낮춘다. 더 큰 lastReadTs가 이전 값을 대체하므로 손실 없이 묶인다.
 *
 * <p>같은 방의 {@link #enqueue}는 dispatcher가 roomId 레인으로 직렬화하고, 버퍼 접근은
 * {@link ConcurrentHashMap#compute}/{@link ConcurrentHashMap#computeIfPresent}의 키 단위 원자성으로
 * enqueue-merge와 flush-take가 서로 끼어들지 않게 한다. window ≤ 0이면 즉시 broadcast(테스트/비활성).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ReadReceiptCoalescer {

    private final SocketIOServer socketIOServer;
    private final ScheduledExecutorService scheduler;
    private final long windowMs;

    private final Map<String, Map<String, Long>> pendingByRoom = new ConcurrentHashMap<>();

    public ReadReceiptCoalescer(
            SocketIOServer socketIOServer,
            @Qualifier("readReceiptScheduler") ScheduledExecutorService scheduler,
            @Value("${socketio.read.coalesce-window-ms:100}") long windowMs) {
        this.socketIOServer = socketIOServer;
        this.scheduler = scheduler;
        this.windowMs = windowMs;
    }

    /** 방 커서 갱신을 버퍼에 넣고(창 미설정이면 flush 예약), window ≤ 0이면 즉시 broadcast한다. */
    public void enqueue(String roomId, String userId, long lastReadTs) {
        if (windowMs <= 0) {
            broadcast(roomId, Map.of(userId, lastReadTs));
            return;
        }

        boolean[] firstForRoom = {false};
        pendingByRoom.compute(roomId, (room, cursors) -> {
            if (cursors == null) {
                cursors = new HashMap<>();
                firstForRoom[0] = true;
            }
            cursors.merge(userId, lastReadTs, Math::max);
            return cursors;
        });

        if (firstForRoom[0]) {
            try {
                scheduler.schedule(() -> flush(roomId), windowMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // 스케줄러 종료 등으로 예약 불가면 즉시 방출해 버퍼가 새지 않게 한다.
                flush(roomId);
            }
        }
    }

    /** 방 버퍼를 원자적으로 비우고 한 번에 broadcast한다. */
    void flush(String roomId) {
        Map<String, Long> batch = new HashMap<>();
        pendingByRoom.computeIfPresent(roomId, (room, cursors) -> {
            batch.putAll(cursors);
            return null; // 버퍼 제거(다음 enqueue가 새 창을 예약)
        });
        if (!batch.isEmpty()) {
            broadcast(roomId, batch);
        }
    }

    private void broadcast(String roomId, Map<String, Long> cursors) {
        socketIOServer.getRoomOperations(roomId)
                .sendEvent(MESSAGES_READ, new MessagesReadResponse(Collections.unmodifiableMap(cursors)));
    }
}
