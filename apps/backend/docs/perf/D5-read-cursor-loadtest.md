# D5 — 읽음 처리(read cursor + A4) 실부하 A/B 검증 (loadtest)

> 목적: 마이크로 실측(findAndModify 1회, coalescing N→1)에 더해, **실제 동시접속 부하의 종단 지표**로
> 읽음 재설계 효과를 확인한다.
> 방법: baseline(읽음 재설계 직전 `05837a9`, 이벤트 루프 오프로드는 이미 포함)과 HEAD(커서+A4+dispatch fix)를
> 각각 빌드해 동일 env로 실행. `loadtest/load-test.js`로 동일 시나리오. **읽음 payload가 버전마다 달라
> 클라이언트를 버전에 맞춰 사용**(baseline=`{messageIds}`, HEAD=`{roomId,lastReadMessageId}`).

## 1. 방법론

- **런타임(양쪽 동일)**: 로컬 Mongo/Redis(docker), JDK25, `MESSAGE_STORE=mongo`, flush off, 시크릿 고정.
- **시나리오**: `load-test.js`는 **단일 방에 N명**을 넣고, 각 유저가 **수신하는 모든 메시지를 읽음 처리**한다.
  즉 메시지 1건 → 방 전원이 읽음 emit = **읽음 N² 팬아웃 시나리오**(이번 개선의 정확한 표적).
- **지표**: (1) **Mongo opcounter 델타**(server 권위, 읽음당 wire 명령 수) (2) `readAcksReceived`(client, 수신 broadcast 이벤트 수) (3) `socketio_messages_total`/`dispatch_rejected`(server actuator, 처리량/포화).
- **정규화**: 실행마다 처리된 읽음 수가 달라, 절대값 대신 **읽음 1건당 비율**로 비교한다.

## 2. 결과 (읽음 1건당 정규화)

| 티어 | 지표 | BASELINE | HEAD | 변화 |
|---|---|---:|---:|---|
| **light (50)** | Mongo ops / read | 4.29 | 2.24 | **−48%** |
| | broadcast acks / read | 32.9 | 0.31 | **−99%** |
| | 메시지 처리시간 합 | 3.43s | 2.65s | −23% |
| | 에러 | 0 | 0 | — |
| **medium (200)** | Mongo ops / read | 4.53 | 2.33 | **−49%** |
| | broadcast acks / read | 6.14* | 0.11 | — |
| | **서버 메시지 처리량** | 2760 / 4000 | **3914 / 4000** | **+42%** |
| | dispatch_rejected | 905 | **0** | 제거 |
| **heavy (1000)** | Mongo ops / read | 5.52 | 2.60 | **−53%** |
| | broadcast acks / read | 6.30* | 0.10 | — |
| | 처리된 읽음 수 | 49,529 | **154,058** | 3.1× |

\* baseline의 medium/heavy는 **이미 포화**되어 메시지·읽음이 셰딩됐다(뒤 broadcast가 덜 나가 acks/read가 실제 팬아웃보다 낮게 찍힘). light(미포화)의 32.9가 방 크기(≈50)에 해당하는 실제 팬아웃이다.

**메커니즘 일치**:
- **Mongo ops/read ~50% 감소**(전 티어 일관): 구식 = 읽음마다 `findById(message)+user+room` 조회 3 + `updateReadersForMessages` 갱신 1 = **4 ops**. 커서 = `findById`(서버 ts·검증) 1 + `findAndModify`(upsert+$max) 1 = **2 ops**. 설계대로 절반.
- **broadcast acks/read 30~100× 감소**: A4 coalescing이 방 단위 창으로 묶어 창당 1회 방출 → 수신 이벤트가 read당 32.9→0.31(light).
- **medium에서 포화 방지**: baseline은 200명 단일 방에서 읽음 팬아웃(읽음마다 4 Mongo ops + 방 전체 broadcast)이 메시지 디스패처/Mongo를 압박해 **메시지 처리량이 2760/4000으로 붕괴(dispatch_rejected 905)**. HEAD는 lean 읽음 경로로 서버를 건강하게 유지해 **3914/4000 처리(+42%), rejected 0**.

## 3. 정직한 단서와 한계

- **heavy는 연결 폭주에 지배됨**: 1000 소켓 단일 머신 램프업에서 **양쪽 모두 평균 연결 ~16–17s, 연결 에러 ~500–600**. 이는 별개의 이벤트 루프/Tomcat 연결 establish 한계(오프로드는 이미 baseline에도 포함)로, **읽음 개선이 완화하지도 악화하지도 않는다**. 읽음 이득은 연결 지표가 아니라 **DB ops/read·팬아웃·읽음 처리 용량**에서 나타난다(HEAD가 3.1× 더 많은 읽음을 처리). 연결 establish는 워커 레인/Tomcat threads 튜닝(별도 측정 과제) 소관.
- **측정 중 발견·수정한 회귀**: 읽음 dispatch key를 roomId로 통일했더니 인기 방의 모든 읽음이 한 레인에 몰려 medium에서 **43k 읽음이 큐 포화로 셰딩**(에러 43,488). 세션 키로 복원(커밋 `0e44586`) 후 medium 에러 0. 위 HEAD 수치는 수정본 기준.
- **사전 존재 버그(양쪽 공통, 미수정)**: `MessageLoader`가 이전 메시지 로드 시 시스템 메시지(senderId=null)를 immutable map에서 조회하다 `NullPointerException`(baseline·HEAD 각 ~80건). A/B에 동일 영향이라 비교엔 무해하나 별도 수정 필요.
- 단일 머신·부하 클라이언트도 단일 node 프로세스. 절대값은 프로덕션 급 아님. **동일 클라(버전별)로 두 빌드를 재므로 delta·비율은 유의미**. 1회 측정.

## 4. 결론

마이크로 실측(findAndModify 1회, coalescing 단위테스트)에 더해, 실부하에서 읽음 재설계가 **읽음당 Mongo 명령을 ~50% 줄이고 broadcast 팬아웃을 30~100× 낮춰**, 단일 방 고밀도 읽음 시나리오에서 **포화 지점을 뒤로 밀었다**(medium: baseline 붕괴 2760/4000 vs HEAD 3914/4000, +42%). 극한(heavy)은 연결 establish가 병목이라 읽음 이득이 종단 연결 지표엔 안 잡히며, 이는 워커/Tomcat 튜닝의 몫이다.
