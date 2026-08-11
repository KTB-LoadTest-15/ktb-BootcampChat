package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.service.UserBatchLoader;
import com.ktb.chatapp.service.message.MessageStore;
import net.datafaker.Faker;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageLoaderTest {

    @Mock
    private MessageStore messageStore;

    @Mock
    private UserBatchLoader userBatchLoader;

    @Mock
    private FileRepository fileRepository;

    private MessageLoader messageLoader;

    private Faker faker;
    private List<Message> testMessages;
    private String roomId;
    private String userId;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
        userId = faker.internet().uuid();

        messageLoader = new MessageLoader(
                messageStore,
                userBatchLoader,
                new MessageResponseMapper(fileRepository)
        );

        var testUser = User.builder()
                .id(userId)
                .name(faker.name().fullName())
                .email(faker.internet().emailAddress())
                .build();

        // 테스트 메시지 50개 생성 (오름차순: 오래된 것 → 최신 것)
        testMessages = IntStream.range(0, 50)
                .mapToObj(i -> createMessage(
                        faker.internet().uuid(),
                        LocalDateTime.now().minusHours(50 - i)
                ))
                .toList();

        lenient().when(userBatchLoader.findByIds(anyCollection())).thenReturn(Map.of(userId, testUser));
    }

    private Message createMessage(String id, LocalDateTime timestamp) {
        Message message = new Message();
        message.setId(id);
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setContent(faker.lorem().sentence(10));
        message.setTimestamp(timestamp);
        return message;
    }

    @Test
    @DisplayName("loadMessages: 내림차순 조회 후 오름차순 재정렬")
    void loadMessages_shouldReturnAscendingOrderAfterReversing() {
        // Given: testMessages[0~29] (오래된 30개)
        List<Message> first30Messages = testMessages.subList(0, 30);
        var messagePage = getMessagePage(first30Messages, true);

        when(messageStore.findMessagesBefore(eq(roomId), any(LocalDateTime.class), anyInt()))
                .thenReturn(messagePage);

        // When
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);

        // Then: 오름차순 정렬 + hasMore
        assertThat(result.getMessages()).hasSize(30);
        assertThat(result.isHasMore()).isTrue();
        verifyAscending(result);
    }

    /** 저장소는 DESC(최신 먼저)로 반환한다고 가정하고 MessagePage를 만든다. */
    private static @NotNull MessageStore.MessagePage getMessagePage(List<Message> ascMessages, boolean hasMore) {
        List<Message> desc = new ArrayList<>(ascMessages.reversed());
        return new MessageStore.MessagePage(desc, hasMore);
    }

    @Test
    @DisplayName("loadInitialMessages: 내림차순 조회 후 오름차순 재정렬")
    void loadInitialMessages_shouldReturnAscendingOrderAfterReversing() {
        // Given: testMessages[20~49] (최신 30개)
        List<Message> last30Messages = testMessages.subList(20, 50);
        var messagePage = getMessagePage(last30Messages, true);

        when(messageStore.findMessagesBefore(eq(roomId), any(LocalDateTime.class), anyInt()))
                .thenReturn(messagePage);

        // When
        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);

        // Then
        assertThat(result.getMessages()).hasSize(30);
        verifyAscending(result);
    }

    private static void verifyAscending(FetchMessagesResponse result) {
        for (int i = 0; i < result.getMessages().size() - 1; i++) {
            long current = result.getMessages().get(i).getTimestamp();
            long next = result.getMessages().get(i + 1).getTimestamp();
            assertThat(current).isLessThanOrEqualTo(next);
        }
    }

    @Test
    @DisplayName("loadInitialMessages: 에러 시 빈 응답")
    void loadInitialMessages_shouldReturnEmptyOnError() {
        when(messageStore.findMessagesBefore(any(), any(LocalDateTime.class), anyInt()))
                .thenThrow(new RuntimeException("DB error"));

        FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
        FetchMessagesResponse result = messageLoader.loadMessages(req, userId);

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.isHasMore()).isFalse();
    }
}
