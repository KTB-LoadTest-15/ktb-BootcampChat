# D9 — 새로고침 입퇴장 스팸 제거 (disconnect grace + 조용한 재조인)

## 문제

새로고침 한 번이 방 전체에 **퇴장 + 입장** 시스템 메시지를 뿌리고, DB 참가자 목록을 뺐다 넣는다.

- **끊김**: `onDisconnect` → 각 방 `processLeaveRoom` → `removeParticipant`(Mongo write) +
  "…님이 퇴장하였습니다." `messageStore.add` + `getRoomOperations(roomId)` 브로드캐스트(N명) +
  참가자 목록 브로드캐스트(N명).
- **재연결**: 프론트 `joinRoom` → 방금 빠졌으니 `isInRoom=false` → `addParticipant` +
  "…님이 입장하였습니다." + 브로드캐스트(N명) + 참가자 목록(N명).

새로고침 1회 = **~4N 방 전체 전송 + 저장 메시지 2 + 참가자 write 2**. M명 동시 재연결(네트워크 블립)이면
**4·M·N 팬아웃 폭풍**. 읽음 coalescing 등 기존 "N² 팬아웃 줄이기"와 같은 종류의 낭비.

## 개선 (Option A: disconnect grace)

소켓 끊김을 곧바로 "퇴장"으로 처리하지 않고 유예(grace, 기본 10s) 뒤로 미룬다. 유예 안에 같은 유저가
재연결(새로고침)하면 취소 → 멤버십이 유지된 채 조용히 복귀.

| 파일 | 변경 |
|---|---|
| `config/SocketIOConfig.java` | `disconnectGraceScheduler` 데몬 스케줄러 빈 추가. |
| `resources/application.properties` | `socketio.disconnect.grace-ms=${SOCKETIO_DISCONNECT_GRACE_MS:10000}` (0이면 즉시 퇴장=기존 동작). |
| `handler/ConnectionLoginHandler.java` | onDisconnect가 `roomLeaveHandler.leaveRoomByUser`를 유예 예약(userId별 `pendingLeaves` 관리). onConnect가 유예 취소 + 멤버 방에 **소켓만 재구독**(`client.joinRoom`, 히스토리/브로드캐스트 없음). |
| `handler/RoomLeaveHandler.java` | client 비의존 `leaveRoomByUser(userId,userName,roomId)` 추출(유예 만료 시 라이브 client 없음). `processLeaveRoom`은 이를 위임. |
| `handler/RoomJoinHandler.java` | **이미 멤버면 조용한 재조인**: 본인에게 히스토리(메시지·참가자·읽음커서)만 서빙(`buildJoinSuccessResponse`), 방 전체 입장 알림·참가자 브로드캐스트·`addParticipant` 없음. 최초 입장과 재조인이 응답 빌더 공유. |

동작: 유예 안 재연결 → 조용(입퇴장 0). 유예 초과(진짜 나감) → 정상 퇴장 브로드캐스트. `grace-ms=0`이면
기존 즉시 퇴장.

## 측정

### 마이크로 (회귀 가드)
- `RoomJoinHandlerTest.handleJoinRoom_whenAlreadyMember_servesHistorySilently_noBroadcastNoChurn`:
  isInRoom=true → 히스토리 응답 O, `getRoomOperations`/`addParticipant`/`userRooms.add`/`messageStore.add` **never**.
- `ConnectionLoginHandlerTest`: onDisconnect(grace>0)=유예 예약·즉시 leave 안 함 / onDisconnect(grace=0)=즉시 leave / onConnect=대기 퇴장 취소. **전체 272 tests green.**

### 실부하 A/B (단일 노드, 동일 JAR, `SOCKETIO_DISCONNECT_GRACE_MS`만 0↔10000)
B가 방에 머무는 동안 A가 새로고침(disconnect→reconnect→rejoin). scratchpad `refresh_ab_run.sh` + `refresh_test.js`.

| grace | A 새로고침 시 **B가 받는 입퇴장 메시지** | Mongo `rooms.update`(run 전체) | `messages.insert` |
|---|---|---|---|
| 0 (baseline) | **2** (퇴장+입장) | 6 | 6 |
| 10000 (after) | **0** | 2 | 2 |

새로고침당 방 전체로 가던 입퇴장 broadcast가 **2 → 0**, 참가자 write·시스템 메시지 저장도 함께 감소.
접속자 많을수록(N·M) 이득이 커진다. 남는 비용은 재조인한 본인의 히스토리 로드(새 페이지라 불가피).

## 참고
- 유예 중 노드/유저가 진짜 떠나면 grace 만료 후 정상 퇴장 처리.
- grace 창(기본 10s)은 새로고침·짧은 네트워크 블립을 흡수하는 값. 필요 시 env로 조정.
