package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MessageReactionRequest;
import com.ktb.chatapp.dto.MessageReactionResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.service.message.MessageStore;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 리액션 처리 핸들러
 * 메시지 이모지 리액션 추가/제거 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReactionHandler {
    
    private final SocketIOServer socketIOServer;
    private final MessageStore messageStore;
    
    @OnEvent(MESSAGE_REACTION)
    public void handleMessageReaction(SocketIOClient client, MessageReactionRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null || userId.isBlank()) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            // 저장소 원자 연산으로 처리한다(Mongo 모드는 lost update 없음, Redis 모드는 best-effort).
            // read-modify-save를 핸들러에서 하지 않는다.
            Optional<Message> updatedOpt = switch (data.getType()) {
                case "add" -> messageStore.addReaction(data.getMessageId(), data.getReaction(), userId);
                case "remove" -> messageStore.removeReaction(data.getMessageId(), data.getReaction(), userId);
                case null, default -> null;
            };

            if (updatedOpt == null) {
                client.sendEvent(ERROR, Map.of("message", "지원하지 않는 리액션 타입입니다."));
                return;
            }

            Message message = updatedOpt.orElse(null);
            if (message == null) {
                client.sendEvent(ERROR, Map.of("message", "메시지를 찾을 수 없습니다."));
                return;
            }

            log.debug("Message reaction processed - type: {}, reaction: {}, messageId: {}, userId: {}",
                data.getType(), data.getReaction(), message.getId(), userId);

            MessageReactionResponse response = new MessageReactionResponse(
                message.getId(),
                message.getReactions()
            );

            socketIOServer.getRoomOperations(message.getRoomId())
                .sendEvent(MESSAGE_REACTION_UPDATE, response);

        } catch (Exception e) {
            log.error("Error handling messageReaction", e);
            client.sendEvent(ERROR, Map.of(
                "message", "리액션 처리 중 오류가 발생했습니다."
            ));
        }
    }
    
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }
}
