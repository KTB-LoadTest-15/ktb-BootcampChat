package com.ktb.chatapp.websocket.socketio;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link RedisChatDataStore}의 왕복·size·삭제 동작을 실측한다(다중 인스턴스 공유 상태 ③).
 *
 * <p>{@code ConnectedUsers}(→SocketUser)·{@code UserRooms}(→Set)가 이 스토어를 경유하므로,
 * 두 값 타입의 직렬화 왕복과 keyset 기반 size가 Local 구현과 동치임을 보장한다.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {"socketio.enabled=false"})
class RedisChatDataStoreIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RedisChatDataStore store;

    @BeforeEach
    void setUp() {
        store = new RedisChatDataStore(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    @DisplayName("SocketUser 왕복: set 후 get이 동일 값 (ConnectedUsers 경로)")
    void socketUser_roundTrip() {
        SocketUser user = new SocketUser("user-1", "tester", "sess-1", "socket-1");
        store.set("conn_users:userid:user-1", user);

        SocketUser got = store.get("conn_users:userid:user-1", SocketUser.class).orElseThrow();
        assertThat(got).isEqualTo(user);
        assertThat(store.get("conn_users:userid:absent", SocketUser.class)).isEmpty();
    }

    @Test
    @DisplayName("Set<String> 왕복: UserRooms 멤버십 저장/조회")
    void roomSet_roundTrip() {
        store.set("userroom:roomids:user-1", Set.of("room-a", "room-b"));

        @SuppressWarnings("unchecked")
        Set<String> rooms = store.get("userroom:roomids:user-1", Set.class).orElseThrow();
        assertThat(rooms).containsExactlyInAnyOrder("room-a", "room-b");
        assertThat(rooms).contains("room-a"); // isInRoom 판정 근거
    }

    @Test
    @DisplayName("size는 keyset 기반으로 키 개수를 반영하고 delete 시 감소한다")
    void size_reflectsKeyCountAndDelete() {
        assertThat(store.size()).isZero();

        store.set("conn_users:userid:u1", new SocketUser("u1", "a", "s1", "sock1"));
        store.set("conn_users:userid:u2", new SocketUser("u2", "b", "s2", "sock2"));
        store.set("userroom:roomids:u1", Set.of("r1"));
        assertThat(store.size()).isEqualTo(3);

        store.delete("conn_users:userid:u1");
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.get("conn_users:userid:u1", SocketUser.class)).isEmpty();
    }
}
