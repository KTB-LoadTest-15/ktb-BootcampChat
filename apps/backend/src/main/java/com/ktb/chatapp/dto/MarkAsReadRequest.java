package com.ktb.chatapp.dto;

import lombok.Data;

/**
 * 읽음 처리 요청. read cursor(high-water mark) 방식.
 *
 * <p>클라이언트는 읽은 메시지 목록 대신 "마지막으로 읽은 메시지의 서버 timestamp" 하나만 보낸다.
 * 서버는 (roomId, userId) 커서를 이 값까지 단조 전진시킨다.
 */
@Data
public class MarkAsReadRequest {
    /** 읽음 대상 방. 클라가 이미 입장한 방이므로 보유하고 있다(서버 조회 제거용). */
    private String roomId;

    /** 마지막으로 읽은 메시지의 timestamp(epoch millis). */
    private long lastReadTs;
}
