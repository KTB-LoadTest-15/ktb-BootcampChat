# P0-4 / P1-4 — 유저 조회 N+1 제거 (findById 루프 → findAllById bulk load)

> 도메인: D5 (메시지·Socket.IO hot path)
> 대상: history의 sender 조회(`MessageLoader`), 방 입/퇴장 참가자 목록(`RoomJoinHandler`, `RoomLeaveHandler`)
> 측정: MongoDB 드라이버 `CommandListener`로 wire 명령 실측 (Testcontainers `mongo:8.3.4`)

## 1. 문제

메시지 저장/조회는 Redis hot store로 옮겼지만(P2/P4), **유저는 여전히 Mongo**다. 그런데 "id 집합 → User"가 필요한 세 hot path가 모두 id마다 `findById`를 반복했다.

- `MessageLoader.loadMessagesInternal` — history 30개를 돌며 메시지마다 `userRepository.findById(senderId)` → 최대 **find 30회**
- `RoomJoinHandler.handleJoinRoom` — `participantIds.stream().map(userRepository::findById)` → 참가자 수만큼 find
- `RoomLeaveHandler.broadcastParticipantList` — 동일 패턴

왕복이 메시지/참가자 개수에 선형 비례(O(N))했다. Redis 전환으로 메시지 컬렉션 Mongo 명령은 사라졌지만, 같은 경로의 이 유저 N+1은 그대로 남아 hot path의 잔여 Mongo 비용을 지배했다.

## 2. 개선

id 집합을 한 번의 `findAllById`(`$in` 쿼리)로 해소하는 공용 이음새 `UserBatchLoader`를 도입하고, 세 지점을 모두 이걸 경유하도록 바꿨다.

```
Map<String, User> findByIds(Collection<String> ids)
  → null 제거 + distinct → userRepository.findAllById(distinct) 1회 → id→User 맵
```

- null id 무시, 중복 id는 한 번만 조회, 존재하지 않는 id는 맵에서 제외 → 기존 `findById().filter(present)` 의미와 동일
- 호출부는 원래 순서(메시지 순서 / 참가자 Set 순회 순서)대로 맵을 조회해 응답을 만들므로 **정렬·필터링 동작 불변**

## 3. 측정 결과 (실측)

측정 테스트: `perf/UserBulkLoadQueryCountIntegrationTest` — 서로 다른 발신자 30명을 해소하는 동일 시나리오(worst-case N+1).

| 구분 | Mongo 명령 | 총 왕복 |
|---|---|---:|
| **BEFORE** (id마다 findById) | `find` 30 | **30** |
| **AFTER** (findAllById, `$in`) | `find` 1 | **1** |

**개선율: 30 → 1 (−96.7%), 유저 조회 O(N) → O(1).**

세 hot path(history sender / 입장 참가자 / 퇴장 참가자)가 모두 같은 `UserBatchLoader`를 경유하므로 동일하게 적용된다.

## 4. 동작 동치성 검증

같은 테스트에서:

- OLD(findById 루프)와 NEW(findAllById)가 **동일한 id 집합의 User를 해소**함을 단언(keySet 일치, 30명, 각 id의 User 일치).
- 별도 케이스로 중복 id는 find 1회로 처리되고, 존재하지 않는/`null` id는 결과 맵에서 제외됨을 단언.
- 핸들러 단위 테스트(`MessageLoaderTest`, `RoomJoinHandlerTest`, `RoomLeaveHandlerTest`)와 통합 테스트(`MessageLoaderIntegrationTest`)가 응답 순서·참가자 목록 결과 불변을 회귀 가드로 검증.

## 5. 파일별 변경

- `service/UserBatchLoader.java` (신규) — id 집합을 `findAllById` 1회로 해소하는 공용 배치 로더.
- `websocket/socketio/handler/MessageLoader.java` — sender를 메시지마다 `findById` 하던 루프 제거, `userBatchLoader.findByIds(senderIds)`로 일괄 해소 후 맵 조회. 미사용이 된 `findUserById`/`UserRepository` 의존 제거.
- `websocket/socketio/handler/RoomJoinHandler.java` — 참가자 목록을 `findByIds`로 일괄 해소(입장 유저 존재 확인용 `userRepository`는 단건이라 유지).
- `websocket/socketio/handler/RoomLeaveHandler.java` — 참가자 목록을 `findByIds`로 일괄 해소(퇴장 유저 단건 조회는 유지).
- `test/.../perf/UserBulkLoadQueryCountIntegrationTest.java` (신규) — OLD vs NEW find 명령 수 실측·단언 + 동치성/중복·결측 처리 회귀 가드.
- 관련 단위/통합 테스트 — 새 `UserBatchLoader` 의존성에 맞춰 mock/생성자 갱신.
