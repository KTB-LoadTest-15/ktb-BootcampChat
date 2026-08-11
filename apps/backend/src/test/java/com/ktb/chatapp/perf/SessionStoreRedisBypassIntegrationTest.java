package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionCreationResult;
import com.ktb.chatapp.service.SessionMetadata;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.service.session.SessionStore;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
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
 * 세션 저장소 Redis 전환 검증 — {@code session.store=redis}일 때 세션 hot path가
 * MongoDB를 전혀 건드리지 않음을 실측하고, 동작 동치성과 Redis 원시 저장을 함께 증명한다.
 *
 * <p><b>Baseline(mongo 모드)</b>은 {@link SessionTouchThrottleQueryCountIntegrationTest}가 이미
 * 증명한다: {@code validateSession} 검증마다 {@code find} 1회(핫패스 Mongo read). 이 테스트는
 * <b>After(redis 모드)</b>로 그 read를 Redis {@code GET}으로 옮겨 Mongo 명령 = 0이 됨을 못박는다.
 *
 * @see com.ktb.chatapp.service.session.SessionRedisStore
 * @see SessionTouchThrottleQueryCountIntegrationTest
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false",
        "session.store=redis",
        "session.touch.throttle-ms=60000"
})
class SessionStoreRedisBypassIntegrationTest {

    private static final int BURST = 5;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CommandCountingListener listener;

    private Faker faker;
    private String userId;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        userId = faker.internet().uuid();
    }

    @AfterEach
    void tearDown() {
        listener.stop();
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    @DisplayName("redis 모드: 세션 hot path(create + validate×N + touch-write)가 Mongo 명령을 0회 발생시킨다")
    void redisMode_sessionHotPath_issuesZeroMongoCommands() {
        listener.start();

        // 1) 로그인: 세션 생성 (removeAll + save)
        SessionCreationResult created = sessionService.createSession(
                userId, new SessionMetadata("junit", "127.0.0.1", "test"));
        String sessionId = created.getSessionId();

        // 2) 핫패스 검증 BURST회 (throttle 창 이내 → GET만, write 생략)
        SessionValidationResult last = null;
        for (int i = 0; i < BURST; i++) {
            last = sessionService.validateSession(userId, sessionId);
        }

        // 3) throttle 창을 넘긴 상태로 만들어 touch-write 경로도 태운다 (Redis SET, Mongo 아님)
        Session stale = sessionStore.findByUserId(userId).orElseThrow();
        stale.setLastActivity(Instant.now().minusSeconds(120).toEpochMilli());
        sessionStore.save(stale);
        SessionValidationResult afterTouch = sessionService.validateSession(userId, sessionId);

        listener.stop();

        System.out.printf("[session-redis] mongo_commands=%s total=%d%n",
                listener.snapshot(), listener.totalDataCommands());

        // After(핵심): redis 모드에서 세션 hot path는 Mongo를 건드리지 않는다
        assertThat(listener.totalDataCommands())
                .as("redis 모드에서 세션 create/validate/touch 는 Mongo 명령 0회여야 한다")
                .isZero();

        // 동작 동치성: 검증은 유효하고, 스토어에 세션이 살아 있다
        assertThat(last).isNotNull();
        assertThat(last.isValid()).isTrue();
        assertThat(afterTouch.isValid()).isTrue();
        assertThat(sessionStore.findByUserId(userId)).isPresent();
    }

    @Test
    @DisplayName("동작 동치성: 잘못된 sessionId 거부 / 삭제 후 무효 / 네이티브 TTL 설정")
    void redisMode_behavioralEquivalence() {
        SessionCreationResult created = sessionService.createSession(
                userId, new SessionMetadata("junit", "127.0.0.1", "test"));
        String sessionId = created.getSessionId();

        // 유효한 검증
        assertThat(sessionService.validateSession(userId, sessionId).isValid()).isTrue();

        // sessionId 불일치는 거부 (Mongo 구현과 동일)
        SessionValidationResult mismatch = sessionService.validateSession(userId, "wrong-session-id");
        assertThat(mismatch.isValid()).isFalse();
        assertThat(mismatch.getError()).isEqualTo("INVALID_SESSION");

        // Redis 네이티브 TTL이 세션 키에 걸려 있다(만료를 Redis에 위임)
        Long ttl = redisTemplate.getExpire("session:" + userId, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(SessionService.SESSION_TTL_SEC);

        // 삭제 후 검증 무효
        sessionService.removeSession(userId, sessionId);
        assertThat(sessionService.validateSession(userId, sessionId).isValid()).isFalse();
        assertThat(redisTemplate.hasKey("session:" + userId)).isFalse();
    }

    @Test
    @DisplayName("Redis 기록 가시화: session:{userId} 키/값/TTL 덤프")
    void dumpRedisContents() {
        SessionCreationResult created = sessionService.createSession(
                userId, new SessionMetadata("junit", "127.0.0.1", "test"));

        System.out.println("========== REDIS DUMP (session.store=redis) ==========");
        String key = "session:" + userId;
        System.out.println("[key]   " + key);
        System.out.println("[value] " + redisTemplate.opsForValue().get(key));
        System.out.println("[ttl-s] " + redisTemplate.getExpire(key, TimeUnit.SECONDS));
        System.out.println("======================================================");

        assertThat(redisTemplate.hasKey(key)).isTrue();
        assertThat(created.getSessionId()).isNotBlank();
    }
}
