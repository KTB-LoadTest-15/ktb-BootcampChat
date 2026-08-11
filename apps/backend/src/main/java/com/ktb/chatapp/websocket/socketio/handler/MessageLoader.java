package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.service.UserBatchLoader;
import com.ktb.chatapp.service.message.MessageStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageStore messageStore;
    private final UserBatchLoader userBatchLoader;
    private final MessageResponseMapper messageResponseMapper;

    private static final int BATCH_SIZE = 30;

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(data.roomId(), data.limit(BATCH_SIZE), data.before(LocalDateTime.now()));
        } catch (Exception e) {
            log.error("Error loading initial messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before) {
        MessageStore.MessagePage messagePage = messageStore.findMessagesBefore(roomId, before, limit);

        // DESC로 조회했으므로 ASC로 재정렬 (채팅 UI 표시 순서)
        List<Message> sortedMessages = messagePage.messages().reversed();

        // 읽음 처리는 read cursor(프론트 IntersectionObserver → markMessagesAsRead)로 이관되어
        // 로드 시 서버측 per-message 읽음 쓰기를 하지 않는다.

        // sender 유저를 한 번의 조회로 일괄 해소(메시지당 findById N+1 제거).
        // senderId가 null(AI/시스템 메시지)이거나 존재하지 않으면 맵에 없고, 매퍼가 null sender를 허용한다.
        var senderIds = sortedMessages.stream().map(Message::getSenderId).toList();
        Map<String, User> sendersById = userBatchLoader.findByIds(senderIds);

        // 메시지 응답 생성
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> {
                    var user = sendersById.get(message.getSenderId());
                    return messageResponseMapper.mapToMessageResponse(message, user);
                })
                .collect(Collectors.toList());

        boolean hasMore = messagePage.hasMore();

        log.debug("Messages loaded - roomId: {}, limit: {}, count: {}, hasMore: {}",
                roomId, limit, messageResponses.size(), hasMore);

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }
}
