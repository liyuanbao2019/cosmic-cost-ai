package com.example.cosmiccostai.controller;

import com.example.cosmiccostai.common.ApiResponse;
import com.example.cosmiccostai.dto.AiPromptRequest;
import com.example.cosmiccostai.dto.AiPromptResponse;
import com.example.cosmiccostai.dto.AiReviewValidationRequest;
import com.example.cosmiccostai.dto.AiReviewValidationResponse;
import com.example.cosmiccostai.dto.CosmicAnalyzeResponse;
import com.example.cosmiccostai.dto.ExtractResponse;
import com.example.cosmiccostai.dto.ExcelExportResponse;
import com.example.cosmiccostai.dto.ExportFileActionResponse;
import com.example.cosmiccostai.dto.ParseResponse;
import com.example.cosmiccostai.dto.ProjectListResponse;
import com.example.cosmiccostai.dto.ReviewResponse;
import com.example.cosmiccostai.dto.RuleValidationResponse;
import com.example.cosmiccostai.dto.WordExportResponse;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.example.cosmiccostai.service.AiExtractionService;
import com.example.cosmiccostai.service.AiReviewValidationService;
import com.example.cosmiccostai.service.CosmicAnalysisService;
import com.example.cosmiccostai.service.DocumentParseService;
import com.example.cosmiccostai.service.ExcelExportService;
import com.example.cosmiccostai.service.ExportFileService;
import com.example.cosmiccostai.service.ProjectListService;
import com.example.cosmiccostai.service.ReviewService;
import com.example.cosmiccostai.service.RuleValidationService;
import com.example.cosmiccostai.service.WordExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
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

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "项目处理")
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final DocumentParseService documentParseService;
    private final AiExtractionService aiExtractionService;
    private final CosmicAnalysisService cosmicAnalysisService;
    private final ReviewService reviewService;
    private final ExcelExportService excelExportService;
    private final WordExportService wordExportService;
    private final ExportFileService exportFileService;
    private final RuleValidationService ruleValidationService;
    private final AiReviewValidationService aiReviewValidationService;
    private final ProjectListService projectListService;

    public ProjectController(DocumentParseService documentParseService,
                             AiExtractionService aiExtractionService,
                             CosmicAnalysisService cosmicAnalysisService,
                             ReviewService reviewService,
                             ExcelExportService excelExportService,
                             WordExportService wordExportService,
                             ExportFileService exportFileService,
                             RuleValidationService ruleValidationService,
                             AiReviewValidationService aiReviewValidationService,
                             ProjectListService projectListService) {
        this.documentParseService = documentParseService;
        this.aiExtractionService = aiExtractionService;
        this.cosmicAnalysisService = cosmicAnalysisService;
        this.reviewService = reviewService;
        this.excelExportService = excelExportService;
        this.wordExportService = wordExportService;
        this.exportFileService = exportFileService;
        this.ruleValidationService = ruleValidationService;
        this.aiReviewValidationService = aiReviewValidationService;
        this.projectListService = projectListService;
    }

    @Operation(summary = "List projects", description = "List historical projects from local storage.")
    @GetMapping
    public ApiResponse<ProjectListResponse> listProjects() {
        return ApiResponse.ok(projectListService.listProjects());
    }

    @Operation(summary = "Delete project", description = "Delete a local project and all files under its storage directory.")
    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> deleteProject(@PathVariable String projectId) {
        projectListService.deleteProject(projectId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "Batch delete projects", description = "Delete multiple local projects and all files under their storage directories.")
    @PostMapping("/batch-delete")
    public ApiResponse<Integer> deleteProjects(@RequestBody List<String> projectIds) {
        return ApiResponse.ok(projectListService.deleteProjects(projectIds));
    }

    @Operation(summary = "解析需求说明书", description = "解析已上传的 docx 文件，结果写入 document-text.txt。")
    @PostMapping("/{projectId}/parse")
    public ApiResponse<ParseResponse> parse(@PathVariable String projectId) {
        return ApiResponse.ok(documentParseService.parseProject(projectId));
    }

    @Operation(summary = "AI 结构化提取", description = "只将 document-text.txt 中的“需求细则”章节发送给 AI，输出 module/function/description。")
    @PostMapping("/{projectId}/extract")
    public ApiResponse<ExtractResponse> extract(@PathVariable String projectId) {
        return ApiResponse.ok(aiExtractionService.extract(projectId));
    }

    @Operation(summary = "Get AI prompt", description = "Return the model name and full prompt used before AI extraction.")
    @GetMapping("/{projectId}/ai-prompt")
    public ApiResponse<AiPromptResponse> getAiPrompt(@PathVariable String projectId) {
        return ApiResponse.ok(aiExtractionService.getPrompt(projectId));
    }

    @Operation(summary = "Re-run AI extraction with edited prompt", description = "Use the user edited prompt to call AI again and overwrite extracted functions.")
    @PostMapping("/{projectId}/ai-prompt/extract")
    public ApiResponse<ExtractResponse> extractWithPrompt(@PathVariable String projectId, @RequestBody AiPromptRequest request) {
        return ApiResponse.ok(aiExtractionService.extractWithPrompt(projectId, request == null ? "" : request.getPrompt()));
    }

    @Operation(summary = "COSMIC analysis", description = "Analyze extracted functions and generate entry/read/write/exit/cfp.")
    @PostMapping("/{projectId}/cosmic")
    public ApiResponse<CosmicAnalyzeResponse> cosmic(@PathVariable String projectId) {
        return ApiResponse.ok(cosmicAnalysisService.analyze(projectId));
    }

    @Operation(summary = "Get review data", description = "Load reviewed or AI-generated COSMIC results for manual review.")
    @GetMapping("/{projectId}/review")
    public ApiResponse<ReviewResponse> getReview(@PathVariable String projectId) {
        return ApiResponse.ok(reviewService.getReview(projectId));
    }

    @Operation(summary = "Save review data", description = "Save manually reviewed COSMIC results before exporting Excel and Word.")
    @PutMapping("/{projectId}/review")
    public ApiResponse<ReviewResponse> saveReview(@PathVariable String projectId, @RequestBody List<FunctionSplitRow> rows) {
        return ApiResponse.ok(reviewService.saveReview(projectId, rows));
    }

    @Operation(summary = "Validate review rows", description = "Validate current function split rows with deterministic COSMIC rules.")
    @PostMapping("/{projectId}/review/validate")
    public ApiResponse<RuleValidationResponse> validateReview(@PathVariable String projectId,
                                                              @RequestBody List<FunctionSplitRow> rows) {
        return ApiResponse.ok(ruleValidationService.validate(rows));
    }

    @Operation(summary = "AI validate selected review risks", description = "Use AI to review selected high-risk rows after deterministic validation.")
    @PostMapping("/{projectId}/review/ai-validate")
    public ApiResponse<AiReviewValidationResponse> aiValidateReview(@PathVariable String projectId,
                                                                    @RequestBody AiReviewValidationRequest request) {
        return ApiResponse.ok(aiReviewValidationService.validate(request));
    }

    @Operation(summary = "Export Excel", description = "Fill COSMIC analysis results into sheet 2 of the fixed Excel template.")
    @PostMapping("/{projectId}/export/excel")
    public ApiResponse<ExcelExportResponse> exportExcel(@PathVariable String projectId) {
        return ApiResponse.ok(excelExportService.export(projectId));
    }

    @Operation(summary = "Export Word", description = "Generate a COSMIC cost report Word document from cosmic-results.json.")
    @PostMapping("/{projectId}/export/word")
    public ApiResponse<WordExportResponse> exportWord(@PathVariable String projectId) {
        return ApiResponse.ok(wordExportService.export(projectId));
    }

    @Operation(summary = "Get export file info", description = "Return export file path, download URL, and local open capability.")
    @GetMapping("/{projectId}/exports/{fileType}/info")
    public ApiResponse<ExportFileActionResponse> exportFileInfo(@PathVariable String projectId,
                                                                @PathVariable String fileType) {
        return ApiResponse.ok(exportFileService.info(projectId, fileType));
    }

    @Operation(summary = "Open export directory", description = "Open export directory on Windows desktop environment.")
    @PostMapping("/{projectId}/exports/{fileType}/open-directory")
    public ApiResponse<ExportFileActionResponse> openExportDirectory(@PathVariable String projectId,
                                                                     @PathVariable String fileType) {
        return ApiResponse.ok(exportFileService.openDirectory(projectId, fileType));
    }

    @Operation(summary = "Download export file", description = "Download exported Excel or Word file.")
    @GetMapping("/{projectId}/exports/{fileType}/download")
    public ResponseEntity<Resource> downloadExportFile(@PathVariable String projectId,
                                                       @PathVariable String fileType) {
        Resource resource = exportFileService.download(projectId, fileType);
        String fileName = exportFileService.fileName(projectId, fileType);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fallbackDownloadFileName(fileType) + "\"; filename*=UTF-8''"
                                + encodeFileName(fileName))
                .body(resource);
    }

    private String fallbackDownloadFileName(String fileType) {
        if ("excel".equalsIgnoreCase(fileType)) {
            return "download.xlsx";
        }
        if ("word".equalsIgnoreCase(fileType)) {
            return "download.docx";
        }
        if ("business".equalsIgnoreCase(fileType)) {
            return "download.xlsx";
        }
        return "download";
    }

    private String encodeFileName(String fileName) {
        try {
            String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
            return encoded.replace("%28", "(").replace("%29", ")");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 encoding is not supported", ex);
        }
    }
}
