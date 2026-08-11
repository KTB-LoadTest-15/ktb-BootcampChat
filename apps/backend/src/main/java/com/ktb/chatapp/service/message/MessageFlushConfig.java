package com.ktb.chatapp.service.message;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치 flusher가 켜졌을 때만 스케줄링을 활성화한다.
 * flush를 끄면(Redis-only 최후안) 스케줄러 자체가 뜨지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "message.flush.enabled", havingValue = "true")
@EnableScheduling
public class MessageFlushConfig {
}
