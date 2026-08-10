package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import com.ktb.chatapp.service.SessionCreationResult;
import com.ktb.chatapp.service.SessionMetadata;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import java.util.Map;
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
 * 메시지 전송 hot path의 세션 write footprint 개선 전/후 Mongo 명령 횟수를 실측한다(P0-5).
 *
 * <p>OLD = 제거된 중복 호출을 그대로 재현: {@code validateSession()} 직후 {@code updateLastActivity()}.
 * NEW = 현재 hot path: {@code validateSession()}만 호출(이미 lastActivity/expiresAt을 갱신·저장함).
 * 숫자는 docs/perf/P0-5-duplicate-session-touch.md 의 근거이자 회귀 가드다.
 *
 * @see com.ktb.chatapp.websocket.socketio.handler.ChatMessageHandler#handleChatMessage
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class SessionTouchQueryCountIntegrationTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private CommandCountingListener listener;

    private Faker faker;
    private String userId;
    private String sessionId;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        userId = faker.internet().uuid();
        SessionCreationResult created = sessionService.createSession(
                userId, new SessionMetadata("junit", "127.0.0.1", "test"));
        sessionId = created.getSessionId();
    }

    @AfterEach
    void tearDown() {
        listener.stop();
        sessionRepository.deleteAll();
    }

    @Test
    @DisplayName("메시지당 세션 touch: OLD 4 명령(find2+update2) → NEW 2 명령(find1+update1)")
    void sessionTouch_commandCount_beforeAndAfter() {
        // --- BEFORE: validateSession() + updateLastActivity() (제거된 중복 호출) ---
        listener.start();
        sessionService.validateSession(userId, sessionId);
        sessionService.updateLastActivity(userId);
        listener.stop();
        Map<String, Long> oldSnapshot = listener.snapshot();
        long oldTotal = listener.totalDataCommands();

        // --- AFTER: validateSession()만 (현재 hot path) ---
        listener.start();
        SessionValidationResult validation = sessionService.validateSession(userId, sessionId);
        listener.stop();
        Map<String, Long> newSnapshot = listener.snapshot();
        long newTotal = listener.totalDataCommands();

        System.out.printf("[session-touch] BEFORE=%s total=%d%n", oldSnapshot, oldTotal);
        System.out.printf("[session-touch] AFTER =%s total=%d%n", newSnapshot, newTotal);

        // --- 쿼리 횟수: 개선 검증 ---
        // BEFORE: validate(find1+update1) + updateLastActivity(find1+update1)
        assertThat(oldSnapshot.get("find")).isEqualTo(2L);
        assertThat(oldSnapshot.get("update")).isEqualTo(2L);
        assertThat(oldTotal).isEqualTo(4L);

        // AFTER: validate(find1+update1) 만 → 세션 write 절반
        assertThat(newSnapshot.get("find")).isEqualTo(1L);
        assertThat(newSnapshot.get("update")).isEqualTo(1L);
        assertThat(newTotal).isEqualTo(2L);

        // --- 동작 동치성: 쿼리가 줄어도 결과는 동일해야 한다 ---
        // validateSession()만으로도 세션은 유효하고 lastActivity/expiresAt이 갱신되어야 한다.
        assertThat(validation.isValid()).isTrue();
        Session refreshed = sessionRepository.findByUserId(userId).orElseThrow();
        assertThat(refreshed.getSessionId()).isEqualTo(sessionId);
        assertThat(refreshed.getExpiresAt()).isNotNull();
        assertThat(refreshed.getLastActivity()).isGreaterThanOrEqualTo(refreshed.getCreatedAt());
    }
}
