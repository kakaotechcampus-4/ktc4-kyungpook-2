package com.itda.backend.service.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;

public interface RawFileStorage {

    void delete(String storedKey);

    static String newKey(String validatedExtension) {
        return UUID.randomUUID() + (validatedExtension.isBlank() ? "" : "." + validatedExtension);
    }

    PresignedUpload presignUpload(Long uploadId, String storedKey, String contentType, long sizeBytes, Duration ttl);

    OptionalLong sizeOf(String storedKey) throws IOException;

    byte[] read(String storedKey) throws IOException;
}
