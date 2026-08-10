package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.event.RoomActivityEvent;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;

/**
 * 방 활성도 알림이 호출 스레드(메시지 전송 hot path)에서 분리되어 전용 풀에서 처리됨을 검증한다(P1-5).
 *
 * <p>{@code @Async}가 Spring 프록시로 실제 적용되는 통합 컨텍스트에서, 이벤트가 여전히 올바른
 * roomId로 발행되며(동작 동치성) 처리는 {@code socketBroadcastExecutor} 스레드에서 일어남(오프로드)을
 * 함께 단언한다.
 */
@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class RoomActivityAsyncIntegrationTest {

    @Autowired
    private RoomActivityNotifier notifier;

    @Autowired
    private CapturingListener listener;

    @Test
    @DisplayName("notifyMessageStored는 호출 스레드가 아닌 전용 풀에서 실행되고 이벤트를 발행한다")
    void notifyMessageStored_offloadsToExecutor_andPublishesEvent() throws InterruptedException {
        String callerThread = Thread.currentThread().getName();

        notifier.notifyMessageStored("room-async-1");

        RoomActivityEvent event = listener.await(5, TimeUnit.SECONDS);

        // 동작 동치성: 이벤트가 올바른 roomId로 발행됨
        assertThat(event).isNotNull();
        assertThat(event.getRoomId()).isEqualTo("room-async-1");

        // 오프로드: 처리 스레드가 호출 스레드가 아니라 전용 풀(room-activity-*)
        assertThat(listener.handlingThread).isNotEqualTo(callerThread);
        assertThat(listener.handlingThread).startsWith("room-activity-");
    }

    @TestConfiguration
    static class Config {
        @Bean
        CapturingListener capturingListener() {
            return new CapturingListener();
        }
    }

    static class CapturingListener {
        private final BlockingQueue<RoomActivityEvent> queue = new LinkedBlockingQueue<>();
        volatile String handlingThread;

        @EventListener
        void onRoomActivity(RoomActivityEvent event) {
            this.handlingThread = Thread.currentThread().getName();
            this.queue.add(event);
        }

        RoomActivityEvent await(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }
    }
}
