package com.itda.backend.service.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;

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
     * <p>Raw files are append-only once accepted, so {@code RawRecordService} keeps the stored
     * file and records a FAILED row instead of deleting it when the DB save fails. Files that were
     * never accepted (rejected or abandoned direct uploads, #450) are deleted.
     */
    void delete(String storedKey);

    /** 저장소 안 이름. 클라이언트가 보낸 파일 이름은 쓰지 않는다 — 추측할 수 없는 UUID 다. */
    static String newKey(String validatedExtension) {
        return UUID.randomUUID() + (validatedExtension.isBlank() ? "" : "." + validatedExtension);
    }

    /**
     * 브라우저가 이 저장소로 파일 본문을 바로 올릴 주소 (#450). BE 는 파일을 거치지 않고 권한만 확인한다.
     *
     * <p>주소에는 형식과 크기가 묶여 있다 — 다른 형식이나 크기로 올리면 저장소가 받지 않는다. 브라우저는 {@code headers} 를
     * 그대로 붙여 {@code PUT} 한다.
     *
     * @param uploadId 접수 id. 저장소가 BE 자신일 때(로컬) 주소를 만드는 데 쓴다
     */
    PresignedUpload presignUpload(Long uploadId, String storedKey, String contentType, long sizeBytes, Duration ttl);

    /** 올라온 파일의 크기. 아직 없으면 비어 있다. */
    OptionalLong sizeOf(String storedKey) throws IOException;

    /** 올라온 파일 전체. 크기 상한은 부르는 쪽이 {@link #sizeOf} 로 먼저 확인한다. */
    byte[] read(String storedKey) throws IOException;
}
