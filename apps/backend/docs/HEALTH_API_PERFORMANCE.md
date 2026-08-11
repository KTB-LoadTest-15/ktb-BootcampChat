# Health API 성능 점검

## 대상과 결론

- `GET /api/health`: 프론트엔드와 E2E가 사용하는 liveness endpoint다. 프로세스 상태와 환경만 반환하며 MongoDB 조회는 **0회**다. 이미 가벼운 구조이므로 변경하지 않았다.
- `GET /api/rooms/health`: 방 서비스와 MongoDB 상태를 함께 반환하는 호환 endpoint다. 기존에는 요청마다 MongoDB를 **2회** 조회해 1회로 줄였다.

API URL, HTTP 상태, 정상 응답 필드는 유지한다.

## 문제

`RoomService.getHealthStatus()`가 다음 조회를 연속으로 수행했다.

1. `findOneForHealthCheck()`로 MongoDB 연결과 지연 확인
2. `findMostRecentRoom()`으로 `lastActivity` 확인

Health probe 주기가 짧거나 인스턴스 수가 늘면 실제 서비스 트래픽과 무관한 MongoDB read가 probe마다 2회 발생한다.

## 원인

연결 확인과 최근 활동 조회를 별도 repository 호출로 분리했지만, 최근 방 조회가 성공했다는 사실만으로도 MongoDB 연결 상태와 해당 조회 지연을 함께 판단할 수 있다.

## 개선

`findMostRecentRoom()` 한 번의 결과로 다음 값을 모두 계산한다.

- MongoDB 연결 여부
- 조회 지연 시간
- 가장 최근 방의 `createdAt`

중복 용도였던 `findOneForHealthCheck()` repository method는 제거했다. 방이 0개여서 결과가 비어 있어도 쿼리가 정상 완료되면 연결 상태는 정상이다. MongoDB 예외가 발생하면 `success=false`, `database.connected=false`로 반환한다.

## 기대 효과

| endpoint | 개선 전 MongoDB 조회 | 개선 후 MongoDB 조회 | 변화 |
|---|---:|---:|---:|
| `GET /api/health` | 0 | 0 | 변경 없음 |
| `GET /api/rooms/health` | 2 | 1 | 50% 감소 |

Health probe가 `N`회 발생할 때 방 서비스 health 조회는 `2N → N`으로 감소한다. 일반 서비스 API의 동작과 데이터 정합성에는 영향이 없다.

## 확인 방법

1. 기존 E2E를 수정하지 않고 `/api/health` 기반 연결 확인을 실행한다.
2. `/api/rooms/health` 정상 호출에서 HTTP 200, `success=true`, `services.database.connected=true`를 확인한다.
3. MongoDB command listener 또는 profiler로 `/api/rooms/health` 요청당 find가 1회인지 확인한다.
4. MongoDB 중단 시 HTTP 503과 `success=false`를 확인한다.

## 검증 결과

- 백엔드 전체 테스트: **258개 통과, 실패 0, 오류 0, 8개 skip**
- 컴파일 성공
- `git diff --check` 통과
- 기존 `e2e/**` 수정 없음
