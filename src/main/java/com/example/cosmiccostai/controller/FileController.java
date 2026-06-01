package com.example.cosmiccostai.controller;

import com.example.cosmiccostai.common.ApiResponse;
import com.example.cosmiccostai.dto.UploadResponse;
import com.example.cosmiccostai.service.FileStorageService;
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

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Operation(summary = "上传需求说明书", description = "支持 docx 和 pdf，保存原始文件并生成项目元数据。")
    @PostMapping("/upload")
    public ApiResponse<UploadResponse> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "projectName", required = false) String projectName) {
        return ApiResponse.ok(fileStorageService.upload(file, projectName));
    }
}
