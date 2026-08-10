package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.repository.RateLimitRepository;
import com.ktb.chatapp.service.RateLimitCheckResult;
import com.ktb.chatapp.service.RateLimitService;
import com.ktb.chatapp.service.ratelimit.RateLimitMongoStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 레이트리밋 원자화(P1-1)의 명령 수와 동시성 정합성을 실측한다.
 *
 * <p>OLD = 제거된 findByClientId + save(read-modify-write)를 그대로 재현. NEW = 단일 원자
 * {@code findAndModify}(pipeline upsert). 숫자·정합성은 docs/perf/P1-1-rate-limit-atomic.md 의
 * 근거이자 회귀 가드다.
 *
 * @see com.ktb.chatapp.service.ratelimit.RateLimitMongoStore
 * @see com.ktb.chatapp.websocket.socketio.handler.ChatMessageHandler#handleChatMessage
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class RateLimitAtomicQueryCountIntegrationTest {

    @Autowired
    private RateLimitRepository rateLimitRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private CommandCountingListener listener;

    private RateLimitMongoStore store;
    private Faker faker;

    @BeforeEach
    void setUp() {
        faker = new Faker();
        store = new RateLimitMongoStore(mongoTemplate);
        rateLimitRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        listener.stop();
        rateLimitRepository.deleteAll();
    }

    /** 제거된 옛 구현을 그대로 재현: findByClientId → in-memory 수정 → save. */
    private void legacyIncrement(String clientId, Instant expiresAt) {
        RateLimit rl = rateLimitRepository.findByClientId(clientId).orElse(null);
        if (rl == null) {
            rl = RateLimit.builder().clientId(clientId).count(1).expiresAt(expiresAt).build();
        } else {
            rl.setCount(rl.getCount() + 1);
        }
        rateLimitRepository.save(rl);
    }

    @Test
    @DisplayName("요청 1건: OLD 2 명령(find+insert) → NEW 1 명령(findAndModify)")
    void rateLimit_commandCount_beforeAndAfter() {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(60);
        String clientOld = "host:" + faker.internet().uuid();
        String clientNew = "host:" + faker.internet().uuid();

        // --- BEFORE: findByClientId + save ---
        listener.start();
        legacyIncrement(clientOld, expiresAt);
        listener.stop();
        Map<String, Long> oldSnapshot = listener.snapshot();
        long oldTotal = listener.totalDataCommands();

        // --- AFTER: 원자 findAndModify upsert ---
        listener.start();
        store.incrementAndGet(clientNew, now, expiresAt);
        listener.stop();
        Map<String, Long> newSnapshot = listener.snapshot();
        long newTotal = listener.totalDataCommands();

        System.out.printf("[rate-limit] BEFORE=%s total=%d%n", oldSnapshot, oldTotal);
        System.out.printf("[rate-limit] AFTER =%s total=%d%n", newSnapshot, newTotal);

        // BEFORE: find(0건) + insert(신규 save) = 2
        assertThat(oldSnapshot.get("find")).isEqualTo(1L);
        assertThat(oldSnapshot.get("insert")).isEqualTo(1L);
        assertThat(oldTotal).isEqualTo(2L);

        // AFTER: findAndModify 단일 명령
        assertThat(newSnapshot.get("findAndModify")).isEqualTo(1L);
        assertThat(newTotal).isEqualTo(1L);

        // 동작 동치성: 두 경로 모두 최초 요청 count=1
        assertThat(rateLimitRepository.findByClientId(clientOld).orElseThrow().getCount()).isEqualTo(1);
        assertThat(rateLimitRepository.findByClientId(clientNew).orElseThrow().getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료된 문서는 원자 연산 내부에서 새 윈도(count=1)로 리셋된다")
    void expiredWindow_resetsAtomically() {
        String clientId = "host:" + faker.internet().uuid();
        // 과거에 만료된 문서(count=5)를 미리 저장
        rateLimitRepository.save(RateLimit.builder()
                .clientId(clientId).count(5).expiresAt(Instant.now().minusSeconds(10)).build());

        Instant now = Instant.now();
        RateLimit result = store.incrementAndGet(clientId, now, now.plusSeconds(60));

        // 만료 → 리셋: count=1, 새 만료시각
        assertThat(result.getCount()).isEqualTo(1);
        assertThat(result.getExpiresAt()).isAfter(now);
    }

    @Test
    @DisplayName("미만료 문서는 count만 증가하고 만료시각은 유지된다")
    void activeWindow_incrementsWithoutExtendingExpiry() {
        String clientId = "host:" + faker.internet().uuid();
        Instant expiresAt = Instant.now().plusSeconds(60);
        rateLimitRepository.save(RateLimit.builder()
                .clientId(clientId).count(1).expiresAt(expiresAt).build());

        Instant now = Instant.now();
        RateLimit result = store.incrementAndGet(clientId, now, now.plusSeconds(60));

        assertThat(result.getCount()).isEqualTo(2);
        // 기존 만료시각 유지(초 단위 동일)
        assertThat(result.getExpiresAt().getEpochSecond()).isEqualTo(expiresAt.getEpochSecond());
    }

    @Test
    @DisplayName("동시 요청: 같은 클라이언트 20건이 동시에 와도 원자 증가로 정확히 maxRequests만 허용(over-admission 없음)")
    void concurrentRequests_exactlyMaxAllowed_noLostUpdate() throws InterruptedException {
        int maxRequests = 10;
        int totalRequests = 20;
        Duration window = Duration.ofSeconds(60);
        String clientId = "ip:" + faker.internet().uuid();

        ExecutorService pool = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalRequests);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < totalRequests; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    RateLimitCheckResult r = rateLimitService.checkRateLimit(clientId, maxRequests, window);
                    if (r.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
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

        // 원자 연산이면 정확히 maxRequests개만 허용(비원자 read-modify-write였다면 lost update로 초과 허용).
        assertThat(allowed.get()).isEqualTo(maxRequests);
        assertThat(rejected.get()).isEqualTo(totalRequests - maxRequests);

        // 모든 요청이 원자 증가되어 저장 count == 총 요청 수(유실 없음).
        assertThat(rateLimitRepository.findAll()).hasSize(1);
        assertThat(rateLimitRepository.findAll().getFirst().getCount()).isEqualTo(totalRequests);
    }
}
