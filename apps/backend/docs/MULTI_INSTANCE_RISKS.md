# 다중 인스턴스(수평 확장) 리스크 분석

> 대상: 여러 백엔드 인스턴스 + 별도 Redis 인스턴스 환경. 현재 웹소켓 서브시스템은
> **설계상 단일노드 전용**이며(`MemoryStoreFactory` 주석 "단일노드 전용"), 그대로 수평 확장하면
> 채팅 핵심 기능이 깨진다. 근본 원인은 (A) Socket.IO 브로드캐스트가 노드 로컬, (B) 접속/멤버십
> 상태가 노드 로컬 인메모리.

## 요약 표

| 상태/기능 | 위치 | 크로스노드 | 심각도 |
|---|---|---|---|
| 모든 `getRoomOperations(...).sendEvent()` 브로드캐스트 | Socket.IO `MemoryStoreFactory` | ✅ 해결(`socketio.store=redisson`, D7) | ~~🔴~~ 완료 |
| `ConnectedUsers` (userId→SocketUser) | `LocalChatDataStore` 힙 맵 | ❌ | 🟠 High |
| `UserRooms` (userId→Set<roomId>) | `LocalChatDataStore` 힙 맵 | ❌ | 🟠 High |
| 중복 로그인 감지/종료 | `ConnectedUsers`+`getClient` | ❌ (코드 TODO 명시) | 🟠 High |
| `RedisReadCursorStore.advance` | Redis(공유)지만 비원자 | ⚠️ lost update | 🟡 Medium |
| `ReadReceiptCoalescer.pendingByRoom` | 힙 `ConcurrentHashMap` | ❌ 로컬 fan-out | 🟡 Medium |
| `socketio.concurrent.users` 게이지 | `ConnectedUsers.size()` | ⚠️ 노드별 집계 | ℹ️ 관측 |
| **세션 저장소** (`session.store=redis`) | Redis(공유) | ✅ | — |
| 레이트리밋 | Mongo `findAndModify` 원자 | ✅ | — |
| 메시지/커서/참가자 **데이터** | 공유 스토어 | ✅ | — |

## 상세

### 🔴 ① 크로스노드 브로드캐스트 없음 (최우선 블로커)
모든 실시간 전달(채팅 `ChatMessageHandler.java:192`, 참가자 갱신 `RoomJoinHandler`/`RoomLeaveHandler`,
리액션 `MessageReactionHandler.java:81`, 읽음 `ReadReceiptCoalescer.java:93`, room-list·AI 스트리밍
`SocketIOEventListener`)이 `socketIOServer.getRoomOperations(roomId).sendEvent(...)`로 나간다.
룸 레지스트리가 `MemoryStoreFactory`(`SocketIOConfig.java:64`) 백업이라 **로컬 노드 소켓만** 안다.
→ 같은 방의 두 유저가 다른 인스턴스에 붙으면 서로의 메시지·AI·읽음·참가자 변화를 실시간으로 못 받는다.
Redis에 데이터가 저장돼도 라이브 push는 로컬에만.

### 🟠 ② 중복 로그인 / 단일 세션 강제 깨짐
`ConnectionLoginHandler.notifyDuplicateLogin`(:151-181)이 `connectedUsers.get(userId)`(노드 로컬)
+ `socketIOServer.getClient(socketId)`(로컬 레지스트리)로 기존 소켓을 찾는다. 기존 세션이 노드 A,
새 로그인이 노드 B면 B는 `get(userId)=null`이라 기존 세션을 통보/종료 못 함 → **같은 유저 세션 2개
동시 생존**. 코드에 인지됨: `:71` "다른 노드에 접속된 사용자는 통보 불가", `:147` TODO.

### 🟠 ③ UserRooms 노드 로컬 (읽음 권한/재입장)
`MessageReadHandler.java:71`이 읽음 처리 권한을 `userRooms.isInRoom`(노드 로컬 인메모리)으로 검증.
sticky session이 있으면 대체로 무해하나, 없거나 재연결로 노드가 바뀌면 정당한 읽음/입장이
"Room access denied"로 거부될 수 있다. (참가자 목록 원본은 Mongo라 데이터는 일관.)

### 🟡 ④ 읽음 커서 advance 비원자 (멀티노드 lost update)
`RedisReadCursorStore.advance`가 `HGET → 비교 → HSET`(비원자). 현재는 "같은 유저=세션 레인
직렬화(단일 노드)"에 의존. 멀티노드에서 한 유저 읽음이 다른 노드들에서 동시 처리되면 커서 전진
유실 가능 → Lua CAS로 원자화 필요.

### 🟡 ⑤ ReadReceiptCoalescer 노드 로컬
`pendingByRoom` 힙 버퍼로 coalescing 후 `getRoomOperations`로 flush → ①의 부분집합.

### ℹ️ 관측: concurrent.users 게이지 노드별
`ConnectedUsers.size()` 기반이라 클러스터 전체가 아닌 노드별 동시접속 보고. 해석 주의.

## 배포 전제조건

**Sticky session(세션 어피니티) 필수** — Socket.IO 폴링 핸드셰이크는 한 클라이언트의 연속 HTTP
요청이 같은 노드로 가야 성립. 단, sticky만으론 ①(같은 방 크로스유저)·②(크로스노드 중복로그인)는
못 고친다(방 하나가 한 노드에 갇히지 않으므로).

## 개선 방향 (의존성 이미 존재)

netty-socketio 2.0.13은 `RedissonStoreFactory`를 제공하고 **redisson 4.6.1이 이미 pom에 있다**
(`RedissonStoreFactory(RedissonClient)` 생성자·`Redisson.create(Config)` API 호환 확인).

1. ✅ **`MemoryStoreFactory` → `RedissonStoreFactory`** (`SocketIOConfig.java`). **완료** — `socketio.store=redisson`
   플래그로 택일(기본 memory). `getRoomOperations().sendEvent()`가 **클러스터 전역 pub/sub fan-out** →
   ①·⑤ 해결. 2노드 A/B로 크로스노드 전달 검증(`docs/perf/D7-socketio-redisson-crossnode.md`).
2. **`LocalChatDataStore` → Redis 백업 ChatDataStore** → `ConnectedUsers`/`UserRooms` 공유 → ②·③ 해결.
   (②는 추가로 `notifyDuplicateLogin`을 `user:{userId}` 룸 emit으로 변경 필요 — 이제 redisson으로 타 노드 전달됨.)
3. **`RedisReadCursorStore.advance` → Lua CAS** → ④ 해결.

우선순위: ~~①(완료)~~ → ②(중복로그인/멤버십) → ③ → ④.
