package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.service.readcursor.ReadCursorStore;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
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
    @Mock private ReadCursorStore readCursorStore;
    @Mock private UserRooms userRooms;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private MessageReadHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageReadHandler(
                socketIOServer,
                readCursorStore,
                userRooms,
                (key, task, onReject) -> task.run());
    }

    @Test
    void handleMarkAsRead_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleMarkAsRead(client, request("room-1", 1_000L));

        verify(client).sendEvent(eq(ERROR), any());
        verify(readCursorStore, never()).advance(anyString(), anyString(), anyLong());
    }

    @Test
    void handleMarkAsRead_rejectsWhenNotRoomMember() {
        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(false);

        handler.handleMarkAsRead(client, request("room-1", 1_000L));

        verify(client).sendEvent(eq(ERROR), any());
        verify(readCursorStore, never()).advance(anyString(), anyString(), anyLong());
    }

    @Test
    void handleMarkAsRead_advancesCursorAndBroadcasts() {
        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(readCursorStore.advance("room-1", "user-1", 1_000L)).thenReturn(true);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMarkAsRead(client, request("room-1", 1_000L));

        verify(readCursorStore).advance("room-1", "user-1", 1_000L);
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(MESSAGES_READ), responseCaptor.capture());
        MessagesReadResponse response = (MessagesReadResponse) responseCaptor.getValue();
        assertEquals("user-1", response.getUserId());
        assertEquals(1_000L, response.getLastReadTs());
    }

    @Test
    void handleMarkAsRead_skipsBroadcastWhenCursorNotAdvanced() {
        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(userRooms.isInRoom("user-1", "room-1")).thenReturn(true);
        when(readCursorStore.advance("room-1", "user-1", 1_000L)).thenReturn(false);

        handler.handleMarkAsRead(client, request("room-1", 1_000L));

        verify(socketIOServer, never()).getRoomOperations(anyString());
    }

    private MarkAsReadRequest request(String roomId, long lastReadTs) {
        MarkAsReadRequest request = new MarkAsReadRequest();
        request.setRoomId(roomId);
        request.setLastReadTs(lastReadTs);
        return request;
    }
}
