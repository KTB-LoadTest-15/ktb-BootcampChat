package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.corundumstudio.socketio.store.StoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.util.StringUtils;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    @Value("${socketio.server.origin:*}")
    private String origin;

    /**
     * Socket.IO 클라이언트/룸 레지스트리 및 이벤트 fan-out 저장소 선택.
     * memory(기본)=단일노드 인메모리(MemoryStoreFactory). redisson=Redis 백업
     * RedissonStoreFactory로, getRoomOperations(...).sendEvent(...) 브로드캐스트가
     * 공유 Redis pub/sub을 통해 클러스터 전역으로 전달된다(다중 인스턴스 필수).
     */
    @Value("${socketio.store:memory}")
    private String storeType;

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * socketio.store=redisson일 때만 생성되는, Socket.IO 전용 RedissonClient.
     * 애플리케이션의 spring.data.redis.* 설정과 같은 Redis 인스턴스를 가리킨다.
     * netty-socketio의 RedissonStoreFactory가 이 클라이언트로 클러스터 전역 pub/sub을 수행한다.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "socketio.store", havingValue = "redisson")
    public RedissonClient socketIoRedissonClient() {
        Config config = new Config();
        var single = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);
        if (StringUtils.hasText(redisPassword)) {
            single.setPassword(redisPassword);
        }
        log.info("Socket.IO RedissonStoreFactory enabled (redis://{}:{}) — cluster-wide broadcast", redisHost, redisPort);
        return Redisson.create(config);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener, MeterRegistry meterRegistry,
                                         ObjectProvider<RedissonClient> redissonProvider) {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);
        
        var socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        // 채팅은 소형 프레임이 빈번하다. Nagle(작은 패킷을 모아 보냄)은 이런 워크로드에서
        // 메시지당 최대 ~수십 ms 지연을 유발하므로 끈다(tcpNoDelay=true).
        socketConfig.setTcpNoDelay(true);
        socketConfig.setAcceptBackLog(10);
        socketConfig.setTcpSendBufferSize(4096);
        socketConfig.setTcpReceiveBufferSize(4096);
        config.setSocketConfig(socketConfig);

        config.setOrigin(origin);

        // Socket.IO settings
        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));

        // 저장소/브로드캐스트 팩토리: redisson이면 클러스터 전역(다중 인스턴스), 아니면 단일노드 인메모리.
        RedissonClient redisson = redissonProvider.getIfAvailable();
        StoreFactory storeFactory = (redisson != null)
                ? new RedissonStoreFactory(redisson)
                : new MemoryStoreFactory();
        config.setStoreFactory(storeFactory);

        log.info("Socket.IO server configured on {}:{} store={} ({} boss / {} worker threads)",
                 host, port, storeFactory.getClass().getSimpleName(),
                 config.getBossThreads(), config.getWorkerThreads());
        var socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addEventInterceptor((client, name, data, ack) -> {
            // 이벤트 발생 빈도 수집
            Counter.builder("socketio.events.total")
                .description("Total Socket.IO events received")
                .tag("event_type", name)
                .register(meterRegistry)
                .increment();
        });
        
        return socketIOServer;
    }
    
    /**
     * SpringAnnotationScanner는 BeanPostProcessor로서
     * ApplicationContext 초기화 초기에 등록되고,
     * 내부에서 사용하는 SocketIOServer는 Lazy로 지연되어
     * 다른 Bean들의 초기화 과정에 간섭하지 않게 한다.
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }
    
    // 인메모리 저장소, 단일 노드 환경에서만 사용
    @Bean
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public ChatDataStore chatDataStore() {
        return new LocalChatDataStore();
    }

    /**
     * 중복 로그인 유예 종료 예약용 공유 스케줄러.
     *
     * <p>접속마다 {@code new Thread(){sleep}}를 만들던 것을 대체한다(로그인 폭주 시 스레드 폭발/네이티브
     * 메모리 고갈 방지, P1-7). 예약 작업은 유예 후 session_ended 1건을 보내는 가벼운 작업이라 단일
     * 데몬 스레드로 충분하다.
     */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public java.util.concurrent.ScheduledExecutorService duplicateLoginScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dup-login-grace");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 읽음 커서 브로드캐스트 coalescing 창을 만료시키는 단일 데몬 스케줄러.
     *
     * <p>방마다 창당 flush 1건을 예약하는 가벼운 작업이라 단일 데몬 스레드로 충분하다.
     */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public java.util.concurrent.ScheduledExecutorService readReceiptScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "read-receipt-coalesce");
            t.setDaemon(true);
            return t;
        });
    }
}
