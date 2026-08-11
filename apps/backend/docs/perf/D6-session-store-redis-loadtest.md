# D6 — 세션 저장소 Redis 전환 실부하 A/B 검증

## 방법

코드 변경이 **플래그 하나 뒤 순수 가산**이라 baseline과 HEAD는 **동일 JAR**이고 `SESSION_STORE`
env만 다르다.

- baseline = `SESSION_STORE=mongo` (현행), HEAD = `SESSION_STORE=redis`.
- `MESSAGE_STORE=mongo` 고정(세션 변수만 격리). 단일 JAR을 5001/5002에서 직접 실행.
- 하네스: scratchpad `sess_ab_run.sh` — 부팅→health→카운터 리셋→tier 부하→델타 캡처→종료.
- **핵심 지표**: MongoDB `top` 명령으로 **`sessions` 컬렉션**만 격리해 op 델타 측정
  (`db.adminCommand({top:1}).totals["bootcamp-chat.sessions"]` 전/후). 전역 opcounters와 달리
  메시지 등 다른 컬렉션 부하가 섞이지 않는 정밀 신호.
- 보조 지표: `redis-cli INFO commandstats`(세션 op가 Redis로 이동했는지), load-test.js 출력.
- 시나리오: load-test.js = N명 접속(핸드셰이크당 validateSession) + 방 조인 + 메시지 전송
  (메시지당 validateSession). 세션 검증 hot path를 그대로 태운다.

## 결과 — `sessions` 컬렉션 Mongo op 델타

| Tier | 모드 | queries(find) | insert | remove | update |
|---|---|---|---|---|---|
| **light** (50u, 1000 msg) | mongo | **1051** | 51 | 51 | 0 |
| | redis | **0** | 0 | 0 | 0 |
| **medium** (200u, 4000 msg) | mongo | **4201** | 201 | 201 | 0 |
| | redis | **0** | 0 | 0 | 0 |

**세션 hot path Mongo 명령 = 100% 제거** (light 1153→0, medium 4603→0).

- `queries`(=validateSession find) = 핸드셰이크 + 메시지당 세션 검증. light 1051 ≈ 50접속 + 1000메시지+α.
- `insert`/`remove` = 로그인당 `createSession`(save + removeAllUserSessions).

## 결과 — Redis commandstats (op가 이동했음을 확인 = 동작 동치성)

| Tier | GET (validate) | SET (login save) | DEL (login clear) |
|---|---|---|---|
| light | **1051** (4.46µs/call) | 51 | 51 |
| medium | **4201** (6.76µs/call) | 201 | 201 |

Redis **GET 횟수가 제거된 Mongo `queries` 횟수와 정확히 일치**(1051, 4201)한다 → 검증 횟수는
그대로이고 저장소만 Mongo find → O(1) Redis GET(~4.5–6.8µs)으로 바뀌었다. insert↔SET,
remove↔DEL도 1:1 대응. 동작 동치성이 실부하에서도 성립.

## 연결/지연 지표

| Tier | 모드 | Avg 연결시간 | Avg msg 지연 | P99 | Auth/Conn 오류 |
|---|---|---|---|---|---|
| light | mongo / redis | 57.96 / 47.82ms | 0.06 / 0.06ms | 1 / 1ms | 0 / 0 |
| medium | mongo / redis | 65.09 / 81.51ms | 0.05 / 0.06ms | 1 / 1ms | 0 / 0 |

연결시간·지연은 런 간 노이즈 수준(light/medium은 미포화, 단일 머신). 이 전환의 이득은 이 티어의
연결시간이 아니라 **제거된 DB 부하**에 있다 — 읽음 커서 전환(D5-read-cursor-loadtest)과 동일한
성격으로, 고부하·경합·scale-out에서 값이 드러난다. 세션 find가 Netty event-loop(핸드셰이크)와
Tomcat(REST 인증)에서 사라지는 것이 본질.

## A/B 무해 확인 (기존 이슈)

양쪽 arm 모두 `MessageLoader` "Error loading initial messages"가 동일 규모로 발생
(medium: mongo 90 / redis 80). 이는 **기존 NPE**(시스템 메시지 `senderId=null`을 immutable map
`.get(null)`, MessageLoader:66 — 별도 task로 플래그됨)로 세션 변경과 무관하며, 두 arm에 동일하게
존재해 A/B에 무해하다. load-test.js의 "Message Errors"(수만 건)는 이 조인 시 fetch 실패의
클라이언트측 집계로, 역시 양쪽 동일. Auth/Connection 오류는 양쪽 0.

## 결론

세션 검증 hot path의 MongoDB read/write를 실부하에서 **100% 제거**하고 동일 횟수의 O(1) Redis
op으로 옮겼다. 검증 횟수·결과 동일(동작 동치성), 신규 오류 없음. 만료는 Redis 네이티브 TTL로 위임.
