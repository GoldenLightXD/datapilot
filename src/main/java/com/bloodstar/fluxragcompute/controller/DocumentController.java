package com.bloodstar.fluxragcompute.controller;

import com.bloodstar.fluxragcompute.common.BaseResponse;
import com.bloodstar.fluxragcompute.common.ResultUtils;
import com.bloodstar.fluxragcompute.dto.DocumentUploadResponse;
import com.bloodstar.fluxragcompute.service.DocumentUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * curl -X POST 'http://localhost:8081/api/documents/upload' \
 *   -H 'Content-Type: multipart/form-data' \
 *   -F 'file=@/absolute/path/to/architecture.pdf'
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentUploadService documentUploadService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<DocumentUploadResponse> upload(@RequestPart("file") MultipartFile file) {
        return ResultUtils.success(documentUploadService.uploadAndDispatch(file));
    }
}
