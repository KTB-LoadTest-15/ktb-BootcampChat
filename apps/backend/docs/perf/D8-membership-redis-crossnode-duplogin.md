# D8 — 멤버십 Redis 공유 + 크로스노드 중복 로그인 (MULTI_INSTANCE ②③)

## 문제

D7(RedissonStoreFactory)이 브로드캐스트 fan-out을 클러스터 전역으로 만들었지만, **접속/멤버십
상태는 여전히 노드 로컬**이었다:
- **③ `ConnectedUsers`/`UserRooms`** 가 `LocalChatDataStore`(힙 맵) 백업 → 노드 A에 붙은 유저는
  노드 B에서 안 보임. `MessageReadHandler.isInRoom` 읽음 권한 판정도 노드 로컬.
- **② 중복 로그인** `notifyDuplicateLogin`이 `connectedUsers.get`(노드 로컬) + `getClient(socketId)`
  (로컬 레지스트리 — store를 공유해도 타 노드의 라이브 소켓 객체는 못 준다)를 씀 → 노드 A의 기존
  세션을 노드 B의 새 로그인이 통보/종료 못 함 → 같은 유저 세션 2개 생존.

## 개선

### ③ ChatDataStore Redis 백업
`RedisChatDataStore`(신규) 추가, `socketio.store=redisson`일 때 `LocalChatDataStore` 대신 주입.
`ConnectedUsers`/`UserRooms`가 이 스토어를 경유하므로 접속·멤버십이 클러스터 공유가 된다.
- 값은 JSON(`socketio:store:{key}`), 키 개수(size)는 Redis Set `socketio:store:__keys`의 `SCARD`로
  O(1)(전체 SCAN 회피). `StringRedisTemplate` 재사용(Redisson과 별개, 새 인프라 빈 0).

### ② 크로스노드 중복 로그인
- 각 클라이언트가 `socket:{socketId}` 룸에 조인(onConnect). 자신의 소켓만 대상으로 하는 룸.
- `notifyDuplicateLogin`이 `getClient(socketId)` 대신 `getRoomOperations("socket:{기존소켓id}")`로
  전송 → RedissonStoreFactory가 기존 소켓이 있는 노드로 fan-out. 기존 세션 조회는 공유된
  `ConnectedUsers`(③)로 크로스노드 가능. 새 클라는 자신의 `socket:{자기id}`만 조인하므로 지연
  SESSION_ENDED가 새(정상) 세션을 오폭하지 않는다.

### 변경/신규 파일

| 파일 | 무엇을 / 어떻게 |
|---|---|
| `websocket/socketio/RedisChatDataStore.java` (신규) | ChatDataStore의 Redis 구현. JSON 값 + keyset 기반 O(1) size. |
| `config/SocketIOConfig.java` | `chatDataStore` 빈을 `socketio.store`로 택일: memory→Local, redisson→Redis. |
| `websocket/socketio/handler/ConnectionLoginHandler.java` | onConnect가 `socket:{id}` 룸 조인; `notifyDuplicateLogin`이 room-ops로 크로스노드 타깃 전송. **부수 수정**: DUPLICATE_LOGIN payload의 null User-Agent → `Map.of` NPE 방지(기본값 보정). |

## 측정 — 2노드 A/B (공유 Redis)

하네스 `xnode_full_ab.sh`: 동일 JAR 2인스턴스(5001/5002, 5011/5012), 공유 Redis/Mongo/JWT,
`socketio.store`만 memory↔redisson. 두 시나리오:
- **메시지 브로드캐스트**(D7 재현): C=발신자동일노드, B=타노드.
- **중복 로그인**: userX가 node1에 접속(A1) → 재로그인 후 node2에 접속(A2) → A1이 `duplicate_login`을
  크로스노드로 받는가.

| 시나리오 | memory (baseline) | redisson (after) |
|---|---|---|
| 메시지 크로스노드 (B) | MISSED | RECEIVED |
| 중복 로그인 크로스노드 (A1) | MISSED | **RECEIVED** |
| 동시접속 카운트(size, 공유) | 노드별 | **클러스터 합산** (로그 "concurrent users: 3→5"로 확인) |

전체 스위트 **264 tests green**(+`RedisChatDataStoreIntegrationTest` 3, `ConnectionLoginHandlerTest`
신동작 반영). memory 기본 경로 무회귀.

## 하네스가 잡은 실제 버그

`notifyDuplicateLogin`의 `Map.of(..., "deviceInfo", headers.get("User-Agent"), ...)`는 UA가 null이면
NPE. 원래는 `getClient`가 크로스노드에서 null이라 그 앞에서 early-return돼 **가려져 있던** 사전
버그다. null-check 제거로 드러나 2노드 실측에서 `Error handling Socket.IO connection` NPE로 재현 →
기본값 보정으로 수정.

## 남은 것

- **④ 읽음 커서 `RedisReadCursorStore.advance` Lua CAS** (HGET→비교→HSET 비원자).
- **크래시 잔존**: Redis 공유 ConnectedUsers는 노드 크래시 시 해당 노드 항목이 명시적 disconnect
  없이 잔존(TTL/heartbeat 미구현). 단기 이벤트 부하 수용, 장기 운영 시 TTL 과제.
- 배포 전제: **sticky session**.
