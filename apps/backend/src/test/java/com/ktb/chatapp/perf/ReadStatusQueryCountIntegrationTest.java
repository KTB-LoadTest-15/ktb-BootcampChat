package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.test.context.TestPropertySource;

/**
 * 읽음 처리(P0-4)의 개선 전/후 Mongo 명령 횟수를 실측한다.
 *
 * <p>OLD = 제거된 findById+save 루프를 그대로 재현. NEW = 현재의 atomic bulk update.
 * 숫자는 docs/perf/P0-4-read-status-bulk-update.md 의 근거이자 회귀 가드다.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class ReadStatusQueryCountIntegrationTest {

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

    private List<String> saveMessages(int n) {
        return IntStream.range(0, n).mapToObj(i -> {
            Message m = new Message();
            m.setRoomId(roomId);
            m.setSenderId(faker.internet().uuid());
            m.setContent(faker.lorem().sentence());
            m.setType(MessageType.text);
            m.setTimestamp(LocalDateTime.now());
            m.setReaders(null); // production 텍스트 메시지 모사
            return messageRepository.save(m).getId();
        }).toList();
    }

    /** 제거된 옛 구현을 그대로 재현: 메시지마다 findById + save. */
    private void legacyUpdateReadStatus(List<String> messageIds, String userId) {
        var readerInfo = Message.MessageReader.builder()
                .userId(userId).readAt(LocalDateTime.now()).build();
        for (String messageId : messageIds) {
            var opt = messageRepository.findById(messageId);
            if (opt.isPresent()) {
                var message = opt.get();
                if (message.getReaders() == null) {
                    message.setReaders(new ArrayList<>());
                }
                boolean alreadyRead = message.getReaders().stream()
                        .anyMatch(r -> r.getUserId().equals(userId));
                if (!alreadyRead) {
                    message.getReaders().add(readerInfo);
                }
                messageRepository.save(message);
            }
        }
    }

    @Test
    @DisplayName("30개 읽음 처리: OLD 60 명령(find30+update30) → NEW 1 명령(update)")
    void readStatus_commandCount_beforeAndAfter() {
        List<String> setOld = saveMessages(BATCH);
        List<String> setNew = saveMessages(BATCH);

        // --- BEFORE: 옛 findById+save 루프 ---
        listener.start();
        legacyUpdateReadStatus(setOld, "user-A");
        listener.stop();
        Map<String, Long> oldSnapshot = listener.snapshot();
        long oldTotal = listener.totalDataCommands();

        // --- AFTER: atomic bulk update ---
        listener.start();
        messageRepository.updateReadersForMessages(setNew, "user-A", LocalDateTime.now());
        listener.stop();
        Map<String, Long> newSnapshot = listener.snapshot();
        long newTotal = listener.totalDataCommands();

        System.out.printf("[read-status] BEFORE=%s total=%d%n", oldSnapshot, oldTotal);
        System.out.printf("[read-status] AFTER =%s total=%d%n", newSnapshot, newTotal);

        // --- 쿼리 횟수: 개선 검증 ---
        // BEFORE: 메시지당 find 1 + update 1
        assertThat(oldSnapshot.get("find")).isEqualTo(BATCH);
        assertThat(oldSnapshot.get("update")).isEqualTo(BATCH);
        assertThat(oldTotal).isEqualTo(2L * BATCH);

        // AFTER: 개수와 무관하게 update 1회
        assertThat(newSnapshot.get("update")).isEqualTo(1L);
        assertThat(newSnapshot.getOrDefault("find", 0L)).isZero();
        assertThat(newTotal).isEqualTo(1L);

        // --- 동작 동치성: 쿼리가 줄어도 기능 결과는 동일해야 한다 ---
        // OLD와 NEW 모두 각 메시지에 user-A가 정확히 한 번 reader로 기록되어야 한다.
        for (String id : setOld) {
            assertThat(readerUserIds(id)).containsExactly("user-A");
        }
        for (String id : setNew) {
            assertThat(readerUserIds(id)).containsExactly("user-A");
        }
    }

    private List<String> readerUserIds(String messageId) {
        Message reloaded = messageRepository.findById(messageId).orElseThrow();
        return reloaded.getReaders() == null
                ? List.of()
                : reloaded.getReaders().stream().map(Message.MessageReader::getUserId).toList();
    }
}
