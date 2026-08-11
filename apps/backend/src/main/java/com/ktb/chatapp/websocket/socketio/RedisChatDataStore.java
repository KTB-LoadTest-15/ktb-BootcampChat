package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link ChatDataStore}의 Redis 구현 (다중 인스턴스 공유 상태).
 *
 * <p>{@code socketio.store=redisson}일 때 {@link LocalChatDataStore} 대신 주입된다.
 * {@code ConnectedUsers}(userId→SocketUser)·{@code UserRooms}(userId→Set&lt;roomId&gt;)가 이
 * 스토어를 경유하므로, Redis 백업으로 바꾸면 접속/멤버십 상태가 노드 간에 공유된다:
 * <ul>
 *   <li>중복 로그인 감지가 다른 노드의 기존 세션을 찾을 수 있다(②).</li>
 *   <li>읽음 권한 판정 {@code UserRooms.isInRoom}이 어느 노드에서든 일관된다(③).</li>
 * </ul>
 *
 * <p>값은 JSON 문자열로 저장한다({@code socketio:store:{key}}). 키 개수(=size)는 별도 Redis Set
 * {@code socketio:store:__keys}의 {@code SCARD}로 O(1)에 구한다(전체 키 SCAN 회피).
 *
 * <p><b>한계</b>: 노드 크래시 시 해당 노드가 남긴 conn_users 항목은 명시적 disconnect 없이 잔존한다
 * (TTL/heartbeat 미구현). 단기 이벤트 부하에서는 수용, 장기 운영 시 TTL 도입 과제.
 */
@Slf4j
public class RedisChatDataStore implements ChatDataStore {

    private static final String NS = "socketio:store:";
    private static final String KEYSET = "socketio:store:__keys";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public RedisChatDataStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String ns(String key) {
        return NS + key;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String json = redis.opsForValue().get(ns(key));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Corrupt socketio store value for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            redis.opsForValue().set(ns(key), objectMapper.writeValueAsString(value));
            redis.opsForSet().add(KEYSET, key);
        } catch (Exception e) {
            throw new IllegalStateException("socketio store 직렬화 실패: key=" + key, e);
        }
    }

    @Override
    public void delete(String key) {
        redis.delete(ns(key));
        redis.opsForSet().remove(KEYSET, key);
    }

    @Override
    public int size() {
        Long n = redis.opsForSet().size(KEYSET);
        return n == null ? 0 : n.intValue();
    }
}
