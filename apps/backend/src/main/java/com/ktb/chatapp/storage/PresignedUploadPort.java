package com.ktb.chatapp.storage;

import java.time.Duration;

public interface PresignedUploadPort {
    PresignedUpload presignPut(String objectKey, String contentType, long size, Duration ttl);
}
