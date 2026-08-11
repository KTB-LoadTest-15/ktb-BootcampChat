# D7 — Socket.IO 크로스노드 브로드캐스트 (MemoryStoreFactory → RedissonStoreFactory)

## 문제 (MULTI_INSTANCE_RISKS ①)

모든 실시간 전달이 `socketIOServer.getRoomOperations(roomId).sendEvent(...)`로 나가는데, 룸
레지스트리가 `MemoryStoreFactory`(`SocketIOConfig.java:64`, 주석 "단일노드 전용") 백업이라 **로컬
노드에 붙은 소켓만** 안다. 다중 인스턴스에서 같은 방의 두 유저가 다른 노드에 붙으면 서로의
메시지·AI·읽음·참가자 변화를 실시간으로 못 받는다. 이것이 수평 확장의 최우선 블로커.

## 개선

`MemoryStoreFactory` → `RedissonStoreFactory`로 플래그 택일. netty-socketio 2.0.13이 제공하고
**redisson 4.6.1이 이미 pom에 존재**(추가 의존성 0). RedissonStoreFactory는 클라이언트/룸
레지스트리를 공유 Redis에 두고, `getRoomOperations().sendEvent()`를 Redis pub/sub으로 **클러스터
전역 fan-out** 한다.

### 변경 파일

| 파일 | 무엇을 / 어떻게 |
|---|---|
| `config/SocketIOConfig.java` | `socketio.store=redisson`일 때만 생성되는 `socketIoRedissonClient`(spring.data.redis.* 기반, `destroyMethod="shutdown"`) 빈 추가. `socketIOServer(...)`가 `ObjectProvider<RedissonClient>`로 팩토리 선택: 있으면 `RedissonStoreFactory`, 없으면 `MemoryStoreFactory`(기본). |
| `resources/application.properties` | `socketio.store=${SOCKETIO_STORE:memory}` 플래그. 기본 memory=단일노드 동작 보존. |

기본값이 memory라 **단일노드 배포는 무변경**. Redis 비번은 `spring.data.redis.password`를 그대로
사용(prod의 비번 있는 별도 Redis와 호환; 값이 없으면 AUTH 생략).

## 측정 — 2노드 크로스노드 전달 A/B

하네스: scratchpad `xnode_ab_run.sh` + `xnode_test.js`. 동일 JAR로 **2개 인스턴스**(node1 5001/5002,
node2 5011/5012)를 **같은 Redis·Mongo·JWT 시크릿** 공유로 기동(`SESSION_STORE=redis`,
`MESSAGE_STORE=redis`). 클라이언트 3개가 같은 방 조인:
- **C** → node1 (발신자와 같은 노드, 하네스 유효성 대조군)
- **B** → node2 (다른 노드, 크로스노드 검증 대상)
- **A** → node1, 메시지 발신

`SOCKETIO_STORE`만 memory vs redisson으로 바꿔 실행:

| SOCKETIO_STORE | 실제 store factory (로그 확인) | 같은노드 C | 크로스노드 B |
|---|---|---|---|
| memory (baseline) | `MemoryStoreFactory` | RECEIVED | **MISSED** |
| redisson (after) | `RedissonStoreFactory` | RECEIVED | **RECEIVED** |

- baseline은 문제를 정확히 재현: 같은 노드는 받고 **다른 노드는 못 받음**.
- redisson은 **크로스노드 B가 수신** → `getRoomOperations().sendEvent()`가 클러스터 전역으로 fan-out됨을 증명.
- 대조군 C가 양쪽 모두 RECEIVED → 하네스 자체가 유효(발신/조인 흐름 정상).

전체 스위트 **261 tests green**(기본 memory 경로 무회귀), 컴파일에서 redisson 4.6.1 ↔ netty-socketio
API 링크 확인.

## 남은 것 (이 변경으로 해결 안 됨)

RedissonStoreFactory는 **브로드캐스트 fan-out(① ⑤)** 을 해결한다. 다음은 별도 작업 필요:
- **② 중복 로그인 크로스노드 종료**: `notifyDuplicateLogin`이 `connectedUsers.get`(노드 로컬)
  + `getClient(socketId)`(로컬 레지스트리)를 쓴다. `getClient`는 store 공유와 무관하게 타 노드의
  라이브 소켓 객체를 못 준다. 해결하려면 `user:{userId}` 룸으로 종료 신호를 emit하도록 핸들러를
  바꿔야 한다(이제 그 emit이 redisson으로 타 노드까지 전달됨).
- **③ UserRooms `isInRoom` 권한 판정**: 여전히 노드 로컬 → Redis 백업 필요.
- **④ 읽음 커서 advance Lua CAS**.
