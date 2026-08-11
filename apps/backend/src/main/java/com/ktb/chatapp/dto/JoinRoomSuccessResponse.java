package com.ktb.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * joinRoomSuccess 이벤트 응답 DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoinRoomSuccessResponse {
    private String roomId;
    private List<UserResponse> participants;
    private List<MessageResponse> messages;
    private boolean hasMore;
    private List<ActiveStreamResponse> activeStreams;
    /** 방 참가자들의 읽음 커서(userId → lastReadTs epoch millis). 프론트 읽음 표시 seed. */
    private Map<String, Long> readCursors;
}
