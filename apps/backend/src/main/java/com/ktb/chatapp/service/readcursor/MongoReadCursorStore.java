package com.ktb.chatapp.service.readcursor;

import com.ktb.chatapp.model.ReadCursor;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * {@link ReadCursorStore}의 MongoDB 구현.
 *
 * <p>전진은 단일 {@code findAndModify}(upsert + {@code $max})로 처리한다. 왕복 1회로
 * (1) 커서를 단조 전진시키고 (2) 이전 값을 돌려받아 실제 전진 여부를 판정한다.
 */
@Component
@ConditionalOnProperty(name = "message.store", havingValue = "mongo", matchIfMissing = true)
@RequiredArgsConstructor
public class MongoReadCursorStore implements ReadCursorStore {

    private final MongoTemplate mongoTemplate;

    @Override
    public boolean advance(String roomId, String userId, long lastReadTs) {
        Query query = Query.query(Criteria.where("roomId").is(roomId).and("userId").is(userId));
        Update update = new Update()
                .max("lastReadTs", lastReadTs)
                .set("updatedAt", LocalDateTime.now());

        // returnNew(false): upsert 전 pre-image를 받아 실제 전진 여부를 판정한다(신규면 null).
        ReadCursor previous = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().upsert(true).returnNew(false),
                ReadCursor.class);

        return previous == null || previous.getLastReadTs() < lastReadTs;
    }

    @Override
    public Map<String, Long> findByRoom(String roomId) {
        List<ReadCursor> cursors = mongoTemplate.find(
                Query.query(Criteria.where("roomId").is(roomId)), ReadCursor.class);
        Map<String, Long> result = new HashMap<>(cursors.size());
        for (ReadCursor cursor : cursors) {
            result.put(cursor.getUserId(), cursor.getLastReadTs());
        }
        return result;
    }
}
