package com.ktb.chatapp.service.message;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

/**
 * {@link MessageStore}의 Redis 구현 (write-behind hot store).
 *
 * <p>{@code message.store=redis}일 때 활성화된다. 저장은 이미 auto-config된 Spring Data Redis
 * ({@link StringRedisTemplate})로 하며 새 인프라 빈이 필요 없다. 자료구조:
 * <ul>
 *   <li>{@code chat:messages} — Hash(id → JSON): 메시지 본문</li>
 *   <li>{@code chat:room:{roomId}:msgIdx} — ZSet(member=id, score=timestamp millis): 방별 순서</li>
 *   <li>{@code chat:fileIndex} — Hash(fileId → id): 파일 권한 조회</li>
 * </ul>
 *
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "message.store", havingValue = "redis")
public class RedisMessageStore implements MessageStore {

    static final String KEY_MESSAGES = "chat:messages";
    static final String KEY_FILE_INDEX = "chat:fileIndex";
    static final String KEY_FLUSH_PENDING = "chat:flushPending";

    private final HashOperations<String, String, String> hashOps;
    private final ZSetOperations<String, String> zSetOps;
    private final SetOperations<String, String> setOps;
    private final MessageCodec codec;

    public RedisMessageStore(StringRedisTemplate redisTemplate, MessageCodec codec) {
        this.hashOps = redisTemplate.opsForHash();
        this.zSetOps = redisTemplate.opsForZSet();
        this.setOps = redisTemplate.opsForSet();
        this.codec = codec;
    }

    private static String roomIndexKey(String roomId) {
        return "chat:room:" + roomId + ":msgIdx";
    }

    private static long toMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String serialize(Message message) {
        return codec.serialize(message);
    }

    private Message deserialize(String json) {
        return codec.deserialize(json);
    }

    /** 배치 flusher가 Mongo로 영속화하도록 변경된 메시지 id를 대기 큐에 넣는다. */
    private void markPending(String messageId) {
        setOps.add(KEY_FLUSH_PENDING, messageId);
    }

    @Override
    public Message add(Message message) {
        if (message.getId() == null) {
            message.setId(new ObjectId().toHexString());
        }
        if (message.getTimestamp() == null) {
            message.setTimestamp(LocalDateTime.now());
        }
        hashOps.put(KEY_MESSAGES, message.getId(), serialize(message));
        zSetOps.add(roomIndexKey(message.getRoomId()), message.getId(),
                (double) message.toTimestampMillis());
        if (message.getFileId() != null) {
            hashOps.put(KEY_FILE_INDEX, message.getFileId(), message.getId());
        }
        markPending(message.getId());
        return message;
    }

    @Override
    public Message update(Message message) {
        // 본문만 갱신(리액션 등). 순서/방은 불변이라 인덱스는 그대로 둔다.
        hashOps.put(KEY_MESSAGES, message.getId(), serialize(message));
        markPending(message.getId());
        return message;
    }

    /**
     * 리액션 추가(best-effort read-modify-write).
     *
     * <p>Redis 모드는 로드테스트용으로 throughput을 우선하며 정합성 완화를 수용한다(프로젝트 방침).
     * 메시지가 Hash field 하나에 JSON 통짜로 저장돼 원자적 부분 갱신이 어렵기 때문에, 여기서는
     * 도메인 메서드로 in-memory 갱신 후 통째로 다시 쓴다. 같은 메시지에 대한 동시 리액션은
     * lost update가 가능하다(저빈도라 영향 작음). 강한 정합성은 Mongo 모드에서 보장한다.
     */
    @Override
    public Optional<Message> addReaction(String messageId, String reaction, String userId) {
        return findById(messageId).map(message -> {
            message.addReaction(reaction, userId);
            return update(message);
        });
    }

    /** 리액션 제거(best-effort read-modify-write). {@link #addReaction}의 정합성 주석 참고. */
    @Override
    public Optional<Message> removeReaction(String messageId, String reaction, String userId) {
        return findById(messageId).map(message -> {
            message.removeReaction(reaction, userId);
            return update(message);
        });
    }

    @Override
    public Optional<Message> findById(String id) {
        String json = hashOps.get(KEY_MESSAGES, id);
        return json == null ? Optional.empty() : Optional.of(deserialize(json));
    }

    @Override
    public Optional<Message> findByFileId(String fileId) {
        String messageId = hashOps.get(KEY_FILE_INDEX, fileId);
        return messageId == null ? Optional.empty() : findById(messageId);
    }

    @Override
    public long countRecentMessages(String roomId, LocalDateTime since) {
        Long count = zSetOps.count(roomIndexKey(roomId), (double) toMillis(since), Double.POSITIVE_INFINITY);
        return count == null ? 0L : count;
    }

    @Override
    public MessagePage findMessagesBefore(String roomId, LocalDateTime before, int limit) {
        // score < before(strict): 점수는 정수 millis이므로 max = before-1로 배타 경계를 만든다.
        double maxExclusive = (double) (toMillis(before) - 1);
        Set<String> ids = zSetOps.reverseRangeByScore(
                roomIndexKey(roomId), Double.NEGATIVE_INFINITY, maxExclusive, 0, (long) limit + 1);
        if (ids == null || ids.isEmpty()) {
            return new MessagePage(List.of(), false);
        }

        boolean hasMore = ids.size() > limit;
        List<String> pageIds = new ArrayList<>(ids);
        if (hasMore) {
            pageIds = pageIds.subList(0, limit);
        }

        List<String> jsons = hashOps.multiGet(KEY_MESSAGES, pageIds);
        List<Message> messages = new ArrayList<>(pageIds.size());
        for (String json : jsons) {
            if (json != null) {
                messages.add(deserialize(json));
            }
        }
        return new MessagePage(messages, hasMore);
    }

    @Override
    public long addReaderToMessages(List<String> messageIds, String userId, LocalDateTime readAt) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0L;
        }
        List<String> jsons = hashOps.multiGet(KEY_MESSAGES, messageIds);
        Map<String, String> updated = new HashMap<>();
        for (String json : jsons) {
            if (json == null) {
                continue;
            }
            Message message = deserialize(json);
            List<Message.MessageReader> readers = message.getReaders();
            if (readers == null) {
                readers = new ArrayList<>();
                message.setReaders(readers);
            }
            boolean alreadyRead = readers.stream().anyMatch(r -> r.getUserId().equals(userId));
            if (!alreadyRead) {
                readers.add(Message.MessageReader.builder().userId(userId).readAt(readAt).build());
                updated.put(message.getId(), serialize(message));
            }
        }
        if (!updated.isEmpty()) {
            hashOps.putAll(KEY_MESSAGES, updated);
        }
        return updated.size();
    }
}
