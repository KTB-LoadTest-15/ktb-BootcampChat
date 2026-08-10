package com.ktb.chatapp.service.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
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
 * P3 배치 flusher 검증: Redis hot store의 메시지가 Mongo로 영속화되고 대기 큐가 비워지는지 확인.
 * 스케줄러 간섭을 막기 위해 interval을 크게 두고 flushBatch를 직접 호출한다.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false",
        "message.store=redis",
        "message.flush.enabled=true",
        "message.flush.interval-ms=600000"
})
class RedisToMongoFlusherIntegrationTest {

    @Autowired
    private MessageStore messageStore; // RedisMessageStore

    @Autowired
    private RedisToMongoFlusher flusher;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Faker faker;
    private String roomId;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
        messageRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private Message newMessage(String content, LocalDateTime timestamp) {
        Message m = new Message();
        m.setRoomId(roomId);
        m.setSenderId(faker.internet().uuid());
        m.setContent(content);
        m.setType(MessageType.text);
        m.setTimestamp(timestamp);
        return m;
    }

    private long pendingCount() {
        Long size = redisTemplate.opsForSet().size(RedisMessageStore.KEY_FLUSH_PENDING);
        return size == null ? 0 : size;
    }

    @Test
    @DisplayName("flush: Redis 메시지를 Mongo로 영속화하고 대기 큐를 비운다")
    void flush_persistsToMongoAndDrainsQueue() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(5);
        List<String> ids = IntStream.range(0, 3)
                .mapToObj(i -> messageStore.add(newMessage("m" + i, base.plusSeconds(i))).getId())
                .toList();

        // flush 전: Mongo에는 없고 대기 큐에 3개
        assertThat(messageRepository.findById(ids.get(0))).isEmpty();
        assertThat(pendingCount()).isEqualTo(3L);

        int flushed = flusher.flushBatch(500);

        assertThat(flushed).isEqualTo(3);
        for (String id : ids) {
            assertThat(messageRepository.findById(id)).isPresent();
        }
        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("flush: 첫 flush 전에 붙은 읽음 상태도 함께 영속화된다")
    void flush_carriesReaderState() {
        String id = messageStore.add(newMessage("hi", LocalDateTime.now().minusMinutes(1))).getId();
        messageStore.addReaderToMessages(List.of(id), "user-A", LocalDateTime.now());

        flusher.flushBatch(500);

        Message persisted = messageRepository.findById(id).orElseThrow();
        assertThat(persisted.getReaders())
                .extracting(Message.MessageReader::getUserId)
                .containsExactly("user-A");
    }

    @Test
    @DisplayName("flush: 대기 큐가 비어있으면 0을 반환한다")
    void flush_emptyQueue_returnsZero() {
        assertThat(flusher.flushBatch(500)).isZero();
    }
}
