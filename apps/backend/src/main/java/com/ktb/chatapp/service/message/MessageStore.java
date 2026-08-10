package com.ktb.chatapp.service.message;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 메시지 저장소 추상화.
 *
 * <p>모든 메시지 읽기/쓰기 경로가 이 인터페이스를 경유하도록 하여, 실제 저장소를
 * MongoDB({@code MongoMessageStore})에서 Redis hot store로 교체할 수 있게 하는 이음새다.
 * Spring Data의 {@code Page}/{@code Pageable} 같은 저장소 특화 타입을 노출하지 않는다.
 *
 * @see com.ktb.chatapp.service.session.SessionStore 동일 패턴의 선례
 */
public interface MessageStore {

    /** 신규 메시지를 추가한다. id가 없으면 부여하고, timestamp가 없으면 현재 시각을 넣는다. */
    Message add(Message message);

    /** 기존 메시지를 갱신한다(리액션 등 read-modify-write 경로). */
    Message update(Message message);

    Optional<Message> findById(String id);

    /** 파일 권한 검증용: fileId로 연결된 메시지 조회. */
    Optional<Message> findByFileId(String fileId);

    /** 최근 {@code since} 이후의 메시지 수(방 활성도 지표). */
    long countRecentMessages(String roomId, LocalDateTime since);

    /**
     * {@code before}보다 오래된 메시지를 timestamp 내림차순으로 최대 {@code limit}개 조회한다.
     * 반환 순서는 최신→과거(DESC)이며, 다음 페이지 존재 여부를 함께 준다.
     */
    MessagePage findMessagesBefore(String roomId, LocalDateTime before, int limit);

    /**
     * 주어진 메시지들 중 아직 이 사용자가 읽지 않은 문서에만 reader를 추가한다.
     * @return 새로 읽음 처리된 문서 수
     */
    long addReaderToMessages(List<String> messageIds, String userId, LocalDateTime readAt);

    /** 페이지 결과: 메시지 목록(DESC)과 다음 페이지 존재 여부. */
    record MessagePage(List<Message> messages, boolean hasMore) {}
}
