# 웹소켓(Socket.IO) 채팅 처리 개선 — 팀 공유

> 브랜치 `feat/websocket-performance` / 대상 hot path: `netty-socketio` 기반 채팅 처리
> 전체 백엔드 테스트 **259 passed**, 기존 API·소켓 이벤트 계약 유지

## 0. 한눈에 요약

| # | 개선 | 무엇을 바꿨나 | 효과 |
|---|---|---|---|
| 1 | **이벤트 루프 오프로드**(핵심) | `@OnEvent` 블로킹 처리를 전용 워커로 분리 | 고동접 시 연결/인증 붕괴 방지 |
| 2 | 레이트리밋 원자화 | find+save(2 I/O) → findAndModify(1) | −50% I/O + 동시성 버그 제거 |
| 3 | 세션 write throttle | 매 메시지 세션 write → 창 단위 1회 | 메시지당 DB write 제거 |
| 4 | 중복 로그인 스케줄러 | 접속마다 raw Thread → 공유 스케줄러 | 스레드 폭발 방지 |
| 5 | 소켓 TCP_NODELAY | Nagle off | 소형 프레임 전송 지연 제거 |

**실부하(1000 동접) 요약: 평균 연결 시간 16,812ms → 129ms(−99.2%), 인증 실패 173건 → 0건, 접속 성공 828 → 1000명(전원), 메시지 처리량 +39%.**

---

## 1. 왜 이게 병목이었나 (핵심 배경)

`netty-socketio`는 `@OnEvent` 핸들러(메시지 전송·방 입장 등)를 **Netty 이벤트 루프 스레드에서 동기로 직접 호출**합니다. 별도 비즈니스 스레드풀이 없습니다.

> 라이브러리 바이트코드로 확인: `InPacketHandler.channelRead0`(이벤트 루프) → `PacketListener.onPacket` → `Namespace.onEvent`가 전부 동기 호출이고, 파이프라인에 별도 `EventExecutorGroup`이 없음. worker 스레드 수 기본값 = `2 × CPU코어`.

즉 메시지 1건을 처리하는 동안 **세션 검증 → 레이트리밋 → 유저/방 조회 → 저장 → 브로드캐스트**가 전부 이벤트 루프 스레드에서 블로킹됩니다. 소수의 이벤트 루프 스레드가 DB 대기에 묶이면 **같은 스레드가 처리하는 다른 연결의 프레임 read/write와 신규 핸드셰이크·인증까지 밀립니다.** 이것이 고동접에서 연결 자체가 붕괴하는 원인이었습니다.

아래 개선은 (1) 이 블로킹 처리를 이벤트 루프에서 떼어내고, (2) 이벤트 루프에 남던 개별 DB write를 줄이고, (3) 연결 처리/전송을 정리하는 순서로 구성됩니다.

---

## 2. 개선 항목별 Before / After

### 개선 1 — 이벤트 루프 오프로드 (핵심)

**문제:** 아래처럼 `@OnEvent` 핸들러 본문(블로킹 DB 작업 포함)이 이벤트 루프에서 그대로 실행됩니다.

```java
// BEFORE — ChatMessageHandler
@OnEvent(CHAT_MESSAGE)
public void handleChatMessage(SocketIOClient client, ChatMessageRequest data) {
    // ↓ 아래 전부가 Netty 이벤트 루프 스레드에서 동기 실행됨
    sessionService.validateSession(...);      // Mongo find + save
    rateLimitService.checkRateLimit(...);     // Mongo find + save
    userRepository.findById(...);             // Mongo find
    roomRepository.findById(...);             // Mongo find
    messageStore.add(message);                // Mongo insert
    socketIOServer.getRoomOperations(roomId).sendEvent(MESSAGE, res); // 직렬화 + fan-out
}
```

**개선:** 처리 본문을 **전용 워커 레인**으로 오프로드하는 `SocketDispatcher`를 도입하고, 진입부는 이벤트 루프에서 즉시 반환합니다.

```java
// AFTER — ChatMessageHandler (진입부는 즉시 반환, 본문은 워커에서)
@OnEvent(CHAT_MESSAGE)
public void handleChatMessage(SocketIOClient client, ChatMessageRequest data) {
    String orderingKey = (data != null && data.getRoom() != null)
            ? data.getRoom() : client.getSessionId().toString();
    socketDispatcher.dispatch(
            orderingKey,                                   // 방(roomId) 단위 순서 보장 key
            () -> processChatMessage(client, data),        // 블로킹 본문 → 워커에서 실행
            () -> onDispatchRejected(client));             // 포화 시 SERVER_BUSY 통지
}
```

```java
// AFTER — KeyedSocketDispatcher 핵심 (N개 단일스레드 레인 + 바운드 큐)
public void dispatch(String orderingKey, Runnable task, Runnable onReject) {
    ThreadPoolExecutor lane = lanes[Math.floorMod(orderingKey.hashCode(), lanes.length)];
    try {
        lane.execute(task);          // 같은 key → 같은 레인 → 제출 순서(FIFO) 보장
    } catch (RejectedExecutionException e) {
        onReject.run();              // 큐 포화 시 인라인 실행 안 함(순서 보장) → 백프레셔
    }
}
```

**나아진 점**
- **이벤트 루프가 프레임 I/O·핸드셰이크 전용으로 남음** → 고동접에서도 연결/인증이 밀리지 않음(아래 부하 표의 핵심).
- **방(roomId) 단위 FIFO 보장** — 같은 key는 같은 레인의 단일 스레드에서 제출 순서대로 처리되어 메시지 순서가 안전.
- **포화 시 순서 보존 + 백프레셔** — 큐가 차면 인라인 실행(순서 뒤집힘·이벤트 루프 재점유) 대신 `SERVER_BUSY`로 명시적으로 거부.
- 같은 패턴을 `chatMessage`뿐 아니라 `joinRoom`/`leaveRoom`/`fetchPreviousMessages`/`markMessagesAsRead`/`messageReaction`에도 적용. `joinRoom`/`leaveRoom`은 연결/해제 흐름에서도 호출되어 **핸드셰이크의 방 N개 재입장 처리도 자동 오프로드**됨.

---

### 개선 2 — 레이트리밋 원자화

**문제:** 메시지마다 레이트리밋을 `find → 메모리 수정 → save`(비원자 read-modify-write)로 처리 → I/O 2배 + 동시 메시지 시 lost update(한도 초과 통과).

```java
// BEFORE — RateLimitService
RateLimit rl = rateLimitStore.findByClientId(id).orElse(null);   // Mongo find
if (rl != null && 만료) { rl.setCount(0); rl.setExpiresAt(...); }
int current = rl != null ? rl.getCount() : 0;
if (current >= max) return rejected(...);                        // 저장 안 함
rl.setCount(current + 1);
rateLimitStore.save(rl);                                          // Mongo save
```

**개선:** 저장소에 단일 원자 연산 `incrementAndGet`을 두고 `findAndModify`(pipeline upsert)로 처리.

```java
// AFTER — RateLimitService
RateLimit rl = rateLimitStore.incrementAndGet(id, now, resetExpiresAt); // 원자 1회
if (rl.getCount() > max) return rejected(...);
return allowed(max, max - rl.getCount(), ...);
```
```java
// AFTER — RateLimitMongoStore: 문서 안에서 조건부 증가/리셋을 원자 실행
// count = 만료? 1 : count+1,  expiresAt = 만료? 새창 : 유지
mongoTemplate.findAndModify(query(clientId), pipelineUpdate,
        options().upsert(true).returnNew(true), RateLimit.class);
```

**나아진 점**
- Mongo 명령 **2 → 1 (−50%)**, 이벤트 루프에서 도는 왕복 절반.
- **동시성 lost update 제거** — 같은 유저 20건 동시 요청에도 정확히 한도(max)개만 허용(실측 검증). 기존엔 초과 통과 가능.
- 불필요한 `@Transactional` 제거.

---

### 개선 3 — 세션 활동시각 write throttle

**문제:** `validateSession`이 검증 성공마다 `lastActivity`/`expiresAt`을 **매번 저장**. 메시지·REST 인증마다 호출되어 세션 컬렉션이 과도하게 hot.

```java
// BEFORE — SessionService.validateSession (매 호출 write)
session.setLastActivity(now);
session.setExpiresAt(now + TTL);
session = sessionStore.save(session);     // 매 메시지마다 Mongo update
```

**개선:** 최근 갱신이 창(기본 60s) 이내면 write 생략.

```java
// AFTER — 창 단위로만 write
if (now - session.getLastActivity() >= sessionTouchThrottleMs) {   // 기본 60s
    session.setLastActivity(now);
    session.setExpiresAt(now + TTL);
    session = sessionStore.save(session);
}
```

**나아진 점**
- 세션 **write(update) N → 창당 1회**. (창 이내 5건 연속 검증에서 update 5 → 0 실측)
- 창(60s)이 TTL(30m)보다 훨씬 작아 **유휴 만료 정밀도 영향은 무시 수준.** `session.touch.throttle-ms=0`이면 기존 동작(끄기 가능).

---

### 개선 4 — 중복 로그인 유예 종료: raw Thread → 공유 스케줄러

**문제:** 같은 계정 재접속 시 기존 세션에 10초 유예를 주는데, **접속마다 스레드를 새로 만들어** 10초씩 sleep. 로그인 폭주 시 스레드 폭발/네이티브 메모리 고갈.

```java
// BEFORE — ConnectionLoginHandler
new Thread(() -> {
    Thread.sleep(Duration.ofSeconds(10));      // 접속마다 스레드 1개가 10초 점유
    existingClient.sendEvent(SESSION_ENDED, ...);
}).start();
```

**개선:** 단일 공유 `ScheduledExecutorService`에 예약.

```java
// AFTER — 공유 스케줄러에 유예 종료 예약(스레드 재사용)
duplicateLoginScheduler.schedule(
    () -> existingClient.sendEvent(SESSION_ENDED, ...),
    DUPLICATE_LOGIN_GRACE_SECONDS, TimeUnit.SECONDS);
```

**나아진 점**
- N건이 몰려도 스레드는 1개(데몬), 작업은 큐로 시각 순 실행 → **스레드 수가 접속 수와 무관하게 상수.**

---

### 개선 5 — 소켓 TCP_NODELAY

**문제:** `tcpNoDelay=false`(Nagle on)라 소형·빈번한 채팅 프레임을 모아 보내며 지연 유발.

```java
// BEFORE → AFTER (SocketIOConfig)
socketConfig.setTcpNoDelay(false);   // →  socketConfig.setTcpNoDelay(true);
```

**나아진 점:** 소형 프레임을 즉시 전송해 메시지 왕복 체감 지연 감소(서버 전용 변경, 클라이언트 계약 불변).

---

## 3. 부하 테스트 결과 (light / medium / heavy)

**방법:** 개선 전 커밋과 개선 후 커밋을 **각각 빌드**해 동일 환경(로컬 Mongo, `MESSAGE_STORE=mongo`)에서 `loadtest/load-test.js`로 같은 시나리오를 걸어 비교. 지표는 부하 클라이언트가 측정한 값.

### light — 50 users

| 지표 | Baseline | HEAD |
|---|---:|---:|
| 접속 성공 | 50 | 50 |
| 평균 연결 시간 | 71 ms | 77 ms |
| P99 메시지 지연 | 1 ms | 1 ms |
| Total 에러 | 0 | 0 |

### medium — 200 users

| 지표 | Baseline | HEAD |
|---|---:|---:|
| 접속 성공 | 200 | 200 |
| 평균 연결 시간 | 164 ms | 85 ms |
| P99 메시지 지연 | 1 ms | 5 ms |
| Total 에러 | 0 | 0 |

### heavy — 1000 users (핵심)

| 지표 | Baseline | HEAD | 변화 |
|---|---:|---:|---|
| **접속 성공 유저** | **828 / 1000** | **1000 / 1000** | 전원 접속 |
| **평균 연결 시간** | **16,812 ms** | **129 ms** | **−99.2%** |
| **Auth 에러** | **173** | **0** | 제거 |
| Connection 에러 | 288 | 200 | −31% |
| **메시지 처리량(전송)** | **11,540** | **16,000** | **+39%** |
| P99 메시지 지연(성공분) | 2 ms | 2 ms | 동일 |

### 결과 해석

- **light·medium(미포화):** 개선 전에도 이미 빠름(연결 수십~백ms, 에러 0). 차이는 노이즈 수준이고, 오프로드는 워커 핸드오프 비용만큼 medium p99를 아주 약간(1→5ms, 둘 다 무시 수준) 올림. **즉 저·중부하에서는 "개선 없음이 아니라, 원래 문제가 안 되는 구간"이며 회귀도 없음.**
- **heavy(포화):** 여기서 갈림. Baseline은 이벤트 루프가 블로킹 DB에 점유되어 **신규 핸드셰이크·인증이 평균 16.8초까지 밀리고 172명이 아예 접속 실패**. HEAD는 오프로드로 이벤트 루프가 연결에 자유로워 **129ms에 전원 접속 + 처리량 +39%**.
- 즉 **개선의 진짜 가치는 "고동접에서 연결 자체가 살아남는가"**에 있고, 그건 개별 메시지 지연(양쪽 2ms)이 아니라 **연결 성공률·연결 시간·인증 에러**에서 드러납니다.

---

## 4. 한계와 후속 과제 (정직하게)

- **부하 측정 한계:** 단일 노트북(타 컨테이너 동시 구동)·각 1회 측정이라 **방향성 근거**이며 프로덕션 급 절대값은 아님. 단 동일 부하 클라이언트로 두 빌드를 재므로 **before/after delta는 유의미**.
- **워커 레인/큐 튜닝(우선 후속):** heavy 극단 버스트에서 워커 큐(기본 1000/lane)가 차 초과 메시지를 `SERVER_BUSY`로 셰딩함(백프레셔는 의도대로 동작). `socketio.worker.lanes`/`socketio.worker.queue-capacity`를 부하 기반으로 튜닝 예정.
- **관측:** `socketio.worker.queued` 게이지 추가. event-loop lag 전용 메트릭은 후속.
- **범위 밖(프론트 협업 필요):** participants 목록 delta 전송, AI chunk 증분 전송은 프론트 이벤트 계약 변경이 필요해 제외.

---

## 부록 — 세부 측정 근거

각 개선의 명령 수 before/after·동작 동치성 실측은 `docs/perf/` 참고:
`P1-1-rate-limit-atomic.md`, `P0-2-session-write-throttle.md`, `P1-7-duplicate-login-scheduler.md`,
`D5-eventloop-offload-chatmessage.md`, `socket-tcp-nodelay.md`, `D5-eventloop-offload-loadtest.md`.
