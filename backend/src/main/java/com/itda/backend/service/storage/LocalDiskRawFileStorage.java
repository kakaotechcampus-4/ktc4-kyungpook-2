package com.itda.backend.service.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile("!docker")
public class LocalDiskRawFileStorage implements RawFileStorage {

    private final Path baseDir;

    public LocalDiskRawFileStorage(@Value("${app.raw-storage.base-dir}") String baseDir) throws IOException {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        Files.createDirectories(this.baseDir);
    }

    @Override
    public String store(MultipartFile file, String validatedExtension) throws IOException {
        String storedFilename = UUID.randomUUID()
                + (validatedExtension.isBlank() ? "" : "." + validatedExtension);

        Path target = resolveWithinBase(storedFilename);
        if (target == null) {
            throw new IOException("resolved storage path escapes base directory");
        }

        file.transferTo(target);
        return storedFilename;
    }

    @Override
    public void delete(String storedKey) {
        Path target = resolveWithinBase(storedKey);
        if (target == null) {
            log.warn("refused to delete path outside base directory: {}", storedKey);
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (Exception e) {
            log.warn("failed to delete orphaned local file: {}", storedKey, e);
        }
    }

    private Path resolveWithinBase(String key) {
        Path target = baseDir.resolve(key).normalize();
        return target.startsWith(baseDir) ? target : null;
    }
}
