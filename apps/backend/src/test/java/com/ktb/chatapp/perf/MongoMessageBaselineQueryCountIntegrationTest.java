package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.datafaker.Faker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

/**
 * P0 baseline — Redis 전환 전, 메시지 컬렉션 operation별 Mongo 명령 footprint 실측.
 *
 * <p>여기서 잰 명령 수가 Redis hot store 전환(P2/P4)에서 hot path Mongo 명령을
 * 얼마나 제거하는지 비교할 "before"다. docs/perf/P0-redis-baseline.md 의 근거.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class MongoMessageBaselineQueryCountIntegrationTest {

    private static final int BATCH = 30;

    @Autowired
    private MessageRepository messageRepository;

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
        messageRepository.deleteAll();
    }

    private Message newMessage() {
        Message m = new Message();
        m.setRoomId(roomId);
        m.setSenderId(faker.internet().uuid());
        m.setContent(faker.lorem().sentence());
        m.setType(MessageType.text);
        m.setTimestamp(LocalDateTime.now());
        return m;
    }

    @Test
    @DisplayName("send: 신규 메시지 저장 = insert 1")
    void send_insertsOnce() {
        Message m = newMessage();

        listener.start();
        messageRepository.save(m);
        listener.stop();

        Map<String, Long> snap = listener.snapshot();
        System.out.printf("[baseline] send=%s total=%d%n", snap, listener.totalDataCommands());
        assertThat(snap.getOrDefault("insert", 0L)).isEqualTo(1L);
        assertThat(listener.totalDataCommands()).isEqualTo(1L);
    }

    @Test
    @DisplayName("history 30개 로드: find 1 + count(aggregate) 1 (Page)")
    void loadHistory_findAndCount() {
        List<String> ids = IntStream.range(0, BATCH)
                .mapToObj(i -> messageRepository.save(newMessage()).getId())
                .toList();
        assertThat(ids).hasSize(BATCH);

        Pageable pageable = PageRequest.of(0, BATCH, Sort.by("timestamp").descending());

        // before 경계를 near-future로 둔다: BSON timestamp는 ms 정밀도라 30건이 같은 ms에 몰릴 수 있고,
        // strict `<` 조회 시 now()와 동률인 건이 제외되면 결과가 30개 미만이 된다. 그러면 Spring Data의
        // Page count 최적화가 count(aggregate)를 생략해(내용이 페이지를 안 채움) 명령 수가 실행 속도에
        // 따라 흔들린다. near-future 경계로 30건을 항상 포함시켜 count가 결정적으로 나가게 한다.
        LocalDateTime before = LocalDateTime.now().plusSeconds(1);

        listener.start();
        messageRepository.findByRoomIdAndTimestampBefore(roomId, before, pageable);
        listener.stop();

        // Page의 count는 count 명령이 아니라 aggregate(countDocuments)로 나간다 (실측 확인).
        Map<String, Long> snap = listener.snapshot();
        System.out.printf("[baseline] history=%s total=%d%n", snap, listener.totalDataCommands());
        assertThat(snap.getOrDefault("find", 0L)).isEqualTo(1L);
        assertThat(snap.getOrDefault("aggregate", 0L)).isEqualTo(1L);
        assertThat(listener.totalDataCommands()).isEqualTo(2L);
    }

    @Test
    @DisplayName("recent count: count 1")
    void recentCount_countsOnce() {
        IntStream.range(0, 5).forEach(i -> messageRepository.save(newMessage()));
        LocalDateTime since = LocalDateTime.now().minus(Duration.ofMinutes(30));

        listener.start();
        messageRepository.countRecentMessagesByRoomId(roomId, since);
        listener.stop();

        // @Query(count=true) 역시 aggregate(countDocuments)로 나간다 (실측 확인).
        Map<String, Long> snap = listener.snapshot();
        System.out.printf("[baseline] recentCount=%s total=%d%n", snap, listener.totalDataCommands());
        assertThat(snap.getOrDefault("aggregate", 0L)).isEqualTo(1L);
        assertThat(listener.totalDataCommands()).isEqualTo(1L);
    }

    @Test
    @DisplayName("findById 단건: find 1")
    void findById_findsOnce() {
        String id = messageRepository.save(newMessage()).getId();

        listener.start();
        messageRepository.findById(id);
        listener.stop();

        Map<String, Long> snap = listener.snapshot();
        System.out.printf("[baseline] findById=%s total=%d%n", snap, listener.totalDataCommands());
        assertThat(snap.getOrDefault("find", 0L)).isEqualTo(1L);
        assertThat(listener.totalDataCommands()).isEqualTo(1L);
    }

    @Test
    @DisplayName("reaction: findById 1 + save(update) 1")
    void reaction_findAndUpdate() {
        String id = messageRepository.save(newMessage()).getId();

        listener.start();
        Message m = messageRepository.findById(id).orElseThrow();
        m.addReaction("👍", "user-A");
        messageRepository.save(m);
        listener.stop();

        Map<String, Long> snap = listener.snapshot();
        System.out.printf("[baseline] reaction=%s total=%d%n", snap, listener.totalDataCommands());
        assertThat(snap.getOrDefault("find", 0L)).isEqualTo(1L);
        assertThat(snap.getOrDefault("update", 0L)).isEqualTo(1L);
        assertThat(listener.totalDataCommands()).isEqualTo(2L);
    }
}
