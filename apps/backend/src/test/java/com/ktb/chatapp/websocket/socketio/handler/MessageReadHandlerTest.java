package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.service.message.MessageStore;
import com.ktb.chatapp.service.readcursor.ReadCursorStore;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReadHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageStore messageStore;
    @Mock private ReadCursorStore readCursorStore;
    @Mock private UserRooms userRooms;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private MessageReadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageReadHandler(
                socketIOServer,
                messageStore,
                readCursorStore,
                userRooms,
                (key, task, onReject) -> task.run());
    }

    private Message messageAt(String id, String roomId, LocalDateTime ts) {
        return Message.builder().id(id).roomId(roomId).type(MessageType.text).timestamp(ts).build();
    }

    @Test
    void handleMarkAsRead_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleMarkAsRead(client, request("room-1", "message-1"));

        verify(client).sendEvent(eq(ERROR), any());
        verify(readCursorStore, never()).advance(anyString(), anyString(), anyLong());
    }

    @Test
    void handleMarkAsRead_rejectsWhenNotRoomMember() {
        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(false);

        handler.handleMarkAsRead(client, request("room-1", "message-1"));

        verify(client).sendEvent(eq(ERROR), any());
        verify(readCursorStore, never()).advance(anyString(), anyString(), anyLong());
    }

    @Test
    void handleMarkAsRead_usesServerTimestampAndBroadcasts() {
        LocalDateTime ts = LocalDateTime.of(2026, 8, 11, 10, 0, 0);
        Message message = messageAt("message-1", "room-1", ts);
        long serverTs = message.toTimestampMillis();

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(messageStore.findById("message-1")).thenReturn(Optional.of(message));
        when(readCursorStore.advance("room-1", "user-1", serverTs)).thenReturn(true);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMarkAsRead(client, request("room-1", "message-1"));

        // 커서는 클라 값이 아니라 서버 메시지 timestamp로 전진한다.
        verify(readCursorStore).advance("room-1", "user-1", serverTs);
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(MESSAGES_READ), responseCaptor.capture());
        MessagesReadResponse response = (MessagesReadResponse) responseCaptor.getValue();
        assertEquals("user-1", response.getUserId());
        assertEquals(serverTs, response.getLastReadTs());
    }

    @Test
    void handleMarkAsRead_ignoresUnknownMessage() {
        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(messageStore.findById("message-1")).thenReturn(Optional.empty());

        handler.handleMarkAsRead(client, request("room-1", "message-1"));

        verify(readCursorStore, never()).advance(anyString(), anyString(), anyLong());
        verify(socketIOServer, never()).getRoomOperations(anyString());
    }

    @Test
    void handleMarkAsRead_rejectsMessageFromAnotherRoom() {
        Message message = messageAt("message-1", "room-OTHER", LocalDateTime.now());
        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(messageStore.findById("message-1")).thenReturn(Optional.of(message));

        handler.handleMarkAsRead(client, request("room-1", "message-1"));

        verify(client).sendEvent(eq(ERROR), any());
        verify(readCursorStore, never()).advance(anyString(), anyString(), anyLong());
    }

    @Test
    void handleMarkAsRead_skipsBroadcastWhenCursorNotAdvanced() {
        Message message = messageAt("message-1", "room-1", LocalDateTime.now());
        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(messageStore.findById("message-1")).thenReturn(Optional.of(message));
        when(readCursorStore.advance(eq("room-1"), eq("user-1"), anyLong())).thenReturn(false);

        handler.handleMarkAsRead(client, request("room-1", "message-1"));

        verify(socketIOServer, never()).getRoomOperations(anyString());
    }

    private MarkAsReadRequest request(String roomId, String lastReadMessageId) {
        MarkAsReadRequest request = new MarkAsReadRequest();
        request.setRoomId(roomId);
        request.setLastReadMessageId(lastReadMessageId);
        return request;
    }
}
