# D6 — 세션 저장소 Mongo → Redis 전환 (핫패스 read 오프로드)

## 문제

세션 검증(`SessionService.validateSession`)은 본질적으로 `userId → 세션` 키-값 조회인데,
이를 **MongoDB find**로 수행한다. 그리고 이 검증은 세 hot path에서 매번 호출된다:

| 호출처 | 빈도 | 실행 스레드 |
|---|---|---|
| `SessionAwareJwtAuthenticationConverter` | 인증된 REST 요청마다 | Tomcat worker |
| `AuthTokenListenerImpl` | WS 핸드셰이크마다 | Netty event-loop |
| `ChatMessageHandler` | 채팅 메시지마다 | dispatch 레인 |

P0-2 write throttle(60s)은 **write(update)** 를 줄였지만 **read(find)는 매 호출마다 그대로**
나간다. 인덱스가 있어도 find는 네트워크 왕복 + 쿼리 플래닝 + BSON 역직렬화이며,
특히 핸드셰이크 경로는 Netty event-loop 위에서 돈다. heavy 티어(1000명 램프업)에서 연결
establish가 병목인데(D5-eventloop-offload-loadtest), 핸드셰이크당 세션 find가 여기에 직접 얹힌다.

부수 문제:
- **로그인마다 Mongo write 2회**: `createSession` = `deleteByUserId` + `save`(insert).
- **만료 이중 관리**: Mongo TTL 인덱스(60s 주기 백그라운드 스캔, 부정확) + 앱의
  `now - lastActivity > 30m` 체크 + touch마다 `expiresAt` write.

## 개선

기존 `SessionStore` 이음새(인터페이스 + `SessionMongoStore`)를 그대로 활용해 `SessionRedisStore`를
추가하고 플래그로 택일한다(메시지·읽음커서와 동일 패턴).

- **플래그**: `session.store=mongo|redis` (기본 `mongo`). `message.store`와 독립.
  `SessionMongoStore`에 `@ConditionalOnProperty(havingValue="mongo", matchIfMissing=true)`,
  `SessionRedisStore`에 `havingValue="redis"`.
- **저장 구조**: 키 `session:{userId}` → Session JSON, `SET key json EX <ttl>`.
  유저당 1세션(현 시맨틱 = createSession이 기존 세션 제거와 일치).
  - `findByUserId` = `GET` (만료 시 Redis가 이미 제거 → null)
  - `save` = `SETEX`. TTL은 `session.expiresAt`에서 파생(단일 진실원), 만료를 **Redis 네이티브
    per-key TTL**에 위임.
  - `delete/deleteAll` = `DEL`.
- **동작 동치성 보존**: `validateSession`의 앱 레벨 `lastActivity` 체크와 `SESSION_EXPIRED`
  에러 코드는 그대로 유지한다(Session 값에 lastActivity가 실려 있으므로 판정 가능).
  Redis TTL은 방치 세션 자동 청소의 안전망으로 둔다.

### 변경/신규 파일

| 파일 | 무엇을 / 어떻게 |
|---|---|
| `service/session/SessionRedisStore.java` (신규) | `SessionStore`의 Redis 구현. `StringRedisTemplate`의 `ValueOperations`로 `SETEX`/`GET`/`DEL`. 자체 `ObjectMapper`(JavaTimeModule, `FAIL_ON_UNKNOWN_PROPERTIES=false`)로 `Instant expiresAt` 직렬화. TTL은 expiresAt에서 파생. |
| `service/session/SessionMongoStore.java` | `@ConditionalOnProperty(session.store=mongo, matchIfMissing=true)` 추가 — 기본 유지, redis 선택 시 비활성. |
| `resources/application.properties` | `session.store=${SESSION_STORE:mongo}` 플래그 추가. |

## 측정

### 마이크로 (결정론적, 회귀 가드)

`SessionStoreRedisBypassIntegrationTest` (신규) — `session.store=redis`로 부팅,
`CommandCountingListener`로 Mongo wire 명령 실측.

- **시나리오**: create(로그인) + validate×5(throttle 창 이내) + touch-write 1회(창 초과 재검증).
- **Baseline (mongo 모드)** — `SessionTouchThrottleQueryCountIntegrationTest`가 증명:
  검증마다 `find` 1회(창 이내 update 0). 즉 hot path validate당 **Mongo find 1회**.
- **After (redis 모드)** — 실측 결과:

```
[session-redis] mongo_commands={} total=0
```

| 지표 | Baseline (mongo) | After (redis) |
|---|---|---|
| 세션 hot path Mongo 명령 (create+validate×5+touch) | find 6+ / write 2+ | **0** |
| validate 1회당 Mongo | find 1 | **0** (Redis GET 1) |
| 만료 | Mongo TTL 인덱스 + 앱 체크 | **Redis 네이티브 TTL** (덤프에서 `ttl-s=1798`) |

**동작 동치성**: 검증 유효 유지, sessionId 불일치 `INVALID_SESSION` 거부, 삭제 후 무효,
TTL(0, SESSION_TTL_SEC] 범위 설정 — 모두 단언.

Redis 원시 저장(덤프):
```
[key]   session:{userId}
[value] {"userId":...,"sessionId":...,"createdAt":...,"lastActivity":...,"metadata":{...},"expiresAt":"2026-...Z"}
[ttl-s] 1798
```

전체 스위트 **261 tests green** (기본 mongo 경로 무회귀).

### 매크로 (실부하 A/B)

동일 JAR을 `SESSION_STORE=mongo`(baseline) vs `redis`(HEAD)로 실행, MongoDB `top` 명령으로
`sessions` 컬렉션만 격리 측정. 상세 `D6-session-store-redis-loadtest.md`.

| Tier | `sessions` Mongo op (mongo→redis) | Redis로 이동 (GET/SET/DEL) |
|---|---|---|
| light (50u, 1000msg) | find 1051 / ins 51 / rem 51 → **0/0/0** | 1051 / 51 / 51 |
| medium (200u, 4000msg) | find 4201 / ins 201 / rem 201 → **0/0/0** | 4201 / 201 / 201 |

세션 hot path Mongo 명령 **100% 제거**. Redis GET 횟수 = 제거된 Mongo find 횟수(1:1) →
검증 횟수 불변, 저장소만 O(1) Redis GET(~4.5–6.8µs)으로 이동(동작 동치성). Auth/Conn 오류 0,
신규 오류 없음.
