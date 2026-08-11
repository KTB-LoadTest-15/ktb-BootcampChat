package com.ktb.chatapp.dto;

import lombok.Data;

/**
 * 읽음 처리 요청. read cursor(high-water mark) 방식.
 *
 * <p>클라이언트는 읽은 메시지 목록 대신 "마지막으로 읽은 메시지의 id"만 보낸다. 서버가 그 메시지의
 * <b>서버 기준 timestamp</b>를 조회해 커서를 단조 전진시킨다. 클라가 timestamp를 직접 보내지 않으므로
 * 위조(미래 시각으로 커서 밀기)가 원천 차단된다.
 */
@Data
public class MarkAsReadRequest {
    /** 읽음 대상 방. 클라가 이미 입장한 방이므로 보유하고 있다(dispatch 라우팅·검증용). */
    private String roomId;

    /** 마지막으로 읽은 메시지의 id. 서버가 이 메시지의 timestamp를 authoritative 값으로 사용한다. */
    private String lastReadMessageId;
}
