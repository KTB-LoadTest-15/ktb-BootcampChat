# D2 MongoDB 핵심 쿼리 인덱스 검증

## 목적

메시지 히스토리·최근 메시지 count와 방 최신순 조회의 실제 MongoDB 실행계획을 확인하고, 조회 형태에 맞는 최소 인덱스를 적용해 전체 컬렉션 스캔과 메모리 정렬을 제거한다.

이번 단계에서는 인덱스만 검증한다. Tomcat thread 수, Mongo connection pool과 timeout 적정값 비교는 동일 부하 조건과 포화 지표를 준비한 뒤 D2 후속 단계에서 진행한다.

## 대상 쿼리

### 메시지 히스토리 조회

채팅방 입장 및 이전 메시지 로딩에서 특정 시점 이전 메시지를 최신순으로 최대 30개 조회한다.

```javascript
db.messages.find({
  room: roomId,
  timestamp: { $lt: before }
}).sort({ timestamp: -1 }).limit(30)
```

애플리케이션 경로:

```text
MessageLoader
→ MessageStore.findMessagesBefore()
→ MongoMessageStore.findMessagesBefore()
→ MessageRepository.findByRoomIdAndTimestampBefore()
```

### 최근 메시지 count

방 목록의 최근 30분 메시지 수와 방 활동 알림에서 방·시간 범위 조건으로 메시지 수를 계산한다.

```javascript
db.messages.aggregate([
  { $match: { room: roomId, timestamp: { $gte: since } } },
  { $count: "count" }
])
```

애플리케이션 경로:

```text
RecentMessageCounter
→ MessageStore.countRecentMessages()
→ MongoMessageStore.countRecentMessages()
→ MessageRepository.countRecentMessagesByRoomId()
```

### 방 최신순 조회

방 목록과 가장 최근 방 조회에서 `createdAt DESC` 정렬을 사용한다.

```javascript
db.rooms.find({}).sort({ createdAt: -1 })
db.rooms.find({}).sort({ createdAt: -1 }).limit(1)
```

## 문제

측정 전 `messages`와 `rooms` 컬렉션에는 기본 `_id` 인덱스만 존재했다.

- 메시지 히스토리는 모든 메시지를 검사한 뒤 메모리에서 정렬했다.
- 최근 메시지 count는 방·시간 조건에 맞는 문서를 찾기 위해 모든 메시지를 검사했다.
- 최신 방 1개만 필요한 경우에도 모든 방을 읽고 정렬했다.
- 데이터가 늘어날수록 대상 방의 메시지 수가 아니라 전체 컬렉션 크기에 비례해 읽기 비용이 증가한다.

## 원인

쿼리의 동등 조건과 범위·정렬 조건을 함께 지원하는 인덱스가 없었다.

```text
메시지 조건: room = 값, timestamp 범위, timestamp DESC
방 조건: createdAt DESC
```

MongoDB는 적절한 인덱스가 없으면 `COLLSCAN`으로 전체 문서를 검사하고, 정렬 요청은 별도 `SORT` 단계에서 처리한다.

## 확인 방법

로컬 개발 MongoDB의 기존 데이터를 유지한 상태에서 `explain("executionStats")`를 실행했다.

측정 조건:

| 항목 | 값 |
|---|---:|
| 기준 커밋 | `5c0a31b` |
| MongoDB | 8.3.4 |
| DB | `bootcamp-chat` |
| 메시지 문서 | 1,132개 |
| 방 문서 | 65개 |
| 대표 방 메시지 | 118개 |
| 히스토리 limit | 30개 |
| 최근 메시지 범위 | 대표 시각 기준 30분 |

현재 데이터가 작아 실행시간은 캐시·시스템 상태에 따라 0~수 ms 단위로 흔들릴 수 있다. 따라서 실행시간보다 실행 단계와 `totalDocsExamined`, `totalKeysExamined`를 우선 근거로 사용한다.

## 개선 전 결과

| 쿼리 | 실행 단계 | 반환/집계 대상 | Keys examined | Docs examined | 실행시간 |
|---|---|---:|---:|---:|---:|
| 메시지 히스토리 30개 | `COLLSCAN → SORT → LIMIT` | 30 | 0 | 1,132 | 6ms |
| 최근 메시지 count | `COLLSCAN → GROUP` | 38개 count | 0 | 1,132 | 12ms |
| 전체 방 최신순 | `COLLSCAN → SORT` | 65 | 0 | 65 | 0ms |
| 최신 방 1개 | `COLLSCAN → SORT → LIMIT` | 1 | 0 | 65 | 0ms |

메시지 히스토리는 대표 방에 메시지가 118개뿐이어도 전체 컬렉션 1,132개를 검사했다. 최근 count 역시 실제 조건에 맞는 메시지는 38개지만 1,132개를 모두 읽었다.

## 개선 방법

### 메시지 복합 인덱스

```javascript
{ room: 1, timestamp: -1 }
```

```java
@CompoundIndex(
    name = "messages_room_timestamp_desc",
    def = "{'room': 1, 'timestamp': -1}"
)
```

선택 근거:

- 동등 조건인 `room`을 첫 필드로 둔다.
- 범위 및 최신순 정렬에 사용하는 `timestamp`를 두 번째 필드로 둔다.
- 히스토리 조회와 최근 count가 같은 인덱스를 공유한다.
- 히스토리 정렬 방향과 인덱스 순서를 맞춰 별도 메모리 정렬을 제거한다.

### 방 생성일 인덱스

```javascript
{ createdAt: -1 }
```

```java
@Indexed(name = "rooms_created_at_desc", direction = IndexDirection.DESCENDING)
```

선택 근거:

- 방 목록의 최신순 정렬을 인덱스 순서대로 읽을 수 있다.
- 가장 최근 방 1개 조회는 인덱스 첫 항목만 확인하고 종료할 수 있다.
- D4에서는 측정 근거 없이 추가하지 않기 위해 제외했지만, D2 `explain()`에서 `COLLSCAN + SORT`가 확인돼 다시 적용한다.

`spring.data.mongodb.auto-index-creation=true` 설정을 사용하므로 애플리케이션 시작 시 annotation 기반 인덱스가 생성된다. 로컬 전후 측정에서는 동일한 이름과 정의로 인덱스를 직접 생성한 뒤 실행계획을 확인했다.

## 개선 후 결과

| 쿼리 | 실행 단계 | 반환/집계 대상 | Keys examined | Docs examined | 실행시간 |
|---|---|---:|---:|---:|---:|
| 메시지 히스토리 30개 | `IXSCAN → FETCH → LIMIT` | 30 | 30 | 30 | 2ms |
| 최근 메시지 count | `COUNT_SCAN → GROUP` | 38개 count | 38 | 0 | 1ms |
| 전체 방 최신순 | `IXSCAN → FETCH` | 65 | 65 | 65 | 1ms |
| 최신 방 1개 | `IXSCAN → FETCH → LIMIT` | 1 | 1 | 1 | 0ms |

### 스캔량 변화

| 쿼리 | 개선 전 Docs examined | 개선 후 Docs examined | 변화 |
|---|---:|---:|---:|
| 메시지 히스토리 | 1,132 | 30 | 약 97.3% 감소 |
| 최근 메시지 count | 1,132 | 0 | 문서 fetch 제거 |
| 최신 방 1개 | 65 | 1 | 약 98.5% 감소 |

전체 방 목록은 응답 자체가 방 65개를 모두 필요로 하므로 문서 조회 수는 줄지 않는다. 대신 별도 `SORT` 단계가 제거되고 인덱스 순서대로 조회한다. 따라서 전체 목록에 대한 인덱스 효과를 “문서 수 감소”로 과장하지 않는다.

## 기대 효과

- 히스토리 조회 비용이 전체 메시지 수가 아니라 요청 limit과 해당 방 범위에 가까워짐
- 최근 메시지 count에서 메시지 본문 문서 fetch 제거
- 메시지 증가 시 채팅방 입장·히스토리 p95/p99 악화 완화
- 최신 방 조회가 전체 방 수와 무관하게 인덱스 첫 항목에서 종료
- MongoDB 메모리 정렬과 CPU 사용량 감소

## 트레이드오프

- 메시지·방 생성 시 인덱스도 갱신되므로 쓰기 비용이 소폭 증가한다.
- 인덱스가 디스크와 메모리를 추가 사용한다.
- 전체 방 목록은 모든 방 DTO를 반환하므로 인덱스만으로 payload와 전체 문서 materialization 비용은 줄지 않는다.
- 현재 데이터가 작아 절대 실행시간 차이는 작으며, 실제 효과는 동일 부하의 p95/p99와 Mongo CPU로 추가 확인해야 한다.

## 검증 결과

```bash
cd apps/backend
./mvnw -DskipTests compile
```

- 컴파일 성공
- API URL, 요청·응답 구조 변경 없음
- 저장 문서 구조 변경 없음
- 로컬 MongoDB에서 두 인덱스 생성 및 `IXSCAN`/`COUNT_SCAN` 사용 확인

## 남은 작업

1. 기존 E2E 시나리오를 수정하지 않고 채팅방 생성·입장·메시지 흐름 확인
2. 동일 메시지 데이터와 Artillery 조건으로 인덱스 전후 p95/p99·Mongo CPU 비교
3. 인덱스 크기와 메시지 insert 처리량 영향 확인
4. Tomcat thread `10/20/40` 비교
5. Mongo connection pool·finite timeout 적정값 검증

## 런타임 설정 비교 방법

측정 때마다 `application.properties`를 수정하지 않도록 Tomcat 설정을 환경변수로 주입할 수 있게 한다. 환경변수를 주지 않으면 기존과 동일한 `10/1/10/50`을 사용하므로 이 변경 자체는 런타임 동작을 바꾸지 않는다.

| 환경변수 | Spring 설정 | 기본값 |
|---|---|---:|
| `TOMCAT_THREADS_MAX` | `server.tomcat.threads.max` | 10 |
| `TOMCAT_THREADS_MIN_SPARE` | `server.tomcat.threads.min-spare` | 1 |
| `TOMCAT_ACCEPT_COUNT` | `server.tomcat.accept-count` | 10 |
| `TOMCAT_MAX_CONNECTIONS` | `server.tomcat.max-connections` | 50 |

Tomcat thread 비교 예시:

```bash
TOMCAT_THREADS_MAX=10 make dev
TOMCAT_THREADS_MAX=20 make dev
TOMCAT_THREADS_MAX=40 make dev
```

각 실행은 동일한 VU·duration·데이터 조건에서 따로 수행하고 p99, 오류율, Tomcat busy thread, CPU를 기록한다. thread 수가 많을수록 좋다고 가정하지 않고, p99 개선이 멈추거나 CPU·context switching이 증가하는 지점 전의 값을 후보로 선정한다.

MongoDB pool과 timeout은 이미 전체 connection string을 받는 `MONGO_URI`로 주입할 수 있으므로 별도 Java 설정 클래스를 추가하지 않는다.

```bash
MONGO_URI='mongodb://localhost:27017/bootcamp-chat?maxPoolSize=10&minPoolSize=0&waitQueueTimeoutMS=5000&connectTimeoutMS=10000&serverSelectionTimeoutMS=10000&socketTimeoutMS=10000' make dev
```

위 숫자는 최종 추천값이 아니라 **finite timeout과 pool 비교를 위한 실험 예시**다. 최종값은 pool wait time, timeout 오류율, MongoDB connection 사용량과 API p99를 함께 비교한 뒤 선정한다. 비밀번호가 포함된 실제 URI는 문서·커밋·테스트 결과에 남기지 않는다.

### 30 VU 브라우저 부하 1차 비교

Artillery + Playwright 통합 시나리오를 30 VU로 실행해 기본 10 threads와 thread 증가 설정을 비교했다.

| 항목 | 기본 10 threads | thread 증가 실험 |
|---|---:|---:|
| 완료 VU | 20/30 | 20/30 |
| 실패 VU | 10/30 | 10/30 |
| `/chat` TTFB 평균 | 232.9ms | 336.5ms |
| `/chat` TTFB p95 | 1,153.1ms | 788.5ms |

p95는 일부 개선됐지만 평균은 악화됐고, 완료·실패 VU는 변하지 않았다. 실패도 대부분 백엔드 API 응답 코드가 아니라 로그인 페이지의 `page.goto` 또는 `locator.fill` 30초 timeout에서 발생했다.

따라서 현재 결과만으로 Tomcat thread 10개를 30 VU 실패의 주요 병목으로 판단할 수 없다. Next.js 개발 서버와 로컬 Chromium 부하 생성기의 CPU·memory 포화가 함께 영향을 줄 수 있으므로 기본값 10을 유지하고, 최종 thread 결정은 백엔드 API 단독 부하에서 busy thread·queue·p99를 같이 측정한 뒤 수행한다.

## 완료 조건

- 핵심 쿼리에서 `COLLSCAN`과 불필요한 `SORT` 제거
- 인덱스 전후 `executionStats`를 동일 데이터로 비교
- 기존 E2E 계약 유지
- thread/pool 값을 임의로 키우지 않고 포화 지표와 p99를 근거로 최종 설정 선택
