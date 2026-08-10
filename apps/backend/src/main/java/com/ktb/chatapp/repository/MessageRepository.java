package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {
    Page<Message> findByRoomIdAndTimestampBefore(String roomId, LocalDateTime timestamp, Pageable pageable);
    /**
     * 특정 시간 이후의 메시지 수 카운트
     * 최근 N분간 메시지 수를 조회할 때 사용
     */
    @Query(value = "{ 'room': ?0, 'timestamp': { $gte: ?1 } }", count = true)
    long countRecentMessagesByRoomId(String roomId, LocalDateTime since);

    /**
     * fileId로 메시지 조회 (파일 권한 검증용)
     */
    Optional<Message> findByFileId(String fileId);

    /**
     * 주어진 메시지들 중 아직 이 사용자가 읽지 않은 문서에만 reader를 추가한다.
     *
     * <p>필터 {@code readers.userId != userId}가 "이미 읽은 메시지"를 서버측에서 걸러내므로
     * 멱등하고(재호출 시 no-op) 동시 호출에도 lost update가 없다. {@code @Query}+{@code @Update}
     * 조합은 매칭되는 <b>모든</b> 문서에 적용되어 메시지 개수와 무관하게 왕복 1회로 끝난다.
     *
     * <p>{@code readers}는 저장 시점에 따라 null/absent일 수 있어 단순 {@code $push}는 실패할 수
     * 있다. aggregation pipeline의 {@code $ifNull}로 null을 빈 배열로 승격시킨 뒤 이어붙여
     * 모든 저장 형태(null/absent/empty/populated)에서 안전하게 동작한다.
     *
     * @return 새로 읽음 처리된(수정된) 문서 수
     */
    @Query("{ '_id': { $in: ?0 }, 'readers.userId': { $ne: ?1 } }")
    @Update(pipeline = {
            "{ '$set': { 'readers': { '$concatArrays': [ "
            + "{ '$ifNull': [ '$readers', [] ] }, "
            + "[ { 'userId': ?1, 'readAt': ?2 } ] ] } } }"
    })
    long updateReadersForMessages(List<String> messageIds, String userId, LocalDateTime readAt);
}
