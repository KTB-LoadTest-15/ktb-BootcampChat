package com.ktb.chatapp.service.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.model.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.service.SessionService.SESSION_TTL_SEC;

/**
 * {@link SessionStore}의 Redis 구현 (hot store).
 *
 * <p>{@code session.store=redis}일 때 활성화된다. 유저당 세션 1개를 키
 * {@code session:{userId}} 아래 JSON 문자열로 저장하며, 만료는 Redis 네이티브 per-key TTL로
 * 위임한다({@code SET ... EX}). 세션 검증 hot path(REST 인증 / WS 핸드셰이크 / 메시지)가
 * Mongo find 대신 O(1) {@code GET} 한 번으로 끝난다.
 *
 * <p>이미 auto-config된 Spring Data Redis({@link StringRedisTemplate})만 쓰고 새 인프라 빈이
 * 필요 없다. {@code Instant expiresAt} 직렬화를 위해 자체 {@link ObjectMapper}(JavaTimeModule)를
 * 구성한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "session.store", havingValue = "redis")
public class SessionRedisStore implements SessionStore {

    private final ValueOperations<String, String> valueOps;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 구버전/이관 잔여 필드를 만나도 역직렬화가 깨지지 않게 한다.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public SessionRedisStore(StringRedisTemplate redisTemplate) {
        this.valueOps = redisTemplate.opsForValue();
    }

    private static String key(String userId) {
        return "session:" + userId;
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        String json = valueOps.get(key(userId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, Session.class));
        } catch (JsonProcessingException e) {
            log.warn("Corrupt session payload for user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Session save(Session session) {
        // TTL은 세션의 expiresAt에서 파생한다(단일 진실원). 없거나 과거면 기본 TTL로 보정.
        long ttlSec = SESSION_TTL_SEC;
        if (session.getExpiresAt() != null) {
            long remaining = Duration.between(Instant.now(), session.getExpiresAt()).getSeconds();
            if (remaining > 0) {
                ttlSec = remaining;
            }
        }
        try {
            String json = objectMapper.writeValueAsString(session);
            valueOps.set(key(session.getUserId()), json, ttlSec, TimeUnit.SECONDS);
            return session;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("세션 직렬화 실패: userId=" + session.getUserId(), e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        // Mongo 구현과 동치: 저장된 세션이 해당 sessionId일 때만 삭제한다.
        findByUserId(userId).ifPresent(session -> {
            if (sessionId.equals(session.getSessionId())) {
                valueOps.getOperations().delete(key(userId));
            }
        });
    }

    @Override
    public void deleteAll(String userId) {
        valueOps.getOperations().delete(key(userId));
    }
}
