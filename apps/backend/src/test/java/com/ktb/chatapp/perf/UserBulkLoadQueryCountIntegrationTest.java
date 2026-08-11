package com.ktb.chatapp.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.UserBatchLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.datafaker.Faker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 유저 N+1 → bulk load(P0-4 sender / P1-4 participant)의 개선 전/후 Mongo 명령 횟수를 실측한다.
 *
 * <p>OLD = 제거된 "id마다 findById" 루프(history의 sender, 방 참가자 목록에서 반복하던 패턴)를
 * 그대로 재현. NEW = {@link UserBatchLoader#findByIds}({@code findAllById} → {@code $in} 1회).
 * 숫자는 docs/perf/P0-4-P1-4-user-bulk-load.md 의 근거이자 회귀 가드다.
 *
 * @see com.ktb.chatapp.websocket.socketio.handler.MessageLoader
 * @see com.ktb.chatapp.websocket.socketio.handler.RoomJoinHandler
 * @see com.ktb.chatapp.websocket.socketio.handler.RoomLeaveHandler
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class, MongoCommandCounterConfig.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class UserBulkLoadQueryCountIntegrationTest {

    private static final int USERS = 30;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserBatchLoader userBatchLoader;

    @Autowired
    private CommandCountingListener listener;

    private Faker faker;

    @BeforeEach
    void setUp() {
        faker = new Faker();
    }

    @AfterEach
    void tearDown() {
        listener.stop();
        userRepository.deleteAll();
    }

    private List<String> saveUsers(int n) {
        return IntStream.range(0, n).mapToObj(i -> {
            User u = User.builder()
                    .id(faker.internet().uuid())
                    .name(faker.name().fullName())
                    .email(faker.internet().emailAddress())
                    .build();
            return userRepository.save(u).getId();
        }).toList();
    }

    /** 제거된 옛 구현을 그대로 재현: id마다 findById. */
    private Map<String, User> legacyFindByIds(List<String> ids) {
        Map<String, User> byId = new HashMap<>();
        for (String id : ids) {
            userRepository.findById(id).ifPresent(u -> byId.put(u.getId(), u));
        }
        return byId;
    }

    @Test
    @DisplayName("서로 다른 발신자 30명 해소: OLD 30 명령(find30) → NEW 1 명령(find, $in)")
    void userLookup_commandCount_beforeAndAfter() {
        List<String> ids = saveUsers(USERS);

        // --- BEFORE: id마다 findById 루프 ---
        listener.start();
        Map<String, User> oldResult = legacyFindByIds(ids);
        listener.stop();
        Map<String, Long> oldSnapshot = listener.snapshot();
        long oldTotal = listener.totalDataCommands();

        // --- AFTER: findAllById 1회 ---
        listener.start();
        Map<String, User> newResult = userBatchLoader.findByIds(ids);
        listener.stop();
        Map<String, Long> newSnapshot = listener.snapshot();
        long newTotal = listener.totalDataCommands();

        System.out.printf("[user-bulk] BEFORE=%s total=%d%n", oldSnapshot, oldTotal);
        System.out.printf("[user-bulk] AFTER =%s total=%d%n", newSnapshot, newTotal);

        // --- 쿼리 횟수: 개선 검증 ---
        // BEFORE: 발신자당 find 1
        assertThat(oldSnapshot.get("find")).isEqualTo((long) USERS);
        assertThat(oldTotal).isEqualTo((long) USERS);

        // AFTER: 개수와 무관하게 find 1회($in)
        assertThat(newSnapshot.get("find")).isEqualTo(1L);
        assertThat(newTotal).isEqualTo(1L);

        // --- 동작 동치성: 쿼리가 줄어도 해소 결과는 동일해야 한다 ---
        assertThat(newResult.keySet()).isEqualTo(oldResult.keySet());
        assertThat(newResult).hasSize(USERS);
        for (String id : ids) {
            assertThat(newResult.get(id).getId()).isEqualTo(oldResult.get(id).getId());
        }
    }

    @Test
    @DisplayName("동일 발신자 중복 id는 한 번만 조회, 존재하지 않는 id는 맵에서 제외")
    void userLookup_dedupAndMissing() {
        List<String> ids = saveUsers(3);
        String present = ids.get(0);
        String missing = faker.internet().uuid();
        // 같은 id를 여러 번 + 존재하지 않는 id + null 을 섞는다.
        List<String> query = java.util.Arrays.asList(present, present, present, missing, null);

        listener.start();
        Map<String, User> result = userBatchLoader.findByIds(query);
        listener.stop();

        // 중복 제거되어 distinct 조회 → 여전히 find 1회
        assertThat(listener.snapshot().get("find")).isEqualTo(1L);
        // 존재하는 id만 맵에 있고, 없는/ null 은 제외
        assertThat(result).containsOnlyKeys(present);
    }
}
