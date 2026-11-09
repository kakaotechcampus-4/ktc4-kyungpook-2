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
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile("!docker")
public class LocalDiskRawFileStorage implements RawFileStorage {

    /** 로컬에서 브라우저가 파일 본문을 올리는 BE 주소 (#450). {@code LocalRawUploadController} 가 받는다. */
    public static final String UPLOAD_CONTENT_PATH = "/api/v1/raw-records/uploads/%d/content";

    private final Path baseDir;

    public LocalDiskRawFileStorage(@Value("${app.raw-storage.base-dir}") String baseDir) throws IOException {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        Files.createDirectories(this.baseDir);
    }

    @Override
    public String store(MultipartFile file, String validatedExtension) throws IOException {
        String storedFilename = RawFileStorage.newKey(validatedExtension);

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

    /**
     * 로컬에는 S3 가 없어 BE 자신의 주소를 준다. 이 주소는 다른 API 처럼 로그인 · CSRF 를 그대로 본다 — 브라우저는 주소가
     * {@code /} 로 시작하면 쿠키와 CSRF 헤더를 붙여 보낸다.
     */
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

    /**
     * 로컬 업로드 본문을 쓴다. 상한보다 길면 쓰지 않는다 — S3 가 서명한 크기와 다른 본문을 받지 않는 것과 같다. 임시 파일에 다 쓴
     * 뒤 옮겨서, 쓰다 끊긴 파일이 완료 확인에 걸리지 않게 한다.
     *
     * @return 실제로 쓴 바이트 수
     */
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

    /** 상한을 넘으면 읽기를 멈추고 실패한다. */
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
