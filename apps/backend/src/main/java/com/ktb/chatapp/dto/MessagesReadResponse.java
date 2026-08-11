package com.ktb.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 읽음 처리 브로드캐스트 페이로드. read cursor 방식.
 *
 * <p>메시지 id 목록 대신 사용자의 새 커서(lastReadTs)만 전송한다. 상수 크기이며, 더 큰
 * lastReadTs가 이전 값을 자연히 대체(supersede)하므로 수신측 coalescing이 자명하다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessagesReadResponse {
    private String userId;
    private long lastReadTs;
}
