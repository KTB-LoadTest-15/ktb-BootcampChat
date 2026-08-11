package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.PresignedUploadRequest;
import com.ktb.chatapp.dto.PresignedUploadResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.storage.PresignedUpload;
import com.ktb.chatapp.storage.PresignedUploadPort;
import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.util.FileUtil;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class PresignedFileUploadService {

    private final PresignedUploadPort presignedUploadPort;
    private final FileRepository fileRepository;

    @Value("${file.s3.presign-put-ttl:10m}")
    private Duration uploadUrlTtl;

    public PresignedUploadResponse issue(PresignedUploadRequest request, String uploaderId) {
        FileUtil.validateFileMetadata(request.originalname(), request.mimetype(), request.size());

        String originalname = FileUtil.normalizeOriginalFilename(
                StringUtils.cleanPath(request.originalname()));
        String filename = FileUtil.generateSafeFileName(originalname);
        String objectKey = StorageKey.chat(filename);

        PresignedUpload upload = presignedUploadPort.presignPut(
                objectKey, request.mimetype(), request.size(), uploadUrlTtl);

        File savedFile = fileRepository.save(File.builder()
                .filename(filename)
                .originalname(originalname)
                .mimetype(request.mimetype())
                .size(request.size())
                .path(objectKey)
                .user(uploaderId)
                .uploadDate(LocalDateTime.now())
                .build());

        return new PresignedUploadResponse(
                true,
                upload.uploadUrl().toString(),
                upload.objectKey(),
                FileResponse.from(savedFile));
    }
}
