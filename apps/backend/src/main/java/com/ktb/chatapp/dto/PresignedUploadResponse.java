package com.ktb.chatapp.dto;

public record PresignedUploadResponse(
        boolean success,
        String uploadUrl,
        String objectKey,
        FileResponse file) {
}
