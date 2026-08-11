package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.service.UserBatchLoader;
import com.ktb.chatapp.service.message.MessageStore;
import com.ktb.chatapp.service.readcursor.ReadCursorStore;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.JOIN_ROOM_SUCCESS;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.PARTICIPANTS_UPDATE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomJoinHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageStore messageStore;
    @Mock private RoomRepository roomRepository;
    @Mock private UserBatchLoader userBatchLoader;
    @Mock private UserRooms userRooms;
    @Mock private MessageLoader messageLoader;
    @Mock private MessageResponseMapper messageResponseMapper;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private ReadCursorStore readCursorStore;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private RoomJoinHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RoomJoinHandler(
                socketIOServer,
                messageStore,
                roomRepository,
                userBatchLoader,
                userRooms,
                messageLoader,
                messageResponseMapper,
                roomLeaveHandler,
                (key, task, onReject) -> task.run(),
                readCursorStore);
    }

    @Test
    void handleJoinRoom_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleJoinRoom(client, "room-1");

        verify(client).sendEvent(eq(JOIN_ROOM_ERROR), any());
    }

    @Test
    void handleJoinRoom_addsParticipantLoadsMessagesAndBroadcasts() {
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        Room room = Room.builder().id("room-1").name("room").participantIds(Set.of("user-1")).build();
        MessageResponse joinMessageResponse = MessageResponse.builder()
                .id("message-1")
                .roomId("room-1")
                .content("tester님이 입장하였습니다.")
                .type(MessageType.system)
                .timestamp(1L)
                .build();
        FetchMessagesResponse loadResponse = FetchMessagesResponse.builder()
                .messages(List.of())
                .hasMore(false)
                .build();

        when(client.get("user")).thenReturn(socketUser);
        when(userBatchLoader.findByIds(any())).thenReturn(Map.of("user-1", user));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(false);
        when(messageStore.add(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId("message-1");
            message.setTimestamp(LocalDateTime.now());
            return message;
        });
        when(messageLoader.loadMessages(any(FetchMessagesRequest.class), eq("user-1")))
                .thenReturn(loadResponse);
        when(messageResponseMapper.mapToMessageResponse(any(Message.class), eq(null)))
                .thenReturn(joinMessageResponse);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleJoinRoom(client, "room-1");

        verify(roomRepository, times(1)).findById("room-1");
        verify(roomRepository).addParticipant("room-1", "user-1");
        verify(client).joinRoom("room-1");
        verify(userRooms).add("user-1", "room-1");
        verify(client).sendEvent(eq(JOIN_ROOM_SUCCESS), any());
        verify(roomOperations).sendEvent(MESSAGE, joinMessageResponse);
        verify(roomOperations).sendEvent(eq(PARTICIPANTS_UPDATE), any());
    }

    /**
     * 새로고침/재연결에 의한 재조인(이미 멤버): 본인에게 히스토리는 서빙하되, 방 전체에는 입장 알림도
     * 참가자 갱신도 broadcast하지 않고 participant DB도 건드리지 않는다(입퇴장 스팸·N 팬아웃 제거).
     */
    @Test
    void handleJoinRoom_whenAlreadyMember_servesHistorySilently_noBroadcastNoChurn() {
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        User user = User.builder().id("user-1").name("tester").email("tester@example.com").build();
        Room room = Room.builder().id("room-1").name("room").participantIds(Set.of("user-1")).build();
        FetchMessagesResponse loadResponse = FetchMessagesResponse.builder()
                .messages(List.of())
                .hasMore(false)
                .build();

        when(client.get("user")).thenReturn(socketUser);
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true); // 이미 멤버
        when(messageLoader.loadMessages(any(FetchMessagesRequest.class), eq("user-1")))
                .thenReturn(loadResponse);
        when(userBatchLoader.findByIds(any())).thenReturn(Map.of("user-1", user));

        handler.handleJoinRoom(client, "room-1");

        verify(roomRepository, times(1)).findById("room-1");
        // 본인에게 히스토리 응답 + 소켓 재구독
        verify(client).joinRoom("room-1");
        verify(client).sendEvent(eq(JOIN_ROOM_SUCCESS), any());
        // 방 전체 브로드캐스트·참가자 DB churn·입장 시스템 메시지 없음
        verify(socketIOServer, never()).getRoomOperations(anyString());
        verify(roomRepository, never()).addParticipant(anyString(), anyString());
        verify(userRooms, never()).add(anyString(), anyString());
        verify(messageStore, never()).add(any(Message.class));
    }
}
