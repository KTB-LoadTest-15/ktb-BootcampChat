package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.UserBatchLoader;
import com.ktb.chatapp.service.message.MessageStore;
import com.ktb.chatapp.service.readcursor.ReadCursorStore;
import com.ktb.chatapp.websocket.socketio.SocketDispatcher;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 입장 처리 핸들러
 * 채팅방 입장, 참가자 관리, 초기 메시지 로드 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    private final SocketIOServer socketIOServer;
    private final MessageStore messageStore;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserBatchLoader userBatchLoader;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    private final MessageResponseMapper messageResponseMapper;
    private final RoomLeaveHandler roomLeaveHandler;
    private final SocketDispatcher socketDispatcher;
    private final ReadCursorStore readCursorStore;

    // 방 입장 처리(DB 조회·저장·브로드캐스트)를 event-loop에서 분리해 방(roomId) 단위로 오프로드한다.
    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        socketDispatcher.dispatch(
                orderingKey(roomId, client),
                () -> processJoinRoom(client, roomId),
                () -> client.sendEvent(JOIN_ROOM_ERROR,
                        Map.of("message", "서버가 혼잡합니다. 잠시 후 다시 시도해주세요.")));
    }

    void processJoinRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
                return;
            }
            
            if (userRepository.findById(userId).isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                return;
            }
            
            if (roomRepository.findById(roomId).isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }
            
            // 이미 해당 방의 멤버 → 새로고침/재연결에 의한 재조인.
            // 본인에게는 히스토리(메시지·참가자·읽음커서)를 그대로 돌려주되, 방 전체에는 입장 알림도
            // 참가자 목록 갱신도 브로드캐스트하지 않는다(입퇴장 스팸·N 팬아웃·참가자 DB churn 제거).
            if (userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} re-joining room {} (already a member) — silent", userId, roomId);
                client.joinRoom(roomId);
                buildJoinSuccessResponse(roomId, userId).ifPresentOrElse(
                        resp -> client.sendEvent(JOIN_ROOM_SUCCESS, resp),
                        () -> client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다.")));
                return;
            }

            roomRepository.addParticipant(roomId, userId);

            // Join socket room and add to user's room set
            client.joinRoom(roomId);
            userRooms.add(userId, roomId);

            Message joinMessage = Message.builder()
                .roomId(roomId)
                .content(userName + "님이 입장하였습니다.")
                .type(MessageType.system)
                .timestamp(LocalDateTime.now())
                .mentions(new ArrayList<>())
                .reactions(new HashMap<>())
                .metadata(new HashMap<>())
                .build();

            joinMessage = messageStore.add(joinMessage);

            // 응답(히스토리·참가자·읽음커서) 구성 — addParticipant 반영된 최신 상태로.
            Optional<JoinRoomSuccessResponse> responseOpt = buildJoinSuccessResponse(roomId, userId);
            if (responseOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }
            JoinRoomSuccessResponse response = responseOpt.get();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            // 입장 메시지 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                .sendEvent(MESSAGE, messageResponseMapper.mapToMessageResponse(joinMessage, null));

            // 참가자 목록 업데이트 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                .sendEvent(PARTICIPANTS_UPDATE, response.getParticipants());

            log.info("User {} joined room {} successfully. Message count: {}, hasMore: {}",
                userName, roomId, response.getMessages().size(), response.isHasMore());

        } catch (Exception e) {
            log.error("Error handling joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                "message", e.getMessage() != null ? e.getMessage() : "채팅방 입장에 실패했습니다."
            ));
        }
    }
    
    /**
     * 입장 응답(초기 메시지 30건 + 참가자 목록 + 읽음커서)을 구성한다. 최초 입장과 조용한 재조인이
     * 공유한다. 방이 없으면 empty. 브로드캐스트/DB 쓰기 없이 조회만 한다(호출부가 본인에게만 전송).
     */
    private Optional<JoinRoomSuccessResponse> buildJoinSuccessResponse(String roomId, String userId) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return Optional.empty();
        }

        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse messageLoadResult = messageLoader.loadMessages(req, userId);

        // 참가자 정보 조회 — id 목록을 한 번의 조회로 일괄 해소(참가자당 findById N+1 제거).
        var participantIds = roomOpt.get().getParticipantIds();
        Map<String, User> participantsById = userBatchLoader.findByIds(participantIds);
        List<UserResponse> participants = participantIds.stream()
                .map(participantsById::get)
                .filter(Objects::nonNull)
                .map(UserResponse::from)
                .toList();

        return Optional.of(JoinRoomSuccessResponse.builder()
                .roomId(roomId)
                .participants(participants)
                .messages(messageLoadResult.getMessages())
                .hasMore(messageLoadResult.isHasMore())
                .activeStreams(Collections.emptyList())
                .readCursors(readCursorStore.findByRoom(roomId))
                .build());
    }

    // 순서 보장 key: 방이 있으면 roomId(방 단위 FIFO), 없으면 연결 세션 id.
    private static String orderingKey(String roomId, SocketIOClient client) {
        if (roomId != null) {
            return roomId;
        }
        var sessionId = client.getSessionId();
        return sessionId != null ? sessionId.toString() : "unknown";
    }

    private SocketUser getUser(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.name() : null;
    }
}
