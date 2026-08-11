package com.ktb.chatapp.service.readcursor;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link ReadCursorStore}의 Redis 구현.
 *
 * <p>방마다 해시 {@code readcursor:{roomId}}(field=userId → value=epoch millis)를 쓴다.
 * 전진은 현재값과 비교 후 큰 경우에만 {@code HSET}. 읽음 처리는 dispatcher가 roomId 키로
 * 직렬화하므로(같은 방은 단일 레인) 노드 내에서 read-compare-set이 레이스 없이 안전하다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "message.store", havingValue = "redis")
public class RedisReadCursorStore implements ReadCursorStore {

    private final HashOperations<String, String, String> hashOps;

    public RedisReadCursorStore(StringRedisTemplate redisTemplate) {
        this.hashOps = redisTemplate.opsForHash();
    }

    private static String key(String roomId) {
        return "readcursor:" + roomId;
    }

    @Override
    public boolean advance(String roomId, String userId, long lastReadTs) {
        String key = key(roomId);
        String current = hashOps.get(key, userId);
        if (current != null) {
            try {
                if (Long.parseLong(current) >= lastReadTs) {
                    return false;
                }
            } catch (NumberFormatException e) {
                log.warn("Corrupt read cursor value for room {} user {}: {}", roomId, userId, current);
            }
        }
        hashOps.put(key, userId, Long.toString(lastReadTs));
        return true;
    }

    @Override
    public Map<String, Long> findByRoom(String roomId) {
        Map<String, String> entries = hashOps.entries(key(roomId));
        Map<String, Long> result = new HashMap<>(entries.size());
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            try {
                result.put(entry.getKey(), Long.parseLong(entry.getValue()));
            } catch (NumberFormatException e) {
                log.warn("Corrupt read cursor value for room {} user {}: {}",
                        roomId, entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
