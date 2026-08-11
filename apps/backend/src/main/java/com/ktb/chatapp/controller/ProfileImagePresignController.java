package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.PresignedUploadRequest;
import com.ktb.chatapp.service.PresignedProfileImageService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile-images")
public class ProfileImagePresignController {

    private final ObjectProvider<PresignedProfileImageService> presignedProfileImageService;

    @PostMapping("/presign")
    public ResponseEntity<?> presign(
            @Valid @RequestBody PresignedUploadRequest request,
            Authentication authentication) {
        PresignedProfileImageService service = presignedProfileImageService.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("success", false, "message", "S3 업로드가 활성화되지 않았습니다."));
        }
        return ResponseEntity.ok(service.issue(request, authenticatedUserId(authentication)));
    }

    private String authenticatedUserId(Authentication authentication) {
        if (authentication != null && authentication.getDetails() instanceof Map<?, ?> details) {
            Object userId = details.get("userId");
            if (userId instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        throw new UsernameNotFoundException("인증된 사용자 ID를 찾을 수 없습니다.");
    }
}
