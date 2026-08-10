package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MessageReadStatusServiceTest {

    @Mock
    private MessageRepository messageRepository;

    private MessageReadStatusService service() {
        return new MessageReadStatusService(messageRepository);
    }

    @Test
    @DisplayName("읽음 처리는 메시지 개수와 무관하게 리포지토리 bulk update 1회로 위임한다")
    void updateReadStatus_delegatesToSingleBulkUpdate() {
        List<String> ids = List.of("m1", "m2", "m3");
        when(messageRepository.updateReadersForMessages(anyList(), anyString(), any(LocalDateTime.class)))
                .thenReturn(3L);

        service().updateReadStatus(ids, "user-1");

        // 개별 findById/save가 아니라 단일 호출이어야 한다 (N+1 제거의 핵심)
        verify(messageRepository, times(1))
                .updateReadersForMessages(eq(ids), eq("user-1"), any(LocalDateTime.class));
        verify(messageRepository, never()).findById(anyString());
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("빈 리스트면 DB를 건드리지 않는다")
    void updateReadStatus_emptyList_noDbCall() {
        service().updateReadStatus(List.of(), "user-1");

        verify(messageRepository, never())
                .updateReadersForMessages(anyList(), anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("null 리스트면 DB를 건드리지 않는다")
    void updateReadStatus_nullList_noDbCall() {
        service().updateReadStatus(null, "user-1");

        verify(messageRepository, never())
                .updateReadersForMessages(anyList(), anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("리포지토리 예외는 삼켜서 호출자 흐름을 깨지 않는다")
    void updateReadStatus_swallowsRepositoryException() {
        when(messageRepository.updateReadersForMessages(anyList(), anyString(), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> service().updateReadStatus(List.of("m1"), "user-1"))
                .doesNotThrowAnyException();
    }
}
