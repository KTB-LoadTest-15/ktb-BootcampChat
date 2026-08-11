package com.ktb.chatapp.service.message;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis hot store에 쌓인 메시지를 주기적으로 MongoDB로 영속화하는 배치 flusher (write-behind).
 *
 * <p>{@code message.flush.enabled=true}일 때만 활성화된다. 끄면 그대로 Redis-only(최후안)가 된다.
 * best-effort·비동기이며 라이브 hot path와 분리된다. {@code chat:flushPending} 집합에서
 * 배치로 꺼내({@code SPOP}) Mongo에 id 기준 upsert({@code saveAll})한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "message.flush.enabled", havingValue = "true")
public class RedisToMongoFlusher {

    private final SetOperations<String, String> setOps;
    private final HashOperations<String, String, String> hashOps;
    private final MessageRepository messageRepository;
    private final MessageCodec codec;

    public RedisToMongoFlusher(StringRedisTemplate redisTemplate,
                               MessageRepository messageRepository,
                               MessageCodec codec) {
        this.setOps = redisTemplate.opsForSet();
        this.hashOps = redisTemplate.opsForHash();
        this.messageRepository = messageRepository;
        this.codec = codec;
    }

    private static final int BATCH_SIZE = 500;

    @Scheduled(fixedDelayString = "${message.flush.interval-ms:5000}")
    public void flush() {
        try {
            int flushed = flushBatch(BATCH_SIZE);
            if (flushed > 0) {
                log.debug("Flushed {} messages Redis -> Mongo", flushed);
            }
        } catch (Exception e) {
            log.error("Redis -> Mongo flush 실패(다음 주기에 재시도)", e);
        }
    }

    /**
     * 대기 큐에서 최대 {@code max}개를 꺼내 Mongo로 영속화한다.
     * @return 실제로 영속화한 메시지 수
     */
    public int flushBatch(int max) {
        List<String> ids = setOps.pop(RedisMessageStore.KEY_FLUSH_PENDING, max);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> jsons = hashOps.multiGet(RedisMessageStore.KEY_MESSAGES, ids);
        List<Message> messages = new ArrayList<>(ids.size());
        for (String json : jsons) {
            if (json != null) {
                messages.add(codec.deserialize(json));
            }
        }
        if (!messages.isEmpty()) {
            messageRepository.saveAll(messages); // id가 있으므로 upsert(replace)
        }
        return messages.size();
    }
}
