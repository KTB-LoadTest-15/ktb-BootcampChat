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
import java.time.Instant;
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
 * 세션 활동 시각 write throttle(P0-2)의 명령 수를 실측한다.
 *
 * <p>메시지 hot path에서 {@code validateSession}은 매 메시지마다 세션을 저장(find + update)했다.
 * throttle을 켜면(기본 60s) 최근 갱신이 창 이내인 동안 update를 생략한다. OLD = throttle 없이 매번
 * find+save를 반복(폭주 시 update N). NEW = throttle 적용 validateSession(창 이내면 update 0).
 * 숫자는 docs/perf/P0-2-session-write-throttle.md 의 근거이자 회귀 가드다.
 *
 * @see com.ktb.chatapp.service.SessionService#validateSession
 * @see com.ktb.chatapp.websocket.socketio.handler.ChatMessageHandler#handleChatMessage
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false",
        "session.touch.throttle-ms=60000"
})
class SessionTouchThrottleQueryCountIntegrationTest {

    private static final int BURST = 5;

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

    /** 제거된 옛 동작을 그대로 재현: 매 검증마다 findByUserId + save(update). */
    private void legacyTouch() {
        Session s = sessionRepository.findByUserId(userId).orElseThrow();
        s.setLastActivity(Instant.now().toEpochMilli());
        s.setExpiresAt(Instant.now().plusSeconds(SessionService.SESSION_TTL_SEC));
        sessionRepository.save(s);
    }

    @Test
    @DisplayName("연속 " + BURST + "건 검증: OLD update " + BURST + " → NEW update 0 (throttle 창 이내 write 생략)")
    void sessionTouch_throttle_beforeAndAfter() {
        // --- BEFORE: throttle 없이 매 검증마다 find + save ---
        listener.start();
        for (int i = 0; i < BURST; i++) {
            legacyTouch();
        }
        listener.stop();
        Map<String, Long> oldSnapshot = listener.snapshot();
        long oldTotal = listener.totalDataCommands();

        // --- AFTER: throttle 적용 validateSession 을 창 이내로 연속 호출 ---
        listener.start();
        SessionValidationResult last = null;
        for (int i = 0; i < BURST; i++) {
            last = sessionService.validateSession(userId, sessionId);
        }
        listener.stop();
        Map<String, Long> newSnapshot = listener.snapshot();
        long newTotal = listener.totalDataCommands();

        System.out.printf("[session-throttle] BEFORE=%s total=%d%n", oldSnapshot, oldTotal);
        System.out.printf("[session-throttle] AFTER =%s total=%d%n", newSnapshot, newTotal);

        // BEFORE: 검증당 find+update → find BURST + update BURST
        assertThat(oldSnapshot.get("find")).isEqualTo((long) BURST);
        assertThat(oldSnapshot.get("update")).isEqualTo((long) BURST);
        assertThat(oldTotal).isEqualTo(2L * BURST);

        // AFTER: 검증마다 find는 있으나(세션 조회) update는 throttle로 0
        assertThat(newSnapshot.get("find")).isEqualTo((long) BURST);
        assertThat(newSnapshot.getOrDefault("update", 0L)).isZero();
        assertThat(newTotal).isEqualTo((long) BURST);

        // 동작 동치성: 창 이내 write를 생략해도 세션은 계속 유효
        assertThat(last).isNotNull();
        assertThat(last.isValid()).isTrue();
    }

    @Test
    @DisplayName("throttle 창을 넘긴 세션은 다시 write 된다(find+update)")
    void staleSession_writesAgain() {
        // 저장된 세션의 lastActivity를 창(60s) 밖으로 되돌린다(측정 밖 준비 write).
        Session s = sessionRepository.findByUserId(userId).orElseThrow();
        s.setLastActivity(Instant.now().minusSeconds(120).toEpochMilli());
        sessionRepository.save(s);

        listener.start();
        SessionValidationResult result = sessionService.validateSession(userId, sessionId);
        listener.stop();
        Map<String, Long> snapshot = listener.snapshot();

        System.out.printf("[session-throttle] STALE=%s%n", snapshot);

        // 창을 넘겼으므로 update가 다시 일어난다
        assertThat(snapshot.get("find")).isEqualTo(1L);
        assertThat(snapshot.get("update")).isEqualTo(1L);
        assertThat(result.isValid()).isTrue();

        // 갱신되어 lastActivity가 최신으로 올라왔다
        Session refreshed = sessionRepository.findByUserId(userId).orElseThrow();
        assertThat(refreshed.getLastActivity())
                .isGreaterThan(Instant.now().minusSeconds(60).toEpochMilli());
    }
}
