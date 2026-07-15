package com.example.cosmiccostai.controller;

import com.example.cosmiccostai.common.ApiResponse;
import com.example.cosmiccostai.dto.AiPromptRequest;
import com.example.cosmiccostai.dto.AiPromptResponse;
import com.example.cosmiccostai.dto.AiReviewValidationRequest;
import com.example.cosmiccostai.dto.AiReviewValidationResponse;
import com.example.cosmiccostai.dto.CosmicAnalyzeResponse;
import com.example.cosmiccostai.dto.DeepSplitJobResponse;
import com.example.cosmiccostai.dto.DeepSplitRequest;
import com.example.cosmiccostai.dto.DocumentChapterSelectionRequest;
import com.example.cosmiccostai.dto.DocumentChapterSelectionResponse;
import com.example.cosmiccostai.dto.ExtractResponse;
import com.example.cosmiccostai.dto.ExcelExportResponse;
import com.example.cosmiccostai.dto.ExportFileActionResponse;
import com.example.cosmiccostai.dto.ExportProgressResponse;
import com.example.cosmiccostai.dto.ParseResponse;
import com.example.cosmiccostai.dto.ProjectListResponse;
import com.example.cosmiccostai.dto.ReviewResponse;
import com.example.cosmiccostai.dto.RuleValidationResponse;
import com.example.cosmiccostai.dto.RequirementCoverageApplyRequest;
import com.example.cosmiccostai.dto.RequirementCoverageResponse;
import com.example.cosmiccostai.dto.RequirementInputRequest;
import com.example.cosmiccostai.dto.WordExportResponse;
import com.example.cosmiccostai.model.FunctionSplitRow;
import com.example.cosmiccostai.service.AiExtractionService;
import com.example.cosmiccostai.service.AiReviewValidationService;
import com.example.cosmiccostai.service.CosmicAnalysisService;
import com.example.cosmiccostai.service.DocumentParseService;
import com.example.cosmiccostai.service.DocumentChapterSelectionService;
import com.example.cosmiccostai.service.DeepSplitAiService;
import com.example.cosmiccostai.service.ExcelExportService;
import com.example.cosmiccostai.service.ExportFileService;
import com.example.cosmiccostai.service.ExportProgressService;
import com.example.cosmiccostai.service.MaterialArchiveService;
import com.example.cosmiccostai.service.ProjectListService;
import com.example.cosmiccostai.service.ReviewService;
import com.example.cosmiccostai.service.RequirementCoverageService;
import com.example.cosmiccostai.service.RequirementInputService;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ExportProgressService exportProgressService;
    private final MaterialArchiveService materialArchiveService;
    private final RuleValidationService ruleValidationService;
    private final AiReviewValidationService aiReviewValidationService;
    private final DeepSplitAiService deepSplitAiService;
    private final ProjectListService projectListService;
    private final RequirementCoverageService requirementCoverageService;
    private final RequirementInputService requirementInputService;
    private final DocumentChapterSelectionService documentChapterSelectionService;

    public ProjectController(DocumentParseService documentParseService,
                             AiExtractionService aiExtractionService,
                             CosmicAnalysisService cosmicAnalysisService,
                             ReviewService reviewService,
                             ExcelExportService excelExportService,
                             WordExportService wordExportService,
                             ExportFileService exportFileService,
                             ExportProgressService exportProgressService,
                             MaterialArchiveService materialArchiveService,
                             RuleValidationService ruleValidationService,
                             AiReviewValidationService aiReviewValidationService,
                             DeepSplitAiService deepSplitAiService,
                             ProjectListService projectListService,
                             RequirementCoverageService requirementCoverageService,
                             RequirementInputService requirementInputService,
                             DocumentChapterSelectionService documentChapterSelectionService) {
        this.documentParseService = documentParseService;
        this.aiExtractionService = aiExtractionService;
        this.cosmicAnalysisService = cosmicAnalysisService;
        this.reviewService = reviewService;
        this.excelExportService = excelExportService;
        this.wordExportService = wordExportService;
        this.exportFileService = exportFileService;
        this.exportProgressService = exportProgressService;
        this.materialArchiveService = materialArchiveService;
        this.ruleValidationService = ruleValidationService;
        this.aiReviewValidationService = aiReviewValidationService;
        this.deepSplitAiService = deepSplitAiService;
        this.projectListService = projectListService;
        this.requirementCoverageService = requirementCoverageService;
        this.requirementInputService = requirementInputService;
        this.documentChapterSelectionService = documentChapterSelectionService;
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

    @Operation(summary = "Get selectable document chapters", description = "Return locally parsed top-level chapters and current AI input selection.")
    @GetMapping("/{projectId}/document-chapters")
    public ApiResponse<DocumentChapterSelectionResponse> documentChapters(@PathVariable String projectId) {
        return ApiResponse.ok(documentChapterSelectionService.get(projectId));
    }

    @Operation(summary = "Apply document chapter selection", description = "Generate the actual AI input from selected local document chapters.")
    @PutMapping("/{projectId}/document-chapters")
    public ApiResponse<DocumentChapterSelectionResponse> applyDocumentChapters(@PathVariable String projectId,
                                                                                @RequestBody DocumentChapterSelectionRequest request) {
        return ApiResponse.ok(documentChapterSelectionService.apply(projectId,
                request == null ? null : request.getChapterIds()));
    }

    @Operation(summary = "Get requirement coverage", description = "Compare business function modules in the original parsed Word text with the actual AI input.")
    @GetMapping("/{projectId}/requirement-coverage")
    public ApiResponse<RequirementCoverageResponse> requirementCoverage(@PathVariable String projectId) {
        return ApiResponse.ok(requirementCoverageService.getCoverage(projectId));
    }

    @Operation(summary = "Include missing requirement modules", description = "Append selected original business modules to the AI input before extraction.")
    @PostMapping("/{projectId}/requirement-coverage/include")
    public ApiResponse<RequirementCoverageResponse> includeRequirementCoverage(@PathVariable String projectId,
                                                                                @RequestBody RequirementCoverageApplyRequest request) {
        return ApiResponse.ok(requirementCoverageService.includeModules(projectId,
                request == null ? null : request.getModuleNumbers()));
    }

    @Operation(summary = "Save edited AI requirement input", description = "Persist a user-corrected requirement input and use it for subsequent AI extraction.")
    @PutMapping("/{projectId}/requirement-input")
    public ApiResponse<Void> saveRequirementInput(@PathVariable String projectId,
                                                   @RequestBody RequirementInputRequest request) {
        requirementInputService.saveManualInput(projectId, request == null ? "" : request.getContent());
        return ApiResponse.ok(null);
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
    public ApiResponse<ReviewResponse> saveReview(@PathVariable String projectId,
                                                  @RequestParam(value = "polishFunctionDescription", defaultValue = "false") boolean polishFunctionDescription,
                                                  @RequestBody List<FunctionSplitRow> rows) {
        return ApiResponse.ok(reviewService.saveReview(projectId, rows, polishFunctionDescription));
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

    @Operation(summary = "Start AI deep split review", description = "Start an asynchronous, chunked full-table review with requirement evidence retrieval.")
    @PostMapping("/{projectId}/review/deep-split")
    public ApiResponse<DeepSplitJobResponse> startDeepSplit(@PathVariable String projectId,
                                                            @RequestBody DeepSplitRequest request) {
        return ApiResponse.ok(deepSplitAiService.start(projectId, request));
    }

    @Operation(summary = "Get AI deep split progress", description = "Return persisted progress and accumulated suggestions for a deep split job.")
    @GetMapping("/{projectId}/review/deep-split/{jobId}")
    public ApiResponse<DeepSplitJobResponse> getDeepSplit(@PathVariable String projectId,
                                                          @PathVariable String jobId) {
        return ApiResponse.ok(deepSplitAiService.get(projectId, jobId));
    }

    @Operation(summary = "Retry failed AI deep split chunks", description = "Retry only failed chunks while retaining completed chunk results.")
    @PostMapping("/{projectId}/review/deep-split/{jobId}/retry")
    public ApiResponse<DeepSplitJobResponse> retryDeepSplit(@PathVariable String projectId,
                                                            @PathVariable String jobId) {
        return ApiResponse.ok(deepSplitAiService.retryFailed(projectId, jobId));
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

    @Operation(summary = "Get Word export progress", description = "Return AI function description generation progress during Word export.")
    @GetMapping("/{projectId}/export/word/progress")
    public ApiResponse<ExportProgressResponse> wordExportProgress(@PathVariable String projectId) {
        return ApiResponse.ok(exportProgressService.get(projectId));
    }

    @Operation(summary = "Export review material archive", description = "Create a ZIP containing attachment 1, attachment 2, and attachment 3 after all three files have been exported.")
    @PostMapping("/{projectId}/export/archive")
    public ApiResponse<ExportFileActionResponse> exportArchive(@PathVariable String projectId) {
        return ApiResponse.ok(materialArchiveService.export(projectId));
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
        if ("archive".equalsIgnoreCase(fileType)) {
            return "download.zip";
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
