package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.ReadCursor;
import com.ktb.chatapp.service.readcursor.ReadCursorStore;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * read cursor 재설계의 읽음 처리 비용을 실측한다.
 *
 * <p>커서 방식은 읽은 메시지 개수와 무관하게 읽음 1건당 {@code findAndModify} 1회로 끝난다.
 * (기존 per-message readers 방식은 읽음마다 조회 3회 + 메시지 수만큼 갱신이었다 —
 * {@link ReadStatusQueryCountIntegrationTest} 참고.) 이 숫자가
 * docs/perf 의 근거이자 회귀 가드다.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class ReadCursorQueryCountIntegrationTest {

    @Autowired
    private ReadCursorStore readCursorStore;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private CommandCountingListener listener;

    @AfterEach
    void tearDown() {
        listener.stop();
        mongoTemplate.dropCollection(ReadCursor.class);
    }

    @Test
    @DisplayName("읽음 처리: 읽은 메시지 수와 무관하게 findAndModify 1회")
    void advance_isSingleCommand() {
        listener.start();
        boolean advanced = readCursorStore.advance("room-1", "user-A", 1_000L);
        listener.stop();

        Map<String, Long> snapshot = listener.snapshot();
        System.out.printf("[read-cursor] advance=%s total=%d%n", snapshot, listener.totalDataCommands());

        assertThat(advanced).isTrue();
        assertThat(snapshot.getOrDefault("findAndModify", 0L)).isEqualTo(1L);
        assertThat(listener.totalDataCommands()).isEqualTo(1L);
    }

    @Test
    @DisplayName("커서는 단조 전진: 더 작거나 같은 ts는 무시(false)")
    void advance_isMonotonic() {
        assertThat(readCursorStore.advance("room-1", "user-A", 100L)).isTrue();
        assertThat(readCursorStore.advance("room-1", "user-A", 50L)).isFalse();  // 역행 무시
        assertThat(readCursorStore.advance("room-1", "user-A", 100L)).isFalse(); // 동일 무시
        assertThat(readCursorStore.advance("room-1", "user-A", 150L)).isTrue();  // 전진

        assertThat(readCursorStore.findByRoom("room-1")).containsEntry("user-A", 150L);
    }

    @Test
    @DisplayName("findByRoom: 방의 모든 참가자 커서를 반환")
    void findByRoom_returnsAllCursors() {
        readCursorStore.advance("room-1", "user-A", 100L);
        readCursorStore.advance("room-1", "user-B", 200L);
        readCursorStore.advance("room-2", "user-C", 300L);

        Map<String, Long> room1 = readCursorStore.findByRoom("room-1");
        assertThat(room1).containsOnly(
                Map.entry("user-A", 100L),
                Map.entry("user-B", 200L));
    }
}
