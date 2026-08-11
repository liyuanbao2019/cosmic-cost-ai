package com.li.jc.cosmiccostai.controller;

import com.li.jc.cosmiccostai.common.ApiResponse;
import com.li.jc.cosmiccostai.dto.AiModelSettings;
import com.li.jc.cosmiccostai.service.AiModelConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "大模型管理")
@RestController
@RequestMapping("/api/ai-models")
public class AiModelController {
    private final AiModelConfigService service;

    public AiModelController(AiModelConfigService service) { this.service = service; }

    @Operation(summary = "查询大模型配置")
    @GetMapping
    public ApiResponse<AiModelSettings> get() { return ApiResponse.ok(service.getSettings()); }

    @Operation(summary = "保存并立即应用大模型配置")
    @PutMapping
    public ApiResponse<AiModelSettings> save(@RequestBody AiModelSettings request) {
        return ApiResponse.ok(service.save(request));
    }
}
