package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.message.MongoMessageStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.datafaker.Faker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 리액션 원자성(P1-3, Mongo 모드)의 명령 수와 동시성 정합성을 실측한다.
 *
 * <p>OLD = 제거된 findById+save(read-modify-save) 루프를 그대로 재현. NEW = 단일 원자 pipeline update.
 * 숫자·정합성은 docs/perf/P1-3-reaction-atomic.md 의 근거이자 회귀 가드다.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "message.store=mongo",
        "socketio.enabled=false"
})
class ReactionAtomicQueryCountIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CommandCountingListener listener;

    private MongoMessageStore store;
    private Faker faker;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        store = new MongoMessageStore(messageRepository, mongoTemplate);
    }

    @AfterEach
    void tearDown() {
        listener.stop();
        messageRepository.deleteAll();
    }

    private Message saveMessage() {
        Message m = new Message();
        m.setRoomId(faker.internet().uuid());
        m.setSenderId(faker.internet().uuid());
        m.setContent(faker.lorem().sentence());
        m.setType(MessageType.text);
        m.setTimestamp(LocalDateTime.now());
        return store.add(m);
    }

    /** 제거된 옛 구현을 그대로 재현: findById → in-memory 수정 → save. */
    private void legacyAddReaction(String messageId, String reaction, String userId) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        message.addReaction(reaction, userId);
        messageRepository.save(message);
    }

    @Test
    @DisplayName("리액션 1건 추가: OLD 2 명령(find+update) → NEW 1 명령(findAndModify)")
    void reactionAdd_commandCount_beforeAndAfter() {
        String idOld = saveMessage().getId();
        String idNew = saveMessage().getId();

        // --- BEFORE: findById + save ---
        listener.start();
        legacyAddReaction(idOld, "👍", "user-A");
        listener.stop();
        Map<String, Long> oldSnapshot = listener.snapshot();
        long oldTotal = listener.totalDataCommands();

        // --- AFTER: 원자 pipeline update ---
        listener.start();
        store.addReaction(idNew, "👍", "user-A");
        listener.stop();
        Map<String, Long> newSnapshot = listener.snapshot();
        long newTotal = listener.totalDataCommands();

        System.out.printf("[reaction] BEFORE=%s total=%d%n", oldSnapshot, oldTotal);
        System.out.printf("[reaction] AFTER =%s total=%d%n", newSnapshot, newTotal);

        assertThat(oldSnapshot.get("find")).isEqualTo(1L);
        assertThat(oldSnapshot.get("update")).isEqualTo(1L);
        assertThat(oldTotal).isEqualTo(2L);

        // findAndModify 단일 명령
        assertThat(newSnapshot.get("findAndModify")).isEqualTo(1L);
        assertThat(newTotal).isEqualTo(1L);

        // 동작 동치성: 두 경로 모두 👍→user-A 로 동일
        assertThat(readReactions(idOld)).isEqualTo(Map.of("👍", Set.of("user-A")));
        assertThat(readReactions(idNew)).isEqualTo(Map.of("👍", Set.of("user-A")));
    }

    @Test
    @DisplayName("동시 리액션: 서로 다른 유저 20명이 같은 메시지에 동시에 추가해도 모두 보존(lost update 없음)")
    void concurrentReactions_noLostUpdate() throws InterruptedException {
        String id = saveMessage().getId();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        List<String> userIds = IntStream.range(0, threads)
                .mapToObj(i -> "user-" + i).toList();

        for (String userId : userIds) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    store.addReaction(id, "👍", userId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 원자 연산이면 20명 전부 보존되어야 한다(read-modify-save였다면 상당수 유실).
        assertThat(readReactions(id).get("👍")).containsExactlyInAnyOrderElementsOf(userIds);
    }

    @Test
    @DisplayName("리액션 제거: 마지막 사용자 제거 시 emoji 키가 사라진다(동치)")
    void removeReaction_lastUserRemovesEmojiKey() {
        String id = saveMessage().getId();
        store.addReaction(id, "👍", "user-A");
        store.addReaction(id, "👍", "user-B");
        store.addReaction(id, "❤️", "user-A");

        // user-A 가 👍 제거 → 👍는 user-B 만 남음
        store.removeReaction(id, "👍", "user-A");
        assertThat(readReactions(id)).isEqualTo(Map.of("👍", Set.of("user-B"), "❤️", Set.of("user-A")));

        // user-B 도 👍 제거 → 👍 키 자체가 사라져야 함(빈 배열이 남지 않음)
        store.removeReaction(id, "👍", "user-B");
        assertThat(readReactions(id)).isEqualTo(Map.of("❤️", Set.of("user-A")));
    }

    @Test
    @DisplayName("리액션 추가는 멱등: 같은 유저가 두 번 추가해도 중복되지 않음")
    void addReaction_idempotent() {
        String id = saveMessage().getId();
        store.addReaction(id, "👍", "user-A");
        store.addReaction(id, "👍", "user-A");
        assertThat(readReactions(id)).isEqualTo(Map.of("👍", Set.of("user-A")));
    }

    private Map<String, Set<String>> readReactions(String messageId) {
        return messageRepository.findById(messageId).orElseThrow().getReactions();
    }
}
