package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.service.message.MessageStore;
import com.ktb.chatapp.service.readcursor.ReadCursorStore;
import com.ktb.chatapp.websocket.socketio.ReadReceiptCoalescer;
import com.ktb.chatapp.websocket.socketio.SocketDispatcher;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 읽음 상태 처리 핸들러 (read cursor 방식).
 *
 * <p>읽음 상태를 per-message가 아니라 (roomId, userId) 커서에 저장한다. 클라가 보낸
 * "마지막으로 읽은 메시지 id"의 <b>서버 timestamp</b>까지 커서를 단조 전진시키고, 실제 전진했을
 * 때만 방에 브로드캐스트한다. timestamp를 클라가 직접 정하지 않으므로 미래 시각 위조가 불가능하다.
 * 접근 검증은 인메모리 멤버십({@link UserRooms})으로 하고, 읽음당 DB 왕복은 메시지 조회 1회 +
 * 커서 upsert 1회뿐이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {

    private final MessageStore messageStore;
    private final ReadCursorStore readCursorStore;
    private final UserRooms userRooms;
    private final ReadReceiptCoalescer readReceiptCoalescer;
    private final SocketDispatcher socketDispatcher;

    // 읽음 처리(커서 upsert + coalescing)를 event-loop에서 분리해 연결(세션) 단위로 오프로드한다.
    // roomId 키로 묶으면 인기 방의 모든 읽음이 한 레인에 몰려 큐가 포화되므로, 세션 키로 레인을 분산한다.
    // 커서 advance는 단조($max/같은 세션 직렬화)라 순서 무관하고, coalescer는 키 단위 원자성으로 안전하다.
    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        socketDispatcher.dispatch(
                sessionKey(client),
                () -> processMarkAsRead(client, data),
                () -> client.sendEvent(ERROR,
                        Map.of("message", "서버가 혼잡합니다. 잠시 후 다시 시도해주세요.")));
    }

    void processMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (data == null || data.getRoomId() == null || data.getRoomId().isBlank()
                    || data.getLastReadMessageId() == null || data.getLastReadMessageId().isBlank()) {
                client.sendEvent(ERROR, Map.of("message", "Invalid request"));
                return;
            }

            String roomId = data.getRoomId();

            // 접근 검증: DB 조회 없이 인메모리 멤버십으로 확인(join 시 채워짐).
            if (!userRooms.isInRoom(userId, roomId)) {
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }

            // timestamp는 클라가 아니라 서버 메시지에서 얻는다(위조 방지). 배칭 경합으로 이미 사라진
            // 메시지면 조용히 무시하고, 방이 불일치하면 거부한다.
            Message message = messageStore.findById(data.getLastReadMessageId()).orElse(null);
            if (message == null) {
                return;
            }
            if (!roomId.equals(message.getRoomId())) {
                client.sendEvent(ERROR, Map.of("message", "Invalid room"));
                return;
            }

            long lastReadTs = message.toTimestampMillis();
            boolean advanced = readCursorStore.advance(roomId, userId, lastReadTs);
            if (!advanced) {
                // 중복/역행 — 상태 변화 없으므로 브로드캐스트 생략.
                return;
            }

            // 방 단위로 짧게 묶어 broadcast(N번 → 창당 1번).
            readReceiptCoalescer.enqueue(roomId, userId, lastReadTs);

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
            ));
        }
    }

    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }

    private static String sessionKey(SocketIOClient client) {
        var sessionId = client.getSessionId();
        return sessionId != null ? sessionId.toString() : "unknown";
    }
}
