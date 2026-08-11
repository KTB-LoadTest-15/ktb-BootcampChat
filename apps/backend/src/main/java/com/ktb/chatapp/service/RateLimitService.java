package com.ktb.chatapp.service;

import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static java.net.InetAddress.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitStore rateLimitStore;
    @Value("${HOSTNAME:''}")
    private String hostName;
    
    @PostConstruct
    public void init() {
        if (!hostName.isEmpty()) {
            return;
        }
        hostName = generateHostname();
    }
    
    private String generateHostname() {
        try {
            return getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }
    
    
    /**
     * 요청 카운트를 <b>단일 원자 연산</b>({@link RateLimitStore#incrementAndGet})으로 증가시키고
     * 한도 초과 여부를 판정한다.
     *
     * <p>기존 find + save(2 왕복, 비원자 read-modify-write)를 원자 findAndModify 1 왕복으로 대체했다.
     * {@code @Transactional}도 제거했다 — 단일 원자 연산이라 트랜잭션 경계가 필요 없다.
     *
     * <p><b>동작 동치성</b>: 관측 가능한 출력(allowed/remaining/retryAfter/reset)은 기존과 동일하다.
     * 한도 도달 후에는 원자 증가 특성상 저장된 {@code count}가 {@code maxRequests}를 넘을 수 있으나,
     * "{@code count > maxRequests}이면 거부"로 판정하므로 <b>정확히 maxRequests개만 허용</b>된다(기존과 동일).
     * 초과 구간의 count 값은 클라이언트에 노출되지 않고 TTL 인덱스로 정리된다.
     */
    public RateLimitCheckResult checkRateLimit(String _clientId, int maxRequests, Duration window) {
        String actualClientId = hostName + ":" + _clientId;
        Duration effectiveWindow = window != null ? window : Duration.ofSeconds(1);
        long windowSeconds = Math.max(1L, effectiveWindow.getSeconds());
        Instant now = Instant.now();
        long nowEpochSeconds = now.getEpochSecond();
        Instant resetExpiresAt = now.plusSeconds(windowSeconds);

        try {
            RateLimit rateLimit = rateLimitStore.incrementAndGet(actualClientId, now, resetExpiresAt);

            int count = rateLimit.getCount();
            long resetEpochSeconds = rateLimit.getExpiresAt().getEpochSecond();
            long retryAfterSeconds = Math.max(1L, resetEpochSeconds - nowEpochSeconds);

            if (count > maxRequests) {
                return RateLimitCheckResult.rejected(
                        maxRequests, windowSeconds, resetEpochSeconds, retryAfterSeconds);
            }

            int remaining = Math.max(0, maxRequests - count);
            return RateLimitCheckResult.allowed(
                    maxRequests, remaining, windowSeconds, resetEpochSeconds, retryAfterSeconds);
        } catch (Exception e) {
            log.error("Rate limit check failed for client: {}", actualClientId, e);
            long resetEpochSeconds = nowEpochSeconds + windowSeconds;
            return RateLimitCheckResult.allowed(
                    maxRequests, maxRequests, windowSeconds, resetEpochSeconds, windowSeconds);
        }
    }

}
