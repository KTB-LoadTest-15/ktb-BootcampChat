# P0 — Redis 전환 전 Mongo 메시지 footprint baseline

> 단계: [REDIS_MESSAGE_STORE_PLAN.md](../REDIS_MESSAGE_STORE_PLAN.md) P0
> 목적: Redis hot store 전환(P2/P4)이 hot path에서 제거할 Mongo 명령의 "before" 기준선.
> 측정: `perf/MongoMessageBaselineQueryCountIntegrationTest` (드라이버 CommandListener, Testcontainers `mongo:8.3.4`)

## 1. 메시지 컬렉션 operation별 Mongo 명령 (실측)

| Operation | 리포지토리 호출 | Mongo 명령 | 총 |
|---|---|---|---:|
| **send** (메시지 전송) | `save(new)` | `insert` 1 | 1 |
| **history** (30개 로드) | `findByRoomIdAndTimestampBefore` (Page) | `find` 1 + `aggregate` 1 | 2 |
| **recentCount** (최근 30분 수) | `countRecentMessagesByRoomId` | `aggregate` 1 | 1 |
| **findById** (단건) | `findById` | `find` 1 | 1 |
| **reaction** | `findById` + `save(existing)` | `find` 1 + `update` 1 | 2 |
| **readStatus** (30개) | `updateReadersForMessages` | `update` 1 | 1 |

> 실측 로그:
> ```
> [baseline] send={insert=1} total=1
> [baseline] history={aggregate=1, find=1} total=2
> [baseline] recentCount={aggregate=1} total=1
> [baseline] findById={find=1} total=1
> [baseline] reaction={find=1, update=1} total=2
> ```
> (readStatus=update 1은 [P0-4 문서](P0-4-read-status-bulk-update.md)에서 실측 확정)

## 2. 측정에서 알게 된 것 (추정 정정)

- **Page의 count와 `@Query(count=true)`는 `count` 명령이 아니라 `aggregate`(countDocuments → `$count`)로 나간다.** 추정이었다면 `count`로 잘못 적었을 지점 — 실측으로 정정.
- send는 `@Version`이 없고 id가 null인 신규 엔티티라 `update`(upsert)가 아니라 `insert`로 나간다.

## 3. Redis 전환이 제거할 hot path Mongo 명령

Option B(Redis hot / Mongo async 아카이브)에서 라이브 hot path의 위 명령은 **전부 Redis로 이동**하고 Mongo로는 배치 flusher만 비동기로 나간다. 따라서 P4 목표치는:

| 경로 | Baseline (Mongo/요청) | Redis 전환 후 목표 (hot path Mongo/요청) |
|---|---:|---:|
| send | insert 1 | **0** (Redis append, flush는 배치·비동기) |
| history 30 | find 1 + aggregate 1 | **0** (Redis ZSET 범위조회) |
| recentCount | aggregate 1 | **0** (Redis score 범위 count) |
| reaction | find 1 + update 1 | **0** (Redis map 갱신) |
| readStatus 30 | update 1 | **0** (Redis map 갱신) |

## 4. DU 목표 (미확정 → 부하로 고정 예정)

명령 footprint는 확정됐으나 **DU 상한 수치**(동시접속/TPS, p99 SLO)는 아직 미측정. `loadtest/`·`e2e/artillery/` 인프라로 부하를 단계적으로 올려 현행 한계점을 찾고 목표로 고정한다. (플랜 §9 열린 질문)

## 5. 다음 단계

P1 — `MessageStore` 이음새 도입(현행 Mongo 구현을 인터페이스 뒤로, 동작·명령 수 무변경). 이 baseline 테스트가 P1 리팩터 후에도 동일 수치를 유지하는지 회귀 가드로 재사용된다.
