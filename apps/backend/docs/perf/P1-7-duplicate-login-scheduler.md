# P1-7 — 중복 로그인 유예 종료를 raw Thread → 공유 스케줄러로

> 도메인: D5 (Socket.IO 연결 처리)
> 대상: `ConnectionLoginHandler.notifyDuplicateLogin` (매 접속 시 중복 로그인 감지 경로)
> 성격: 쿼리 감소가 아니라 **스레드 생명주기 개선**(스레드 폭발 방지). 스레드 오프로드로 검증(정성적).

## 1. 문제

같은 계정이 새로 접속하면 기존 세션에 10초 유예를 준 뒤 `session_ended`를 보낸다. 이 지연을 **접속마다 raw Thread를 만들어** 처리했다(`ConnectionLoginHandler`, 개선 전):

```java
new Thread(() -> {
    Thread.sleep(Duration.ofSeconds(10));
    existingClient.sendEvent(SESSION_ENDED, ...);
}).start();
```

문제:

- **스레드 폭발**: 중복 로그인이 몰리면(부하테스트에서 같은 계정 대량 재접속·플래핑) 접속마다 새 스레드가 생기고 10초씩 살아 있어, live thread 수와 네이티브 메모리(스레드당 스택)가 급증한다. event-loop와 무관한 축에서 자원을 소모한다.
- **경계 없음**: 생성 상한·큐·거부 정책이 없어 폭주를 흡수하지 못한다.

## 2. 개선

유예 종료를 **단일 공유 `ScheduledExecutorService`**(데몬 스레드 `dup-login-grace`)에 예약한다.

```java
duplicateLoginScheduler.schedule(() -> {
    existingClient.sendEvent(SESSION_ENDED, ...);
}, DUPLICATE_LOGIN_GRACE_SECONDS, TimeUnit.SECONDS);
```

- 스케줄러 빈은 `SocketIOConfig`에 정의(`@Bean(destroyMethod="shutdownNow")`, `@ConditionalOnProperty(socketio.enabled)`)하여 핸들러와 동일 조건·수명으로 관리한다.
- 예약 작업은 유예 후 `session_ended` 1건을 보내는 가벼운 작업이라 **단일 데몬 스레드로 충분**하다. N건이 몰려도 스레드는 1개, 작업은 큐에 쌓여 시각 순서대로 실행된다 → 접속 수와 무관하게 스레드 수가 상수.
- 앱 종료 시 `shutdownNow`로 정리(데몬이라 JVM 종료를 막지도 않음).

## 3. 검증 (동작 동치성 + 스레드 생명주기)

측정/검증 테스트: `websocket/socketio/handler/ConnectionLoginHandlerTest` — 공유 스케줄러를 mock으로 주입하고 예약 작업을 즉시 실행하도록 스텁해 결정적으로 검증(10초 대기 없음).

| 관점 | 단언 |
|---|---|
| **동작 동치성** | 중복 로그인 시 기존 클라이언트에 `DUPLICATE_LOGIN` 즉시 통지 + (스케줄러 경유) `SESSION_ENDED` 통지 |
| **스레드 생명주기** | 유예 종료가 `new Thread`가 아니라 주입된 공유 `ScheduledExecutorService.schedule(...)`로 예약됨 |
| **회귀** | 기존 `onConnect`(재입장/저장/joinRooms), `onDisconnect`(정리) 동작 불변 |

접속마다 스레드를 만들던 것을 단일 스케줄러로 옮겼으므로, 부하 시 live thread 수가 접속 수에 비례해 늘지 않는다(상수).

## 4. 파일별 변경

- `config/SocketIOConfig.java` — `duplicateLoginScheduler` 빈 추가(단일 데몬 스레드 `ScheduledExecutorService`, `destroyMethod=shutdownNow`, socketio 조건부).
- `websocket/socketio/handler/ConnectionLoginHandler.java` — 생성자에 스케줄러 주입. `notifyDuplicateLogin`의 `new Thread(){sleep;send}`를 `scheduler.schedule(send, 10s)`로 대체. 유예 상수 `DUPLICATE_LOGIN_GRACE_SECONDS` 도입. `java.time.Duration`(sleep용) import 제거.
- `test/.../handler/ConnectionLoginHandlerTest.java` — 생성자에 mock 스케줄러 추가, 중복 로그인 예약(즉시 실행 스텁)으로 `DUPLICATE_LOGIN`+`SESSION_ENDED` 통지와 스케줄러 사용을 단언하는 테스트 추가.

## 5. 남은 여지 (문서화)

- 멀티 노드에서는 기존 세션이 다른 노드에 있으면 이 로컬 통지가 닿지 않는다(코드 내 기존 TODO). `getRoomOperations("user:"+userId)` 기반으로 바꾸면 노드 무관 통지가 되지만, 단일 노드 범위에서는 현행 유지.
- 유예 10초는 상수다. 필요 시 property로 노출 가능.
