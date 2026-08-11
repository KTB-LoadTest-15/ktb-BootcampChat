package com.ktb.chatapp.config;

import io.lettuce.core.ReadFrom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

/**
 * Redis 연결 팩토리를 Sentinel(1 Primary + 2 Replica) 고가용성 + 읽기/쓰기 분산(ReadFrom)으로 정의한다.
 *
 * <p><b>이 빈은 {@code app.redis.sentinel.nodes}가 비어있지 않을 때만 생성된다</b>({@link ConditionalOnExpression}).
 * sentinel 미설정(로컬/테스트/기존 단일노드)일 때는 이 빈이 백오프하고 Spring Boot 자동설정 팩토리가
 * 그대로 쓰인다. 자동설정은 {@code spring.data.redis.*}뿐 아니라 Testcontainers {@code @ServiceConnection}이
 * 만드는 {@code RedisConnectionDetails}(랜덤 매핑 포트)도 존중하므로, 여기서 팩토리를 무조건 덮어쓰면
 * 통합테스트가 컨테이너 포트를 못 보고 localhost:6379로 붙어 실패한다(그래서 conditional로 격리).
 * 또한 standalone은 replica가 없어 ReadFrom이 무의미하므로 sentinel일 때만 관여하는 게 맞다.
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

    /**
     * Sentinel 노드가 설정된 경우에만 생성되는 팩토리. 비어 있으면 이 빈은 백오프하고
     * Boot 자동설정(standalone / @ServiceConnection)이 팩토리를 담당한다.
     */
    @Bean
    @ConditionalOnExpression("!'${app.redis.sentinel.nodes:}'.isBlank()")
    public LettuceConnectionFactory redisConnectionFactory() {
        ReadFrom readFrom = parseReadFrom(readFromName);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .readFrom(readFrom)
                .build();

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
}
