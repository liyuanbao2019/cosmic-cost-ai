package com.li.jc.cosmiccostai.controller;

import com.li.jc.cosmiccostai.common.ApiResponse;
import com.li.jc.cosmiccostai.dto.ReviewResponse;
import com.li.jc.cosmiccostai.dto.UploadResponse;
import com.li.jc.cosmiccostai.service.ExcelReviewImportService;
import com.li.jc.cosmiccostai.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "文件上传")
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;
    private final ExcelReviewImportService excelReviewImportService;

    public FileController(FileStorageService fileStorageService, ExcelReviewImportService excelReviewImportService) {
        this.fileStorageService = fileStorageService;
        this.excelReviewImportService = excelReviewImportService;
    }

    @Operation(summary = "上传需求说明书", description = "支持 docx 和 pdf，保存原始文件并生成项目元数据。")
    @PostMapping("/upload")
    public ApiResponse<UploadResponse> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "projectName", required = false) String projectName,
                                               @RequestParam(value = "splitLevel", defaultValue = "REQUIREMENT") String splitLevel) {
        return ApiResponse.ok(fileStorageService.upload(file, projectName, splitLevel));
    }

    @Operation(summary = "Import reviewed split Excel", description = "Import a pre-split Excel file and load it into the normal review workflow.")
    @PostMapping("/upload-split-excel")
    public ApiResponse<ReviewResponse> uploadSplitExcel(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(value = "projectName", required = false) String projectName,
                                                        @RequestParam(value = "splitLevel", defaultValue = "REQUIREMENT") String splitLevel,
                                                        @RequestParam(value = "polishFunctionDescription", defaultValue = "false") boolean polishFunctionDescription) {
        return ApiResponse.ok(excelReviewImportService.importSplitExcel(file, projectName, splitLevel, polishFunctionDescription));
    }

    @Operation(summary = "Validate reviewed split Excel", description = "Validate one pre-split Excel without creating a project or starting any processing.")
    @PostMapping("/validate-split-excel")
    public ApiResponse<Integer> validateSplitExcel(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "projectName", required = false) String projectName) {
        return ApiResponse.ok(excelReviewImportService.validateSplitExcel(file, projectName));
    }
}
