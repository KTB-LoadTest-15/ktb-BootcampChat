package com.ktb.chatapp.service;

import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RateLimitService 단위 테스트 (원자 저장소 계약 기준).
 *
 * <p>서비스는 {@link RateLimitStore#incrementAndGet}가 반환한 최신 count로 한도 초과 여부를 판정한다.
 * 저장소의 증가/리셋 원자성 자체는 통합/부하 테스트에서, 여기서는 서비스의 판정·정규화·fail-open과
 * host-prefixed 키/리셋 만료시각 전달을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String HOST_NAME = "test-host";
    private static final String CLIENT_ID = "client-1";
    private static final String STORE_CLIENT_ID = HOST_NAME + ":" + CLIENT_ID;

    @Mock
    private RateLimitStore rateLimitStore;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(rateLimitStore);
        ReflectionTestUtils.setField(rateLimitService, "hostName", HOST_NAME);
    }

    private RateLimit stored(int count, Instant expiresAt) {
        return RateLimit.builder().clientId(STORE_CLIENT_ID).count(count).expiresAt(expiresAt).build();
    }

    @Test
    @DisplayName("최초 요청은 host-prefixed clientId + 리셋 만료시각으로 원자 증가되고 남은 횟수를 반환한다")
    void checkRateLimit_FirstRequest_UsesHostPrefixedKeyAndResetExpiry() {
        Instant expiresAt = Instant.now().plusSeconds(30);
        when(rateLimitStore.incrementAndGet(eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(1, expiresAt));
        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> resetCaptor = ArgumentCaptor.forClass(Instant.class);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isBetween(1L, 30L);

        org.mockito.Mockito.verify(rateLimitStore)
                .incrementAndGet(eq(STORE_CLIENT_ID), nowCaptor.capture(), resetCaptor.capture());
        // 리셋 만료시각 = now + window(30s)
        assertThat(resetCaptor.getValue()).isAfter(nowCaptor.getValue());
        assertThat(Duration.between(nowCaptor.getValue(), resetCaptor.getValue()).getSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("한도 미만이면 증가된 count로 남은 횟수를 계산해 허용한다")
    void checkRateLimit_BelowLimit_Allows() {
        when(rateLimitStore.incrementAndGet(eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(2, Instant.now().plusSeconds(20)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("증가된 count가 한도를 초과하면 retry-after/reset을 반환하고 거부한다")
    void checkRateLimit_OverLimit_Rejects() {
        Instant expiresAt = Instant.now().plusSeconds(10);
        // 원자 증가로 한도(3) 초과: 반환 count=4 → 거부
        when(rateLimitStore.incrementAndGet(eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(4, expiresAt));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isBetween(1L, 10L);
        assertThat(result.resetEpochSeconds()).isEqualTo(expiresAt.getEpochSecond());
    }

    @Test
    @DisplayName("count가 정확히 한도와 같으면 마지막 허용으로 remaining 0을 반환한다")
    void checkRateLimit_AtLimit_AllowsWithZeroRemaining() {
        when(rateLimitStore.incrementAndGet(eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(3, Instant.now().plusSeconds(30)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isZero();
    }

    @Test
    @DisplayName("0초 window는 최소 1초 window로 정규화된다")
    void checkRateLimit_ZeroWindow_NormalizesToOneSecond() {
        when(rateLimitStore.incrementAndGet(eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(1, Instant.now().plusSeconds(1)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ZERO);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("null window는 최소 1초 window로 정규화된다")
    void checkRateLimit_NullWindow_NormalizesToOneSecond() {
        when(rateLimitStore.incrementAndGet(eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(1, Instant.now().plusSeconds(1)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("저장소 실패 시 요청은 허용하고 전체 한도를 남긴다(fail-open)")
    void checkRateLimit_StoreFailure_FailsOpenDeterministically() {
        when(rateLimitStore.incrementAndGet(eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("null clientId도 host prefix가 적용된 저장소 key로 처리된다")
    void checkRateLimit_NullClientId_UsesHostPrefixedNullKey() {
        String storeClientId = HOST_NAME + ":null";
        when(rateLimitStore.incrementAndGet(eq(storeClientId), any(Instant.class), any(Instant.class)))
                .thenReturn(RateLimit.builder().clientId(storeClientId).count(1)
                        .expiresAt(Instant.now().plusSeconds(30)).build());

        RateLimitCheckResult result = rateLimitService.checkRateLimit(null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        org.mockito.Mockito.verify(rateLimitStore)
                .incrementAndGet(eq(storeClientId), any(Instant.class), any(Instant.class));
    }
}
