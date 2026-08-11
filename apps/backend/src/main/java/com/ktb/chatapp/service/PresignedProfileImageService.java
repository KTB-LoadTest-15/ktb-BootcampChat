package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.PresignedUploadRequest;
import com.ktb.chatapp.dto.ProfileImagePresignResponse;
import com.ktb.chatapp.dto.ProfileImageResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.storage.PresignedUpload;
import com.ktb.chatapp.storage.PresignedUploadPort;
import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.util.FileUtil;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class PresignedProfileImageService {

    private final PresignedUploadPort presignedUploadPort;
    private final StoragePort storagePort;
    private final UserRepository userRepository;

    @Value("${file.s3.presign-put-ttl:10m}")
    private Duration uploadUrlTtl;

    public ProfileImagePresignResponse issue(PresignedUploadRequest request, String userId) {
        FileUtil.validateFileMetadata(request.originalname(), request.mimetype(), request.size());
        if (!request.mimetype().startsWith("image/")) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }

        String originalname = FileUtil.normalizeOriginalFilename(
                StringUtils.cleanPath(request.originalname()));
        String filename = userId + "_" + FileUtil.generateSafeFileName(originalname);
        String objectKey = StorageKey.profile(filename);
        PresignedUpload upload = presignedUploadPort.presignPut(
                objectKey, request.mimetype(), request.size(), uploadUrlTtl);

        return new ProfileImagePresignResponse(
                true, upload.uploadUrl().toString(), upload.objectKey());
    }

    public ProfileImageResponse confirm(String objectKey, String userId) {
        String ownedPrefix = "profiles/" + userId + "_";
        if (objectKey == null || !objectKey.startsWith(ownedPrefix)) {
            throw new IllegalArgumentException("잘못된 프로필 이미지 key입니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        String previousImage = user.getProfileImage();
        user.setProfileImage(objectKey);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        if (previousImage != null && !previousImage.isBlank()
                && !previousImage.equals(objectKey)) {
            try {
                storagePort.delete(previousImage);
            } catch (RuntimeException ignored) {
                // 새 프로필은 이미 반영됐으므로 이전 객체 정리 실패로 응답을 되돌리지 않는다.
            }
        }

        return ProfileImageResponse.updated(objectKey);
    }
}
