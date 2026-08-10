# 백엔드 성능·정합성 감사 보고서

> 기준일: 2026-08-10  
> 범위: `apps/backend` REST API, Socket.IO, MongoDB, 로컬 파일 저장소, 모니터링 설정  
> 원칙: 기존 API URL·요청/응답 계약을 유지하고, 측정 후 한 번에 하나의 병목만 개선한다.

## 1. 결론

현재 30초 LIGHT 테스트는 **성공률 100%(271/271), 평균 405ms, p90 1,290ms, p99 1,290ms**다. 오류는 없지만 평균의 3.2배인 tail latency와 초당 처리량이 0∼15 RPS 사이를 크게 오가는 현상이 보인다. 10 RPS·3 VU·30초 결과만으로 임계점을 판정할 수는 없지만, 이 수치는 최적화 전 관측 환경을 보강하고 다음 후보를 순서대로 검증해야 한다는 근거가 된다.

1. HTTP 서블릿 스레드/accept queue가 각각 10으로 작다.
2. 모든 인증 REST 요청이 세션 MongoDB read 1회 + full-document write 1회를 선행한다.
3. 방 목록은 무제한 `findAll()` 후 방·참가자별 조회와 메시지 count를 반복한다.
4. 메시지 30개 로드는 최대 30 read + 30 write의 읽음 처리와 sender N+1을 동기로 수행한다.
5. 메시지 전송 한 번이 MongoDB 작업 여러 개와 1만 개 금칙어 선형 탐색을 직렬로 수행한다.

즉, **P0은 캐시·Redis 도입이 아니라 스레드 관측, 인증 세션 write amplification, N+1/인덱스, 읽음 처리 bulk update**다. API URL을 바꿀 이유는 없다.

## 2. 근거 수준

- **확정**: 코드/설정으로 작업 횟수·동시성 위험을 확인했다.
- **강한 후보**: 현재 부하 결과와 방향은 일치하지만 trace/profiler로 인과를 확정해야 한다.
- **운영 위험**: 현 단일 노드 테스트에서는 바로 보이지 않아도 확장/장애 시 문제가 된다.

## 3. 부하테스트 결과 해석과 한계

### 3.1 관측된 신호

- 271건이 모두 성공했으므로 현 부하에서 기능적 파괴는 없다.
- 평균 405ms에 비해 p90/p99가 1,290ms로 높아 일부 요청이 queueing, DB/I/O, JVM pause 중 하나의 영향을 크게 받았을 가능성이 있다.
- 지연 스파이크 구간에서 RPS가 거의 0으로 떨어지는 그래프는 worker/DB 대기 후 응답이 몰리는 패턴과 합치할 수 있다. 단, 초당 aggregation/보간 방식을 모르므로 원인으로 단정하지 않는다.

### 3.2 현 리포트로 알 수 없는 것

- endpoint별 요청 수/p50/p95/p99/오류율
- 실제 전송 RPS가 고정 10인지, VU가 응답을 기다리는 closed model인지
- 300건 대신 271건이 된 이유(30초 경계에서 미완료/취소된 요청 포함 여부)
- client-side connection/DNS 시간과 server processing 시간의 구분
- Tomcat active/busy/queued, Mongo pool wait, query command latency, CPU/GC, Socket.IO event loop 지연
- p90과 p99가 모두 1,290ms인 것이 rounding/bucket 상한/원시 표본 특성 중 무엇인지

다음 테스트부터는 endpoint/tag별 `http.server.requests`, `tomcat.threads.*`, `mongodb.driver.pool.*`, Mongo command timer, process CPU, JVM heap/GC pause를 동일 timeline으로 저장한다. 현 스크린샷은 baseline 참고용이지 개선 효과 증명용으로는 부족하다.

## 4. 상세 문제와 개선안

### P0-1. HTTP 스레드와 대기열이 지나치게 작음

- **문제**: `server.tomcat.threads.max=10`, `accept-count=10`, `max-connections=50`이다 (`application.properties:4-7`). 최대 10개의 동기 REST 요청만 처리하며 짧은 DB 정체에도 대기열이 빠르게 찬다.
- **원인**: blocking Spring MVC + blocking MongoDB/I/O 구조에 비해 worker/queue 크기가 작다.
- **확인 방법**: `tomcat.threads.busy/max/current`, `http.server.requests` p95/p99, Mongo pool wait를 1초 단위로 비교한다. busy/max=1이 지연 스파이크와 겹치면 확정한다.
- **개선 방법**: CPU core, DB pool, 요청당 blocking 시간을 측정한 후 10→20→40 순으로 한 변수만 변경한다. queue를 무제한 키우지 말고 timeout/거절율도 같이 본다.
- **기대 효과**: 짧은 DB 지연 시 queueing 완화. DB가 실제 병목이면 worker만 늘려도 효과가 없으므로 반드시 계층별 측정이 필요하다.

### P0-2. 모든 인증 REST 요청의 세션 read + write

- **문제**: JWT converter가 모든 인증 요청에서 `validateSession()`을 호출한다 (`SessionAwareJwtAuthenticationConverter:44-56`). 이 메서드는 세션을 조회한 뒤 `lastActivity`/`expiresAt`을 바꾸고 전체 문서를 저장한다 (`SessionService:77-100`).
- **원인**: sliding expiration을 매 요청의 동기 full-document write로 구현했다.
- **확인 방법**: endpoint 1회를 호출하고 Mongo command `find`/`update` 수를 비교한다. session collection ops/s와 REST RPS가 거의 2:1인지, write latency/pool wait가 p99와 겹치는지 확인한다.
- **개선 방법**: API 계약은 그대로 두고 (1) activity 갱신을 예: 30∼60초에 한 번만 수행, (2) `_id/sessionId` 조건의 atomic `$set`/`$max` update, (3) 보안 요구사항이 허용하면 단기 로컬 유효성 메모를 단계적으로 검토한다. 먼저 (1)만 적용하고 재측정한다.
- **기대 효과**: 인증 요청당 Mongo write를 대폭 제거하고 session hot-document 경합을 줄인다.

### P0-3. 방 목록의 무제한 조회 + 복합 N+1

- **문제**: `findAll()`로 모든 방을 JVM에 로드/정렬한다 (`RoomService:34-43`). 각 방마다 creator 1회, 참가자 P회, 최근 메시지 count 1회를 수행한다 (`RoomService:181-195`). 대략 `1 + R×(2+P)` DB 작업이다.
- **원인**: pagination/projection/bulk user loading이 없고 DTO mapping 내부에 repository 호출이 있다.
- **확인 방법**: 방 1/10/100개, 방당 참가자 1/10/100명 fixture로 동일 GET의 Mongo command count·payload·p95를 측정한다.
- **개선 방법**: URL은 유지한다. 첫 단계로 방 페이지를 DB에서 `createdAt desc` 정렬/제한하고, 현 응답 metadata를 활용한다. 해당 페이지의 모든 user ID를 모아 `findAllById` 1회로 로드한다. 최근 count는 aggregation 1회로 방별 group하거나, 먼저 인덱스 효과를 측정한다.
- **기대 효과**: DB round trip이 방/참가자 수에 선형 증가하는 구조를 제거하고 response 크기와 JVM allocation을 제한한다.

### P0-4. 메시지 로드/읽음 처리의 N+1·write amplification

- **문제**: 기본 30개 메시지를 조회한 후, 읽음 처리가 메시지별 `findById` + `save`를 수행한다 (`MessageLoader:59-75`, `MessageReadStatusService:38-53`). 응답 mapping은 sender를 메시지별로 조회한다. `Page` 반환은 hasNext를 위한 count query도 수행할 수 있다.
- **원인**: 배치 요청을 개별 document read-modify-save로 구현했고, sender를 bulk loading하지 않았다.
- **확인 방법**: 메시지 30개 로드 1회의 Mongo command count를 profiler/command listener로 재다. 현 코드상 최대 60개 읽음 read/write + sender reads + page query가 가능하다.
- **개선 방법**: sender ID를 모아 bulk user query 1회로 변환한다. 읽음 처리는 Mongo `updateMulti` + `$addToSet` 조건(`readers.userId != userId`)의 atomic bulk update 1회로 바꾸고, offset count가 필요 없다면 `Slice`/기준 개수+1 조회로 hasMore를 구한다.
- **기대 효과**: 방 입장/이전 메시지 로드의 DB round trip을 수십 회에서 수 회로 축소한다.

### P0-5. 메시지 전송 hot path가 동기 DB 작업을 과도하게 수행

- **문제**: Socket 메시지 1건이 세션 find+save, rate-limit find+save, user find, room find, message save, recent-message count, session find+save를 직렬 수행한다 (`ChatMessageHandler:80-176`). session activity는 전체 흐름에서 두 번 갱신된다.
- **원인**: 인증/제한/권한/집계/활동 갱신이 모두 critical path의 blocking I/O다.
- **확인 방법**: 핸들러 timer를 `session_validate`, `rate_limit`, `user`, `room`, `save`, `recent_count`, `session_touch`, `broadcast` 단계로 쌍아 p95/p99와 command count를 본다.
- **개선 방법**: P0-2 세션 touch 절감, P1-1 atomic rate limit, 연결 시 확인한 user를 안전한 범위에서 재사용, recent count 갱신 주기 coalescing을 각각 독립 실험한다. 메시지 save와 방 권한 검증은 정합성 핵심이므로 근거 없이 제거하지 않는다.
- **기대 효과**: 메시지 TPS 상승, event-loop/worker 점유 시간과 p99 감소.

### P0-6. 1만 개 금칙어를 메시지마다 선형 탐색

- **문제**: 메시지를 lowercase로 복사한 뒤 금칙어 Set 전체에 `contains`를 실행한다 (`BannedWordChecker:21-27`). 자원 파일은 10k 목록이다.
- **원인**: multi-pattern matching을 N개의 substring scan으로 구현했다.
- **확인 방법**: 10/100/1,000/10,000자 메시지와 금칙어 목록 크기별 JMH 또는 핸들러 stage timer/CPU flame graph를 비교한다.
- **개선 방법**: Aho-Corasick/trie 등 사전 컴파일된 multi-pattern matcher로 대체하되, 현 금칙어 판정 계약을 유지하는 regression test를 먼저 만든다.
- **기대 효과**: 메시지 당 CPU 사용량을 크게 줄이고 동시 메시지 시 event loop 정체를 완화한다.

### P1-1. MongoDB rate limiter가 비원자적이고 요청당 2 I/O를 사용

- **문제**: `findByClientId`후 count를 JVM에서 증가하고 `save`한다 (`RateLimitService:42-86`). 동시 요청이 같은 count를 읽어 lost update/한도 초과 허용이 가능하다. `@Transactional`은 standalone Mongo 환경에서 이 시퀀스를 자동으로 atomic하게 만들지 않는다.
- **원인**: atomic conditional update가 아닌 read-modify-write다.
- **확인 방법**: 같은 client ID로 barrier를 둔 동시 요청을 보내 허용 건수와 저장 count가 maxRequests와 일치하는지 확인한다.
- **개선 방법**: Mongo `findAndModify` + `$inc` + window 조건으로 1 round trip atomic 구현을 먼저 검토한다. 이미 Redis 의존성이 있어도, 측정 근거 없이 Redis/Lua로 옮기지 않는다.
- **기대 효과**: 제한 정확성 회복과 I/O 절반.

### P1-2. MongoDB 핵심 조회 복합 인덱스가 코드에 보이지 않음

- **문제**: 메시지는 `(room, timestamp)` 정렬/범위, `(room, timestamp>=)` count를 반복하지만 `Message`에 복합 인덱스 선언이 없다 (`MessageRepository:14-20`, `Message:28-68`). `Room.createdAt`, `File.filename`, `Message.file` 조회도 명시 인덱스가 없다.
- **원인**: auto-index creation은 선언된 인덱스만 만들며, query pattern 기반 인덱스 설계가 누락됐다.
- **확인 방법**: `explain("executionStats")`로 `totalDocsExamined`, `totalKeysExamined`, `nReturned`, in-memory sort 여부를 확인한다. 실 DB의 `getIndexes()`를 반드시 먼저 확인한다.
- **개선 방법**: 최우선 후보는 `messages {room:1,timestamp:-1}`다. 각 인덱스를 하나씩 생성하고 read 개선과 write/storage 비용을 비교한다. 운영에서 `auto-index-creation=true`로 무거운 인덱스를 기동 시 생성하지 말고 migration을 관리한다.
- **기대 효과**: 데이터 증가에 따른 메시지 조회/count p99 악화 완화.

### P1-3. 읽음·리액션·REST 방 참여의 lost update 위험

- **문제**: 읽음 목록과 reaction map은 문서를 읽고 JVM에서 변경한 후 전체 save한다. REST `joinRoom` 또한 Room 전체를 save한다 (`RoomService:146-168`). 동시 수정이 서로를 덮어쓸 수 있다.
- **원인**: Mongo array/map atomic operator대신 read-modify-save를 사용한다. `@Version` 낙관적 락도 없다.
- **확인 방법**: 같은 message/room에 50개 동시 읽음·reaction·join을 보낸 후 최종 set이 모든 작업을 포함하는지 검증한다.
- **개선 방법**: `$addToSet`, `$pull`, 필요 시 arrayFilters를 사용한 atomic update로 바꾸고 matched/modified count를 확인한다. 이미 `RoomRepository.addParticipant/removeParticipant` 경로를 REST에도 재사용할 수 있다.
- **기대 효과**: 동시성 정합성 회복, payload/write amplification 감소.

### P1-4. 방 입장/퇴장의 조회·브로드캐스트 폭증

- **문제**: 입장 1회에 user/room 검증, room update, system message save, 최근 30개 로드/읽음, room 재조회, 참가자 N+1, 다수 broadcast가 실행된다 (`RoomJoinHandler:46-133`). 퇴장도 참가자 전체를 재조회해 전송한다 (`RoomLeaveHandler:111-131`).
- **원인**: 온보딩 한 이벤트에 DB mutation, history, full presence snapshot을 모두 동기 연결했다.
- **확인 방법**: 방 참가자 1/10/100/1,000명 별 join TTI, DB ops, serialized response bytes, Netty event-loop queue를 측정한다.
- **개선 방법**: P0-4 bulk loading을 먼저 적용한다. 그 후 API/Socket 계약을 깨지 않는 범위에서 변경이 없을 때 join 재처리를 줄이고, 참가자 full snapshot 빈도/크기를 측정한다.
- **기대 효과**: 동시 접속 시 onboarding p95/p99와 DB 부하 감소.

### P1-5. 동기 Spring event listener가 요청 critical path에 존재

- **문제**: `ApplicationEventPublisher` 기본 리스너는 동기다. 방 생성/갱신, 최근 count, Socket broadcast가 publisher thread에서 완료될 때까지 호출자를 점유한다 (`SocketIOEventListener:24-138`).
- **원인**: 이벤트를 decoupling 표현으로 사용했지만 executor/queue 경계는 없다.
- **확인 방법**: publisher 전/후 timer와 listener timer를 추가하고, 느린 client/많은 room subscriber 시 응답 p99 영향을 본다.
- **개선 방법**: 먼저 broadcast 지연이 실제 병목임을 확인한다. 확정된 경우에만 bounded executor + rejection/queue metrics로 비핵심 알림을 분리한다. 메시지 저장 순서/실패 정책은 명시한다.
- **기대 효과**: 느린 broadcast가 REST/Socket 수신 worker를 장시간 점유하는 현상 완화.

### P1-6. 단일 노드 전용 Socket/파일 상태

- **문제**: Socket.IO store와 connected users/user rooms가 process memory이며 (`SocketIOConfig:61-63,92-97`), 파일은 로컬 `./uploads`다. 여러 인스턴스에서 presence/duplicate login/room membership가 분할되고 파일 접근이 노드별로 달라진다.
- **원인**: 설계가 명시적으로 단일 노드 전용이다.
- **확인 방법**: 2개 인스턴스 + non-sticky LB에서 접속/재접속/방 broadcast/파일 업로드-다운로드를 교차 검증한다.
- **개선 방법**: 행사 범위가 단일 노드면 제약을 문서화하고 변경하지 않는다. scale-out이 필요하다는 측정 근거가 생긴 후 Socket shared store/pub-sub과 shared object storage를 별도 실험한다.
- **기대 효과**: scale-out 시 정합성/파일 404 문제 방지. 단일 노드 부하 테스의 즉시 성능 개선은 아니다.

### P1-7. 스레드당 10초 sleep하는 중복 로그인 처리

- **문제**: `ConnectionLoginHandler` 중복 로그인 경로가 매번 `new Thread` 생성 후 10초 sleep한다 (`ConnectionLoginHandler:162-170`).
- **원인**: delay disconnect를 raw thread로 구현했다.
- **확인 방법**: 같은 계정의 중복 접속을 대량 발생시켜 live thread count, native memory, context switch를 재다.
- **개선 방법**: 이미 존재하는 Netty scheduler 또는 한 개의 bounded `ScheduledExecutorService`로 예약하고 queue size/rejection을 측정한다.
- **기대 효과**: thread explosion/native memory 고갈 방지.

### P2-1. 로그인이 중복 DB/세션 작업을 수행

- **문제**: controller가 이메일로 user를 조회한 뒤 `AuthenticationManager` 인증에서 `UserDetailsServiceImpl` 다시 조회한다 (`AuthController:167-174`, `UserDetailsServiceImpl:20-23`). controller에서 `removeAllUserSessions`을 호출한 후 `createSession()`도 다시 삭제한다 (`AuthController:178-189`, `SessionService:37-54`).
- **원인**: authentication 결과 principal 재사용과 세션 생성 책임 정리가 부족하다.
- **확인 방법**: 로그인 1회의 user/session command count와 bcrypt CPU time을 측정한다.
- **개선 방법**: 인증 결과에서 user ID를 재사용하고 세션 전체 삭제는 `createSession()` 한 곳에서만 수행하도록 최소 변경한다.
- **기대 효과**: 로그인 burst의 Mongo I/O 감소.

### P2-2. DB/AI/I/O timeout·pool 관측 설정이 명시적이지 않음

- **문제**: application 설정에 Mongo connect/server-selection/socket timeout과 pool size/wait queue timeout이 명시되지 않았고 OpenAI 연결/read timeout·동시 stream 제한도 코드에서 확인되지 않는다.
- **원인**: driver/default에 의존한다.
- **확인 방법**: 실행 시 effective MongoClient settings/HTTP client settings을 출력하고 pool checked-out/wait/timeout 메트릭을 dashboard에 추가한다.
- **개선 방법**: 현 부하에서 사용량을 확인한 후 실패 예산에 맞는 finite timeout/pool upper bound를 명시한다. pool은 Tomcat/Socket worker와 DB 용량 이상으로 무조건 키우지 않는다.
- **기대 효과**: 장애 시 무한/장시간 worker 점유 방지, 병목 계층 식별.

### P2-3. 건강 검사가 불필요한 DB 쿼리 2개를 수행

- **문제**: room health check가 단순 조회와 `createdAt desc` 최근 room 조회를 모두 실행한다 (`RoomService:69-103`). `createdAt` 인덱스가 없으면 헬스체크가 collection sort 부하를 만든다.
- **원인**: liveness/readiness/business last activity가 한 응답에 혼합됐다.
- **확인 방법**: LB/Kubernetes 호출 빈도×DB command count와 explain을 확인한다.
- **개선 방법**: 외부 URL은 유지하되 빈번한 liveness는 process 상태, readiness는 최소 DB ping 한 번으로 한다. lastActivity가 계약상 필수면 인덱스/더 저렴한 상태 갱신을 검토한다.
- **기대 효과**: probe 스스로가 DB 병목을 증폭하는 피드백 루프 방지.

### P2-4. 응답 정합성/코드 중복이 성능 분석을 흐림

- **문제**: Room DTO mapping이 service/controller에 중복되고 둘 다 `room.creator`(user ID)를 `name`(email)과 비교하여 `isCreator`가 잘못될 수 있다 (`RoomService:215`, `RoomController:287`). 또한 service가 예외을 숨기고 `success=false` 200 응답을 만들 수 있다 (`RoomService:60-66`).
- **원인**: mapping/예외 정책이 두 계층에 분산됐다.
- **확인 방법**: creator/non-creator API contract test, Mongo 장애 시 HTTP status/error metric 계약을 검증한다.
- **개선 방법**: URL/응답 JSON을 유지하면서 mapper를 하나로 합치고 authenticated user ID로 creator를 비교한다. 내부 오류를 정상 응답으로 계수하지 않도록 오류 지표/상태 계약을 통일한다.
- **기대 효과**: 성공률 100%라는 지표가 실제 성공을 반영하고, 최적화 전후 기능 regression을 정확히 판별한다.

### P2-5. 관측 사각지대

- **문제**: HTTP/Socket 총 latency와 일부 counter는 있지만 DB command/pool, 단계별 latency, executor/event-loop queue, response bytes, 실제 business failure를 연결하기 어렵다.
- **원인**: dashboard가 node exporter 중심이고 application hot-path 계층이 얇다.
- **확인 방법**: 다음 항목이 한 번의 테스 run ID/timeline으로 조회되는지 확인한다: endpoint/event, status/business outcome, p50/p95/p99, DB op count/time, pool wait, Tomcat busy, Netty queue, CPU/GC.
- **개선 방법**: 낮은 cardinality tag(`route`, `operation`, `outcome`)만 사용하고 user/room/message ID를 metric tag로 추가하지 않는다. trace sampling은 baseline 때만 높이고 성능 영향을 같이 측정한다.
- **기대 효과**: 추측이 아닌 근거 기반 순차 최적화가 가능해진다.

## 5. 요청 경로별 예상 DB 작업

아래는 코드 기준 상한/대략이며 driver 내부 작업은 제외한다.

| 경로 | 비즈니스 작업 전 공통 비용 | 주요 비즈니스 DB 작업 | 위험 |
|---|---:|---:|---|
| 인증 REST | session read 1 + write 1 | endpoint별 | 모든 API에 write amplification |
| GET `/api/rooms` | session 2 + rate-limit 2 | rooms 1 + 방별 creator 1 + participants P + message count 1 | 최악 N+1 |
| GET `/api/rooms/{id}` | session 2 | room 1 + creator 1 + participants P + count 1 | 참가자 수에 비례 |
| Socket `chatMessage` | session 2 + rate-limit 2 | user 1 + room 1 + message 1 + count 1 + session read/write 2 | 약 10 ops + CPU scan |
| Socket `joinRoom` | 연결 인증 비용 | user/room/update/save/history/read writes/senders/participants | onboarding 폭증 |
| 30개 history load | - | page query + 최대 30 read + 30 write + sender 30 | 최대 90+ ops |
| mark-as-read N개 | - | first msg 1 + user 1 + room 1 + N read + N write | client fan-out으로 증폭 |

## 6. 작업 도메인 분할

성능 개선은 아래 6개 도메인으로 나눈다. 각 도메인은 담당 파일이 최대한 겹치지 않도록 구성했으며, **관측 도메인은 모든 개선 작업보다 먼저 완료**한다.

### D1. 부하테스트·관측성

| 항목 | 내용 |
|---|---|
| 목표 | 병목 계층과 개선 전후 차이를 수치로 증명 |
| 담당 문제 | P2-5, P0-1/P0-5의 확인 방법 |
| 주요 범위 | `loadtest/`, `e2e/artillery/`, Actuator, Micrometer, Prometheus/Grafana 설정 |
| 산출물 | 동일 조건 baseline, endpoint/event별 p50/p95/p99·TPS·오류율, DB ops/request, pool wait, CPU/GC, Tomcat/Netty 포화도 |
| 완료 조건 | 한 테스트 run의 client·application·Mongo·system 지표를 같은 timeline으로 비교 가능 |

이 도메인은 애플리케이션 로직을 최적화하지 않는다. metric tag에 user/room/message ID를 넣지 않고, 테스트 데이터·부하 모델·warm-up·반복 횟수를 고정한다.

### D2. HTTP 런타임·DB 기반 설정

| 항목 | 내용 |
|---|---|
| 목표 | Tomcat과 MongoDB pool/timeout/인덱스의 적정값 검증 |
| 담당 문제 | P0-1, P1-2, P2-2, P2-3 |
| 주요 범위 | `application.properties`, Mongo 설정, 인덱스 migration, health check |
| 산출물 | Tomcat 10/20/40 비교표, Mongo explain 결과, 핵심 인덱스 전후 결과, finite timeout/pool 기준 |
| 완료 조건 | thread/pool을 임의로 키우지 않고 포화 지표와 p99로 선택 근거 제시 |

첫 실험은 `messages(room, timestamp desc)` 인덱스다. Tomcat thread 변경은 세션/N+1 개선 뒤 마지막에 수행해야 DB 병목을 worker로 가리지 않는다.

### D3. 인증·세션·Rate Limit

| 항목 | 내용 |
|---|---|
| 목표 | 모든 인증 요청의 공통 DB 비용과 동시성 오류 제거 |
| 담당 문제 | P0-2, P1-1, P2-1, 중복 로그인 thread 문제 P1-7 |
| 주요 범위 | `security/`, `SessionService`, session/rate-limit store·repository, `AuthController`, `ConnectionLoginHandler` |
| 산출물 | session touch throttling, atomic rate limit, 로그인 중복 조회/삭제 제거, bounded scheduler |
| 완료 조건 | 인증 REST의 session ops/request 감소, rate-limit 동시성 테스트 통과, 인증/단일 세션 계약 유지 |

세션 유효성 검증 자체를 제거하면 안 된다. 먼저 activity write 빈도만 낮추고, rate limit은 Mongo atomic update를 검증한 뒤에만 저장 기술 변경을 논의한다.

### D4. 채팅방 조회·참여

| 항목 | 내용 |
|---|---|
| 목표 | 방·참가자 수에 따라 증가하는 N+1과 전체 조회 제거 |
| 담당 문제 | P0-3, P1-3 중 방 참여, P1-4의 참가자 조회, P2-4 |
| 주요 범위 | `RoomController`, `RoomService`, `RoomRepository`, Room DTO mapping |
| 산출물 | DB 정렬/페이지 제한, user bulk load, atomic 참가/퇴장, mapper 단일화, `isCreator` 수정 |
| 완료 조건 | rooms 요청의 DB ops가 방/참가자 수에 비례하지 않고 API JSON·URL 계약 테스트 통과 |

Socket 입퇴장 핸들러 자체는 D5가 담당한다. D4는 공용 bulk participant 조회 메서드와 atomic repository 연산을 제공하고 D5가 이를 사용한다.

### D5. 메시지·Socket.IO hot path

| 항목 | 내용 |
|---|---|
| 목표 | 메시지 전송·history·읽음·리액션·입퇴장 경로의 DB round trip과 event-loop 점유 감소 |
| 담당 문제 | P0-4, P0-5, P1-3 중 읽음/리액션, P1-4, P1-5 |
| 주요 범위 | `websocket/socketio/handler/`, `MessageRepository`, `MessageReadStatusService`, `RoomActivityNotifier`, Socket event listener |
| 산출물 | sender bulk load, 읽음 atomic bulk update, reaction atomic update, 중복 session touch 제거, 단계별 Socket timer |
| 완료 조건 | history 30개 DB ops 대폭 감소, 동시 읽음/리액션 정합성 테스트 통과, Socket event payload/order 유지 |

D4의 참가자 bulk 조회와 D3의 session/rate-limit 변경에 의존한다. 공용 repository 메서드를 먼저 합친 뒤 handler를 수정해야 충돌이 적다.

### D6. CPU·외부 I/O·스토리지·확장성

| 항목 | 내용 |
|---|---|
| 목표 | DB 외 CPU/I/O 병목과 단일 노드 제약 검증 |
| 담당 문제 | P0-6, P1-6, AI/파일 I/O timeout 영역 |
| 주요 범위 | `BannedWordChecker`, AI stream, `storage/`, file service, Socket in-memory store |
| 산출물 | 금칙어 matcher benchmark/regression test, AI/file timeout 지표, 단일/다중 노드 제약 문서 |
| 완료 조건 | 금칙어 판정 결과 동일, CPU 개선 수치 확보, 단일 노드 운영 여부에 맞는 명확한 결론 |

행사가 단일 노드라면 shared Socket store/object storage 도입은 작업하지 않고 제약만 기록한다. scale-out 요구가 확인된 경우에만 별도 개선 과제로 전환한다.

### 추천 담당자 배치

| 팀 규모 | 배치 |
|---|---|
| 2명 | A: D1+D2+D6, B: D3+D4+D5. 단, 한 번에 한 도메인씩 진행 |
| 3명 | A: D1+D2, B: D3, C: D4+D5+D6 |
| 4명 | A: D1+D2, B: D3, C: D4, D: D5+D6 |
| 5명 이상 | D1, D2+D6, D3, D4, D5로 분리. D1 담당은 공통 검증 담당 겸임 |

### 파일 충돌과 병합 순서

1. D1 관측 변경과 baseline을 먼저 병합한다.
2. D2의 메시지 인덱스만 적용하고 재측정한다.
3. D3 session touch를 적용하고 재측정한다.
4. D4 bulk user/participant 조회를 먼저 병합한다.
5. D5가 D4의 공용 조회를 사용해 history/join/read 경로를 개선한다.
6. D6 금칙어 개선을 독립 적용한다.
7. 마지막으로 D2가 Tomcat worker 크기를 비교한다.

`MessageRepository`는 D2와 D5, `RoomRepository`는 D4와 D5, `ConnectionLoginHandler`는 D3와 D5가 겹칠 수 있다. 이 파일들은 동시 수정하지 말고 위 순서대로 담당권을 넘긴다.

## 7. 권장 실험 순서

### 0단계: baseline 재수집

- 동일 DB 데이터 스냅샷, JVM option, container CPU/memory, 네트워크, warm-up 2분을 고정한다.
- 30초는 너무 짧으므로 warm-up 후 최소 3∼5분 sustain, 각 조건 3회 반복한다.
- 1/5/10/20/40 RPS 또는 1/3/10/30/100 VU를 단계적으로 올리며 open/closed model을 명시한다.
- 요청 mix를 login, rooms list/detail/create, profile, file, Socket connect/join/message/read로 분리해 endpoint별로 저장한다.

### 1단계: 관측만 추가

- Tomcat busy/max/queue, Mongo command timer/pool wait, JVM GC pause/CPU, Socket event timer를 dashboard에 올린다.
- Mongo profiler/explain으로 상위 slow query와 요청당 query count를 확정한다.

### 2단계: 가장 작은 코드/설정 변경

1. `messages(room, timestamp desc)` 인덱스 1개 실험
2. 세션 activity write throttling 1개 실험
3. 방 목록 user bulk-load 1개 실험
4. 읽음 처리 atomic bulk update 1개 실험
5. 금칙어 matcher 1개 실험
6. 그 후에만 Tomcat worker 10→20→40 비교

각 단계는 직전 baseline과 동일 조건으로 테스트하고 코드 변경을 섞지 않는다.

## 8. 개선 전/후 판정표

| 지표 | Baseline | 개선 후 | 판정 |
|---|---:|---:|---|
| 성공률 | 100% (271/271) |  | 유지, business error 별도 |
| 평균 | 405ms |  | 참고용 |
| p90 | 1,290ms |  | tail 개선 |
| p99 | 1,290ms |  | 최우선 SLO |
| 실제 평균 처리량 | 271/30 = 9.03 req/s |  | 전송 RPS와 별도 |
| endpoint별 DB ops/request | 미측정 |  | N+1 개선 핵심 |
| Mongo pool wait p95/p99 | 미측정 |  | 0에 가까워야 함 |
| Tomcat busy/max | 미측정 |  | 1 지속 여부 |
| CPU/GC pause | 미측정 |  | 금칙어/JVM 구분 |
| Socket onboarding p95/p99 | 미측정 |  | join hot path 판정 |

## 9. 변경 시 반드시 유지할 계약

- REST API URL, HTTP method, request/response JSON field, status code
- Socket.IO event name/payload/order 중 client가 의존하는 부분
- 단일 세션 정책과 세션 폐기 전파
- 방 참가 권한, 파일 접근 권한, 읽음/리액션 멱등성
- 메시지 저장 성공 전/후 broadcast 순서
- 금칙어 판정 결과

## 10. 즉시 하지 말아야 할 것

- 근거 없이 Redis/cache/비동기 queue를 추가하지 않는다.
- Tomcat/Mongo pool을 동시에 크게 키우지 않는다.
- 읽음/리액션을 fire-and-forget으로 바꾸어 오류를 숨기지 않는다.
- 방 목록 응답을 임의로 줄이거나 URL/payload를 바꾸지 않는다.
- 한 번의 30초 테스트로 개선/퇴행을 확정하지 않는다.

## 11. 추가 기능/보안 발견(성능과 분리)

- BCrypt cost 4는 부하테스트 로그인 CPU를 낮추지만 보안 강도가 낮다 (`SecurityConfig:54-59`). 성능 수치만 보고 더 낮추지 말고 보안 기준과 별도 합의한다.
- 회원가입 OpenAPI 설명은 token/session ID 반환을 말하지만 실제 `registerUser` 응답은 생성한 metadata를 사용하지 않고 token/session을 넣지 않는다 (`AuthController:71,101-125`). 현 load test도 재로그인으로 우회한다. 계약을 정의하고 테스트를 고정해야 한다.
- CORS `*` + credentials 기본값과 Swagger 기본 활성화는 운영 환경에서 보안 설정을 명시해야 한다 (`application.properties`, `SecurityConfig:101-123`).

---

이 문서의 후보는 코드 정적 분석에서 발견한 것이다. **첫 수정은 반드시 0∼1단계 관측으로 원인을 확정한 뒤 시작한다.**
