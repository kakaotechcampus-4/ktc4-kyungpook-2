package com.itda.backend.service.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Component
@Profile("docker")
public class S3RawFileStorage implements RawFileStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public S3RawFileStorage(S3Client s3Client, S3Presigner s3Presigner,
            @Value("${app.raw-storage.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
    }

    @Override
    public String store(MultipartFile file, String validatedExtension) throws IOException {
        String key = RawFileStorage.newKey(validatedExtension);

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        return key;
    }

    @Override
    public void delete(String storedKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storedKey).build());
        } catch (RuntimeException e) {
            log.warn("failed to delete orphaned S3 object: {}", storedKey, e);
        }
    }

    /**
     * 형식과 크기를 서명에 넣는다 — 브라우저는 {@code Content-Length} 를 본문 크기로 직접 채우므로, 접수한 크기와 다른 파일은 S3 가
     * 서명 불일치로 받지 않는다. 브라우저가 붙일 수 있는 헤더는 {@code Content-Type} 뿐이라 그것만 돌려준다.
     */
    @Override
    public PresignedUpload presignUpload(Long uploadId, String storedKey, String contentType, long sizeBytes,
            Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storedKey)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build());
        return new PresignedUpload(presigned.url().toString(), Map.of("Content-Type", contentType),
                presigned.expiration());
    }

    @Override
    public OptionalLong sizeOf(String storedKey) throws IOException {
        try {
            return OptionalLong.of(s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(storedKey).build())
                    .contentLength());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return OptionalLong.empty();
            }
            throw new IOException("failed to read S3 object metadata", e);
        } catch (SdkException e) {
            throw new IOException("failed to read S3 object metadata", e);
        }
    }

    @Override
    public byte[] read(String storedKey) throws IOException {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(storedKey).build())
                    .asByteArray();
        } catch (SdkException e) {
            throw new IOException("failed to read S3 object", e);
        }
    }
}
