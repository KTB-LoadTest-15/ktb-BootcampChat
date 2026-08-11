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
 * 전진은 현재값과 비교 후 큰 경우에만 {@code HSET}. 같은 사용자의 읽음은 세션 키 레인으로 직렬화되어
 * 단일 필드 read-compare-set이 안전하다(서로 다른 사용자는 다른 필드라 무관). 한 사용자가 다중
 * 세션/노드로 동시에 읽는 경우의 원자성은 Lua CAS 과제(docs/perf D5 §6)로 남긴다.
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
