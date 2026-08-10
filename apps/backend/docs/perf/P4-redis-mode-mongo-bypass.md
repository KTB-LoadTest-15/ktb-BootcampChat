# P4 — Redis 모드에서 메시지 hot path의 Mongo 우회 실측 + Redis 기록 확인

> 단계: [REDIS_MESSAGE_STORE_PLAN.md](../REDIS_MESSAGE_STORE_PLAN.md) P4 (부분 — flush 부하 검증은 P3 이후)
> 측정: `perf/RedisModeMongoBypassIntegrationTest` (Testcontainers Mongo + Redis, message.store=redis)

## 1. Mongo 명령 = 0 (실측)

`message.store=redis`에서 메시지 hot path(send 3 + findMessagesBefore + countRecent + addReader + findById)를 실행하고, 드라이버 CommandListener로 Mongo 명령을 셌다.

```
[redis-p4] mongo_commands={} total=0 | loaded=3 recent=3
```

**Mongo 데이터 명령 0회.** P0 baseline과 비교:

| Operation | Mongo 모드 (P0 baseline) | Redis 모드 (P4) |
|---|---:|---:|
| send | insert 1 | **0** |
| history 30 | find 1 + aggregate 1 | **0** |
| recentCount | aggregate 1 | **0** |
| findById | find 1 | **0** |
| readStatus | update 1 | **0** |

hot path의 모든 메시지 Mongo I/O가 Redis로 이동했다. (영속화는 P3 배치 flusher가 비동기로 담당 예정.)

## 2. Redis 기록 확인 (원시 덤프)

메시지 2건 저장 + user-A 읽음 처리 후 실제 Redis 내용:

```
[keys] [chat:messages, chat:room:{roomId}:msgIdx]

[zset] chat:room:{roomId}:msgIdx           # 방별 순서 (score=timestamp millis)
   score=1786356477140  member=6a79a42987dcf40fc0d5080c
   score=1786356478140  member=6a79a42987dcf40fc0d5080d

[hash] chat:messages (id -> JSON)          # 본문
   6a79...080c -> {"id":"6a79...080c","roomId":"...","content":"첫 메시지","type":"text",
                   "timestamp":"2026-08-10T10:07:57.140...","reactions":{},
                   "readers":[{"userId":"user-A","readAt":"..."}],"metadata":{}}
   6a79...080d -> {"id":"6a79...080d",...,"content":"둘째 메시지","readers":[],...}
```

확인 사항:
- ZSet `chat:room:{roomId}:msgIdx` — id를 timestamp(millis) score로 정렬 저장 → 순서·페이지네이션·최근수의 근거
- Hash `chat:messages` — 본문 JSON. `readers:[{userId:user-A}]`가 addReader 후 실제 반영됨
- id는 앱측 ObjectId(P1에서 선생성)

## 3. 남은 것

- **P3 배치 flusher**: Redis→Mongo 비동기 영속화. flush가 부하에서 병목이 되는지 검증 → 병목+DU 미달 시 flusher off(Redis-only 최후안).
- 실제 부하(DU) 상한 측정은 `loadtest/`·`e2e/artillery/`로 별도.

## 검증

`perf/RedisModeMongoBypassIntegrationTest` 2/2 통과 (Mongo=0 단언 + Redis 기록 존재 단언).
