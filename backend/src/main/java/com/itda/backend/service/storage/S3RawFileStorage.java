package com.itda.backend.service.storage;

import java.io.IOException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@Profile("docker")
public class S3RawFileStorage implements RawFileStorage {

    private final S3Client s3Client;
    private final String bucket;

    public S3RawFileStorage(S3Client s3Client, @Value("${app.raw-storage.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String store(MultipartFile file, String validatedExtension) throws IOException {
        String key = UUID.randomUUID() + (validatedExtension.isBlank() ? "" : "." + validatedExtension);

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        return key;
    }
}
