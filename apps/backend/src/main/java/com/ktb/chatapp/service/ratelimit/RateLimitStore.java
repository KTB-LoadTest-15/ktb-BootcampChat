package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.time.Instant;

/**
 * Data store interface for rate limit storage.
 *
 * <p>요청 카운트 증가는 단일 원자 연산({@link #incrementAndGet})으로만 노출한다.
 * find + save 두 왕복의 read-modify-write는 동시 요청에서 lost update가 생기고 I/O도 2배라,
 * 저장소 특화 원자 연산(Mongo {@code findAndModify} upsert 등)으로 대체한다.
 */
public interface RateLimitStore {

    /**
     * clientId의 요청 수를 <b>단일 원자 연산</b>으로 1 증가시키고 갱신된 문서를 반환한다.
     *
     * <p>문서가 없거나 만료({@code expiresAt <= now})했으면 {@code count=1},
     * {@code expiresAt=resetExpiresAt}로 새 윈도를 시작한다. 그렇지 않으면 {@code count}만 1 증가하고
     * {@code expiresAt}은 유지한다. upsert + returnNew로 find+save(2 왕복)를 1 왕복으로 합치며,
     * 동시 요청에도 lost update가 없다.
     *
     * @param clientId       host-prefixed 저장소 키
     * @param now            현재 시각(만료 판정 기준)
     * @param resetExpiresAt 리셋 시 새로 설정할 만료 시각(= now + window)
     * @return 증가/리셋이 반영된 최신 문서
     */
    RateLimit incrementAndGet(String clientId, Instant now, Instant resetExpiresAt);
}
