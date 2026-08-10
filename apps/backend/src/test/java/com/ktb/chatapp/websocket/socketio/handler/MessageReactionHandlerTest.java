package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MessageReactionRequest;
import com.ktb.chatapp.dto.MessageReactionResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.service.message.MessageStore;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE_REACTION_UPDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReactionHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageStore messageStore;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private MessageReactionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageReactionHandler(socketIOServer, messageStore);
    }

    @Test
    void handleMessageReaction_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleMessageReaction(client, new MessageReactionRequest("👍", "message-1", "add", "👍"));

        verify(client).sendEvent(eq(ERROR), any());
        verify(messageStore, never()).addReaction(any(), any(), any());
        verify(messageStore, never()).removeReaction(any(), any(), any());
    }

    @Test
    void handleMessageReaction_addsReactionAndBroadcasts() {
        Message message = Message.builder().id("message-1").roomId("room-1").build();
        message.addReaction("👍", "user-1");
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "add", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(messageStore.addReaction("message-1", "👍", "user-1")).thenReturn(Optional.of(message));
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMessageReaction(client, request);

        verify(messageStore).addReaction("message-1", "👍", "user-1");
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(MESSAGE_REACTION_UPDATE), responseCaptor.capture());
        MessageReactionResponse response = (MessageReactionResponse) responseCaptor.getValue();
        assertEquals("message-1", response.getMessageId());
        assertEquals(Set.of("user-1"), response.getReactions().get("👍"));
    }

    @Test
    void handleMessageReaction_messageNotFound_sendsError() {
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "missing", "add", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(messageStore.addReaction("missing", "👍", "user-1")).thenReturn(Optional.empty());

        handler.handleMessageReaction(client, request);

        verify(client).sendEvent(eq(ERROR), any());
        verify(socketIOServer, never()).getRoomOperations(any());
    }

    @Test
    void handleMessageReaction_unsupportedType_sendsErrorWithoutStoreCall() {
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "toggle", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));

        handler.handleMessageReaction(client, request);

        verify(client).sendEvent(eq(ERROR), any());
        verify(messageStore, never()).addReaction(any(), any(), any());
        verify(messageStore, never()).removeReaction(any(), any(), any());
    }
}
