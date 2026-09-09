package com.itda.backend.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.dto.RawRecordResponse;
import com.itda.backend.service.RawRecordService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/raw-records")
@RequiredArgsConstructor
public class RawRecordController {

    private final RawRecordService rawRecordService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RawRecordResponse> upload(
            @RequestParam("institutionId") String institutionId,
            @RequestParam("file") MultipartFile file) {
        RawRecord saved = rawRecordService.ingest(institutionId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(RawRecordResponse.from(saved));
    }

    @GetMapping("/{id}")
    public RawRecordResponse getOne(@PathVariable Long id) {
        return RawRecordResponse.from(rawRecordService.getById(id));
    }

    @GetMapping
    public List<RawRecordResponse> list(@RequestParam String institutionId) {
        return rawRecordService.getByInstitution(institutionId).stream()
                .map(RawRecordResponse::from)
                .toList();
    }
}
