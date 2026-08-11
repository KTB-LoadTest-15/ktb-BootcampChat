package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;

public record ProfileImageConfirmRequest(
        @NotBlank String objectKey) {
}
