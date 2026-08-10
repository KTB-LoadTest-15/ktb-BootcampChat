# P1-3 — 리액션 atomic 처리 (read-modify-save → 원자 연산)

> 도메인: D5 (메시지·Socket.IO hot path)
> 대상: `MessageReactionHandler.handleMessageReaction` (이모지 리액션 추가/제거)
> 측정: MongoDB 드라이버 `CommandListener`로 wire 명령 실측 + 동시성 정합성 테스트 (Testcontainers `mongo:8.3.4`)

## 1. 문제

리액션 처리가 `findById` → JVM에서 `reactions` 맵 수정(`addReaction`/`removeReaction`) → `messageStore.update`(문서 전체 재저장)의 read-modify-save였다.

- 왕복 2회(find + update)
- **문서를 통째로 다시 쓰므로 동시 리액션 시 lost update**: 두 사용자가 같은 메시지에 (다른 이모지라도) 거의 동시에 리액션하면, 나중에 저장하는 쪽이 먼저 저장된 리액션을 덮어써 유실될 수 있다. (D5 완료조건 "동시 리액션 정합성"의 미달분)

## 2. 개선

`MessageStore`에 원자 연산 2개를 추가하고(`addReaction`/`removeReaction`, 갱신된 메시지를 반환), 핸들러는 read-modify-save 대신 이를 호출하도록 바꿨다.

**Mongo 모드 — 완전 atomic (단일 pipeline update + findAndModify(returnNew)):**

```
add:    reactions.<emoji> = $setUnion( $ifNull(reactions.<emoji>, []), [userId] )   // = $addToSet, 멱등
remove: 1) reactions.<emoji> = $filter( ..., $$this != userId )                      // userId 제거
        2) reactions = $arrayToObject( $filter($objectToArray(reactions), size(v) > 0) ) // 빈 emoji 키 제거
```

- 해당 필드만 갱신하므로 문서 전체 덮어쓰기가 없어 **동시 리액션에도 lost update 없음**.
- remove의 2단계 정리는 "마지막 사용자 제거 시 emoji 키 삭제"라는 기존 in-memory 동작과 **정확히 동치**(빈 배열이 남지 않음). 두 stage가 단일 pipeline이라 원자적.

**Redis 모드 — best-effort (프로젝트 방침: throughput 우선, 정합성 완화 수용):**

메시지가 Hash field에 JSON 통짜로 저장돼 부분 원자 갱신이 어렵고(Lua 전체 재직렬화는 빈 배열의 cjson 손상 위험), 리액션은 메시지 대비 저빈도라 영향이 작다. 따라서 Redis 구현은 도메인 메서드로 in-memory 갱신 후 다시 쓰는 read-modify-write를 유지하며, 이 완화는 코드/문서에 명시했다. **강한 정합성이 필요한 영속 경로(Mongo 모드)는 완전 atomic.**

## 3. 측정 결과 (실측, Mongo 모드)

측정 테스트: `perf/ReactionAtomicQueryCountIntegrationTest`.

| 구분 | Mongo 명령 | 총 왕복 |
|---|---|---:|
| **BEFORE** (findById + save) | `find` 1 + `update` 1 | **2** |
| **AFTER** (원자 pipeline update) | `findAndModify` 1 | **1** |

**개선율: 2 → 1 (−50%), 그리고 동시성 lost update 제거(정성적 정합성 개선).**

## 4. 동작 동치성 · 정합성 검증

같은 테스트 클래스에서:

- **명령 수**: BEFORE 2 → AFTER 1, 그리고 add 결과가 두 경로 동일(👍→user-A).
- **동시성(핵심)**: 서로 다른 20명이 같은 메시지에 동시에 리액션 → **20명 전부 보존**. read-modify-save였다면 상당수 유실됐을 시나리오로 원자성을 증명.
- **remove 동치**: 마지막 사용자 제거 시 emoji 키가 사라짐(빈 배열 잔존 없음).
- **멱등**: 같은 유저의 중복 add가 중복되지 않음.
- 핸들러 단위 테스트(`MessageReactionHandlerTest`): add 브로드캐스트, 메시지 없음/미지원 타입 에러 경로 검증.

## 5. 파일별 변경

- `service/message/MessageStore.java` — 원자 연산 `addReaction`/`removeReaction`(갱신 메시지 반환) 추가.
- `service/message/MongoMessageStore.java` — MongoTemplate 주입, 위 두 연산을 단일 pipeline update + `findAndModify(returnNew)`로 구현.
- `service/message/RedisMessageStore.java` — best-effort read-modify-write로 구현(정합성 완화 주석 명시).
- `websocket/socketio/handler/MessageReactionHandler.java` — read-modify-save 제거, 저장소 원자 연산 호출로 전환. 타입 검증 → 원자 연산 → 반환 메시지로 broadcast.
- `test/.../perf/ReactionAtomicQueryCountIntegrationTest.java` (신규) — 명령 수 실측 + 동시성/동치/멱등 회귀 가드.
- `test/.../handler/MessageReactionHandlerTest.java` — 새 API에 맞춰 갱신, 에러 경로 케이스 추가.
- `test/.../handler/MessageLoaderIntegrationTest.java` — `MongoMessageStore` 생성자에 MongoTemplate 인자 반영.
