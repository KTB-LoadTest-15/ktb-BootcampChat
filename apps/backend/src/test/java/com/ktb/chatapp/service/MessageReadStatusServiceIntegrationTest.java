package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;

/**
 * 읽음 처리 atomic bulk update의 실제 Mongo 동작 검증.
 * - null/absent readers에서도 안전하게 reader 추가
 * - 멱등성(재호출 no-op)
 * - 대상 messageIds만 영향
 * - 동시 읽음에서 lost update 없음
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class MessageReadStatusServiceIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageReadStatusService service;

    private Faker faker;
    private String roomId;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        roomId = faker.internet().uuid();
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
    }

    /** readers를 명시적으로 null로 두어 저장 (new Message()로 만든 텍스트 메시지 모사). */
    private Message saveMessageWithNullReaders() {
        Message m = new Message();
        m.setRoomId(roomId);
        m.setSenderId(faker.internet().uuid());
        m.setContent(faker.lorem().sentence());
        m.setType(MessageType.text);
        m.setTimestamp(LocalDateTime.now());
        m.setReaders(null);
        return messageRepository.save(m);
    }

    private List<String> readerUserIds(String messageId) {
        Message reloaded = messageRepository.findById(messageId).orElseThrow();
        return reloaded.getReaders() == null
                ? List.of()
                : reloaded.getReaders().stream().map(Message.MessageReader::getUserId).toList();
    }

    @Test
    @DisplayName("readers가 null인 메시지에도 안전하게 reader를 추가한다")
    void marksRead_whenReadersIsNull() {
        List<String> ids = IntStream.range(0, 5)
                .mapToObj(i -> saveMessageWithNullReaders().getId())
                .toList();

        service.updateReadStatus(ids, "user-A");

        for (String id : ids) {
            assertThat(readerUserIds(id)).containsExactly("user-A");
        }
    }

    @Test
    @DisplayName("같은 사용자가 다시 호출해도 reader가 중복되지 않는다 (멱등)")
    void idempotent_forSameUser() {
        String id = saveMessageWithNullReaders().getId();

        service.updateReadStatus(List.of(id), "user-A");
        service.updateReadStatus(List.of(id), "user-A");

        assertThat(readerUserIds(id)).containsExactly("user-A");

        // 두 번째 호출은 필터에서 걸러져 수정 문서 0
        long secondModified = messageRepository.updateReadersForMessages(
                List.of(id), "user-A", LocalDateTime.now());
        assertThat(secondModified).isZero();
    }

    @Test
    @DisplayName("서로 다른 사용자는 각각 reader로 누적된다")
    void accumulatesDistinctReaders() {
        String id = saveMessageWithNullReaders().getId();

        service.updateReadStatus(List.of(id), "user-A");
        service.updateReadStatus(List.of(id), "user-B");

        assertThat(readerUserIds(id)).containsExactlyInAnyOrder("user-A", "user-B");
    }

    @Test
    @DisplayName("대상 messageIds에 포함되지 않은 메시지는 영향받지 않는다")
    void onlyTargetedMessagesAffected() {
        String target = saveMessageWithNullReaders().getId();
        String other = saveMessageWithNullReaders().getId();

        service.updateReadStatus(List.of(target), "user-A");

        assertThat(readerUserIds(target)).containsExactly("user-A");
        assertThat(readerUserIds(other)).isEmpty();
    }

    @Test
    @DisplayName("동시에 여러 사용자가 같은 메시지를 읽어도 lost update 없이 모두 누적된다")
    void concurrentReads_noLostUpdate() throws InterruptedException {
        String id = saveMessageWithNullReaders().getId();
        int users = 20;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);

        for (int i = 0; i < users; i++) {
            String userId = "user-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.updateReadStatus(List.of(id), userId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // read-modify-save였다면 일부 유실됐을 것. atomic이면 20명 모두 존재.
        assertThat(readerUserIds(id)).hasSize(users);
    }
}
