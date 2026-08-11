package com.ktb.chatapp.dto;

public record ProfileImagePresignResponse(
        boolean success,
        String uploadUrl,
        String objectKey) {
}
