package com.ktb.chatapp.service;

import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * UserBatchLoader 단위 테스트.
 *
 * <p>핵심 계약: 반환 맵은 어떤 입력에서든 null 키 조회를 허용해야 한다(null → {@code null}).
 * 호출부(MessageLoader, RoomJoin/LeaveHandler)가 null이 섞인 id로 {@code get(id)}를 호출하기
 * 때문이다. 과거 빈 결과에서 {@code Map.of()}(불변)를 반환해 {@code get(null)}이 NPE를 던지던
 * 회귀를 막는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserBatchLoader 단위 테스트")
class UserBatchLoaderTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserBatchLoader userBatchLoader;

    private static User user(String id) {
        return User.builder().id(id).name("user-" + id).build();
    }

    @Test
    @DisplayName("모든 id가 null인 배치 → get(null)이 NPE 대신 null을 반환한다 (실 장애 재현)")
    void allNullIds_getNull_returnsNullNotThrow() {
        // senderId가 전부 null인 메시지 배치(AI/시스템 메시지)를 흉내낸다.
        List<String> allNull = Arrays.asList(null, null, null);

        Map<String, User> result = userBatchLoader.findByIds(allNull);

        assertThat(result).isEmpty();
        // 회귀 지점: Map.of()였다면 여기서 NullPointerException.
        assertThatCode(() -> assertThat(result.get(null)).isNull())
                .doesNotThrowAnyException();
        // 조회할 non-null id가 없으므로 DB round-trip도 없어야 한다.
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("빈/ null 컬렉션 → 빈 맵이며 get(null) 안전, DB 조회 없음")
    void emptyOrNullCollection_returnsSafeEmptyMap() {
        assertThatCode(() -> {
            assertThat(userBatchLoader.findByIds(List.of()).get(null)).isNull();
            assertThat(userBatchLoader.findByIds(null).get(null)).isNull();
        }).doesNotThrowAnyException();
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("일부만 null인 배치 → non-null만 조회, null 키는 null 반환")
    void mixedNullIds_resolvesNonNullAndToleratesNullKey() {
        when(userRepository.findAllById(anyList())).thenReturn(List.of(user("a")));

        List<String> mixed = Arrays.asList("a", null, "a"); // 중복 + null 혼재

        Map<String, User> result = userBatchLoader.findByIds(mixed);

        assertThat(result).containsOnlyKeys("a");
        assertThat(result.get("a").getName()).isEqualTo("user-a");
        assertThat(result.get(null)).isNull();
        // 중복 제거 + null 제거 후 distinct id("a")만 한 번 조회.
        verify(userRepository).findAllById(List.of("a"));
    }

    @Test
    @DisplayName("존재하지 않는 id는 맵에서 빠지고 get은 null을 반환한다")
    void missingId_absentFromMap() {
        lenient().when(userRepository.findAllById(anyList())).thenReturn(List.of(user("a")));

        Map<String, User> result = userBatchLoader.findByIds(List.of("a", "ghost"));

        assertThat(result).containsOnlyKeys("a");
        assertThat(result.get("ghost")).isNull();
    }
}
