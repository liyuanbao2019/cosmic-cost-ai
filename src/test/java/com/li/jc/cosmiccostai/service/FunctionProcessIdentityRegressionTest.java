package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.dto.ReviewResponse;
import com.li.jc.cosmiccostai.model.CosmicResult;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import com.li.jc.cosmiccostai.model.ProjectMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionProcessIdentityRegressionTest {

    @Test
    void movementHeadingDifferencesDoNotSplitOneFunctionalProcess() throws Exception {
        FileStorageService storage = new FileStorageService("target/test-function-process-identity-storage");
        String projectId = "identity-regression-project";
        Path projectDir = storage.resolveProjectDir(projectId);
        Files.createDirectories(projectDir);
        Files.write(projectDir.resolve("document-outline.tsv"), Arrays.asList(
                "1\t业务功能需求",
                "2\t接口新增地址信息",
                "3\t2.1.1按照需求生成接口文件（新增）",
                "4\t功能描述"), StandardCharsets.UTF_8);
        String detail = "业务功能需求\n"
                + "接口新增地址信息\n"
                + "在现有PPPOE接口中增加地址。\n"
                + "2.1.1按照需求生成接口文件（新增）\n"
                + "功能描述\n"
                + "读取地址表并写入PPPOE接口文件。";
        Path requirementPath = projectDir.resolve("requirement-detail.txt");
        Files.write(requirementPath, detail.getBytes(StandardCharsets.UTF_8));

        ExtractedFunction function = new ExtractedFunction();
        function.setFunction("新增PPPOE接口地址信息");
        function.setSourceReferences(Arrays.asList("REQ-L0003", "REQ-L0006"));
        Path extractedPath = projectDir.resolve("extracted-functions.json");
        new ObjectMapper().writeValue(extractedPath.toFile(), Collections.singletonList(function));

        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setId(projectId);
        metadata.setName("测试项目");
        metadata.setRequirementDetailPath(requirementPath.toString());
        metadata.setExtractedFunctionsPath(extractedPath.toString());
        metadata.setCosmicResultsPath(projectDir.resolve("cosmic-results.json").toString());
        metadata.setStatus("ANALYZED");
        storage.saveMetadata(metadata);

        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>(Arrays.asList(
                row("E", "发起接口文件生成", "REQ-L0003"),
                row("R", "读取地址信息", "REQ-L0006"),
                row("W", "写入PPPOE接口文件", "REQ-L0003")));

        ReviewResponse response = new ReviewService(storage).saveReview(projectId, rows);

        assertEquals(1, response.getResults().size());
        CosmicResult result = response.getResults().get(0);
        assertEquals(3, result.getMovements().size());
        assertEquals("E", result.getMovements().get(0).getType());
        assertEquals("W", result.getMovements().get(2).getType());
        assertTrue(result.getFunctionProcessId().startsWith("FP-"));
        String processId = response.getSplitRows().get(0).getFunctionProcessId();
        for (FunctionSplitRow row : response.getSplitRows()) {
            assertEquals(processId, row.getFunctionProcessId());
            assertEquals("接口新增地址信息", row.getLevel1Module());
            assertEquals("按照需求生成接口文件（新增）", row.getLevel2Module());
            assertEquals("新增PPPOE接口地址信息", row.getLevel3Module());
        }
    }

    private FunctionSplitRow row(String movement, String description, String reference) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setProjectName("测试项目");
        row.setLevel1Module("接口新增地址信息");
        row.setFunctionProcess("新增PPPOE接口地址信息");
        row.setFunctionalUser("发起者：用户\n接收者：系统");
        row.setTriggerEvent("用户发起接口文件生成");
        row.setSubProcessDescription(description);
        row.setMovementType(movement);
        row.setDataGroup("PPPOE接口文件");
        row.setDataAttributes("地址编码");
        row.setReuseDegree("新增");
        row.setCfp(1D);
        row.setSourceReferences(Collections.singletonList(reference));
        return row;
    }
}
