package com.ktb.chatapp.service.message;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationExpression;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * {@link MessageStore}의 MongoDB 구현. {@link MessageRepository}에 위임한다.
 *
 * <p>명령 footprint는 기존과 동일하게 유지한다: 신규는 {@code insert}, 갱신은 {@code update},
 * 페이지 조회는 {@code find} + {@code aggregate}(count). (docs/perf/P0-redis-baseline.md)
 */
@Component
@ConditionalOnProperty(name = "message.store", havingValue = "mongo", matchIfMissing = true)
@RequiredArgsConstructor
public class MongoMessageStore implements MessageStore {

    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;

    @Override
    public Message add(Message message) {
        if (message.getId() == null) {
            message.setId(new ObjectId().toHexString());
        }
        if (message.getTimestamp() == null) {
            message.setTimestamp(LocalDateTime.now());
        }
        // id를 앱에서 부여하므로 save(is-new 판정) 대신 insert로 명령을 insert로 고정한다.
        return messageRepository.insert(message);
    }

    @Override
    public Message update(Message message) {
        return messageRepository.save(message);
    }

    /**
     * 리액션 추가를 단일 원자 pipeline update로 처리한다.
     *
     * <p>{@code reactions.<emoji>} 배열에 {@code $setUnion}으로 userId를 합집합(중복 무시)한다.
     * 문서 전체를 다시 쓰지 않고 해당 필드만 갱신하므로 동시 리액션에도 lost update가 없다.
     */
    @Override
    public Optional<Message> addReaction(String messageId, String reaction, String userId) {
        String field = "reactions." + reaction;
        AggregationExpression union = ctx -> new Document("$setUnion", List.of(
                new Document("$ifNull", List.of("$" + field, List.of())),
                List.of(userId)));
        AggregationUpdate update = AggregationUpdate.update().set(field).toValueOf(union);
        return findAndModifyReactions(messageId, update);
    }

    /**
     * 리액션 제거를 단일 원자 pipeline update로 처리한다.
     *
     * <p>1) {@code $filter}로 {@code reactions.<emoji>}에서 userId를 제거한 뒤,
     * 2) {@code $objectToArray}+{@code $filter(size>0)}+{@code $arrayToObject}로 빈 emoji 키를 제거한다.
     * (기존 in-memory 로직이 "마지막 사용자 제거 시 emoji 키 삭제"였던 것과 동치)
     */
    @Override
    public Optional<Message> removeReaction(String messageId, String reaction, String userId) {
        String field = "reactions." + reaction;
        AggregationExpression pulled = ctx -> new Document("$filter", new Document()
                .append("input", new Document("$ifNull", List.of("$" + field, List.of())))
                .append("cond", new Document("$ne", List.of("$$this", userId))));
        AggregationExpression cleaned = ctx -> new Document("$arrayToObject", new Document("$filter",
                new Document()
                        .append("input", new Document("$objectToArray", "$reactions"))
                        .append("cond", new Document("$gt", List.of(new Document("$size", "$$this.v"), 0)))));
        AggregationUpdate update = AggregationUpdate.update()
                .set(field).toValueOf(pulled)
                .set("reactions").toValueOf(cleaned);
        return findAndModifyReactions(messageId, update);
    }

    private Optional<Message> findAndModifyReactions(String messageId, AggregationUpdate update) {
        Message updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(messageId)),
                update,
                FindAndModifyOptions.options().returnNew(true),
                Message.class);
        return Optional.ofNullable(updated);
    }

    @Override
    public Optional<Message> findById(String id) {
        return messageRepository.findById(id);
    }

    @Override
    public Optional<Message> findByFileId(String fileId) {
        return messageRepository.findByFileId(fileId);
    }

    @Override
    public long countRecentMessages(String roomId, LocalDateTime since) {
        return messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    @Override
    public Map<String, Long> countRecentMessages(Collection<String> roomIds, LocalDateTime since) {
        Map<String, Long> counts = new LinkedHashMap<>();
        roomIds.forEach(roomId -> counts.putIfAbsent(roomId, 0L));
        if (counts.isEmpty()) {
            return counts;
        }

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("room").in(counts.keySet())
                        .and("timestamp").gte(since)),
                Aggregation.group("room").count().as("count"));
        for (Document result : mongoTemplate
                .aggregate(aggregation, Message.class, Document.class).getMappedResults()) {
            Object roomId = result.get("_id");
            Object count = result.get("count");
            if (roomId != null && count instanceof Number number) {
                counts.put(roomId.toString(), number.longValue());
            }
        }
        return counts;
    }

    @Override
    public MessagePage findMessagesBefore(String roomId, LocalDateTime before, int limit) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());
        Page<Message> page = messageRepository.findByRoomIdAndTimestampBefore(roomId, before, pageable);
        return new MessagePage(page.getContent(), page.hasNext());
    }
}
