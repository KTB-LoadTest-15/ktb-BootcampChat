package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationExpression;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of RateLimitStore.
 *
 * <p>요청 카운트 증가를 단일 {@code findAndModify}(pipeline update + upsert + returnNew)로 처리한다.
 * 기존 find+save(2 왕복, 비원자 read-modify-write)를 1 왕복 원자 연산으로 대체해
 * 동시 요청의 lost update를 제거한다. (docs/perf/P1-1-rate-limit-atomic.md)
 */
@Component
@RequiredArgsConstructor
public class RateLimitMongoStore implements RateLimitStore {

    private final MongoTemplate mongoTemplate;

    @Override
    public RateLimit incrementAndGet(String clientId, Instant now, Instant resetExpiresAt) {
        // 만료 또는 신규(문서 없음): expiresAt이 없거나 now 이하이면 true
        Document expiredCond = new Document("$lte", List.of(
                new Document("$ifNull", List.of("$expiresAt", new Date(0L))),
                Date.from(now)));

        // 만료/신규면 count=1, 아니면 count+1 (기존 값은 $ifNull로 0 방어)
        AggregationExpression countExpr = ctx -> new Document("$cond", List.of(
                expiredCond,
                1,
                new Document("$add", List.of(new Document("$ifNull", List.of("$count", 0)), 1))));

        // 만료/신규면 새 만료시각으로 리셋, 아니면 기존 만료시각 유지
        AggregationExpression expiresExpr = ctx -> new Document("$cond", List.of(
                expiredCond,
                Date.from(resetExpiresAt),
                "$expiresAt"));

        AggregationUpdate update = AggregationUpdate.update()
                .set("clientId").toValue(clientId)
                .set("count").toValueOf(countExpr)
                .set("expiresAt").toValueOf(expiresExpr);

        try {
            return findAndIncrement(clientId, update);
        } catch (DuplicateKeyException e) {
            // 동시 최초 요청이 unique(clientId) 인덱스에서 충돌하면 문서가 이미 생겼으므로 1회 재시도(증가 경로).
            return findAndIncrement(clientId, update);
        }
    }

    private RateLimit findAndIncrement(String clientId, AggregationUpdate update) {
        return mongoTemplate.findAndModify(
                Query.query(Criteria.where("clientId").is(clientId)),
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                RateLimit.class);
    }
}
