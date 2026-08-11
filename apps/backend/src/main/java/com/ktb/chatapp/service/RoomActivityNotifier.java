package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 새 메시지가 저장되면 채팅방 목록의 활성도 지표를 갱신하도록 알린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomActivityNotifier {

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 메시지 저장 후 방 활성도 갱신을 알린다.
     *
     * <p>{@code @Async}: 이 메서드는 메시지 전송 hot path(Socket.IO worker 스레드)에서 호출되므로,
     * 최근 메시지 수 집계 쿼리와 room-list 브로드캐스트를 전용 풀({@code socketBroadcastExecutor})로
     * 오프로드해 event-loop 점유를 없앤다. 활성도는 각 이벤트가 독립적인 count 스냅샷이라 순서에
     * 의존하지 않으므로 비동기화해도 안전하다. (AI 스트리밍 이벤트는 순서 보장이 필요해 동기 유지)
     */
    @Async("socketBroadcastExecutor")
    public void notifyMessageStored(String roomId) {
        if (roomId == null) {
            return;
        }

        try {
            int recentMessageCount = recentMessageCounter.countRecentMessages(roomId);
            eventPublisher.publishEvent(new RoomActivityEvent(this, roomId, recentMessageCount));
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발행 실패: roomId={}", roomId, e);
        }
    }
}
