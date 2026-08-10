package com.ktb.chatapp.service;

import com.ktb.chatapp.service.message.MessageStore;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MessageStore messageStore;

    /**
     * 메시지 읽음 상태 업데이트
     *
     * <p>메시지별 read-modify-save 루프 대신, 아직 읽지 않은 문서에만 reader를 추가하는
     * 단일 atomic bulk update로 처리한다. 왕복은 메시지 개수와 무관하게 1회이며,
     * 필터 조건({@code readers.userId != userId}) 덕분에 멱등하고 동시성 안전하다.
     *
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     */
    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }

        try {
            long updated = messageStore.addReaderToMessages(
                    messageIds, userId, LocalDateTime.now());
            log.debug("Read status updated: {} of {} messages newly marked as read by user {}",
                    updated, messageIds.size(), userId);
        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
        }
    }
}
