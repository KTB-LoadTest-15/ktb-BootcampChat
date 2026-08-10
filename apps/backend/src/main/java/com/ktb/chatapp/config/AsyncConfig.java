package com.ktb.chatapp.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 실행 설정.
 *
 * <p>Socket.IO 브로드캐스트/집계처럼 이벤트 처리 스레드(Netty worker)에서 굳이 동기로 할 필요가 없는
 * fire-and-forget 작업을 전용 풀로 오프로드하기 위한 executor를 제공한다. 메시지 전송 hot path가
 * room-list 활성도 집계·브로드캐스트를 기다리지 않도록 한다(P1-5).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * room-list 활성도 알림 등 비핵심 브로드캐스트 전용 풀.
     *
     * <p>바운드 큐 + {@code CallerRunsPolicy}: 포화 시 조용히 유실하지 않고 호출 스레드에서 실행해
     * 백프레셔를 준다(정상 부하에서는 event-loop에서 분리, 극단 포화에서만 인라인).
     */
    @Bean("socketBroadcastExecutor")
    public Executor socketBroadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("room-activity-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
