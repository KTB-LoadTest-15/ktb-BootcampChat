package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.ChatMessageRequest;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.service.message.MessageStore;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.RateLimitCheckResult;
import com.ktb.chatapp.service.RateLimitService;
import com.ktb.chatapp.service.RoomActivityNotifier;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.util.BannedWordChecker;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.ai.AiService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMessageHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageStore messageStore;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileRepository fileRepository;
    @Mock private AiService aiService;
    @Mock private SessionService sessionService;
    @Mock private RoomActivityNotifier roomActivityNotifier;
    @Mock private BannedWordChecker bannedWordChecker;
    @Mock private RateLimitService rateLimitService;
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ChatMessageHandler handler;

    @BeforeEach
    void setUp() {
        // 동기 디스패처: 오프로드 대신 즉시 실행해 기존 동기 검증을 유지한다.
        // (오프로드/순서/거부는 KeyedSocketDispatcherTest에서 검증)
        com.ktb.chatapp.websocket.socketio.SocketDispatcher syncDispatcher =
                (key, task, onReject) -> task.run();
        handler =
                new ChatMessageHandler(
                        socketIOServer,
                        messageStore,
                        roomRepository,
                        userRepository,
                        fileRepository,
                        aiService,
                        sessionService,
                        roomActivityNotifier,
                        bannedWordChecker,
                        rateLimitService,
                        meterRegistry,
                        syncDispatcher);
    }

    @Test
    void handleChatMessage_blocksMessagesContainingBannedWords() {
        SocketIOClient client = mock(SocketIOClient.class);
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(client.get("user")).thenReturn(socketUser);

        SessionValidationResult validResult = SessionValidationResult.valid(null);
        when(sessionService.validateSession(socketUser.id(), socketUser.authSessionId()))
                .thenReturn(validResult);

        RateLimitCheckResult allowedResult = RateLimitCheckResult.allowed(10000, 9999, 60, System.currentTimeMillis() / 1000 + 60, 60);
        when(rateLimitService.checkRateLimit(eq(socketUser.id()), anyInt(), any()))
                .thenReturn(allowedResult);

        User user = new User();
        user.setId("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        Room room = new Room();
        room.setId("room-1");
        room.setParticipantIds(new HashSet<>(java.util.List.of("user-1")));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));

        ChatMessageRequest request =
                ChatMessageRequest.builder()
                        .room("room-1")
                        .type("text")
                        .content("bad word")
                        .build();

        when(bannedWordChecker.containsBannedWord("bad word")).thenReturn(true);

        handler.handleChatMessage(client, request);

        ArgumentCaptor<Map<String, String>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).sendEvent(eq(ERROR), payloadCaptor.capture());
        Map<String, String> payload = payloadCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("MESSAGE_REJECTED", payload.get("code"));
        verifyNoInteractions(messageStore);
        verify(socketIOServer, never()).getRoomOperations(any());
    }

    @Test
    void handleChatMessage_echoesSavedMessageToSenderSocket() {
        SocketIOClient client = mock(SocketIOClient.class);
        BroadcastOperations roomOperations = mock(BroadcastOperations.class);
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(client.get("user")).thenReturn(socketUser);

        when(sessionService.validateSession(socketUser.id(), socketUser.authSessionId()))
                .thenReturn(SessionValidationResult.valid(null));
        when(rateLimitService.checkRateLimit(eq(socketUser.id()), anyInt(), any()))
                .thenReturn(RateLimitCheckResult.allowed(10000, 9999, 60, System.currentTimeMillis() / 1000 + 60, 60));

        User user = new User();
        user.setId("user-1");
        user.setName("Tester");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        Room room = new Room();
        room.setId("room-1");
        room.setParticipantIds(new HashSet<>(java.util.List.of("user-1")));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
        when(bannedWordChecker.containsBannedWord("hello")).thenReturn(false);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);
        when(messageStore.add(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId("message-1");
            message.setTimestamp(LocalDateTime.of(2026, 7, 7, 9, 0));
            message.setType(MessageType.text);
            return message;
        });

        ChatMessageRequest request =
                ChatMessageRequest.builder()
                        .room("room-1")
                        .type("text")
                        .content("hello")
                        .build();

        handler.handleChatMessage(client, request);

        ArgumentCaptor<MessageResponse> payloadCaptor = ArgumentCaptor.forClass(MessageResponse.class);
        verify(client).sendEvent(eq(MESSAGE), payloadCaptor.capture());
        verify(roomOperations).sendEvent(eq(MESSAGE), any(MessageResponse.class));
        verify(roomActivityNotifier).notifyMessageStored("room-1");
        org.junit.jupiter.api.Assertions.assertEquals("message-1", payloadCaptor.getValue().getId());
        org.junit.jupiter.api.Assertions.assertEquals("hello", payloadCaptor.getValue().getContent());
    }

    @Test
    void handleChatMessage_offloadsToDispatcherKeyedByRoom() {
        java.util.concurrent.atomic.AtomicReference<String> capturedKey =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 디스패처가 task를 실행하지 않고 key만 캡처 → event-loop에서 즉시 반환(오프로드)됨을 증명
        com.ktb.chatapp.websocket.socketio.SocketDispatcher capturing =
                (key, task, onReject) -> capturedKey.set(key);
        ChatMessageHandler offloadingHandler =
                new ChatMessageHandler(
                        socketIOServer, messageStore, roomRepository, userRepository,
                        fileRepository, aiService, sessionService, roomActivityNotifier,
                        bannedWordChecker, rateLimitService, meterRegistry, capturing);

        SocketIOClient client = mock(SocketIOClient.class);
        ChatMessageRequest request =
                ChatMessageRequest.builder().room("room-42").type("text").content("hi").build();

        offloadingHandler.handleChatMessage(client, request);

        // 방(roomId)이 순서 보장 key로 전달되고, 처리 본문은 아직 실행되지 않았다(오프로드)
        org.junit.jupiter.api.Assertions.assertEquals("room-42", capturedKey.get());
        verifyNoInteractions(sessionService, messageStore, rateLimitService);
    }
}
