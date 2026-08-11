package com.ktb.chatapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3StorageConfig {

    @Bean
    S3Client s3Client(@Value("${file.s3.region}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean
    S3Presigner s3Presigner(@Value("${file.s3.region}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }
}
