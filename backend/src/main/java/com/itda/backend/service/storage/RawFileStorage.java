package com.itda.backend.service.storage;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

public interface RawFileStorage {

    /**
     * Persists the file under a generated, non-guessable name and returns the stored path.
     * The client-supplied filename is intentionally never used to build the storage path.
     */
    String store(MultipartFile file, String validatedExtension) throws IOException;
}
