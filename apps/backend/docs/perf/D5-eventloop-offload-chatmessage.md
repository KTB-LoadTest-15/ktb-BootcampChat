# D5 — 메시지 처리를 Netty 이벤트 루프에서 오프로드 (chatMessage)

> 도메인: D5 (메시지·Socket.IO hot path) — 웹소켓 처리의 **핵심 구조 개선**
> 대상: `ChatMessageHandler.handleChatMessage` (@OnEvent chatMessage)
> 성격: 쿼리 감소가 아니라 **event-loop 점유 제거**(구조적). 스레드 오프로드 + 순서 보장 + 포화 거부로 검증.

## 1. 문제 (라이브러리 바이트코드로 확인)

netty-socketio 2.0.13은 `@OnEvent` 핸들러를 **Netty worker(event-loop) 스레드에서 동기로** 호출한다. 근거:

- `InPacketHandler.channelRead0`(이벤트 루프) → `PacketListener.onPacket` → `Namespace.onEvent`가 전부 동기 `invokevirtual`이고 중간에 `Executor.submit/execute`가 없다.
- 파이프라인 핸들러가 모두 2-인자 `addLast(name, handler)`로 등록된다(3-인자 `addLast(EventExecutorGroup, ...)` 아님) → 별도 비즈니스 executor 없음.
- `workerThreads` 미설정 → 기본 `2 × CPU코어`.

따라서 `handleChatMessage`의 모든 블로킹 작업(세션 검증, 레이트리밋, user/room 조회, 금칙어, 메시지 저장, 브로드캐스트 직렬화)이 **event-loop 스레드에서 직렬 실행**됐다. 소수의 event-loop 스레드가 DB 대기에 묶이면 **같은 스레드가 처리하는 다른 연결의 프레임 read/write와 ping/pong heartbeat까지 지연**된다 → 클라이언트가 연결 끊김으로 오인 → 재연결 폭주. 부하테스트의 DU/TPS 한계가 여기서 결정된다.

(P1-1 레이트리밋 원자화·P0-2 세션 write throttle로 이 경로의 *블로킹 작업 수*는 줄였지만, 남은 작업이 event-loop에서 도는 구조 자체는 그대로였다.)

## 2. 개선

블로킹 처리 본문을 전용 워커로 오프로드하는 `SocketDispatcher`를 도입하고, `chatMessage` 핸들러를 이를 경유하도록 바꿨다. event-loop은 프레임 I/O 전용으로 남는다.

`KeyedSocketDispatcher` (기본 구현):

- **N개 레인**(각 단일 스레드 + 바운드 큐), `orderingKey` 해시로 레인 선택. 레인 수 기본 `2×코어`(`socketio.worker.lanes`), 큐 용량 기본 1000(`socketio.worker.queue-capacity`).
- **순서 보장**: 같은 key(= chatMessage의 `roomId`)는 항상 같은 레인 → 단일 스레드에서 제출 순서대로 실행 → **방 단위 메시지 순서(FIFO) 보장**. 서로 다른 방은 서로 다른 레인에서 병렬.
- **포화 처리**: `CallerRunsPolicy`(event-loop 인라인 실행)를 쓰지 않는다 — 인라인은 (1) event-loop을 다시 블로킹하고 (2) 큐에 밀린 앞 작업과 순서가 뒤집힌다. 대신 `AbortPolicy`로 거부하고, 호출측 `onReject`가 클라이언트에 `SERVER_BUSY` 에러를 보낸다 → **순서 보장 + 명시적 백프레셔**.
- `socketio.worker.queued` 게이지로 전체 레인 적체를 관측.

`chatMessage` 진입부는 이제 event-loop에서 즉시 반환한다:
```java
@OnEvent(CHAT_MESSAGE)
public void handleChatMessage(SocketIOClient client, ChatMessageRequest data) {
    String key = (data != null && data.getRoom() != null) ? data.getRoom() : client.getSessionId().toString();
    socketDispatcher.dispatch(key, () -> processChatMessage(client, data), () -> onDispatchRejected(client));
}
```
`client.sendEvent` / `getRoomOperations().sendEvent`는 워커 스레드에서 호출해도 안전하다(Netty가 실제 채널 write를 event-loop에 스케줄).

## 3. 검증 (오프로드 + 순서 + 거부)

`websocket/socketio/KeyedSocketDispatcherTest`:

| 관점 | 단언 |
|---|---|
| **오프로드** | 작업이 호출 스레드가 아니라 `socket-worker-*` 스레드에서 실행 |
| **순서 보장** | 같은 key로 200건 제출 → 정확히 제출 순서(FIFO)로 실행 |
| **포화 거부** | 레인 1·큐 1에서 워커 점유+큐 가득 시 다음 제출은 `task` 대신 `onReject` 호출(인라인 실행 안 함) |

`websocket/socketio/handler/ChatMessageHandlerTest`:

- **오프로드 위임**: `handleChatMessage`가 `roomId`를 순서 key로 디스패처에 위임하고, 처리 본문은 즉시 실행되지 않음(다운스트림 무상호작용).
- **동작 동치성**: 기존 테스트(금칙어 차단, 발신자 echo+방 broadcast)는 동기 디스패처 주입으로 그대로 통과 → 처리 본문 로직 불변.

## 4. 파일별 변경

- `websocket/socketio/SocketDispatcher.java` (신규) — 오프로드 계약 인터페이스(orderingKey FIFO + onReject).
- `websocket/socketio/KeyedSocketDispatcher.java` (신규) — N 레인(단일스레드+바운드큐) 구현, 해시 라우팅, AbortPolicy+onReject, queued 게이지, `@PreDestroy` 정리. socketio 조건부 빈.
- `websocket/socketio/handler/ChatMessageHandler.java` — `SocketDispatcher` 주입. `handleChatMessage`를 진입부(디스패치)와 `processChatMessage`(기존 본문)로 분리. 포화 시 `SERVER_BUSY` 통지.
- `test/.../handler/ChatMessageHandlerTest.java` — 동기 디스패처 주입(기존 검증 유지) + roomId key 위임 테스트.
- `test/.../socketio/KeyedSocketDispatcherTest.java` (신규) — 오프로드/순서/거부 검증.

## 5. 남은 여지 (후속 — 웹소켓 개선 흐름 내에서 완성)

이번 커밋은 **가장 뜨거운 `chatMessage` 경로만** 오프로드했다. 인프라(`SocketDispatcher`)가 생겼으므로 나머지는 기계적이다:

- **다른 @OnEvent 핸들러 오프로드**: `joinRoom`/`leaveRoom`/`fetchPreviousMessages`/`markMessagesAsRead`/`messageReaction`도 같은 디스패처로. key = roomId(가능하면), 없으면 sessionId. 각 핸들러 테스트에 동기 디스패처 주입 필요.
- **연결 핸드셰이크 오프로드**: `AuthTokenListenerImpl`의 `onConnect`(재접속 시 방 N개 재입장)는 handshake 스레드에서 도므로 별도 검토(auth 결과는 동기 반환 필요).
- **부하 실측**: 레인 수/큐 용량 튜닝, event-loop lag·`socketio.worker.queued`·p99를 부하에서 측정해 값 고정.
