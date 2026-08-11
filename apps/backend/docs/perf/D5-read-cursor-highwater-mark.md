# D5 — 읽음 처리 재설계 (per-message readers → read cursor / high-water mark)

> 도메인: D5 (메시지·Socket.IO hot path)
> 대상: 읽음 처리(`markMessagesAsRead`) 송·수신 경로 + 메시지 로드 시 자동 읽음
> 측정: MongoDB 드라이버 `CommandListener`로 wire 명령 실측 (Testcontainers `mongo:8.3.4`)
> 관련: 백엔드 `ReadCursorStore`(Mongo/Redis), `MessageReadHandler`; 프론트 `useReadReceiptBatch`·`roomEventHandlers`·`ReadStatus`

## 1. 문제

읽음 상태를 **메시지마다** `Message.readers[]`에 저장하던 구조가 **N² 팬아웃**을 유발했다.
방 N명에 메시지 1건이 올라오면 → N명이 각자 읽음 emit → 백엔드가 N번 방(N명)에 broadcast = **N² 이벤트**. 그 빈도가 다음과 곱해진다:

- **백엔드 조회 3회/읽음**: `MessageReadHandler`가 읽음 1건마다 `messageStore.findById`(roomId 역산) + `userRepository.findById` + `roomRepository.findById`(접근검증)를 했다.
- **메시지 개수만큼 쓰기**: `addReaderToMessages`가 읽은 메시지 수만큼 문서를 갱신(Redis 모드는 메시지 JSON 전체를 재직렬화). `MessageLoader`는 fetch·입장마다 로드된 30개에 대해 이 쓰기를 무조건 수행 → 조회성 요청이 write를 유발.
- **프론트 O(n) 재생성**: 수신측 `applyReadReceipts`가 읽음 이벤트마다 전체 메시지 배열을 `map`으로 재생성(+`ChatMessages` 재정렬). N² 이벤트 × O(전체 메시지).

## 2. 개선

읽음 상태를 메시지가 아니라 **(roomId, userId) 커서**(각 유저의 "여기까지 읽음" high-water mark)에 저장한다.
메시지 M이 유저 U에게 읽힘 ⇔ `cursor(U) >= M.timestamp`.

- 클라는 messageId 목록 대신 **"마지막으로 읽은 메시지의 id" 1개**만 전송(`{roomId, lastReadMessageId}`).
- 백엔드는 접근검증을 DB 없이 인메모리 `UserRooms.isInRoom`으로 하고, `findById`로 그 메시지의 **서버 timestamp**를 얻어(클라가 timestamp를 직접 정하지 못하게 해 미래 시각 위조 차단) 커서를 **단일 upsert(`$max`)**로 단조 전진. 실제 전진했을 때만 방에 `{userId, lastReadTs}` broadcast(상수 크기, supersedable).
- `MessageLoader`의 서버측 자동 읽음 쓰기 제거 → 읽음은 프론트 IntersectionObserver가 커서로 구동.
- 프론트 수신은 `applyReadReceipts`(O(n) 재생성) → `mergeReadCursor`(커서 맵 1칸, 역행/중복 시 동일 참조). "n명 안 읽음"은 `participants.filter(p => cursor[p] < msg.ts)`로 파생.
- `MessageResponse.readers` 제거 → 메시지 페이로드 축소(특히 히스토리 로드).

저장소는 `MessageStore`와 동일하게 `message.store`로 Mongo/Redis 선택:
```
Mongo:  read_cursors 컬렉션, {roomId,userId} 유니크 인덱스, findAndModify(upsert + $max) 1회
Redis:  readcursor:{roomId} 해시(field=userId → epoch millis), roomId 레인 직렬화로 CAS 안전
```

## 3. 측정 결과 (실측)

`ReadCursorQueryCountIntegrationTest` — 읽음 처리 1건의 Mongo wire 명령:

| 경로 | BEFORE (per-message readers) | AFTER (read cursor) |
|---|---|---|
| 읽음 처리 조회 | 3 (message + user + room findById) | **1** (`findById` — 서버 timestamp·검증용, `_id` 인덱스 O(1)) |
| 읽음 처리 쓰기 | 읽은 메시지 수 N (Redis는 JSON 전체 재직렬화) | **1** (`findAndModify` upsert + `$max`) |
| 메시지 로드 시 읽음 쓰기 | 로드 배치(30개) bulk update 1 | **0** (프론트 커서로 이관) |
| 수신측 프론트 비용 | 이벤트당 O(전체 메시지) 배열 재생성 + 재정렬 | **O(1)** 커서 맵 병합 |
| broadcast payload | `{userId, messageIds[]}` | `{cursors: {userId: lastReadTs}}` (상수) |
| broadcast 횟수 | 읽음 emit당 1회 (방 N명 동시 읽음 → N² 전송) | **방 단위 창당 1회** (A4 coalescing, N² → N) |

핵심: 읽음 1건의 상태 반영이 `3 + N`개 명령에서 **조회 1 + upsert 1**로, 프론트 수신은 O(n)에서 O(1)로 줄었다. N² 팬아웃의 각 이벤트 단가가 상수화되어 부하 구간에서 Mongo/Redis와 브라우저 메인스레드 부담이 동시에 감소한다. (`ReadCursorQueryCountIntegrationTest`는 커서 저장소 `advance`=`findAndModify` 1회를 측정; 핸들러의 `findById`는 서버 timestamp 확보와 방 검증을 겸한다.)

## 4. 안전성 / 트레이드오프

- **위조 방지**: 클라가 timestamp를 직접 보내지 않고 messageId만 보낸다. 서버가 `findById`로 서버 timestamp를 얻고 `message.roomId == roomId`를 검증하므로, 임의의 미래 timestamp로 커서를 밀어 이후 메시지까지 읽음 처리하는 공격이 불가능하다.
- **동일 millisecond 충돌(수용)**: 커서 단위가 timestamp(ms)라, 같은 ms의 두 메시지 중 하나만 봐도 둘 다 읽음으로 계산된다. 읽음 영수증 UX에선 무시 가능한 수준으로 판단해 수용한다. 정확한 전체 순서가 필요하면 `(ts, messageId)` 복합 커서로 확장(프론트 정렬·비교 동반).
- **비연속 읽음 표현 불가**("3번은 읽고 5번은 안 읽음"). 채팅 read 의미론에선 불필요.
- **메시지별 정확한 readAt 손실**. 현 UI는 "모두 읽음/n명 안 읽음" 카운트만 쓰므로 커서에서 파생 가능.
- **기존 `readers[]` 미이관**(새로 시작). 기존 메시지는 재조회/스크롤 시 커서로 다시 읽음 처리. `Message.readers` 필드·`MessageReader`는 제거했고, Redis 구버전 JSON 호환을 위해 `MessageCodec`에 `FAIL_ON_UNKNOWN_PROPERTIES=false`를 켰다.
- `addReaderToMessages`/`updateReadersForMessages`/`MessageReadStatusService`는 제거.

## 5. A4 — 읽음 broadcast coalescing (구현됨)

`ReadReceiptCoalescer`가 방 단위 창(`socketio.read.coalesce-window-ms`, 기본 100ms) 동안의 커서 갱신을 모아 `{cursors: {userId → lastReadTs}}` 1건으로 방출한다. 방 N명이 같은 메시지를 읽어도 broadcast가 창당 1회로 줄어 **N² → N**. 더 큰 lastReadTs가 이전 값을 대체하므로 손실 없이 묶인다. `window ≤ 0`이면 즉시 broadcast(비활성/테스트). 같은 방 `enqueue`는 dispatcher roomId 레인으로 직렬화되고, 버퍼는 `ConcurrentHashMap.compute`/`computeIfPresent`의 키 단위 원자성으로 enqueue-merge와 flush-take가 끼어들지 않는다. 프론트는 `mergeReadCursorMap`으로 배치 payload를 병합.

## 6. 남은 항목

- **Redis 다중노드 원자화**: 현재 `advance`는 단일노드 방 디스패처 직렬화에 의존(HGET→비교→HSET). 다중노드로 가면 Lua 스크립트로 compare-and-set을 원자화해야 한다. (Mongo `$max`는 노드 수와 무관하게 원자적.)
- **단일노드 인메모리 `UserRooms`/coalescer 버퍼**: scale-out 시 접근검증 캐시·coalescing 버퍼도 노드 로컬(커서·메시지는 이미 Redis 지원).
