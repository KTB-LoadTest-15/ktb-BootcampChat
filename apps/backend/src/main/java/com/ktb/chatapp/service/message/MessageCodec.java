package com.ktb.chatapp.service.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.model.Message;
import org.springframework.stereotype.Component;

/**
 * 메시지 ↔ JSON 직렬화 코덱. Redis 저장(RedisMessageStore)과 Mongo flush(RedisToMongoFlusher)가 공유한다.
 *
 * <p>이 앱 컨텍스트에는 ObjectMapper 빈이 없어 자체 구성한다(LocalDateTime 지원).
 */
@Component
public class MessageCodec {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 구버전 문서에 남아 있는 필드(예: 이관 전 readers)를 만나도 역직렬화가 깨지지 않게 한다.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String serialize(Message message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("메시지 직렬화 실패: " + message.getId(), e);
        }
    }

    public Message deserialize(String json) {
        try {
            return objectMapper.readValue(json, Message.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("메시지 역직렬화 실패", e);
        }
    }
}
