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
     */
    public Map<String, User> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, User> byId = new HashMap<>(distinct.size());
        userRepository.findAllById(distinct).forEach(user -> byId.put(user.getId(), user));
        return byId;
    }
}
