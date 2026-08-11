# P1-1 — 레이트리밋을 원자 연산으로 (find+save → findAndModify)

> 도메인: D5 (메시지·Socket.IO hot path)
> 대상: `RateLimitService.checkRateLimit` (메시지 전송마다 호출) → `RateLimitStore`
> 측정: MongoDB 드라이버 `CommandListener`로 wire 명령 실측 (Testcontainers `mongo:8.3.4`)

## 1. 문제

메시지 한 건을 보낼 때마다 `ChatMessageHandler`가 `rateLimitService.checkRateLimit(userId, 10000, 1분)`을 호출하고, 이 메서드는 **비원자 read-modify-write**로 레이트리밋 문서를 갱신했다(`RateLimitService:52-78`).

1. `rateLimitStore.findByClientId(clientId)` — find 1
2. (메모리에서 만료 판정 → count+1)
3. `rateLimitStore.save(rateLimit)` — insert/update 1

두 가지 비용이 있었다.

- **I/O 2배**: 요청당 find + save 두 번의 Mongo 왕복. 이 두 왕복은 Socket.IO worker(Netty event-loop) 스레드에서 **동기로** 일어나 event-loop을 점유한다(웹소켓 처리 hot path).
- **lost update**: find와 save 사이가 원자적이지 않다. 같은 사용자의 동시 메시지 N건이 같은 count를 읽고 각자 +1해서 저장하면 증가분이 유실되어, **실제보다 적게 집계 → 한도 초과 요청이 통과**할 수 있었다(over-admission). `@Transactional`이 붙어 있었지만 단일 노드 standalone Mongo에서는 실질적 원자성을 주지 못했다.

## 2. 개선

`RateLimitStore`에 **단일 원자 연산** `incrementAndGet(clientId, now, resetExpiresAt)`을 두고, Mongo 구현을 `findAndModify`(aggregation pipeline update + upsert + returnNew)로 바꿨다.

파이프라인 로직(문서 안에서 원자 실행):

- `expired = ($ifNull(expiresAt, epoch0)) <= now` — 문서가 없거나(신규 upsert) 만료됐으면 true
- `count = expired ? 1 : count + 1`
- `expiresAt = expired ? resetExpiresAt : expiresAt` (미만료면 윈도 연장 안 함)

`RateLimitService`는 반환된 최신 `count`로만 판정한다: **`count > maxRequests`이면 거부, 아니면 허용**. `@Transactional`은 제거했다(단일 원자 연산이라 트랜잭션 경계 불필요).

- unique(`clientId`) 인덱스에서 동시 최초 upsert가 충돌(E11000)하면 문서가 이미 생긴 것이므로 **1회 재시도**(증가 경로)로 흡수한다.

## 3. 측정 결과 (실측)

측정 테스트: `perf/RateLimitAtomicQueryCountIntegrationTest` — 실제 Mongo로 OLD(find+save)와 NEW(findAndModify)를 각각 수행해 wire 명령을 카운트.

| 구분 | Mongo 명령 | 총 왕복 |
|---|---|---:|
| **BEFORE** (findByClientId + save) | `find` 1 + `insert` 1 | **2** |
| **AFTER** (incrementAndGet) | `findAndModify` 1 | **1** |

측정 로그:
```
[rate-limit] BEFORE={find=1, insert=1} total=2
[rate-limit] AFTER ={findAndModify=1} total=1
```

**개선율: 2 → 1 (−50%), 메시지당 레이트리밋 I/O 2 → 1.** 이 왕복은 event-loop 스레드에서 동기로 일어나므로, 왕복 감소는 곧 event-loop 점유 시간 감소다.

## 4. 동작 동치성 검증

같은 테스트 스위트에서 관측 가능한 출력이 기존과 동일함을 단언한다.

| 관점 | 단언 |
|---|---|
| **명령 수** | BEFORE 2 → AFTER 1 |
| **최초 요청** | count=1, remaining=max-1, allowed |
| **만료 리셋** | 과거 만료 문서(count=5) → 원자 연산 내부에서 count=1로 리셋 |
| **미만료 증가** | count만 +1, expiresAt 유지(윈도 연장 없음) |
| **동시성(핵심)** | 같은 클라이언트 20건 동시 요청 → **정확히 maxRequests(10)개만 허용**, 나머지 거부, 저장 count=20(유실 없음) |
| **통합 회귀** | 기존 `RateLimitServiceTest` 4건(연속 감소/한도 차단/클라이언트 독립성) 그대로 통과 |
| **단위 회귀** | `RateLimitServiceUnitTest` 재작성 — 정규화(0/null window), fail-open, host-prefixed 키, reset 만료시각 전달 |

한도 도달 후에는 원자 증가 특성상 저장 `count`가 `maxRequests`를 넘을 수 있으나(예: 11,12,…), "`count > maxRequests`이면 거부"로 판정하므로 **정확히 maxRequests개만 허용**된다(기존과 관측 동일). 초과 구간 count 값은 클라이언트에 노출되지 않고 TTL 인덱스(`expiresAt`)로 정리된다.

## 5. 파일별 변경

- `service/ratelimit/RateLimitStore.java` — `findByClientId`/`save`를 제거하고 원자 `incrementAndGet(clientId, now, resetExpiresAt)` 하나로 대체. 왜 원자여야 하는지(lost update·I/O) 주석 명시.
- `service/ratelimit/RateLimitMongoStore.java` — `RateLimitRepository` 위임을 걷어내고 `MongoTemplate.findAndModify`(pipeline upsert + returnNew)로 구현. 만료/신규 리셋 조건을 `$cond`로 문서 내 원자 처리, E11000 시 1회 재시도.
- `service/RateLimitService.java` — `checkRateLimit`을 `incrementAndGet` 1회 호출 + `count > max` 판정으로 단순화. `@Transactional` 및 관련 import 제거. 동작 동치성(초과 구간 count)·판정 근거를 주석화.
- `test/.../service/RateLimitServiceUnitTest.java` — 원자 저장소 계약으로 재작성(8건). 서비스 레벨 행위(정규화·fail-open·host-prefix·reset 만료시각 전달)를 `incrementAndGet` mock으로 검증.
- `test/.../perf/RateLimitAtomicQueryCountIntegrationTest.java` (신규) — OLD 2 vs NEW 1 명령 수 실측 + 만료 리셋 + 미만료 증가 + **동시 20건 over-admission 없음**을 단언하는 회귀 가드.

## 6. 남은 여지 (문서화)

- 레이트리밋 저장소는 여전히 Mongo다. `message.store=redis` 부하에서 레이트리밋 write가 메시지당 남는 유일한 Mongo 왕복이 되므로, 극단 처리량에서는 **Redis `INCR`+`EXPIRE` 원자 카운터**로 옮기는 것이 다음 후보다(별도 항목).
- 세션 `lastActivity` write(메시지당 find+update)는 아직 남아 있다 — 웹소켓 개선 흐름의 다음 항목(세션 write throttle)에서 다룬다.
