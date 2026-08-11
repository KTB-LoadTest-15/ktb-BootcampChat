# 프론트엔드 성능 병목 분석

분석 결과, 가장 먼저 문제가 될 가능성이 높은 자원은 **브라우저 메인 스레드 CPU와 React 렌더 횟수**입니다. 특히 읽음 이벤트가 참가자 수만큼 증폭되고, 이벤트 하나마다 전체 메시지 배열을 순회·렌더링하는 구조가 결합되어 있습니다. 장시간 사용에서는 그다음으로 DOM 크기와 메모리가 누적될 가능성이 높습니다.

아래 심각도는 런타임 실측값이 아니라 코드 경로와 계산 복잡도를 기준으로 분류했습니다.

## 1. 프론트엔드 구조

### 라우팅과 Provider

App Router와 Pages Router가 함께 사용됩니다.

- App Router
  - `/chat`: `apps/frontend/app/chat/page.js`
  - `/chat/[room]`: `apps/frontend/app/chat/[room]/page.js`
  - 전역 Provider: `apps/frontend/app/providers.js`
- Pages Router
  - 로그인 `/`: `apps/frontend/pages/index.js`
  - 회원가입 `/register`: `apps/frontend/pages/register.js`
  - 프로필 `/profile`: `apps/frontend/pages/profile.js`
  - 방 생성 `/chat/new`: `apps/frontend/pages/chat/new.js`
  - 별도 Provider 트리: `apps/frontend/pages/_app.js`

각 라우터가 `ThemeProvider → AuthProvider → SocketProvider` 구조를 별도로 가집니다. 한 화면에서 두 Provider 트리가 동시에 실행되는 구조는 아니지만, 두 라우터가 서로 다른 클라이언트 엔트리를 형성합니다.

### 상태 흐름

- 인증 상태: `apps/frontend/contexts/AuthContext.js`의 `AuthProviderWithRouter`
  - `user`, `isLoading`
  - 로그인·로그아웃·프로필·토큰 갱신 함수
- 채팅방 상태: `apps/frontend/features/chat/room/useChatRoomState.js`
  - `room`
  - `messages`
  - `currentUser`
  - 연결·메시지 로딩 상태
- 메시지 ID 보조 상태
  - `processedMessageIds: Set`
  - `previousMessagesRef: Set`
  - 메시지 배열과 별도로 메모리에 유지
- 리액션과 읽음 상태
  - 별도 전역 저장소가 아니라 각 `message` 객체의 `reactions`, `readers`를 갱신
- 방 목록
  - `apps/frontend/features/chat/rooms/useRoomList.js`의 로컬 `rooms` 상태

### Socket 흐름

실제 Socket.IO 인스턴스는 모듈 싱글턴인 `apps/frontend/services/socket.js`의 `SocketService`에서 `io()`로 생성합니다.

흐름은 다음과 같습니다.

`SocketService → lib/socket/socketClient.js → 방 목록/채팅방 훅`

- 방 목록 연결: `features/chat/rooms/useRoomsSocket.js`
- 방 연결·입장·초기 메시지: `features/chat/room/useRoomHandling.js`
- 재연결 수명주기: `features/chat/room/useChatRoomLifecycle.js`
- 이벤트 구독 매핑: `lib/socket/socketClient.js`

`SocketProvider` 자체는 마운트 시 연결하지 않습니다. 온라인 복귀 시 연결을 시도하고 오프라인 시 연결을 끊는 역할만 합니다.

### API 흐름

- 공통 Axios 클라이언트: `apps/frontend/lib/api/client.js`
- 네트워크·5xx·429 등에 최대 2회 자동 재시도
- 방 목록 조회
  - `/api/health`
  - 성공 후 `/api/rooms`
- 방 입장
  - REST `/api/rooms/{id}/join`
  - 방 화면에서 REST `/api/rooms/{id}`
  - 이후 Socket `joinRoom`
- 메시지 조회·전송·읽음·리액션은 Socket 이벤트
- 파일 업로드만 REST `/api/files/upload`

## 2. 성능 병목 후보

### 병목 1 — 메시지별 읽음 처리와 이벤트 증폭

- 위치:
  - `apps/frontend/components/ReadStatus.js`
    - `markMessageAsRead`
    - 메시지별 `IntersectionObserver` 생성 effect
  - `apps/frontend/features/chat/room/roomEventHandlers.js`
    - `onMessagesRead`
    - `applyReadReceipts`
  - 서버 동작 근거:
    - `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageReadHandler.java`
- 현재 동작:
  - 화면에 보이는 메시지마다 개별적으로 `markMessagesAsRead([messageId])`를 보냅니다.
  - 각 `messagesRead` 수신 시 전체 `messages` 배열을 `map`합니다.
  - 서버는 각 읽음 결과를 방 전체에 다시 브로드캐스트합니다.
- 왜 문제가 될 수 있는지:
  - 참가자가 `U`명인 방에서 새 메시지 하나가 보이면 최대 `U`개의 읽음 요청이 발생할 수 있습니다.
  - 각 결과가 다시 `U`명에게 전달되므로 서버→클라이언트 이벤트 전달량은 메시지당 대략 `O(U²)`까지 증가할 수 있습니다.
  - 각 브라우저는 읽음 이벤트마다 메시지 `M`개를 다시 순회하므로 브라우저 비용은 메시지당 `O(U × M)`에 가까워집니다.
- 부하가 커졌을 때 예상되는 현상:
  - 메시지 수신 자체보다 `messagesRead` 이벤트 처리가 더 많아짐
  - 메인 스레드 장기 작업, 입력 지연, 스크롤 끊김
  - 같은 메시지에 대해 짧은 시간 동안 반복되는 React commit
  - Socket 수신 큐 지연
- 심각도: **높음 — 실제 발생 가능성이 높은 구조**
- 실제로 검증해야 할 지표:
  - 채팅 메시지 1개당 `markMessagesAsRead`, `messagesRead` 이벤트 수
  - 참가자 수별 Socket 이벤트/초
  - `messagesRead` 처리 시간과 React commit 시간
  - long task 수, INP, 프레임 드롭
  - 참가자 1/5/20/50명에서 메시지→화면 반영 지연

### 병목 2 — 이벤트 하나가 전체 메시지 목록 리렌더로 확산

- 위치:
  - `apps/frontend/features/chat/room/useReactionHandling.js`
  - `apps/frontend/components/ChatMessages.js`
  - `apps/frontend/features/chat/room/ChatRoomView.js`
  - `apps/frontend/features/chat/room/roomEventHandlers.js`의 `appendIncomingMessage`
- 현재 동작:
  - 새 메시지, 읽음, 리액션 모두 `messages` 배열을 새 배열로 만듭니다.
  - `ChatMessages`는 변경될 때마다 전체 메시지를 다시 정렬합니다.
  - `handleReactionAdd`와 `handleReactionRemove`는 `messages`를 의존하므로 메시지 변경마다 함수 참조가 바뀝니다.
  - 이 함수들이 모든 `UserMessage`와 `FileMessage`에 prop으로 전달됩니다.
  - 결과적으로 자식에 `React.memo`가 있어도 모든 메시지가 변경된 callback prop을 받습니다.
  - `ChatInput`과 `ChatRoomInfo`도 memo 처리되지 않아 `ChatRoomView` 상태 변경마다 다시 렌더됩니다.
- 왜 문제가 될 수 있는지:
  - 새 메시지 하나가 `appendIncomingMessage.some()`의 전체 검색, 전체 정렬, 전체 element 생성, 전체 메시지 컴포넌트 렌더로 이어집니다.
  - 현재 구조는 메시지 이벤트당 최소 `O(M log M)` 작업을 수행합니다.
  - 읽음 이벤트가 증폭되면 이 작업이 매우 자주 반복됩니다.
- 부하가 커졌을 때 예상되는 현상:
  - 처음에는 정상이나 수백~수천 메시지 이후 새 메시지 표시가 점점 느려짐
  - 입력창 타이핑·이모지 버튼 반응 지연
  - 스크롤 애니메이션 끊김
  - React commit 시간이 메시지 수에 비례해 증가
- 심각도: **높음 — 코드상 리렌더 경로가 명확함**
- 실제로 검증해야 할 지표:
  - 메시지 수 30/300/1,000/3,000일 때 이벤트당 React commit 시간
  - 새 메시지 1개 수신 시 `UserMessage` 렌더 횟수
  - `ChatMessages` 정렬 시간
  - main-thread task duration과 dropped frames
  - 입력창 keydown 처리 지연

### 병목 3 — 메시지 누적에 따른 DOM·Observer·이벤트 리스너 증가

- 위치:
  - `apps/frontend/features/chat/room/useChatRoomState.js`의 `messages`, `processedMessageIds`
  - `apps/frontend/components/ChatMessages.js`의 전체 메시지 map
  - `apps/frontend/components/ReadStatus.js`의 메시지별 Observer
  - `apps/frontend/components/CustomAvatar.js`의 persistent 전역 리스너
  - `apps/frontend/components/UserMessage.js`의 persistent avatar 사용
- 현재 동작:
  - 이전 메시지를 30개씩 가져오지만 이미 가져온 메시지를 제거하지 않습니다.
  - 모든 메시지가 DOM과 React state에 남습니다.
  - 각 일반/파일 메시지는 `ReadStatus`를 통해 독립적인 `IntersectionObserver`를 가집니다.
  - 각 일반 메시지의 persistent avatar는 `userProfileUpdate` 전역 리스너를 하나씩 등록합니다.
  - 메시지 객체 외에 ID도 `processedMessageIds`에 다시 저장됩니다.
  - `contentVisibility: auto`가 있지만 이는 주로 화면 밖 paint/layout을 줄일 뿐 DOM, React 인스턴스, state, effect를 제거하지 않습니다.
- 왜 문제가 될 수 있는지:
  - 메시지 `M`개에 대해 DOM, Observer, avatar state·리스너, ID Set이 모두 선형으로 증가합니다.
  - 프로필 업데이트 이벤트가 발생하면 메시지별 avatar 리스너들이 한꺼번에 실행되어 로컬 스토리지도 반복 조회합니다.
- 부하가 커졌을 때 예상되는 현상:
  - 탭 메모리가 계속 증가하고 GC가 자주 발생
  - 히스토리를 많이 펼친 후 스크롤과 React commit 악화
  - 프로필 업데이트 시 순간 CPU 스파이크
  - 장시간 열린 방에서 브라우저가 느려지거나 탭이 종료될 가능성
- 심각도: **높음 — 메시지 누적 시 확실히 증가하는 구조**
- 실제로 검증해야 할 지표:
  - 메시지 수별 DOM node 수
  - `IntersectionObserver` 인스턴스 수
  - `userProfileUpdate` 리스너 실행 횟수
  - JS heap, retained React fiber 수, GC pause
  - 메시지 1,000개 추가 전후 스크롤 FPS

### 병목 4 — 전역 `roomActivity` 이벤트와 방 목록 전체 갱신

- 위치:
  - `apps/frontend/features/chat/rooms/useRoomsSocket.js`의 `roomActivity`
  - `apps/frontend/features/chat/rooms/RoomsTable.js`의 전체 rooms map
  - 서버의 전역 room-list 가입:
    - `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/ConnectionLoginHandler.java`
  - 메시지별 roomActivity 발송:
    - `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/SocketIOEventListener.java`
- 현재 동작:
  - 모든 Socket 사용자가 서버의 `room-list` room에 가입합니다.
  - 어떤 방에서든 메시지가 저장될 때마다 `roomActivity`가 전체 연결 사용자에게 전달됩니다.
  - 방 목록 화면은 이벤트마다 전체 `rooms` 배열을 `map`하고 새 배열을 만듭니다.
  - `RoomsTable`은 memo/가상화 없이 전체 행을 다시 렌더합니다.
  - 채팅방 화면에는 `roomActivity` 핸들러가 없지만 패킷 자체는 수신·파싱됩니다.
- 왜 문제가 될 수 있는지:
  - 전역 메시지 발생률이 `G msg/s`라면 모든 접속 브라우저가 대략 `G`개의 roomActivity 패킷을 받습니다.
  - 시스템 전체 전달량은 연결 사용자 수와 전역 메시지율의 곱으로 증가합니다.
  - 방 목록을 보고 있는 사용자는 이벤트마다 방 수 `R`만큼 배열을 탐색하고 테이블을 렌더합니다.
- 부하가 커졌을 때 예상되는 현상:
  - 현재 참여 중인 방과 무관한 이벤트가 Socket 대역폭과 CPU를 점유
  - 방 목록 화면이 지속적으로 렌더링되어 스크롤과 클릭이 느려짐
  - 방 수가 많을수록 이벤트 처리 시간이 증가
  - 실제 채팅 메시지보다 roomActivity 이벤트가 더 많은 상황
- 심각도: **높음 — 사용자·방·메시지가 함께 증가할 때 핵심 확장성 위험**
- 실제로 검증해야 할 지표:
  - 클라이언트당 `roomActivity` 수신량/초
  - 방 화면에서도 수신되는 전체 Socket frame 수
  - 방 100/1,000/5,000개에서 목록 update 시간
  - `RoomsTable` 렌더 횟수와 commit 시간
  - Socket 수신 바이트/초

### 병목 5 — 이전 메시지 로딩의 반복 전체 정렬·Set 복사

- 위치:
  - `apps/frontend/features/chat/room/useMessageHandling.js`의 `handleLoadMore`
  - `apps/frontend/features/chat/room/roomEventHandlers.js`의 `processLoadedRoomMessages`
  - `apps/frontend/features/chat/messages/useMessageList.js`의 `deriveUniqueSortedMessages`
- 현재 동작:
  - 가장 오래된 메시지를 찾기 위해 현재 메시지 전체를 다시 복사·정렬합니다.
  - 새 페이지를 병합할 때 전체 ID Set을 여러 번 복사합니다.
  - 전체 메시지를 다시 정렬하고 `Map`으로 다시 중복 제거합니다.
  - 초기 `previousMessagesLoaded`는 영구 이벤트 핸들러와 `fetchPreviousMessagesAndWait`의 `once`가 동시에 받아 초기 메시지를 두 번 처리할 수 있습니다. 중복 데이터는 제거되지만 계산과 state dispatch는 반복됩니다.
- 왜 문제가 될 수 있는지:
  - 페이지는 30개씩 증가하지만 매 페이지마다 지금까지 로드한 전체 `M`개를 다시 처리합니다.
  - 히스토리를 끝까지 여는 누적 비용이 단순 선형보다 훨씬 빠르게 증가합니다.
- 부하가 커졌을 때 예상되는 현상:
  - 위로 스크롤할수록 다음 30개를 여는 시간이 계속 길어짐
  - 스크롤 위치 복원 중 프레임 드롭
  - 큰 Set과 임시 배열로 인한 GC 증가
- 심각도: **높음 — 긴 히스토리에서 발생 가능성이 높음**
- 실제로 검증해야 할 지표:
  - 페이지 번호별 `previousMessagesLoaded` 처리 시간
  - 페이지당 임시 heap 증가량
  - 정렬·Set 복사 self time
  - 30개 단위 로딩 후 DOM/heap 증가 곡선
  - 한 응답당 `processMessages` 호출 횟수

### 병목 6 — 참가자 변경이 전체 메시지 렌더로 전파

- 위치:
  - `apps/frontend/features/chat/room/roomEventHandlers.js`의 `onParticipantsUpdate`
  - `apps/frontend/features/chat/room/ChatRoomView.js`
  - `apps/frontend/components/ReadStatus.js`의 `unreadParticipants`
- 현재 동작:
  - 참가자 변경 시 새 `room` 객체를 만듭니다.
  - 이 객체를 모든 메시지에 전달합니다.
  - 각 메시지의 `ReadStatus`는 참가자마다 `readers.some()`을 실행합니다.
  - `MessageActions`도 tooltip 이름을 찾기 위해 참가자 Map을 메시지별로 생성할 수 있습니다.
- 왜 문제가 될 수 있는지:
  - 참가자 입·퇴장 하나가 모든 메시지 렌더를 유발합니다.
  - 메시지 `M`, 참가자 `P`, reader 수 `Q`에 따라 읽음 계산이 대략 `O(M × P × Q)`까지 커질 수 있습니다.
- 부하가 커졌을 때 예상되는 현상:
  - 대규모 방에서 입·퇴장 시 순간 UI 정지
  - 참가자 ramp-up 동안 지속적인 commit
  - 읽음 상태 계산이 flame chart 상단에 등장
- 심각도: **중간 — 참가자 churn이 큰 방에서 높아짐**
- 실제로 검증해야 할 지표:
  - 참가자 수별 join/leave 이벤트 처리 시간
  - 참가자 한 명 추가 시 재렌더된 메시지 수
  - `unreadParticipants` 계산 self time
  - participant update 중 long task와 FPS

### 병목 7 — 방 목록의 중복 health 요청과 polling

- 위치:
  - `apps/frontend/features/chat/rooms/ChatRoomsView.js`의 30초 polling
  - `apps/frontend/features/chat/rooms/useRoomList.js`의 `loadRooms`
  - `apps/frontend/features/chat/rooms/useServerConnection.js`의 `attemptConnection`
  - `apps/frontend/lib/api/errors.js`의 공통 재시도 설정
- 현재 동작:
  - 방 목록 갱신마다 `/api/health` 후 `/api/rooms`를 요청합니다.
  - 화면이 보이는 동안 30초마다 반복합니다.
  - Socket으로 `roomCreated`, `roomUpdated`, `roomActivity`도 별도로 받고 있습니다.
  - 네트워크 장애 시 Axios의 최초 요청+2회 재시도와 `attemptConnection` 자체 재시도가 겹쳐 health 요청이 한 번의 논리적 확인에서 최대 6회까지 발생할 수 있습니다.
  - `lib/api/client.js`의 `pendingRequests`는 삭제만 하고 등록하지 않아 요청 중복 제거 기능을 하지 않습니다.
- 왜 문제가 될 수 있는지:
  - 정상 상태에서도 방 목록 사용자 한 명당 분당 약 4개의 REST 요청이 발생합니다.
  - Socket 연결이 이미 살아 있어도 health 요청을 선행합니다.
  - 장애 시 다수 클라이언트가 비슷한 시점에 재시도하여 복구 중 서버에 요청 파동을 만들 수 있습니다.
- 부하가 커졌을 때 예상되는 현상:
  - 방 목록 사용자 수에 비례하는 health/rooms 요청
  - 서버 장애 시 요청량 순간 증가
  - Socket 이벤트와 polling 응답이 같은 목록을 연속 갱신
- 심각도: **중간 — 실제 반복 요청이지만 브라우저 CPU의 첫 병목은 아님**
- 실제로 검증해야 할 지표:
  - 사용자당 `/api/health`, `/api/rooms` 요청 수
  - 장애 30초 동안 실제 health 시도 횟수
  - polling 응답 payload 크기
  - polling 직후 React commit
  - Socket 갱신과 REST 갱신의 중복 횟수

추가로, 방 입장 한 번에 REST `join`, REST room detail, Socket `joinRoom`이 순차 실행됩니다. 각각 역할은 다르지만 신규 입장 트래픽은 최소 세 경로로 증폭됩니다.

### 병목 8 — Socket 중복 연결보다 요청별 임시 리스너가 더 현실적인 위험

- 위치:
  - `apps/frontend/services/socket.js`의 `SocketService.connect`
  - `apps/frontend/lib/socket/socketClient.js`의 `waitForSocketEvent`
  - `apps/frontend/features/chat/room/useRoomHandling.js`의 `setupEventListeners`
- 현재 동작:
  - `connectionPromise`로 동시 `connect()` 호출을 합치고 있습니다.
  - 방 이벤트는 재등록 전에 기존 unsubscribe를 실행합니다.
  - 연결 이벤트도 정확한 handler로 `off`합니다.
  - 따라서 정상적인 mount/unmount에서 영구 리스너가 계속 중복 등록된다는 직접적인 증거는 없습니다.
  - 다만 메시지 전송마다 공통 `message` 이벤트에 `once` 리스너를 추가합니다.
  - 입력 UI는 async 전송 완료를 기다리지 않고 다음 전송을 허용하므로 burst 전송 시 여러 `once`가 동시에 존재할 수 있습니다.
  - 이 리스너들은 메시지 ID를 상관하지 않아 다음 아무 `message` 이벤트 하나가 여러 전송 Promise를 동시에 완료할 수 있습니다.
- 왜 문제가 될 수 있는지:
  - 일반적인 리스너 누수보다는 빠른 연속 전송 중 임시 listener 증가와 잘못된 응답 상관이 문제입니다.
  - cleanup의 `socket.off('message')`처럼 handler 없이 호출하는 경로는 동일 singleton을 공유하는 다른 구독이 겹칠 경우 모두 제거할 수 있습니다.
- 부하가 커졌을 때 예상되는 현상:
  - 빠른 연속 전송이 실제 ack보다 먼저 성공 처리됨
  - 일시적인 listener 수 증가
  - 라우트 전환과 재연결이 겹칠 때 이벤트 누락 가능성
  - 연결 수 자체가 계속 늘어나는 누수 가능성은 현재 코드만으로는 높지 않음
- 심각도: **중간 — 주로 burst 시 정확성과 일시적 처리량 문제**
- 실제로 검증해야 할 지표:
  - 브라우저 하나당 실제 Socket 연결 수
  - 라우트 왕복 후 이벤트별 listener 수
  - 메시지 burst 중 최대 `once('message')` 수
  - 한 서버 메시지로 resolve되는 send Promise 수
  - 재연결 후 동일 이벤트 handler 호출 횟수

### 병목 9 — 초기 번들 후보와 Client Component 범위

- 위치:
  - `apps/frontend/app/providers.js`
  - `apps/frontend/components/ChatInput.js`의 lazy EmojiPicker
  - `apps/frontend/components/MessageActions.js`의 정적 EmojiPicker import
  - `apps/frontend/components/EmojiPicker.js`의 emoji 전체 데이터 import
- 현재 동작:
  - App Router의 chat 페이지 전체가 Client Component입니다.
  - 실제 채팅 상태와 Socket 때문에 대부분 클라이언트 실행이 필요하므로 `use client` 자체가 주 병목이라고 보기는 어렵습니다.
  - 전역 Provider 때문에 Vapor Theme, Auth, Socket.IO, Toast 코드가 모든 App Router 화면의 client graph에 포함됩니다.
  - `ChatInput`은 EmojiPicker를 lazy import하지만 `MessageActions`가 동일 컴포넌트를 정적으로 import합니다.
  - 따라서 채팅방 번들에서는 EmojiPicker와 emoji 데이터의 지연 로딩 효과가 사라질 가능성이 높습니다.
  - `react-canvas-confetti`는 의존성에만 있고 import가 없어 현재 번들 증가 근거는 없습니다.
- 왜 문제가 될 수 있는지:
  - emoji 데이터는 메시지의 리액션 버튼을 열지 않은 사용자에게도 초기 채팅방 chunk에 포함될 수 있습니다.
  - `FileMessage`, `FilePreview`, Toast 등도 채팅 entry에서 정적으로 연결됩니다.
- 부하가 커졌을 때 예상되는 현상:
  - 동시 사용자 수와 무관하게 개별 사용자의 첫 채팅방 진입 시 다운로드·파싱·실행 비용 증가
  - 저사양 기기에서 hydration과 첫 interaction 지연
- 심각도: **중간 — 유력 후보지만 production bundle 실측 필요**
- 실제로 검증해야 할 지표:
  - production route별 JS transferred/parsed size
  - EmojiPicker chunk가 초기 요청에 포함되는지
  - script evaluation과 hydration 시간
  - cold-load LCP, INP, Total Blocking Time

### 낮은 우선순위로 판단한 부분

- `AuthContext` value는 memo되지 않았지만 인증 상태는 메시지마다 변하지 않으므로 실시간 채팅의 주 병목은 아닙니다.
- 메시지 key는 정상 데이터에서 `_id`, 방 목록은 `room._id`를 사용해 양호합니다. index fallback은 ID가 없는 비정상 메시지·참가자에만 적용됩니다.
- `SocketProvider`의 Context value는 안정적이며 Socket 이벤트마다 전체 앱 Context가 변경되는 구조가 아닙니다.
- 인증의 5분 interval과 방 화면의 1분 interval은 로컬 상태 확인만 하며 API polling은 아닙니다.

## 3. 가장 먼저 검증할 병목 TOP 5

1. **읽음 이벤트 fan-out과 전체 메시지 갱신**
   - 참가자 수와 메시지 수가 동시에 곱해집니다.
   - Socket 이벤트 수, React commit, 서버 read 처리 모두 한 번에 악화될 수 있습니다.
2. **새 메시지·읽음·리액션마다 전체 메시지 목록 리렌더**
   - `React.memo`가 존재하지만 `messages`에 의존하는 reaction callback 때문에 기존 메시지까지 다시 렌더됩니다.
   - 빠른 메시지 발생 상황에서 브라우저 CPU가 가장 먼저 포화될 가능성이 큽니다.
3. **메시지 누적에 따른 DOM·Observer·전역 리스너 증가**
   - 가상화나 보존 상한이 없습니다.
   - 수천 메시지까지 히스토리를 연 이후 메모리와 스크롤 성능이 지속적으로 나빠질 구조입니다.
4. **전역 `roomActivity` 전달**
   - 사용자가 들어 있지 않은 방의 메시지 활동도 모든 Socket 연결로 전달됩니다.
   - 다중 방·다중 사용자 환경에서는 클라이언트 네트워크와 Socket 파싱량이 전역 메시지율에 종속됩니다.
5. **이전 메시지 페이지 로딩의 전체 재정렬·Set 복사**
   - 페이지 크기는 30개지만 비용은 현재까지 누적한 전체 메시지 수를 기준으로 증가합니다.
   - 긴 방을 탐색할 때 점진적으로 느려지는 형태라 일반 기능 테스트에서 발견되기 어렵습니다.

### 부하 형태별 예상 병목 순서

- 한 방에 많은 참가자와 빠른 메시지
  - CPU/React 렌더 → Socket 이벤트 처리량 → 메모리/DOM
- 많은 방에서 전체 메시지율 증가
  - roomActivity 네트워크/Socket 파싱 → 방 목록 CPU
- 장시간 히스토리 탐색
  - DOM·heap → GC → 스크롤/렌더 CPU
- 방 목록 사용자만 증가
  - REST 요청 수도 증가하지만 현재 코드상 브라우저 렌더 병목보다 먼저 터질 가능성은 상대적으로 낮습니다.

## 4. 부하테스트에서 확인해야 할 것

### 현재 테스트가 커버하는 범위

#### `loadtest/load-test.js`

- 모든 사용자를 한 방에 모읍니다.
- 수신 메시지마다 읽음 이벤트를 전송합니다.
- 메시지의 약 10%에 reaction을 전송합니다.
- 읽음 fan-out과 서버 Socket 부하를 재현하는 데 가장 적합합니다.
- 단, Node Socket 클라이언트라 React·DOM·브라우저 heap은 전혀 검증하지 못합니다.
- 기존 `latencies`는 `emit()` 호출 직후 시간을 기록해 실제 round-trip latency가 아닙니다.

#### `loadtest/ramp-up-test.js`

- 여러 방과 사용자를 점진적으로 증가시킵니다.
- 실제 Socket echo RTT와 REST 지연을 수집합니다.
- 읽음·reaction·프로필 업데이트·파일 업로드까지 포함합니다.
- 글로벌 `roomActivity` 트래픽을 발생시키지만 해당 이벤트 수신량은 메트릭으로 세지 않습니다.
- 브라우저 UI 비용은 검증하지 못합니다.

#### Artillery Playwright 시나리오

- 위치: `e2e/artillery/scenarios/chat.scenario.js`
- 실제 Chromium을 사용합니다.
- 기본 `MASS_MESSAGE_COUNT`는 10입니다.
- 각 VU가 방을 만들거나 랜덤 방에 들어가므로 동일 hot room 집중 부하가 약합니다.
- DOM node, JS heap, long task, React commit을 명시적으로 수집하지 않습니다.

#### `e2e/tests/chat.spec.js`

- 다자간 테스트는 5명입니다.
- 히스토리 테스트는 61개 메시지입니다.
- 기능 정확성에는 유효하지만 성능 곡선을 보기에는 작습니다.

#### `e2e/tests/timeout.spec.js`

- API/Socket 무응답과 재시도 시간을 잘 검증합니다.
- 정상 고처리량 상태의 브라우저 CPU·렌더 병목과는 다른 범위입니다.

### 병목과 연결할 테스트

#### 1. Hot room 읽음 폭증 테스트

- `load-test.js`로 사용자 1/5/20/50명을 같은 방에 연결합니다.
- 메시지 전송률을 고정합니다.
- 같은 방에 Playwright 관측 브라우저 하나를 추가합니다.
- 측정 항목:
  - 메시지 1개당 `messagesRead` 수
  - Socket frame/초
  - 관측 브라우저 long task, React commit, 메시지 표시 지연
  - 참가자 수 증가에 따라 이벤트가 선형인지 제곱에 가까운지

#### 2. 메시지 수별 렌더 성능 테스트

- 동일 방에 30/300/1,000/3,000개 메시지를 미리 생성합니다.
- 각 단계에서 일정한 초당 메시지를 추가합니다.
- Playwright/CDP로 측정할 항목:
  - `JSHeapUsedSize`
  - DOM node 수
  - `TaskDuration`, `LayoutCount`, `RecalcStyleCount`
  - 메시지 수신부터 DOM 표시까지의 지연
  - 한 이벤트당 `UserMessage` 렌더 수

#### 3. 히스토리 누적 테스트

- E2E의 현재 61개를 최소 수백~수천 개로 확장한 별도 성능 시나리오로 검증합니다.
- 30개 페이지를 계속 위로 로드합니다.
- 페이지별 측정 항목:
  - `previousMessagesLoaded` 처리 시간
  - node/heap 증가량
  - 스크롤 복원 시간
  - long task 수
- 페이지 번호가 증가할수록 처리 시간이 증가하면 전체 정렬·Set 복사 병목을 확인할 수 있습니다.

#### 4. 전역 roomActivity 테스트

- `ramp-up-test.js`로 방 수와 전역 메시지율을 증가시킵니다.
- 별도 브라우저는 `/chat` 목록에 고정하고 또 하나는 특정 방에 고정합니다.
- 측정 항목:
  - 두 브라우저 모두의 `roomActivity` frame 수
  - 목록 브라우저의 `RoomsTable` commit 시간
  - 방 브라우저에서 사용하지 않는 roomActivity가 차지하는 수신 바이트와 CPU
  - 방 수 100/1,000에서 한 이벤트 처리 시간

#### 5. Socket 연결·리스너 수명주기 테스트

- `/chat → /chat/{room} → /chat` 왕복을 반복합니다.
- 중간에 offline/online 또는 강제 disconnect를 삽입합니다.
- 측정 항목:
  - 브라우저당 활성 Socket 연결 수가 항상 1인지
  - 메시지 하나에 `onMessage`가 몇 번 호출되는지
  - 재연결 후 `participantsUpdate`, `messagesRead` 중복 호출 여부
  - burst 전송 중 `once('message')` 최대 수와 resolve된 Promise 수

#### 6. API 요청 증폭 테스트

- 방 목록 화면의 동시 브라우저 수를 증가시킵니다.
- 정상 상태 2분과 `/api/health` 장애 상태를 각각 수행합니다.
- 측정 항목:
  - 사용자당 health/rooms 요청
  - 장애 시 한 논리적 갱신당 실제 요청 횟수
  - 모든 클라이언트 재시도 시점 분포
  - Socket room update와 polling refresh가 연속으로 발생한 횟수

#### 7. 번들 테스트

- production build 기준 route별 chunk를 분석합니다.
- `/chat/[room]` 초기 요청에 `@emoji-mart/data`가 포함되는지 확인합니다.
- 다운로드 크기뿐 아니라 parse/evaluate 시간과 hydration long task를 측정해야 합니다.

## 최종 결론

현재 테스트 구성만으로는 서버와 Socket의 처리 한계는 비교적 잘 볼 수 있지만, 가장 유력한 프론트 병목인 전체 메시지 리렌더·Observer 수·DOM/heap 누적은 직접 관측되지 않습니다.

Node 부하기로 트래픽을 만들고 소수의 Playwright 브라우저를 관측 프로브로 두는 조합이 이 프로젝트에는 가장 적합합니다.

코드 수정에 들어가기 전에 가장 먼저 확인해야 할 것은 다음 세 가지입니다.

1. 읽음 이벤트가 참가자 수에 따라 실제로 얼마나 증폭되는가
2. Socket 이벤트 하나에 기존 메시지 컴포넌트가 몇 개 다시 렌더되는가
3. 메시지 히스토리 증가에 따라 DOM node와 JS heap이 어떤 곡선으로 증가하는가
