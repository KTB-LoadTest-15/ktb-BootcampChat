package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.service.message.MessageStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
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
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.context.TestPropertySource;

/**
 * P4 — message.store=redis일 때 메시지 hot path가 MongoDB를 전혀 건드리지 않음을 실측하고,
 * Redis에 실제로 무엇이 어떻게 기록되는지 원시 데이터로 보여준다.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false",
        "message.store=redis"
})
class RedisModeMongoBypassIntegrationTest {

    @Autowired
    private MessageStore messageStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CommandCountingListener listener;

    private Faker faker;
    private String roomId;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
    }

    @AfterEach
    void tearDown() {
        listener.stop();
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

    @Test
    @DisplayName("redis 모드: 메시지 hot path가 Mongo 명령을 0회 발생시킨다")
    void redisMode_issuesZeroMongoCommands() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(5);

        listener.start();
        // send 3 + load + count + read + findById — 전부 Redis로 가야 한다
        String id0 = messageStore.add(newMessage("hello", base)).getId();
        messageStore.add(newMessage("world", base.plusSeconds(1)));
        messageStore.add(newMessage("!!", base.plusSeconds(2)));
        MessageStore.MessagePage page = messageStore.findMessagesBefore(roomId, LocalDateTime.now(), 30);
        long recent = messageStore.countRecentMessages(roomId, base.minusMinutes(1));
        messageStore.addReaderToMessages(List.of(id0), "user-A", LocalDateTime.now());
        messageStore.findById(id0);
        listener.stop();

        System.out.printf("[redis-p4] mongo_commands=%s total=%d | loaded=%d recent=%d%n",
                listener.snapshot(), listener.totalDataCommands(), page.messages().size(), recent);

        assertThat(listener.totalDataCommands())
                .as("redis 모드에서 메시지 hot path는 Mongo를 건드리지 않아야 한다")
                .isZero();
        assertThat(page.messages()).hasSize(3);
        assertThat(recent).isEqualTo(3L);
    }

    @Test
    @DisplayName("Redis 기록 가시화: 키/ZSet/Hash 원시 데이터 덤프")
    void dumpRedisContents() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(5);
        String id0 = messageStore.add(newMessage("첫 메시지", base)).getId();
        messageStore.add(newMessage("둘째 메시지", base.plusSeconds(1)));
        messageStore.addReaderToMessages(List.of(id0), "user-A", LocalDateTime.now());

        System.out.println("========== REDIS DUMP (message.store=redis) ==========");

        Set<String> keys = redisTemplate.keys("chat:*");
        System.out.println("[keys] " + keys);

        String zkey = "chat:room:" + roomId + ":msgIdx";
        Set<ZSetOperations.TypedTuple<String>> zset =
                redisTemplate.opsForZSet().rangeWithScores(zkey, 0, -1);
        System.out.println("[zset] " + zkey);
        if (zset != null) {
            zset.forEach(t -> System.out.printf("   score=%.0f  member=%s%n", t.getScore(), t.getValue()));
        }

        System.out.println("[hash] chat:messages (id -> JSON)");
        redisTemplate.<String, String>opsForHash().entries("chat:messages")
                .forEach((id, json) -> System.out.printf("   %s -> %s%n", id, json));

        System.out.println("======================================================");

        // 덤프가 의미 있으려면 실제로 기록돼 있어야 한다
        assertThat(keys).anyMatch(k -> k.startsWith("chat:room:"));
        assertThat(keys).contains("chat:messages");
    }
}
