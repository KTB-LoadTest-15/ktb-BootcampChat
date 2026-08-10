package com.ktb.chatapp.service.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    private final HashOperations<String, String, String> hashOps;
    private final ZSetOperations<String, String> zSetOps;
    // 이 앱 컨텍스트에는 ObjectMapper 빈이 없어 내부에서 구성한다(LocalDateTime 지원).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public RedisMessageStore(StringRedisTemplate redisTemplate) {
        this.hashOps = redisTemplate.opsForHash();
        this.zSetOps = redisTemplate.opsForZSet();
    }

    private static String roomIndexKey(String roomId) {
        return "chat:room:" + roomId + ":msgIdx";
    }

    private static long toMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String serialize(Message message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("메시지 직렬화 실패: " + message.getId(), e);
        }
    }

    private Message deserialize(String json) {
        try {
            return objectMapper.readValue(json, Message.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("메시지 역직렬화 실패", e);
        }
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
        return message;
    }

    @Override
    public Message update(Message message) {
        // 본문만 갱신(리액션 등). 순서/방은 불변이라 인덱스는 그대로 둔다.
        hashOps.put(KEY_MESSAGES, message.getId(), serialize(message));
        return message;
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
