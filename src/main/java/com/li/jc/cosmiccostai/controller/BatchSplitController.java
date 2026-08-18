package com.li.jc.cosmiccostai.controller;

import com.li.jc.cosmiccostai.common.ApiResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitConfirmRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitCreateRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitItemsRequest;
import com.li.jc.cosmiccostai.dto.BatchSplitJobResponse;
import com.li.jc.cosmiccostai.dto.BatchSplitSummaryResponse;
import com.li.jc.cosmiccostai.service.BatchSplitService;
import com.li.jc.cosmiccostai.service.BatchSplitSummaryExcelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

@Tag(name = "批量拆分")
@RestController
@RequestMapping("/api/projects/batch-split")
public class BatchSplitController {

    private final BatchSplitService batchSplitService;
    private final BatchSplitSummaryExcelService batchSplitSummaryExcelService;

    public BatchSplitController(BatchSplitService batchSplitService,
                                BatchSplitSummaryExcelService batchSplitSummaryExcelService) {
        this.batchSplitService = batchSplitService;
        this.batchSplitSummaryExcelService = batchSplitSummaryExcelService;
    }

    @Operation(summary = "Create batch split", description = "Parse selected Word projects without starting AI work.")
    @PostMapping
    public ApiResponse<BatchSplitJobResponse> create(@RequestBody BatchSplitCreateRequest request) {
        return ApiResponse.ok(batchSplitService.create(request));
    }

    @Operation(summary = "Create project group", description = "Combine completed requirement-level results into a separately managed project-level project.")
    @PostMapping("/project-groups")
    public ApiResponse<BatchSplitJobResponse> createProjectGroup(@RequestBody BatchSplitCreateRequest request) {
        return ApiResponse.ok(batchSplitService.createProjectGroup(request));
    }

    @Operation(summary = "List project groups", description = "List separately managed project-level projects.")
    @GetMapping("/project-groups")
    public ApiResponse<List<BatchSplitJobResponse>> listProjectGroups() {
        return ApiResponse.ok(batchSplitService.listProjectGroups());
    }

    @Operation(summary = "Get project group", description = "Resolve a project-level project number to its current execution batch and members.")
    @GetMapping("/project-groups/{projectGroupId}")
    public ApiResponse<BatchSplitJobResponse> getProjectGroup(@PathVariable String projectGroupId) {
        return ApiResponse.ok(batchSplitService.getProjectGroup(projectGroupId));
    }

    @Operation(summary = "Delete project group", description = "Delete one project-level container and aggregate material versions without deleting linked requirements.")
    @DeleteMapping("/project-groups/{projectGroupId}")
    public ApiResponse<Void> deleteProjectGroup(@PathVariable String projectGroupId) {
        batchSplitService.deleteProjectGroup(projectGroupId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "Batch delete project groups", description = "Delete multiple project-level containers and aggregate material versions.")
    @PostMapping("/project-groups/batch-delete")
    public ApiResponse<Integer> deleteProjectGroups(@RequestBody List<String> projectGroupIds) {
        return ApiResponse.ok(batchSplitService.deleteProjectGroups(projectGroupIds));
    }

    @Operation(summary = "Get batch split", description = "Get preparation, confirmation and execution progress.")
    @GetMapping("/{batchId}")
    public ApiResponse<BatchSplitJobResponse> get(@PathVariable String batchId) {
        return ApiResponse.ok(batchSplitService.get(batchId));
    }

    @Operation(summary = "Get batch split summary", description = "Summarize final CFP for every completed requirement in a requirement-level or project-level batch.")
    @GetMapping("/{batchId}/summary")
    public ApiResponse<BatchSplitSummaryResponse> summary(@PathVariable String batchId) {
        return ApiResponse.ok(batchSplitService.summary(batchId));
    }

    @Operation(summary = "Download batch split summary", description = "Download the requirement-level or project-level batch CFP summary as Excel.")
    @GetMapping("/{batchId}/summary/download")
    public ResponseEntity<Resource> downloadSummary(@PathVariable String batchId) {
        Path path = batchSplitSummaryExcelService.export(batchSplitService.summary(batchId));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(path.getFileName().toString(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(path.toFile().length())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new PathResource(path));
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

    @Operation(summary = "Add project members", description = "Add completed requirements or new uploads to an existing project-level project.")
    @PostMapping("/{batchId}/items")
    public ApiResponse<BatchSplitJobResponse> addItems(@PathVariable String batchId,
                                                        @RequestBody BatchSplitItemsRequest request) {
        return ApiResponse.ok(batchSplitService.addItems(batchId, request));
    }

    @Operation(summary = "Rerun project member", description = "Rerun one requirement without rerunning the other completed project members.")
    @PostMapping("/{batchId}/items/{projectId}/rerun")
    public ApiResponse<BatchSplitJobResponse> rerunItem(@PathVariable String batchId,
                                                        @PathVariable String projectId) {
        return ApiResponse.ok(batchSplitService.rerunItem(batchId, projectId));
    }

    @Operation(summary = "Cancel current AI recognition", description = "Cancel one queued or AI-recognizing requirement without stopping the batch or service.")
    @PostMapping("/{batchId}/items/{projectId}/cancel")
    public ApiResponse<BatchSplitJobResponse> cancelItem(@PathVariable String batchId,
                                                         @PathVariable String projectId) {
        return ApiResponse.ok(batchSplitService.cancelItem(batchId, projectId));
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

    @Operation(summary = "Generate requirement material bundle", description = "Generate every completed requirement's attachment 1-3 archive and wrap all requirement archives in one ZIP.")
    @PostMapping("/{batchId}/requirement-materials")
    public ApiResponse<BatchSplitJobResponse> exportRequirementMaterials(@PathVariable String batchId) {
        return ApiResponse.ok(batchSplitService.exportRequirementMaterials(batchId));
    }

    @Operation(summary = "Download requirement material bundle", description = "Download the ZIP containing every successfully generated single-requirement material archive.")
    @GetMapping("/{batchId}/requirement-materials/download")
    public ResponseEntity<Resource> downloadRequirementMaterials(@PathVariable String batchId) {
        Path path = batchSplitService.requirementMaterialArchiveFile(batchId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(path.getFileName().toString(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(path.toFile().length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new PathResource(path));
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
