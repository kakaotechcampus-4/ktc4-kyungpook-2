package com.itda.backend.service.storage;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

public interface RawFileStorage {

    /**
     * Persists the file under a generated, non-guessable name and returns the stored path.
     * The client-supplied filename is intentionally never used to build the storage path.
     */
    String store(MultipartFile file, String validatedExtension) throws IOException;

    /**
     * Best-effort delete for a previously stored key. Must not throw; implementations
     * log and swallow their own failures.
     *
     * <p>Currently unused: raw files are append-only, so {@code RawRecordService} keeps the
     * stored file and records a FAILED row instead of deleting it when the DB save fails.
     */
    void delete(String storedKey);
}
