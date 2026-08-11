package com.ktb.chatapp.storage;

import java.net.URI;

public record PresignedUpload(URI uploadUrl, String objectKey) {
}
