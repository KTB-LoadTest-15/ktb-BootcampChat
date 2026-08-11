package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record PresignedUploadRequest(
        @NotBlank String originalname,
        @NotBlank String mimetype,
        @Positive long size) {
}
