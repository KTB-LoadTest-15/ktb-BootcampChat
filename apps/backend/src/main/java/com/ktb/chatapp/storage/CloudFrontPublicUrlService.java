package com.ktb.chatapp.storage;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 공개 객체 key를 CloudFront URL로 바꾼다. 프로필 이미지만 이 경로를 사용한다. */
@Component
@ConditionalOnProperty(name = "file.cdn.public-url.enabled", havingValue = "true")
public class CloudFrontPublicUrlService {

    private final String baseUrl;

    public CloudFrontPublicUrlService(@Value("${file.cdn.base-url}") String baseUrl) {
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("file.cdn.base-url must start with https://");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public URI url(String objectKey) {
        String encodedKey = Arrays.stream(objectKey.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        return URI.create(baseUrl + "/" + encodedKey);
    }
}
