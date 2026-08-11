package com.ktb.chatapp.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 읽음 처리 브로드캐스트 페이로드. read cursor + 방 단위 coalescing 방식.
 *
 * <p>메시지 id 목록 대신 방 안에서 갱신된 커서들을 {@code userId → lastReadTs(epoch millis)} 맵으로
 * 한 번에 전송한다. 짧은 창 동안의 여러 사용자 읽음을 1건으로 묶으므로(N번 broadcast → 창당 1번)
 * 부하 구간의 N² 팬아웃을 줄인다. 더 큰 lastReadTs가 이전 값을 자연히 대체(supersede)한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessagesReadResponse {
    private Map<String, Long> cursors;
}
