package com.ktb.chatapp.config;

import io.lettuce.core.ReadFrom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

/**
 * Redis 연결 팩토리를 직접 정의해 Sentinel(1 Primary + 2 Replica) 고가용성과
 * 읽기/쓰기 분산(ReadFrom)을 제어한다. Spring Boot의 프로퍼티 기반 sentinel 자동설정은
 * sentinel 노드가 비어 있을 때 로컬(단일 노드) 부팅을 깨뜨리므로, 여기서 env 존재 여부로
 * 명시적으로 분기한다({@code app.redis.sentinel.nodes} 있으면 Sentinel, 없으면 standalone).
 *
 * <p><b>읽기/쓰기 분산 주의</b>: 쓰기는 항상 Primary로 가지만, replica read는 비동기 복제라
 * 방금 쓴 값을 replica에서 읽으면 stale일 수 있다(read-your-writes 위반). 이 앱의 세션/멤버십/
 * 메시지 읽기는 대부분 read-after-write hot path라 기본값을 {@code MASTER_PREFERRED}로 둔다.
 * 부하테스트에서 {@code REDIS_READ_FROM=REPLICA_PREFERRED}로 켜서 인증 실패율을 A/B 측정하고
 * 안전이 확인될 때만 유지할 것.
 */
@Slf4j
@Configuration
public class RedisConfig {

    /** MASTER_PREFERRED(기본,안전) | REPLICA_PREFERRED(읽기분산) | REPLICA | NEAREST 등 io.lettuce.core.ReadFrom 상수. */
    @Value("${app.redis.read-from:MASTER_PREFERRED}")
    private String readFromName;

    /** "host:port,host:port,..." 형식의 Sentinel 프로세스 주소들(데이터 노드 6379가 아니라 sentinel 포트, 보통 26379). 비어 있으면 standalone. */
    @Value("${app.redis.sentinel.nodes:}")
    private String sentinelNodes;

    /** sentinel monitor 에 등록된 마스터 이름(예: mymaster). */
    @Value("${app.redis.sentinel.master:mymaster}")
    private String sentinelMaster;

    /** sentinel 자체 인증을 쓰는 경우의 비밀번호(없으면 생략). */
    @Value("${app.redis.sentinel.password:}")
    private String sentinelPassword;

    /** Primary/Replica 데이터 노드 공통 비밀번호(requirepass). */
    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.host:localhost}")
    private String standaloneHost;

    @Value("${spring.data.redis.port:6379}")
    private int standalonePort;

    /**
     * env 값(UPPER_SNAKE, 예: REPLICA_PREFERRED)을 Lettuce {@link ReadFrom} 상수로 매핑한다.
     * 주의: {@code ReadFrom.valueOf(...)}는 필드명이 아니라 camelCase 토큰(replicaPreferred)만 받으므로
     * 직접 쓰면 안 된다. 여기서 상수를 직접 반환한다.
     */
    static ReadFrom parseReadFrom(String name) {
        String n = name == null ? "" : name.trim().toUpperCase().replace('-', '_');
        return switch (n) {
            case "MASTER", "PRIMARY", "UPSTREAM" -> ReadFrom.MASTER;
            case "MASTER_PREFERRED", "PRIMARY_PREFERRED", "UPSTREAM_PREFERRED", "" -> ReadFrom.MASTER_PREFERRED;
            case "REPLICA", "SLAVE" -> ReadFrom.REPLICA;
            case "REPLICA_PREFERRED", "SLAVE_PREFERRED" -> ReadFrom.REPLICA_PREFERRED;
            case "NEAREST", "LOWEST_LATENCY" -> ReadFrom.NEAREST;
            case "ANY" -> ReadFrom.ANY;
            case "ANY_REPLICA" -> ReadFrom.ANY_REPLICA;
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 REDIS_READ_FROM 값: '" + name + "' (MASTER|MASTER_PREFERRED|REPLICA|REPLICA_PREFERRED|NEAREST|ANY)");
        };
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        ReadFrom readFrom = parseReadFrom(readFromName);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .readFrom(readFrom)
                .build();

        if (StringUtils.hasText(sentinelNodes)) {
            RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration();
            sentinel.master(sentinelMaster);
            for (String node : sentinelNodes.split(",")) {
                String trimmed = node.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int idx = trimmed.lastIndexOf(':');
                String host = trimmed.substring(0, idx);
                int port = Integer.parseInt(trimmed.substring(idx + 1));
                sentinel.sentinel(host, port);
            }
            if (StringUtils.hasText(redisPassword)) {
                sentinel.setPassword(RedisPassword.of(redisPassword));
            }
            if (StringUtils.hasText(sentinelPassword)) {
                sentinel.setSentinelPassword(RedisPassword.of(sentinelPassword));
            }
            log.info("Redis Sentinel mode: master={} sentinels=[{}] readFrom={} (쓰기=Primary, 읽기={})",
                     sentinelMaster, sentinelNodes, readFrom, readFrom);
            return new LettuceConnectionFactory(sentinel, clientConfig);
        }

        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(standaloneHost, standalonePort);
        if (StringUtils.hasText(redisPassword)) {
            standalone.setPassword(RedisPassword.of(redisPassword));
        }
        log.info("Redis standalone mode: {}:{} readFrom={} (단일 노드, Sentinel 미설정)",
                 standaloneHost, standalonePort, readFrom);
        return new LettuceConnectionFactory(standalone, clientConfig);
    }
}
