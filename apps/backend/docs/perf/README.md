# 성능 개선 결과 (측정 기반)

각 개선은 **추정이 아니라 실측**으로 증명한다. 개선 1건 = 이 폴더의 결과 md 1개.

## 워크플로우

1. **수정 전** — 실제로 쿼리를 날려 나가는 Mongo 명령 수를 측정(baseline).
2. **수정 후** — 같은 시나리오로 다시 측정.
3. **동작 동치성 검증** — 같은 테스트에서 OLD·NEW가 동일한 결과(최종 상태)를 만드는지 단언. 쿼리 감소가 기능을 깨지 않았음을 함께 증명한다.
4. 결과 md에 시나리오·before·after·개선율·동작 검증·근거(공식문서)를 기록.
5. **파일별 변경 설명** — 변경/신규 파일 각각이 무엇을 위해·어떻게 바뀌었는지 파일 단위로 설명(diff 없이 이해 가능하게).
6. **커밋 제안** — 커밋할 파일 목록과 추천 커밋명(conventional commits, 논리 단위 분리)을 제시. 실제 커밋은 승인 후.

## 측정 도구

MongoDB 드라이버 레벨 `CommandListener`로 wire 명령을 카운트한다(heartbeat 제외).

- `src/test/java/com/ktb/chatapp/perf/CommandCountingListener.java` — 명령 카운터
- `src/test/java/com/ktb/chatapp/perf/MongoCommandCounterConfig.java` — 자동 구성 MongoClient에 리스너 연결
- 각 `*QueryCountIntegrationTest` — before/after를 실측·단언(회귀 가드 겸용)

실행 예:
```bash
mvn -Dtest='ReadStatusQueryCountIntegrationTest' test
```

## 결과 목록

| 문제 | 시나리오 | Before | After | 개선 | 문서 |
|---|---|---:|---:|---|---|
| P0-4 | 30개 메시지 읽음 처리 | 60 (find30+update30) | 1 (update) | −98.3%, O(N)→O(1) | [P0-4-read-status-bulk-update.md](P0-4-read-status-bulk-update.md) |
| P0-5 | 메시지당 세션 touch | 4 (find2+update2) | 2 (find1+update1) | −50%, write 2→1 | [P0-5-duplicate-session-touch.md](P0-5-duplicate-session-touch.md) |
| P0-4/P1-4 | 발신자·참가자 30명 유저 조회 | 30 (find30) | 1 (find, $in) | −96.7%, O(N)→O(1) | [P0-4-P1-4-user-bulk-load.md](P0-4-P1-4-user-bulk-load.md) |
| P1-3 | 리액션 추가 (Mongo) | 2 (find+update) | 1 (findAndModify) | −50% + 동시성 lost update 제거 | [P1-3-reaction-atomic.md](P1-3-reaction-atomic.md) |
| P1-5 | 메시지당 방활성도 브로드캐스트 | 동기(event-loop 점유) | @Async 오프로드 | event-loop 점유 제거(정성적) | [P1-5-async-room-activity-broadcast.md](P1-5-async-room-activity-broadcast.md) |

## Baseline (전환 전 기준선)

| 이니셔티브 | 내용 | 문서 |
|---|---|---|
| Redis 메시지 저장 | 메시지 컬렉션 operation별 Mongo 명령 footprint (send/history/recent/reaction/read) | [P0-redis-baseline.md](P0-redis-baseline.md) |
