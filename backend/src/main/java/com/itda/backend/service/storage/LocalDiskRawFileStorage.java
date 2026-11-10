package com.itda.backend.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile("!docker")
public class LocalDiskRawFileStorage implements RawFileStorage {

    public static final String UPLOAD_CONTENT_PATH = "/api/v1/raw-records/uploads/%d/content";

    private final Path baseDir;

    public LocalDiskRawFileStorage(@Value("${app.raw-storage.base-dir}") String baseDir) throws IOException {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        Files.createDirectories(this.baseDir);
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

    @Override
    public PresignedUpload presignUpload(Long uploadId, String storedKey, String contentType, long sizeBytes,
            Duration ttl) {
        return new PresignedUpload(UPLOAD_CONTENT_PATH.formatted(uploadId), Map.of("Content-Type", contentType),
                Instant.now().plus(ttl));
    }

    @Override
    public OptionalLong sizeOf(String storedKey) throws IOException {
        Path target = requireWithinBase(storedKey);
        return Files.exists(target) ? OptionalLong.of(Files.size(target)) : OptionalLong.empty();
    }

    @Override
    public byte[] read(String storedKey) throws IOException {
        return Files.readAllBytes(requireWithinBase(storedKey));
    }

    public long write(String storedKey, InputStream body, long maxBytes) throws IOException {
        Path target = requireWithinBase(storedKey);
        Path temporary = Files.createTempFile(baseDir, ".upload-", ".part");
        try {
            long written;
            try (InputStream limited = body) {
                written = Files.copy(new BoundedInputStream(limited, maxBytes), temporary,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return written;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path requireWithinBase(String key) throws IOException {
        Path target = resolveWithinBase(key);
        if (target == null) {
            throw new IOException("resolved storage path escapes base directory");
        }
        return target;
    }

    private Path resolveWithinBase(String key) {
        Path target = baseDir.resolve(key).normalize();
        return target.startsWith(baseDir) ? target : null;
    }

    private static final class BoundedInputStream extends InputStream {

        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long maxBytes) {
            this.in = in;
            this.remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && --remaining < 0) {
                throw new IOException("upload body exceeds the declared size");
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                remaining -= n;
                if (remaining < 0) {
                    throw new IOException("upload body exceeds the declared size");
                }
            }
            return n;
        }
    }
}
