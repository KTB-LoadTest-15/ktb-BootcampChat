# D4 채팅방 API 작업 기준선

## 목적

D4 개선 전 현재 API 계약과 방 목록의 DB 호출 구조를 기록한다. 별도 테스트 코드는 추가하지 않고 기존 부하테스트 시나리오와 동일한 API URL 및 payload를 사용한다.

## 1단계: 기존 시나리오 기준 API 계약

### 방 목록 조회

```http
GET /api/rooms
Authorization: Bearer {token}
```

현재 주요 응답 구조:

```json
{
  "success": true,
  "data": [
    {
      "_id": "room-id",
      "name": "room-name",
      "hasPassword": false,
      "creator": {
        "id": "user-id",
        "name": "user-name",
        "email": "user@example.com"
      },
      "participants": [],
      "participantsCount": 0,
      "createdAt": "2026-08-10T03:00:00Z",
      "recentMessageCount": 0
    }
  ],
  "metadata": {
    "total": 1,
    "page": 0,
    "pageSize": 1,
    "totalPages": 1,
    "hasMore": false,
    "currentCount": 1
  }
}
```

- HTTP 200
- `Cache-Control: max-age=10`
- 전체 방을 반환하고 애플리케이션에서 최신순 정렬
- Room ID는 `_id`, User ID는 `id`

### 방 생성

```http
POST /api/rooms
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "room-name",
  "password": null
}
```

- 성공: HTTP 201, `{ "success": true, "data": RoomResponse }`
- 빈 이름: HTTP 400
- API URL과 요청/응답 구조를 변경하지 않는다.

### 방 상세 조회

```http
GET /api/rooms/{roomId}
Authorization: Bearer {token}
```

- 성공: HTTP 200, `{ "success": true, "data": RoomResponse }`
- 존재하지 않는 방: HTTP 404

### 방 참여

```http
POST /api/rooms/{roomId}/join
Authorization: Bearer {token}
Content-Type: application/json

{
  "password": null
}
```

- 성공: HTTP 200
- 존재하지 않는 방: HTTP 404
- 비밀번호 불일치: HTTP 401
- 중복 참여 시 참가자가 중복 추가되면 안 된다.

### 현재 확인된 계약 문제

- `RoomResponse.isCreator`는 현재 실제 JSON에 직렬화되지 않을 가능성이 높다. `creator` 객체와 boolean getter 이름이 충돌한다.
- `isCreator` 계산도 저장된 creator user ID와 로그인 principal의 email을 비교하고 있어 올바르지 않다.
- controller가 `Last-Modified`에 ISO-8601 문자열을 직접 설정한다. 표준 HTTP 날짜 형식과 맞지 않을 수 있으므로 실제 응답 헤더를 부하테스트 도구에서 확인한다.

위 문제를 고칠 때도 URL이나 기존 필드의 이름을 변경하지 않는다. `isCreator`를 정상 노출하는 것은 별도 기능 확인 후 진행한다.

## 2단계: 현재 DB 호출 구조 기준선

`RoomService.getAllRooms()`의 코드 기준 repository 호출 수는 다음과 같다.

```text
roomQueries = 1
userQueries = rooms × (participantsPerRoom + creator 1)
recentCountQueries = rooms
totalRepositoryCalls = 1 + rooms × (participantsPerRoom + 2)
```

| 방 수 | 방당 참가자 | Room 조회 | User 조회 | 최근 메시지 count | 총 repository 호출 |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 1 | 2 | 1 | 4 |
| 1 | 10 | 1 | 11 | 1 | 13 |
| 1 | 100 | 1 | 101 | 1 | 103 |
| 10 | 1 | 1 | 20 | 10 | 31 |
| 10 | 10 | 1 | 110 | 10 | 121 |
| 10 | 100 | 1 | 1,010 | 10 | 1,021 |
| 100 | 1 | 1 | 200 | 100 | 301 |
| 100 | 10 | 1 | 1,100 | 100 | 1,201 |
| 100 | 100 | 1 | 10,100 | 100 | 10,201 |

이는 mock 성능 테스트 결과가 아니라 현재 코드의 호출문을 기준으로 계산한 값이다. 실제 MongoDB에서는 driver 동작, session 검증, rate limit 호출이 추가된다.

## 2026-08-10 실행 기준선

### 제공된 간단 부하테스트 결과

| 조건/지표 | 개선 전 |
|---|---:|
| 실행 조건 | 10 RPS, 30초, 3 VU |
| 요청 성공 | 271 / 271 |
| 성공률 | 100.0% |
| 평균 응답 시간 | 405 ms |
| p90 | 1,290 ms |
| p99 | 1,290 ms |

- 성공률은 100%지만 평균 대비 p90이 약 3.19배이므로 간헐적인 지연 스파이크가 있다.
- 그래프상 약 7~9초, 14~16초 구간에서 지연 증가와 처리량 하락이 함께 나타난다.
- 이 결과는 여러 API가 섞인 리포트이므로 `GET /api/rooms` 단독 병목의 증거로 확정하지 않는다.
- 다음 측정에서는 URL별 latency/TPS와 Mongo find/count를 함께 수집해야 N+1 영향과 다른 API 영향을 분리할 수 있다.

### 기존 E2E 안정성 게이트 실행 결과

E2E 파일은 수정하지 않았다.

```bash
BASE_URL=http://127.0.0.1:3000 pnpm run test:full
```

- 결과: 14 passed, 3 failed, 18 did not run
- 동일 조건 재실행에서도 같은 결과가 재현됐다.
- 세 실패 모두 백엔드 방 API 호출 전 `loginAction()`의 `page.goto('/login')`에서 `net::ERR_ABORTED`가 발생했다.
- 실패 진입점: `auth.spec.js` 회원가입 후 로그인, `chat.spec.js` 사전 로그인, `timeout.spec.js` 사전 로그인

실행 간 병렬 간섭을 분리하기 위해 소스 변경 없이 워커만 1개로 실행했다.

```bash
BASE_URL=http://127.0.0.1:3000 pnpm exec playwright test --workers=1
```

- 결과: 19 passed, 2 failed, 14 did not run
- `chat.spec.js`와 `timeout.spec.js`의 사전 로그인 단계에서 동일 오류가 재현되어, 5-worker 병렬 실행만의 문제는 아니다.
- 현재 기준으로 전체 E2E 안정성 게이트는 **미통과**다.
- D4 구현 전부터 발생한 기준선 실패로 기록하며, E2E를 수정해 우회하지 않는다. 이후 D4 변경은 이 실패를 추가로 늘리지 않아야 하고, 최종 안정 판정에는 전체 E2E 통과가 필요하다.

### 기존 Artillery 기본 시나리오 결과

E2E/Artillery 파일을 변경하지 않고 기존 기본 조건으로 실행했다.

```bash
BASE_URL=http://localhost:3000 PHASE1_DURATION=5 PHASE1_ARRIVAL_COUNT=1 make artillery
```

| 항목 | 결과 |
|---|---:|
| VU | 1 created / 1 completed / 0 failed |
| 전체 시나리오 수행 시간 | 약 20.1초 |
| 브라우저 HTTP status | 200: 255, 201: 2, 304: 273, 401: 1 |
| `GET /api/rooms` 증가량 | 2회 |
| `GET /api/rooms` 서버 처리 시간 증가량 | 약 31.99ms |
| `GET /api/rooms` 서버 평균 | 약 16.00ms |
| Mongo query 증가량 | 493회 |
| Mongo current connections | 9 |

- HTTP 401 한 건은 기존 `failedLoginScenario`가 의도한 응답이다.
- Mongo query 493회는 인증·채팅·파일·프로필을 모두 포함한 값이므로 방 목록 호출 수로 해석하지 않는다.
- 방 목록의 사용자 개별 조회 N+1은 코드에서 확정되므로, 첫 D4 변경은 사용자 ID를 모아 한 번에 조회하는 것으로 제한한다.

## D4-1 사용자 조회 N+1 개선

`RoomService.getAllRooms()`가 모든 방의 creator/participant ID를 먼저 모은 뒤 `findAllById()`로 한 번에 조회하도록 변경했다.

```text
개선 전 userQueries = rooms × (participantsPerRoom + creator 1)
개선 후 userQueries = 1

개선 전 totalRepositoryCalls = 1 + rooms × (participantsPerRoom + 2)
개선 후 totalRepositoryCalls = 2 + rooms
```

최근 메시지 count는 방마다 한 번씩 유지했으며 D5 소유 영역은 수정하지 않았다. API URL과 응답 구조도 변경하지 않았다.

### 검증 결과

- 기존 백엔드 테스트: 210 passed, 0 failed, 0 errors, 8 skipped
- 변경 후 동일 Artillery 재실행: 방 생성과 대량 메시지까지 진행 후 기존 파일 업로드 시나리오에서 인증이 풀려 실패
- 실패 내용: `/chat/{roomId}`를 기대했지만 로그인 화면(`/`)으로 이동
- 부분 실행의 `GET /api/rooms`: 2회, 서버 처리 시간 합계 약 76.07ms
- 서버 재시작과 시나리오 중단 때문에 이 값은 개선 전 약 31.99ms와 유효한 성능 비교값으로 사용하지 않는다.
- 다음 비교는 동일한 데이터셋에서 기존 전체 시나리오가 완주한 실행끼리 최소 3회 수행한다.

### 로컬 API 단순 비교

동일 DB와 동일 계정으로 워밍업 3회 후 `GET /api/rooms`를 순차 30회 호출했다. 부하 한계 측정이 아닌 빠른 방향성 확인 결과다.

| 버전 | 성공 | 평균 | p90 | 최대 |
|---|---:|---:|---:|---:|
| 기존 개별 사용자 조회 | 30/30 | 22.90ms | 30.97ms | 37.90ms |
| 사용자 bulk 조회 | 30/30 | 21.89ms | 26.24ms | 32.46ms |

- 평균 약 4.4%, p90 약 15.3%, 최대 약 14.4% 감소했다.
- 현재 데이터 규모와 순차 요청에서는 차이가 작다. 방과 참가자가 증가할수록 사용자 조회 횟수 차이가 커지는 구조적 개선이다.

## 기존 시나리오로 측정할 항목

별도 테스트를 만들지 않고 현재 부하테스트 시나리오에서 다음 값만 추가로 기록한다.

| 항목 | 개선 전 | 개선 후 |
|---|---:|---:|
| `GET /api/rooms` 요청 수 |  |  |
| 성공률 및 HTTP 오류율 |  |  |
| 평균 응답 시간 |  |  |
| p90 |  |  |
| p95 |  |  |
| p99 |  |  |
| 실제 처리량 |  |  |
| Mongo find/count 횟수 |  |  |
| Mongo query 실행 시간 |  |  |
| Mongo connection pool wait |  |  |
| Tomcat busy threads |  |  |
| CPU/Memory/GC |  |  |

조건은 동일하게 유지한다.

- 동일한 API 시나리오와 URL
- 동일한 방·참가자 데이터
- 동일한 RPS, VU, 실행 시간
- 동일한 서버 자원과 JVM 옵션
- warm-up 후 측정
- 가능하면 각 조건 3회 반복

## 다음 작업 진입 조건

1. 기존 시나리오에서 방 목록 endpoint의 개선 전 수치를 확보한다.
2. MongoDB에서 실제 find/count 횟수 또는 slow query를 확인한다.
3. N+1이 수치로 확인되면 mapper 정리와 user bulk loading을 각각 별도 변경한다.
4. 각 변경 후 같은 시나리오를 다시 실행한다.
5. 최근 메시지 count aggregation은 D5와 `MessageRepository` 소유권을 합의하기 전에는 수정하지 않는다.
