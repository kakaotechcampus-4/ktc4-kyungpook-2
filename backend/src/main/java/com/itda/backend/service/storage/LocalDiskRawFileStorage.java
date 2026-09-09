package com.itda.backend.service.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
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

        Path target = baseDir.resolve(storedFilename).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IOException("resolved storage path escapes base directory");
        }

        file.transferTo(target);
        return storedFilename;
    }
}
