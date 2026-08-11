package com.ktb.chatapp.service;

import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 여러 사용자 id를 한 번의 조회로 해소하는 배치 로더.
 *
 * <p>메시지 history의 sender, 방 참가자 목록처럼 "id 집합 → User"가 필요한 hot path에서
 * id마다 {@code findById}를 반복하는 N+1을 없애기 위한 이음새다. 내부적으로
 * {@code findAllById}({@code $in} 쿼리) 한 번으로 끝낸다.
 */
@Component
@RequiredArgsConstructor
public class UserBatchLoader {

    private final UserRepository userRepository;

    /**
     * 주어진 id들에 해당하는 User를 id→User 맵으로 반환한다.
     *
     * <p>null id는 무시하고, 중복 id는 한 번만 조회한다. 존재하지 않는 id는 맵에서 빠진다
     * (기존 {@code findById().filter(present)} 의미와 동일). 왕복은 id 개수와 무관하게 1회.
     *
     * <p>반환 맵은 항상 null 키 조회를 허용한다(null → {@code null} 반환). 호출부가 sender/
     * participant id에 null이 섞인 채로 {@code get(id)}를 호출해도 안전하도록 {@code HashMap}을
     * 쓴다. 불변 맵({@code Map.of()})은 null 키 조회 시 NPE를 던지므로 여기서 반환하지 않는다.
     */
    public Map<String, User> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }
        List<String> distinct = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, User> byId = new HashMap<>(distinct.size());
        userRepository.findAllById(distinct).forEach(user -> byId.put(user.getId(), user));
        return byId;
    }
}
