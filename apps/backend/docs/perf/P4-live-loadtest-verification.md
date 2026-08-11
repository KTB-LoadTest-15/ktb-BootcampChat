# P4 (라이브) — 실부하로 Redis write-behind 파이프라인 검증

> 단계: [REDIS_MESSAGE_STORE_PLAN.md](../REDIS_MESSAGE_STORE_PLAN.md) P4
> 일자: 2026-08-10
> 환경: 로컬. 앱 = JAR(JDK 25), 인프라 = docker-compose(mongo-ktb:27017, redis-ktb:6379)
> 플래그: `message.store=redis`, `message.flush.enabled=true`, `message.flush.interval-ms=5000`
> 부하: `loadtest/` `pnpm run test:light` (50 users, 20 msgs/user, batch 10/1s)

## 1. 로드 결과

| 지표 | 값 |
|---|---|
| Users Connected | 50 |
| Messages Sent | 1000 |
| Messages Received | ~43,000 |
| Reaction Updates Received | ~146,000 |
| Messages/sec (송신율) | ~22 |
| p95 / p99 message latency | ~1ms |
| **Total Errors** | **0** (Auth/Connection/Message 모두 0) |

## 2. 저장소 상태 (실측)

부하 종료 후:

| 확인 | 명령 | 값 | 의미 |
|---|---|---|---|
| Redis 키 | `KEYS 'chat:*'` | `chat:messages`, `chat:room:{id}:msgIdx`, `chat:flushPending` | 설계한 3개 자료구조 그대로 생성 |
| Redis 메시지 수 | `HLEN chat:messages` | **1100** | 전송 1000 + 입퇴장 시스템 메시지 ~100 |
| flush 대기 큐 | `SCARD chat:flushPending` | **0** | flusher가 SPOP으로 전부 Mongo에 넘김 |
| Mongo 메시지 수 | `db.messages.countDocuments()` | 1100 → **2200** | flush로 1100건 영속화(증가). 2200은 이전 실행분 누적 포함 |

> 참고: Mongo count가 Redis HLEN보다 큰 것은 `mongo_data` 볼륨에 이전 실행 데이터가 누적됐기 때문이며 double-write가 아니다. flusher는 id 기준 `saveAll`(upsert=replaceOne)이라 같은 메시지를 재-flush해도 문서가 중복 생성되지 않는다. 정확한 1:1은 `messages.deleteMany({})` + `redis flushdb` 후 단일 실행으로 확인.

## 3. 검증된 것

```
부하 → RedisMessageStore(chat:messages Hash + msgIdx ZSet) → chat:flushPending(SADD)
     → RedisToMongoFlusher(@Scheduled 5s, SPOP batch) → Mongo saveAll(upsert) → flushPending=0
```

- ✅ **hot path가 Redis로** — 메시지 저장/순서/대기 큐가 Redis에 실제 적재
- ✅ **5초 배치 flush로 Mongo 영속화** — flushPending 0 + Mongo count 증가로 확인
- ✅ **에러 0** — 계약(로그인·소켓·메시지·리액션) 정상 동작

## 4. 최후안(Redis-only) 확인 방법

앱을 `MESSAGE_FLUSH_ENABLED=false`로 재시작하면: Redis엔 계속 쌓이지만 `chat:flushPending`이 비워지지 않고 Mongo `countDocuments()`는 증가하지 않는다(스케줄러 자체가 뜨지 않음) = Redis-only 최후안.

## 5. 한계 / 다음

- test:light(50u)는 서버 한계를 밀지 않는다. DU 상한·flush 병목은 `test:medium/heavy`, `ramp-up`으로 상향하며 `SCARD chat:flushPending` 증가 여부로 관측한다(부하 체크리스트: 메모리 `redis-writebehind-loadtest-checklist`).
- 지연 지표(p99 ~1ms)는 스크립트측 계측이라 서버 처리시간과 별개다.
