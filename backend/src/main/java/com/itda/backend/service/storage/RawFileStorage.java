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
     * Best-effort compensating delete for a previously stored key — used to clean up
     * an orphaned file after a downstream failure (e.g. DB save). Must not throw;
     * implementations log and swallow their own failures.
     */
    void delete(String storedKey);
}
