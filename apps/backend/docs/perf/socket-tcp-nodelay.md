# 소켓 TCP_NODELAY 활성화 (Nagle 비활성화)

> 도메인: D5 (Socket.IO 전송)
> 대상: `SocketIOConfig`의 `SocketConfig.setTcpNoDelay`
> 성격: 소형·빈번 프레임의 전송 지연 제거(설정 변경).

## 1. 문제

`SocketConfig.setTcpNoDelay(false)`였다(`SocketIOConfig`). `false`는 Nagle 알고리즘을 켜서 **작은 패킷을 잠시 모아 한 번에** 보낸다. 채팅은 메시지·타이핑·읽음·리액션 등 **소형 프레임이 매우 빈번한** 워크로드라, Nagle이 delayed-ACK와 맞물리면 프레임당 최대 수십 ms의 전송 지연을 유발할 수 있다. 실시간 체감(메시지 왕복 지연)에 불리하다.

## 2. 개선

`setTcpNoDelay(true)`로 바꿔 Nagle을 끈다. 소형 프레임이 모이길 기다리지 않고 즉시 전송한다.

- 트레이드오프: 작은 세그먼트가 늘어 대역폭·패킷 수가 소폭 증가할 수 있으나, 채팅의 실시간성이 이 비용보다 우선한다(실시간 채팅 서버의 일반적 선택).
- 서버 전용 변경 — 클라이언트 프로토콜/이벤트 계약 불변.

## 3. 검증

- 이 플래그는 `socketio.enabled=true`일 때 생성되는 `SocketIOServer` 빈 내부에 적용된다. 현 테스트 스위트는 실제 Netty 서버를 포트 바인딩하지 않으므로(대부분 `socketio.enabled=false`) 값 자체를 단언하는 테스트는 없다.
- 전체 스위트 green(회귀 없음)으로 변경이 다른 경로를 깨지 않음을 확인.

## 4. 파일별 변경

- `config/SocketIOConfig.java` — `setTcpNoDelay(false)` → `true`. 이유(소형 빈번 프레임 Nagle 지연) 주석 명시.

## 5. 남은 여지 (측정 게이트)

- `acceptBackLog=10`, `tcpSendBufferSize=4096`, `tcpReceiveBufferSize=4096`는 작다. 재접속 폭주/대량 동접에서 backlog가 작으면 연결이 드롭될 수 있다. 다만 이는 **용량 튜닝**이라 감사 원칙(측정 없이 상향 금지)에 따라 부하 실측 후 조정한다.
