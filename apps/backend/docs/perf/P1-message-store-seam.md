# P1 — MessageStore 이음새 도입 (파일별 변경 설명)

> 단계: [REDIS_MESSAGE_STORE_PLAN.md](../REDIS_MESSAGE_STORE_PLAN.md) P1
> 성격: **순수 리팩터(이음새)** — 저장은 여전히 100% MongoDB, 동작·쿼리 footprint 무변경.
> 목적: 모든 메시지 읽기/쓰기 경로를 `MessageStore` 인터페이스 뒤로 옮겨, P2에서 구현체만 Redis로 교체 가능하게 함.
> 검증: 관련 46 tests(단위+Testcontainers 통합) 통과. baseline 명령 footprint 그대로 유지.

## 구조 변화

```
변경 전:  [핸들러/서비스 10곳] ──▶ MessageRepository ──▶ MongoDB
변경 후:  [핸들러/서비스 10곳] ──▶ MessageStore ──▶ MongoMessageStore ──▶ MessageRepository ──▶ MongoDB
                                    (인터페이스)     (구현, P2에서 RedisMessageStore 추가)
```

`MessageRepository`는 **제거하지 않음** — `MongoMessageStore` 뒤에 그대로 존재(P2에서 fallback·flush 영속화에 재사용).

## 신규 파일 (2)

| 파일 | 무엇을 위해 | 어떻게 |
|---|---|---|
| `service/message/MessageStore.java` | 저장소 교체의 이음새(인터페이스) | `add`/`update`/`findById`/`findByFileId`/`countRecentMessages`/`findMessagesBefore`/`addReaderToMessages` 정의. Spring `Page`/`Pageable` 대신 `MessagePage(messages, hasMore)` record로 저장소 특화 타입 미노출 |
| `service/message/MongoMessageStore.java` | 현행 Mongo 구현 | `@Component`로 `MessageRepository`에 위임. `add`는 앱측 ObjectId·timestamp 선생성 후 `insert`(명령 고정), `update`는 `save`, `findMessagesBefore`는 Page→MessagePage 변환(footprint `find`+`aggregate` 유지) |

## 변경 파일 — 프로덕션 (10)

모두 **`MessageRepository` 직접 주입 → `MessageStore` 주입**으로 전환. 세부:

| 파일 | 무엇을 위해 | 어떻게 |
|---|---|---|
| `handler/ChatMessageHandler.java` | 메시지 전송 저장 경로 | 필드·import 교체, `messageRepository.save(message)` → `messageStore.add(message)` |
| `handler/RoomJoinHandler.java` | 입장 시스템 메시지 저장 | `save(joinMessage)` → `messageStore.add(joinMessage)` |
| `handler/RoomLeaveHandler.java` | 퇴장 시스템 메시지 저장 | `save(systemMessage)` → `messageStore.add(systemMessage)` |
| `handler/MessageReactionHandler.java` | 리액션 조회+갱신 | `findById` → `messageStore.findById`, `save(message)` → `messageStore.update(message)` |
| `handler/MessageReadHandler.java` | 읽음 처리 시 roomId 조회 | `findById` → `messageStore.findById` |
| `handler/MessageLoader.java` | 히스토리 페이지 로드 | `Page`/`Pageable` 제거, `findByRoomIdAndTimestampBefore` → `messageStore.findMessagesBefore`, `MessagePage.messages()/hasMore()` 사용 |
| `ai/AiService.java` | AI 메시지 저장 | 생성자 파라미터 `MessageRepository` → `MessageStore`, `save(...)` → `messageStore.add(...)` |
| `service/RecentMessageCounter.java` | 최근 메시지 수 집계 | `countRecentMessagesByRoomId` → `messageStore.countRecentMessages` |
| `service/FileAccessService.java` | 파일 권한 검증(파일→메시지) | `findByFileId` → `messageStore.findByFileId` |
| `service/MessageReadStatusService.java` | 읽음 bulk update | `updateReadersForMessages` → `messageStore.addReaderToMessages` |

## 변경 파일 — 테스트 (11)

| 파일 | 무엇을 위해 | 어떻게 |
|---|---|---|
| `handler/ChatMessageHandlerTest.java` | 전송 핸들러 단위 | mock `MessageRepository`→`MessageStore`, `save`→`add`, `verifyNoInteractions` 대상 교체 |
| `handler/RoomJoinHandlerTest.java` | 입장 핸들러 단위 | mock·생성자·`save`→`add` 교체 |
| `handler/RoomLeaveHandlerTest.java` | 퇴장 핸들러 단위 | mock·생성자·`save`→`add` 교체 |
| `handler/MessageReactionHandlerTest.java` | 리액션 핸들러 단위 | `findById`/`save`→`messageStore.findById`/`update`, verify 교체 |
| `handler/MessageReadHandlerTest.java` | 읽음 핸들러 단위 | mock·생성자·`findById` 교체 |
| `handler/MessageLoaderTest.java` | 로더 단위 | `Page` stub → `MessagePage` stub으로 재작성, `findMessagesBefore` mock |
| `handler/MessageLoaderIntegrationTest.java` | 로더 통합 | `new MessageLoader(new MongoMessageStore(messageRepository), ...)`로 실제 store 주입 |
| `service/MessageReadStatusServiceTest.java` | 읽음 서비스 단위 | mock `MessageStore`, `addReaderToMessages` 위임·가드·예외 검증으로 재작성 |
| `service/FileAccessServiceTest.java` | 파일 접근 단위 | mock·생성자·`findByFileId` 교체 |
| `ai/AiServiceUnitTest.java` | AI 서비스 단위 | 생성자 인자 `MessageStore`로 교체 |
| `ai/OpenAiConfigurationTest.java` | AI 설정 컨텍스트 | `.withBean(MessageRepository)` → `.withBean(MessageStore)` |

> 미변경: `MessageReadStatusServiceIntegrationTest`, perf 측정 테스트 2종 — `MessageRepository`를 setup/직접 측정에 쓰며 repo 빈은 그대로 존재하므로 수정 불필요.

## 커밋 제안

```
refactor: introduce MessageStore seam over message persistence
```
파일: 위 신규 2 + 프로덕션 10 + 테스트 11 + 본 문서. (실제 커밋은 승인 후)
