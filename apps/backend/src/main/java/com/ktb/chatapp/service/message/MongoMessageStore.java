package com.ktb.chatapp.service.message;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * {@link MessageStore}의 MongoDB 구현. {@link MessageRepository}에 위임한다.
 *
 * <p>명령 footprint는 기존과 동일하게 유지한다: 신규는 {@code insert}, 갱신은 {@code update},
 * 페이지 조회는 {@code find} + {@code aggregate}(count). (docs/perf/P0-redis-baseline.md)
 */
@Component
@ConditionalOnProperty(name = "message.store", havingValue = "mongo", matchIfMissing = true)
@RequiredArgsConstructor
public class MongoMessageStore implements MessageStore {

    private final MessageRepository messageRepository;

    @Override
    public Message add(Message message) {
        if (message.getId() == null) {
            message.setId(new ObjectId().toHexString());
        }
        if (message.getTimestamp() == null) {
            message.setTimestamp(LocalDateTime.now());
        }
        // id를 앱에서 부여하므로 save(is-new 판정) 대신 insert로 명령을 insert로 고정한다.
        return messageRepository.insert(message);
    }

    @Override
    public Message update(Message message) {
        return messageRepository.save(message);
    }

    @Override
    public Optional<Message> findById(String id) {
        return messageRepository.findById(id);
    }

    @Override
    public Optional<Message> findByFileId(String fileId) {
        return messageRepository.findByFileId(fileId);
    }

    @Override
    public long countRecentMessages(String roomId, LocalDateTime since) {
        return messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    @Override
    public MessagePage findMessagesBefore(String roomId, LocalDateTime before, int limit) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());
        Page<Message> page = messageRepository.findByRoomIdAndTimestampBefore(roomId, before, pageable);
        return new MessagePage(page.getContent(), page.hasNext());
    }

    @Override
    public long addReaderToMessages(List<String> messageIds, String userId, LocalDateTime readAt) {
        return messageRepository.updateReadersForMessages(messageIds, userId, readAt);
    }
}
