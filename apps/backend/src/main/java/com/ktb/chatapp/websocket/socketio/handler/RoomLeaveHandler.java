package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.UserBatchLoader;
import com.ktb.chatapp.service.message.MessageStore;
import com.ktb.chatapp.websocket.socketio.SocketDispatcher;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 퇴장 처리 핸들러
 * 채팅방 퇴장, 스트리밍 세션 종료, 참가자 목록 업데이트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomLeaveHandler {

    private final SocketIOServer socketIOServer;
    private final MessageStore messageStore;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserBatchLoader userBatchLoader;
    private final UserRooms userRooms;
    private final MessageResponseMapper messageResponseMapper;
    private final SocketDispatcher socketDispatcher;

    // 방 퇴장 처리를 event-loop에서 분리해 방(roomId) 단위로 오프로드한다.
    @OnEvent(LEAVE_ROOM)
    public void handleLeaveRoom(SocketIOClient client, String roomId) {
        socketDispatcher.dispatch(
                orderingKey(roomId, client),
                () -> processLeaveRoom(client, roomId),
                () -> client.sendEvent(ERROR,
                        Map.of("message", "서버가 혼잡합니다. 잠시 후 다시 시도해주세요.")));
    }

    void processLeaveRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (!userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} is not in room {}", userId, roomId);
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            Room room = roomRepository.findById(roomId).orElse(null);
            
            if (user == null || room == null) {
                log.warn("Room {} not found or user {} has no access", roomId, userId);
                return;
            }
            
            roomRepository.removeParticipant(roomId, userId);
            
            client.leaveRoom(roomId);
            userRooms.remove(userId, roomId);
            
            log.info("User {} left room {}", userName, room.getName());
            
            log.debug("Leave room cleanup - roomId: {}, userId: {}", roomId, userId);
            
            sendSystemMessage(roomId, userName + "님이 퇴장하였습니다.");
            broadcastParticipantList(roomId);

        } catch (Exception e) {
            log.error("Error handling leaveRoom", e);
            client.sendEvent(ERROR, Map.of("message", "채팅방 퇴장 중 오류가 발생했습니다."));
        }
    }
    
    private void sendSystemMessage(String roomId, String content) {
        try {
            Message systemMessage = new Message();
            systemMessage.setRoomId(roomId);
            systemMessage.setContent(content);
            systemMessage.setType(MessageType.system);
            systemMessage.setTimestamp(LocalDateTime.now());
            systemMessage.setMentions(new ArrayList<>());
            systemMessage.setReactions(new HashMap<>());
            systemMessage.setReaders(new ArrayList<>());
            systemMessage.setMetadata(new HashMap<>());

            Message savedMessage = messageStore.add(systemMessage);
            MessageResponse response = messageResponseMapper.mapToMessageResponse(savedMessage, null);

            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, response);

        } catch (Exception e) {
            log.error("Error sending system message", e);
        }
    }
    
    private void broadcastParticipantList(String roomId) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return;
        }
        
        // 참가자 id 목록을 한 번의 조회로 일괄 해소(참가자당 findById N+1 제거).
        var participantIds = roomOpt.get().getParticipantIds();
        Map<String, User> participantsById = userBatchLoader.findByIds(participantIds);
        var participantList = participantIds.stream()
                .map(participantsById::get)
                .filter(Objects::nonNull)
                .map(UserResponse::from)
                .toList();
        
        if (participantList.isEmpty()) {
            return;
        }
        
        socketIOServer.getRoomOperations(roomId)
                .sendEvent(PARTICIPANTS_UPDATE, participantList);
    }

    private static String orderingKey(String roomId, SocketIOClient client) {
        if (roomId != null) {
            return roomId;
        }
        var sessionId = client.getSessionId();
        return sessionId != null ? sessionId.toString() : "unknown";
    }

    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
}
