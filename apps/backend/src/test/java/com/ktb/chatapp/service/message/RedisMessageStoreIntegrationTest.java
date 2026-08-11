package com.ktb.chatapp.service.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import net.datafaker.Faker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * RedisMessageStore 계약 검증 (Testcontainers Redis).
 * message.store=redis 플래그로 실제 활성화되는지 + 모든 연산이 계약대로 동작하는지 확인한다.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false",
        "message.store=redis"
})
class RedisMessageStoreIntegrationTest {

    @Autowired
    private MessageStore messageStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Faker faker;
    private String roomId;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private Message newMessage(LocalDateTime timestamp) {
        Message m = new Message();
        m.setRoomId(roomId);
        m.setSenderId(faker.internet().uuid());
        m.setContent(faker.lorem().sentence());
        m.setType(MessageType.text);
        m.setTimestamp(timestamp);
        return m;
    }

    @Test
    @DisplayName("message.store=redis면 MessageStore 구현이 RedisMessageStore다")
    void wiring_selectsRedisStore() {
        assertThat(messageStore).isInstanceOf(RedisMessageStore.class);
    }

    @Test
    @DisplayName("add: id/timestamp를 부여하고 findById로 왕복 조회된다")
    void addAndFindById_roundTrip() {
        Message added = messageStore.add(newMessage(LocalDateTime.now()));

        assertThat(added.getId()).isNotBlank();
        assertThat(added.getTimestamp()).isNotNull();

        Message found = messageStore.findById(added.getId()).orElseThrow();
        assertThat(found.getId()).isEqualTo(added.getId());
        assertThat(found.getRoomId()).isEqualTo(roomId);
        assertThat(found.getContent()).isEqualTo(added.getContent());
        assertThat(found.getType()).isEqualTo(MessageType.text);
        assertThat(found.toTimestampMillis()).isEqualTo(added.toTimestampMillis());
    }

    @Test
    @DisplayName("findMessagesBefore: 최신순 DESC로 limit개 + hasMore")
    void findMessagesBefore_ordersDescAndLimits() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(10);
        List<String> ids = IntStream.range(0, 5)
                .mapToObj(i -> messageStore.add(newMessage(base.plusSeconds(i))).getId())
                .toList(); // ids[0]=가장 오래됨 ... ids[4]=가장 최신

        MessageStore.MessagePage page = messageStore.findMessagesBefore(
                roomId, LocalDateTime.now(), 3);

        // 최신 3개를 DESC로: ids[4], ids[3], ids[2]
        assertThat(page.messages()).hasSize(3);
        assertThat(page.messages().stream().map(Message::getId).toList())
                .containsExactly(ids.get(4), ids.get(3), ids.get(2));
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    @DisplayName("findMessagesBefore: 커서 페이지네이션에 중복/누락이 없다")
    void findMessagesBefore_cursorPagination() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(10);
        IntStream.range(0, 5).forEach(i -> messageStore.add(newMessage(base.plusSeconds(i))));

        MessageStore.MessagePage first = messageStore.findMessagesBefore(roomId, LocalDateTime.now(), 3);
        assertThat(first.messages()).hasSize(3);
        assertThat(first.hasMore()).isTrue();

        // 첫 페이지에서 가장 오래된 메시지의 시각을 다음 커서로
        LocalDateTime cursor = first.messages().getLast().getTimestamp();
        MessageStore.MessagePage second = messageStore.findMessagesBefore(roomId, cursor, 3);

        assertThat(second.messages()).hasSize(2);
        assertThat(second.hasMore()).isFalse();

        // 두 페이지 id가 겹치지 않는다
        var firstIds = first.messages().stream().map(Message::getId).toList();
        var secondIds = second.messages().stream().map(Message::getId).toList();
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    }

    @Test
    @DisplayName("countRecentMessages: since 이후 개수만 센다")
    void countRecentMessages_windowCount() {
        LocalDateTime now = LocalDateTime.now();
        messageStore.add(newMessage(now.minusMinutes(40)));
        messageStore.add(newMessage(now.minusMinutes(5)));
        messageStore.add(newMessage(now.minusMinutes(1)));

        long recent = messageStore.countRecentMessages(roomId, now.minusMinutes(30));
        assertThat(recent).isEqualTo(2L);
    }

    @Test
    @DisplayName("addReaderToMessages: 멱등하고 서로 다른 사용자는 누적된다")
    void addReaderToMessages_idempotentAndDistinct() {
        String id = messageStore.add(newMessage(LocalDateTime.now())).getId();

        long first = messageStore.addReaderToMessages(List.of(id), "user-A", LocalDateTime.now());
        long second = messageStore.addReaderToMessages(List.of(id), "user-A", LocalDateTime.now());
        long third = messageStore.addReaderToMessages(List.of(id), "user-B", LocalDateTime.now());

        assertThat(first).isEqualTo(1L);
        assertThat(second).isZero(); // 멱등: 이미 읽음
        assertThat(third).isEqualTo(1L);

        Message found = messageStore.findById(id).orElseThrow();
        assertThat(found.getReaders().stream().map(Message.MessageReader::getUserId).toList())
                .containsExactlyInAnyOrder("user-A", "user-B");
    }

    @Test
    @DisplayName("findByFileId: 파일 메시지를 fileId로 조회한다")
    void findByFileId_locatesMessage() {
        Message m = newMessage(LocalDateTime.now());
        m.setType(MessageType.file);
        m.setFileId("file-123");
        String id = messageStore.add(m).getId();

        Message found = messageStore.findByFileId("file-123").orElseThrow();
        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("update: 리액션 변경이 persist된다")
    void update_persistsReactionChange() {
        String id = messageStore.add(newMessage(LocalDateTime.now())).getId();

        Message m = messageStore.findById(id).orElseThrow();
        m.addReaction("👍", "user-A");
        messageStore.update(m);

        Message found = messageStore.findById(id).orElseThrow();
        assertThat(found.getReactions()).containsKey("👍");
        assertThat(found.getReactions().get("👍")).contains("user-A");
    }
}
