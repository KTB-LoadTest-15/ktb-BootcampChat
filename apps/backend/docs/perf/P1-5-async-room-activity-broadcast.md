# P1-5 — 방 활성도 브로드캐스트 event-loop 오프로드 (동기 → @Async)

> 도메인: D5 (메시지·Socket.IO hot path)
> 대상: `RoomActivityNotifier.notifyMessageStored` (메시지 전송마다 호출) → `SocketIOEventListener`의 room-list 브로드캐스트
> 성격: 쿼리 횟수 감소가 아니라 **event-loop 점유 제거**(정성적 개선). 오프로드는 스레드로 검증.

## 1. 문제

`ChatMessageHandler.handleChatMessage`는 메시지 저장 후 매번 `roomActivityNotifier.notifyMessageStored(roomId)`를 **동기로** 호출했고, 이 메서드는 Socket.IO worker(Netty) 스레드 위에서:

1. `recentMessageCounter.countRecentMessages(roomId)` — 저장소 집계 쿼리(Mongo `aggregate`/Redis `ZCOUNT`)
2. `publishEvent(RoomActivityEvent)` → 동기 `@EventListener` → `getRoomOperations("room-list").sendEvent(...)` — room-list를 보는 **모든 클라이언트로 fan-out**

를 수행했다. 즉 **메시지 한 건의 전송 스레드가 집계 쿼리 + 대규모 브로드캐스트를 끝낼 때까지 붙잡혔다**. Redis 전환으로 메시지 저장의 Mongo 왕복은 사라졌지만, 이 event-loop 점유는 Redis와 무관한 축이라 그대로 남아 있었다(P1-5).

## 2. 개선

`notifyMessageStored`를 전용 풀(`socketBroadcastExecutor`)에서 도는 `@Async`로 바꿔, 집계 쿼리와 room-list 브로드캐스트를 **메시지 전송 hot path에서 분리**했다.

- 활성도 이벤트는 각각이 독립적인 "최근 메시지 수" 스냅샷이라 **순서에 의존하지 않는다** → 비동기화해도 안전(늦게 처리돼도 다음 메시지가 최신 count로 덮음).
- 전용 executor: `core 2 / max 4 / queue 1000 / CallerRunsPolicy`. 포화 시 조용히 유실하지 않고 호출 스레드에서 실행해 백프레셔를 준다.
- **AI 스트리밍 이벤트(start→chunk→complete)는 순서 보장이 필요해 동기 유지**. 이들은 별도 스트리밍 스레드에서 발행되어 Socket.IO worker를 점유하지 않으므로 오프로드 대상이 아니다.

## 3. 검증 (스레드 오프로드 + 동작 동치성)

측정/검증 테스트: `service/RoomActivityAsyncIntegrationTest` — `@Async`가 Spring 프록시로 실제 적용되는 통합 컨텍스트.

| 관점 | 단언 |
|---|---|
| **오프로드** | `notifyMessageStored` 호출 후 이벤트 처리 스레드가 **호출 스레드가 아니고** `room-activity-*`(전용 풀)로 시작 |
| **동작 동치성** | 이벤트가 여전히 올바른 `roomId`로 발행됨 |

기존 단위 테스트(`RoomActivityNotifierTest`)는 `new RoomActivityNotifier(...)` 직접 생성이라 프록시가 없어 동기 실행되며, 발행 로직(멱등/ null 처리/예외 삼킴)이 불변임을 그대로 회귀 가드로 유지한다.

## 4. 파일별 변경

- `config/AsyncConfig.java` (신규) — `@EnableAsync` + 바운드 `socketBroadcastExecutor`(CallerRunsPolicy) 빈.
- `service/RoomActivityNotifier.java` — `notifyMessageStored`에 `@Async("socketBroadcastExecutor")` 부여. 왜 비동기가 안전한지(순서 무관) 주석 명시.
- `test/.../service/RoomActivityAsyncIntegrationTest.java` (신규) — 오프로드(전용 풀 스레드) + 이벤트 동치성 검증.

## 5. 남은 여지 (문서화)

- 다른 room-list 브로드캐스트(`RoomCreated`/`RoomUpdate`)는 REST 스레드에서 발행되어 Socket.IO event-loop를 점유하지 않으므로 이번 범위에서 제외했다.
- 대규모 room-list fan-out 자체의 비용(클라이언트 수 비례)은 별도 과제(예: 활성도 debounce/coalescing)로 남는다.
