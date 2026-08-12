package com.li.jc.cosmiccostai.controller;

import com.li.jc.cosmiccostai.common.ApiResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitConfirmRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitCreateRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitJobResponse;
import com.li.jc.cosmiccostai.service.BatchSplitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Tag(name = "批量拆分")
@RestController
@RequestMapping("/api/projects/batch-split")
public class BatchSplitController {

    private final BatchSplitService batchSplitService;

    public BatchSplitController(BatchSplitService batchSplitService) {
        this.batchSplitService = batchSplitService;
    }

    @Operation(summary = "Create batch split", description = "Parse selected Word projects without starting AI work.")
    @PostMapping
    public ApiResponse<BatchSplitJobResponse> create(@RequestBody BatchSplitCreateRequest request) {
        return ApiResponse.ok(batchSplitService.create(request));
    }

    @Operation(summary = "Get batch split", description = "Get preparation, confirmation and execution progress.")
    @GetMapping("/{batchId}")
    public ApiResponse<BatchSplitJobResponse> get(@PathVariable String batchId) {
        return ApiResponse.ok(batchSplitService.get(batchId));
    }

    @Operation(summary = "Confirm AI input", description = "Freeze the selected chapters and final AI input for one document.")
    @PutMapping("/{batchId}/items/{projectId}/ai-input")
    public ApiResponse<BatchSplitJobResponse> confirm(@PathVariable String batchId,
                                                       @PathVariable String projectId,
                                                       @RequestBody BatchSplitConfirmRequest request) {
        return ApiResponse.ok(batchSplitService.confirm(batchId, projectId, request));
    }

    @Operation(summary = "Start batch split", description = "Start concurrent split only after every document input is confirmed.")
    @PostMapping("/{batchId}/start")
    public ApiResponse<BatchSplitJobResponse> start(@PathVariable String batchId) {
        return ApiResponse.ok(batchSplitService.start(batchId));
    }

    @Operation(summary = "Retry failed batch items", description = "Retry failed preparation or split items without rerunning successful items.")
    @PostMapping("/{batchId}/retry")
    public ApiResponse<BatchSplitJobResponse> retry(@PathVariable String batchId) {
        return ApiResponse.ok(batchSplitService.retryFailed(batchId));
    }

    @Operation(summary = "Retry project materials", description = "Regenerate project-level attachments 1-5 after a material export failure.")
    @PostMapping("/{batchId}/materials/retry")
    public ApiResponse<BatchSplitJobResponse> retryMaterials(@PathVariable String batchId) {
        return ApiResponse.ok(batchSplitService.retryProjectMaterials(batchId));
    }

    @Operation(summary = "Download project material", description = "Download one approved project-level attachment or the complete archive.")
    @GetMapping("/{batchId}/materials/{type}/download")
    public ResponseEntity<Resource> downloadMaterial(@PathVariable String batchId,
                                                     @PathVariable String type) {
        Path path = batchSplitService.projectMaterialFile(batchId, type);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(path.getFileName().toString(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(path.toFile().length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new PathResource(path));
    }
}
