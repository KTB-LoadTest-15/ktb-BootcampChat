package com.ktb.chatapp.service.readcursor;

import java.util.Map;

/**
 * 방-멤버 읽음 커서 저장소 추상화.
 *
 * <p>{@link com.ktb.chatapp.service.message.MessageStore}와 동일하게 {@code message.store}
 * 프로퍼티로 Mongo/Redis 구현을 선택한다. 읽음 상태를 per-message가 아닌 (roomId, userId)당
 * 단일 high-water mark로 관리한다.
 */
public interface ReadCursorStore {

    /**
     * 사용자의 방 읽음 커서를 {@code lastReadTs}까지 단조 전진시킨다(기존 값보다 클 때만).
     *
     * @return 실제로 커서가 전진했으면 {@code true}(중복/역행이면 {@code false}). 호출측은 이 값으로
     *         불필요한 브로드캐스트를 생략할 수 있다.
     */
    boolean advance(String roomId, String userId, long lastReadTs);

    /** 방의 모든 참가자 커서(userId → lastReadTs). 입장 시 프론트 seed 용도. */
    Map<String, Long> findByRoom(String roomId);
}
