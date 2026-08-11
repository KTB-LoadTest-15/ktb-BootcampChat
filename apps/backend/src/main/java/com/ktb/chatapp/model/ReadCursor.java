package com.ktb.chatapp.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 방-멤버 읽음 커서(high-water mark) 문서.
 *
 * <p>읽음 상태를 메시지마다({@code Message.readers})가 아니라 (roomId, userId)당 단일 커서에
 * 저장한다. 메시지 M이 사용자 U에게 읽힘 ⇔ {@code cursor(U) >= M.timestamp}.
 * 읽음 처리 1건 = 커서 1개 upsert(O(1))로, per-message 갱신·문서 비대·N² 팬아웃 비용을 없앤다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "read_cursors")
@CompoundIndexes({
    @CompoundIndex(name = "roomId_userId_idx", def = "{'roomId': 1, 'userId': 1}", unique = true)
})
public class ReadCursor {

    @Id
    private String id;

    private String roomId;

    private String userId;

    /** 마지막으로 읽은 메시지의 timestamp(epoch millis). 단조 증가한다. */
    private long lastReadTs;

    private LocalDateTime updatedAt;
}
