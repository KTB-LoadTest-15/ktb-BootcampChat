# P0-4 — 읽음 처리 N+1 제거 (read-modify-save 루프 → atomic bulk update)

> 도메인: D5 (메시지·Socket.IO hot path)
> 대상: 메시지 로드 시 읽음 상태 처리 (`fetchPreviousMessages`, 방 입장 초기 로드 경로)
> 측정: MongoDB 드라이버 `CommandListener`로 wire 명령 실측 (Testcontainers `mongo:8.3.4`)

## 1. 문제

`MessageReadStatusService.updateReadStatus`가 메시지마다 `findById` → JVM에서 readers 수정 → `save`(전체 문서 재저장)를 반복했다. 이미 `MessageLoader`가 메시지 객체를 손에 들고 있는데도 **같은 문서를 다시 읽어** 하나씩 저장하는 구조.

- 왕복이 메시지 개수에 선형 비례 (배치 30개 → 60 왕복)
- 조회(스크롤)성 요청이 write를 유발 → read amplification
- 동시 읽음 시 read-modify-save의 lost update 위험 (P1-3 읽음 부분)

## 2. 개선

아직 이 사용자가 읽지 않은 문서에만 reader를 추가하는 **단일 atomic bulk update**로 대체.

```
필터:  { _id: { $in: messageIds }, "readers.userId": { $ne: userId } }
갱신:  [ { $set: { readers: { $concatArrays: [ { $ifNull: ["$readers", []] },
                                              [ { userId, readAt } ] ] } } } ]
```

- `@Query`+`@Update` 조합은 **매칭되는 모든 문서에 적용** → 메시지 개수와 무관하게 `update` 1회
- 필터 `readers.userId != userId` → 멱등(재호출 no-op) + 서버측 조건 평가로 **lost update 없음**
- `$addToSet` 대신 필터+append: `MessageReader{userId, readAt}`는 readAt 때문에 subdocument dedup 불가
- `$push` 대신 pipeline+`$ifNull`: 저장된 `readers`가 null일 수 있어 (코드 곳곳의 null 가드가 증거) 단순 push는 실패

## 3. 측정 결과 (실측)

측정 테스트: `perf/ReadStatusQueryCountIntegrationTest` — 30개 메시지를 한 사용자가 읽음 처리하는 동일 시나리오.

| 구분 | Mongo 명령 | 총 왕복 |
|---|---|---:|
| **BEFORE** (findById+save 루프) | `find` 30 + `update` 30 | **60** |
| **AFTER** (atomic bulk update) | `update` 1 | **1** |

**개선율: 60 → 1 (−98.3%), 메시지 개수에 대한 선형 증가 O(N) → O(1) 제거.**

> 실측 로그:
> ```
> [read-status] BEFORE={find=30, update=30} total=60
> [read-status] AFTER ={update=1} total=1
> ```

**동작 동치성 (같은 측정 테스트에서 함께 검증):** OLD·NEW 모두 각 메시지에 `user-A`가 정확히 한 번 reader로 기록됨(`containsExactly("user-A")`). 즉 쿼리 60→1 감소에도 **관측 가능한 결과는 동일**하다.

부수 효과:
- **정합성**: 20명 동시 읽음 → 20명 전부 보존 (`MessageReadStatusServiceIntegrationTest#concurrentReads_noLostUpdate`). 옛 RMW였다면 일부 유실.
- **read amplification 감소**: 이미 읽은 메시지는 필터에서 제외되어 실제 write 0에 수렴.

## 4. 검증

| 테스트 | 성격 | 결과 |
|---|---|---|
| `MessageReadStatusServiceTest` | 단위 (단일 호출 위임·가드·예외) | 4/4 ✅ |
| `MessageReadStatusServiceIntegrationTest` | 통합 (null 안전·멱등·누적·동시성) | 5/5 ✅ |
| `perf/ReadStatusQueryCountIntegrationTest` | 명령 횟수 실측·회귀 가드 | 1/1 ✅ |

## 5. 변경 파일

- `repository/MessageRepository.java` — `updateReadersForMessages(...)` 추가 (`@Query`+`@Update(pipeline)`)
- `service/MessageReadStatusService.java` — RMW 루프 제거 → 단일 호출 위임
- 측정 하네스: `perf/CommandCountingListener`, `perf/MongoCommandCounterConfig`

## 6. 계약 유지

REST/Socket 계약, 읽음 멱등성 유지. `updateReadStatus(List<String>, String)` 시그니처 무변경 → 호출부(`MessageLoader`, `MessageReadHandler`) 영향 없음.

## 근거 (공식문서)

- [Spring Data MongoDB — Modifying methods (`@Update`, 매칭 전체 적용, 반환=수정 문서 수, pipeline)](https://github.com/spring-projects/spring-data-mongodb/blob/main/src/main/antora/modules/ROOT/pages/mongodb/repositories/modifying-methods.adoc)
- [MongoDB — Update operators / aggregation pipeline update](https://www.mongodb.com/docs/manual/reference/operator/update/)
