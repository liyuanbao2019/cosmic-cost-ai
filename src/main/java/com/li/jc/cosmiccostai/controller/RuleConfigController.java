package com.li.jc.cosmiccostai.controller;

import com.li.jc.cosmiccostai.common.ApiResponse;
import com.li.jc.cosmiccostai.dto.SplitRuleConfigRequest;
import com.li.jc.cosmiccostai.dto.SplitRuleConfigResponse;
import com.li.jc.cosmiccostai.service.SplitRuleConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "初审规则配置")
@RestController
@RequestMapping("/api/rules")
public class RuleConfigController {

    private final SplitRuleConfigService splitRuleConfigService;

    public RuleConfigController(SplitRuleConfigService splitRuleConfigService) {
        this.splitRuleConfigService = splitRuleConfigService;
    }

    @Operation(summary = "Get split rules", description = "Return editable split validation rules used by AI extraction and manual rule precheck.")
    @GetMapping("/split")
    public ApiResponse<SplitRuleConfigResponse> getSplitRules() {
        SplitRuleConfigResponse response = new SplitRuleConfigResponse();
        response.setContent(splitRuleConfigService.getRules());
        response.setPath(splitRuleConfigService.getRulePath());
        return ApiResponse.ok(response);
    }

    @Operation(summary = "Save split rules", description = "Save editable split validation rules.")
    @PutMapping("/split")
    public ApiResponse<SplitRuleConfigResponse> saveSplitRules(@RequestBody SplitRuleConfigRequest request) {
        String content = splitRuleConfigService.saveRules(request == null ? "" : request.getContent());
        SplitRuleConfigResponse response = new SplitRuleConfigResponse();
        response.setContent(content);
        response.setPath(splitRuleConfigService.getRulePath());
        return ApiResponse.ok(response);
    }
}
