package com.ktb.chatapp.perf;

import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 측정용 {@link CommandCountingListener}를 자동 구성된 MongoClient에 연결하는 테스트 설정.
 * 성능 측정 통합테스트에서 {@code @Import}하여 사용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MongoCommandCounterConfig {

    @Bean
    public CommandCountingListener commandCountingListener() {
        return new CommandCountingListener();
    }

    @Bean
    public MongoClientSettingsBuilderCustomizer commandCounterCustomizer(CommandCountingListener listener) {
        return builder -> builder.addCommandListener(listener);
    }
}
