package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.dto.RuleValidationIssue;
import com.li.jc.cosmiccostai.dto.RuleValidationResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RuleValidationService {

    private static final List<String> HIGH_RISK_KEYWORDS = Arrays.asList(
            "\u5339\u914d", "\u89e3\u6790", "\u6062\u590d", "\u6e05\u9664", "\u8ba1\u7b97", "\u8fc7\u6ee4", "\u5237\u65b0",
            "\u8c03\u7814", "\u8bbe\u8ba1", "\u6d4b\u8bd5", "\u4e0a\u7ebf", "\u8c03\u4f18", "UI", "UE",
            "\u89c4\u5219\u6821\u9a8c", "\u8f93\u5165\u6821\u9a8c", "\u5408\u6cd5\u6027\u6821\u9a8c", "\u652f\u6491",
            "\u96c6\u6210", "\u73b0\u573a", "\u5b9e\u65bd", "\u63a5\u53e3", "\u9875\u9762\u8bbe\u8ba1",
            "\u8c03\u7528", "\u65e5\u5fd7", "\u89e6\u53d1", "\u63d0\u793a", "\u7ffb\u9875", "\u52fe\u9009",
            "\u53d6\u6d88\u52fe\u9009", "\u56fe\u5c42\u5207\u6362", "\u81ea\u52a8\u586b\u5145", "\u9ad8\u4eae\u6e32\u67d3", "\u6682\u5b58",
            "\u90e8\u7f72", "\u8fc1\u79fb", "\u91cd\u7f6e\u67e5\u8be2\u6761\u4ef6", "\u6bcf\u9875\u6761\u6570", "Tab\u5207\u6362", "\u6253\u5f00\u5f39\u7a97");

    private static final Set<String> GENERIC_PROCESS_NAMES = new HashSet<String>(Arrays.asList(
            "\u67e5\u8be2", "\u67e5\u770b", "\u5c55\u793a", "\u7ba1\u7406", "\u65b0\u589e", "\u5220\u9664", "\u4fee\u6539", "\u4fdd\u5b58",
            "\u5bfc\u5165", "\u5bfc\u51fa", "\u4e0a\u4f20", "\u4e0b\u8f7d", "\u914d\u7f6e", "\u540c\u6b65", "\u53d1\u9001", "\u5ba1\u6838"));

    private static final List<String> PURE_FRONTEND_KEYWORDS = Arrays.asList(
            "\u7ffb\u9875", "\u52fe\u9009", "\u53d6\u6d88\u52fe\u9009", "\u56fe\u5c42\u5207\u6362", "\u81ea\u52a8\u586b\u5145", "\u9ad8\u4eae\u6e32\u67d3",
            "\u91cd\u7f6e\u67e5\u8be2\u6761\u4ef6", "\u6e05\u7a7a\u6761\u4ef6", "\u6bcf\u9875\u6761\u6570", "\u9875\u7801\u8df3\u8f6c", "\u5217\u8868\u5b57\u6bb5\u6392\u5e8f",
            "\u4e0b\u62c9\u6846", "\u9009\u9879\u52a0\u8f7d", "Tab \u5207\u6362", "Tab\u5207\u6362", "\u70b9\u51fb\u7edf\u8ba1\u5361\u7247", "\u9875\u9762\u8df3\u8f6c",
            "\u6253\u5f00\u5f39\u7a97", "\u6253\u5f00\u9875\u9762", "\u5173\u95ed\u5f39\u7a97", "\u53d6\u6d88\u64cd\u4f5c", "\u8fd4\u56de\u5217\u8868", "\u8be6\u60c5\u5c55\u793a");

    private static final List<String> DEVELOPMENT_TERMS = Arrays.asList(
            "\u7ebf\u7a0b", "\u6784\u9020\u51fd\u6570", "\u5bf9\u8c61\u5b9e\u4f8b", "Java\u7c7b", "\u5b9e\u4f53\u7c7b", "\u63a5\u53e3\u7c7b", "\u65b9\u6cd5\u8c03\u7528");

    private static final double CFP_TOLERANCE = 0.005D;
    private static final Set<String> GENERATED_BLOCKING_RISK_FIELDS = new HashSet<String>(Arrays.asList(
            "G功能过程", "H子过程描述", "功能过程", "数据移动结构", "数据移动组合",
            "数据组", "复用与 CFP", "拆分粒度", "纯前端操作", "开发术语", "日志审计", "持久化语义"));

    private final SplitRuleConfigService splitRuleConfigService;

    public RuleValidationService() {
        this(null);
    }

    @Autowired
    public RuleValidationService(SplitRuleConfigService splitRuleConfigService) {
        this.splitRuleConfigService = splitRuleConfigService;
    }

    public RuleValidationResponse validate(List<FunctionSplitRow> rows) {
        RuleValidationResponse response = new RuleValidationResponse();
        response.setRuleVersion(getRuleVersion());
        response.setTotalRows(rows == null ? 0 : rows.size());
        if (rows == null || rows.isEmpty()) {
            add(response, 0, "", "\u5168\u8868", "ERROR", "\u6ca1\u6709\u53ef\u6821\u9a8c\u7684\u62c6\u5206\u6570\u636e", "\u8bf7\u5148\u4e0a\u4f20\u6216\u751f\u6210\u529f\u80fd\u70b9\u62c6\u5206\u8868\u3002");
            finish(response);
            return response;
        }

        Map<String, List<RowRef>> groups = new LinkedHashMap<String, List<RowRef>>();
        for (int i = 0; i < rows.size(); i++) {
            FunctionSplitRow row = rows.get(i);
            int rowNumber = i + 1;
            validateRequired(response, row, rowNumber);
            validateEnums(response, row, rowNumber);
            validateCfpConsistency(response, row, rowNumber);
            validateMovementWording(response, row, rowNumber);
            validateAuditWording(response, row, rowNumber);
            validateHistoricalReviewDecision(response, row, rowNumber);
            validateDataAttributeAudit(response, row, rowNumber);
            validatePersistentDataGroupCardinality(response, row, rowNumber);

            String process = safe(row.getFunctionProcess());
            String key = FunctionProcessKey.of(row);
            if (!StringUtils.hasText(process)) {
                key = "__ROW_" + rowNumber;
            }
            List<RowRef> groupRows = groups.get(key);
            if (groupRows == null) {
                groupRows = new ArrayList<RowRef>();
                groups.put(key, groupRows);
            }
            groupRows.add(new RowRef(rowNumber, row));
        }
        Set<Integer> structureRiskRows = validateGroups(response, groups);
        validatePendingDataAttributes(response, groups);
        validateReuseAndDuplicateRules(response, groups);
        validateXinjiangScheduledEntryPolicy(response, groups);
        validateQueryDisplayOverlap(response, groups);
        List<String> highRiskKeywords = resolveHighRiskKeywords();
        for (int i = 0; i < rows.size(); i++) {
            validateKeywords(response, rows.get(i), i + 1, structureRiskRows.contains(i + 1), highRiskKeywords);
        }
        finish(response);
        return response;
    }

    private void validatePersistentDataGroupCardinality(RuleValidationResponse response,
                                                        FunctionSplitRow row,
                                                        int rowNumber) {
        String movement = safe(row.getMovementType()).toUpperCase(Locale.ROOT);
        if (!("R".equals(movement) || "W".equals(movement))) {
            return;
        }
        List<String> groups = explicitPersistentDataGroups(row.getDataGroup());
        if (groups.size() < 2) {
            return;
        }
        add(response, rowNumber, safe(row.getFunctionProcess()), "数据组", "RISK",
                "单个 " + movement + " 同时包含 " + groups.size()
                        + " 个明确持久化数据组（" + String.join("、", groups) + "）",
                "保持当前功能过程不变，按不同逻辑数据组分别拆成 " + groups.size() + " 个 "
                        + movement + "；同一对象仅更新多个字段时不得拆分。");
    }

    private List<String> explicitPersistentDataGroups(String dataGroup) {
        String value = safe(dataGroup);
        if (!StringUtils.hasText(value) || !value.matches(".*[、,，;；].*")) {
            return Collections.emptyList();
        }
        List<String> groups = new ArrayList<String>();
        for (String part : value.split("[、,，;；]+")) {
            String group = safe(part).replaceAll("^[及和与]", "");
            if (looksLikePersistentDataGroup(group) && !groups.contains(group)) {
                groups.add(group);
            }
        }
        return groups;
    }

    private boolean looksLikePersistentDataGroup(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return containsAny(value, "表", "记录", "台账", "清单", "档案", "文件", "数据库", "数据集")
                || value.endsWith("库存") || value.endsWith("数据");
    }

    /**
     * Deterministic gate shared by document AI generation and deep-review
     * replacement rows. Material-only risks such as a whole-cell "待确认" stay
     * reviewable, while known structural and non-countable output is blocked.
     */
    public List<RuleValidationIssue> generatedBlockingIssues(List<FunctionSplitRow> rows) {
        RuleValidationResponse response = validate(rows);
        List<RuleValidationIssue> blocking = new ArrayList<RuleValidationIssue>();
        for (RuleValidationIssue issue : response.getIssues()) {
            if (issue != null && ("ERROR".equals(issue.getSeverity())
                    || GENERATED_BLOCKING_RISK_FIELDS.contains(safe(issue.getField())))) {
                blocking.add(issue);
            }
        }
        return blocking;
    }

    public void ensureNoErrors(List<FunctionSplitRow> rows, String action) {
        RuleValidationResponse response = validate(rows);
        if (response.getErrorCount() <= 0) {
            return;
        }
        throw new com.li.jc.cosmiccostai.common.BusinessException(
                safe(action) + "未通过统一 COSMIC 规则门禁：" + summarize(response.getIssues(), "ERROR", 5));
    }

    public void ensureReadyForExport(List<FunctionSplitRow> rows, String action) {
        ensureReadyForExport(validate(rows), action);
    }

    public void ensureReadyForExport(RuleValidationResponse response, String action) {
        if (response == null) {
            throw new com.li.jc.cosmiccostai.common.BusinessException(
                    safe(action) + "未取得统一 COSMIC 规则校验结果，无法导出送审材料。");
        }
        // Both deterministic errors and structure risks remain visible in the
        // exported workbook for manual correction. Export is intentionally a
        // review aid rather than a hard approval gate.
    }

    public String getRuleVersion() {
        return splitRuleConfigService == null ? "builtin" : splitRuleConfigService.getRuleVersion();
    }

    private String summarize(List<RuleValidationIssue> issues, String severity, int limit) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (RuleValidationIssue issue : issues == null ? Collections.<RuleValidationIssue>emptyList() : issues) {
            if (issue == null || (StringUtils.hasText(severity) && !severity.equals(issue.getSeverity()))) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append("第").append(issue.getRowIndex()).append("行 ").append(safe(issue.getMessage()));
            if (++count >= limit) {
                break;
            }
        }
        return builder.toString();
    }

    private void validateRequired(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        require(response, row, rowNumber, row.getLevel1Module(), "B\u4e00\u7ea7\u6a21\u5757");
        require(response, row, rowNumber, row.getFunctionProcess(), "G\u529f\u80fd\u8fc7\u7a0b");
        require(response, row, rowNumber, row.getSubProcessDescription(), "H\u5b50\u8fc7\u7a0b\u63cf\u8ff0");
        require(response, row, rowNumber, row.getMovementType(), "I\u6570\u636e\u79fb\u52a8\u7c7b\u578b");
        require(response, row, rowNumber, row.getDataGroup(), "J\u6570\u636e\u7ec4");
        require(response, row, rowNumber, row.getDataAttributes(), "K\u6570\u636e\u5c5e\u6027");
        require(response, row, rowNumber, row.getReuseDegree(), "L\u590d\u7528\u5ea6");
    }

    private void require(RuleValidationResponse response, FunctionSplitRow row, int rowNumber, String value, String field) {
        if (!StringUtils.hasText(value)) {
            add(response, rowNumber, safe(row.getFunctionProcess()), field, "ERROR",
                    field + "\u5fc5\u586b", "\u8bf7\u8865\u5145\u8be5\u5b57\u6bb5\u540e\u518d\u5bfc\u51fa\u9001\u5ba1\u3002");
        }
    }

    private void validateEnums(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        String movement = safe(row.getMovementType()).toUpperCase(Locale.ROOT);
        if (StringUtils.hasText(movement) && !("E".equals(movement) || "R".equals(movement) || "W".equals(movement) || "X".equals(movement))) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "I\u6570\u636e\u79fb\u52a8\u7c7b\u578b", "ERROR",
                    "\u6570\u636e\u79fb\u52a8\u7c7b\u578b\u5fc5\u987b\u662f E/R/W/X", "\u8bf7\u6309 COSMIC \u6570\u636e\u79fb\u52a8\u7c7b\u578b\u4fee\u6b63\u3002");
        }
        String reuse = safe(row.getReuseDegree());
        if (StringUtils.hasText(reuse) && !("\u65b0\u589e".equals(reuse) || "\u590d\u7528".equals(reuse) || "\u5229\u65e7".equals(reuse))) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "L\u590d\u7528\u5ea6", "ERROR",
                    "\u590d\u7528\u5ea6\u5fc5\u987b\u662f\u65b0\u589e/\u590d\u7528/\u5229\u65e7", "\u8bf7\u4fee\u6b63\u590d\u7528\u5ea6\u679a\u4e3e\u503c\u3002");
        }
    }

    private void validateCfpConsistency(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        String reuse = safe(row.getReuseDegree());
        if (!("\u65b0\u589e".equals(reuse) || "\u590d\u7528".equals(reuse) || "\u5229\u65e7".equals(reuse))) {
            return;
        }
        double expected = "\u65b0\u589e".equals(reuse) ? 1D : ("\u590d\u7528".equals(reuse) ? 0.33D : 0D);
        if (Math.abs(row.getCfp() - expected) <= CFP_TOLERANCE) {
            return;
        }
        add(response, rowNumber, safe(row.getFunctionProcess()), "M CFP", "ERROR",
                "\u590d\u7528\u5ea6\u4e0e CFP \u4e0d\u4e00\u81f4\uff1a" + reuse + " \u5e94\u4e3a " + formatCfp(expected)
                        + "\uff0c\u5f53\u524d\u4e3a " + formatCfp(row.getCfp()),
                "\u6309\u7edf\u4e00\u53e3\u5f84\u4fee\u6b63\uff1a\u65b0\u589e=1\u3001\u590d\u7528=0.33\u3001\u5229\u65e7=0\u3002");
    }

    private String formatCfp(double value) {
        if (Math.abs(value - Math.rint(value)) <= CFP_TOLERANCE) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void validateAuditWording(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        String process = safe(row.getFunctionProcess());
        String description = safe(row.getSubProcessDescription());
        String text = process + " " + safe(row.getFunctionDescription()) + " " + description;
        if (GENERIC_PROCESS_NAMES.contains(process)) {
            add(response, rowNumber, process, "G\u529f\u80fd\u8fc7\u7a0b", "RISK",
                    "\u529f\u80fd\u8fc7\u7a0b\u53ea\u6709\u52a8\u4f5c\u8bcd\uff0c\u672a\u5199\u660e\u4e1a\u52a1\u5bf9\u8c61",
                    "\u6539\u4e3a\u201c\u52a8\u4f5c+\u4e1a\u52a1\u5bf9\u8c61\u201d\uff0c\u4f8b\u5982\u201c\u67e5\u8be2\u7528\u6237\u4fe1\u606f\u201d\u201c\u5220\u9664\u6620\u5c04\u89c4\u5219\u201d\u3002");
        }
        String frontendKeyword = firstContained(text, PURE_FRONTEND_KEYWORDS);
        if (StringUtils.hasText(frontendKeyword)) {
            add(response, rowNumber, process, "\u7eaf\u524d\u7aef\u64cd\u4f5c", "RISK",
                    "\u51fa\u73b0\u201c" + frontendKeyword + "\u201d\uff0c\u5bb9\u6613\u88ab\u5224\u5b9a\u4e3a\u65e0\u72ec\u7acb\u6570\u636e\u79fb\u52a8\u7684\u524d\u7aef\u4ea4\u4e92",
                    "\u5982\u679c\u53ea\u662f\u524d\u7aef\u72b6\u6001\u53d8\u5316\uff0c\u8bf7\u5220\u9664\u6216\u5408\u5e76\u5230\u76f8\u5173\u67e5\u8be2\u529f\u80fd\uff1b\u5982\u679c\u5b58\u5728\u540e\u7aef\u6570\u636e\u4ea4\u4e92\uff0c\u8bf7\u5199\u660e\u8bfb\u53d6/\u5199\u5165\u7684\u6301\u4e45\u5316\u6570\u636e\u7ec4\u548c\u8f93\u51fa\u7ed3\u679c\u3002");
        }
        String developmentTerm = firstContained(text, DEVELOPMENT_TERMS);
        if (StringUtils.hasText(developmentTerm)) {
            add(response, rowNumber, process, "\u5f00\u53d1\u672f\u8bed", "RISK",
                    "\u51fa\u73b0\u201c" + developmentTerm + "\u201d\u5f00\u53d1\u5b9e\u73b0\u672f\u8bed\uff0c\u4e0d\u662f\u529f\u80fd\u7528\u6237\u53ef\u8bc6\u522b\u7684\u4e1a\u52a1\u8fc7\u7a0b",
                    "\u8bf7\u56de\u5230\u529f\u80fd\u7528\u6237\u89c6\u89d2\uff0c\u6539\u5199\u4e3a\u8f93\u5165\u3001\u8bfb\u53d6\u3001\u5199\u5165\u6216\u8f93\u51fa\u7684\u4e1a\u52a1\u6570\u636e\u3002");
        }
        if (text.contains("\u65e5\u5fd7") && containsAny(text, "\u4fdd\u5b58\u65e5\u5fd7", "\u8bb0\u5f55\u65e5\u5fd7", "\u5220\u9664\u65e5\u5fd7")) {
            add(response, rowNumber, process, "\u65e5\u5fd7\u5ba1\u8ba1", "RISK",
                    "\u4fdd\u5b58/\u8bb0\u5f55/\u5220\u9664\u65e5\u5fd7\u5c5e\u4e8e\u9001\u5ba1\u9ad8\u98ce\u9669\u8868\u8ff0\uff0c\u53ef\u80fd\u88ab\u5224\u4e3a\u6280\u672f\u9644\u5e26\u52a8\u4f5c",
                    "\u82e5\u53ea\u662f\u540e\u53f0\u6280\u672f\u65e5\u5fd7\uff0c\u4e0d\u8981\u5355\u72ec\u8ba1\u91cf\uff1b\u53ea\u6709\u539f\u6587\u660e\u786e\u5b58\u5728\u72ec\u7acb\u529f\u80fd\u7528\u6237\u3001\u89e6\u53d1\u548c\u65e5\u5fd7\u7ba1\u7406\u6570\u636e\u79fb\u52a8\u65f6\u624d\u4fdd\u7559\u3002");
        }
        if (text.contains("\u6682\u5b58")) {
            add(response, rowNumber, process, "\u6301\u4e45\u5316\u8bed\u4e49", "RISK",
                    "\u201c\u6682\u5b58\u201d\u65e0\u6cd5\u8bc1\u660e\u6570\u636e\u5df2\u8fdb\u5165\u6301\u4e45\u6027\u5b58\u50a8",
                    "\u8bf7\u6838\u5bf9\u539f\u6587\uff1a\u5199\u5165\u6570\u636e\u5e93\u3001\u6301\u4e45\u6587\u4ef6\u6216\u53d7\u7ba1\u7406\u6570\u636e\u7ec4\u65f6\u6539\u5199\u4e3a\u201c\u5b58\u50a8\u201d\u5e76\u4fdd\u7559 W\uff1b\u4ec5\u5185\u5b58/\u9875\u9762\u4e34\u65f6\u72b6\u6001\u4e0d\u8ba1 W\u3002");
        }
        if (isGenericPlaceholderAttributes(row.getDataAttributes())) {
            add(response, rowNumber, process, "K\u6570\u636e\u5c5e\u6027", "RISK",
                    "\u6570\u636e\u5c5e\u6027\u4f7f\u7528\u4e86\u8bbe\u8ba1\u9662\u8bc4\u5ba1\u4e2d\u9ad8\u9891\u91cd\u590d\u7684\u6cdb\u5316\u5360\u4f4d\u7ec4\u5408",
                    "\u4f18\u5148\u6362\u6210\u9700\u6c42\u539f\u6587\u3001\u8868\u683c\u6216\u63a5\u53e3\u5b9a\u4e49\u4e2d\u7684\u771f\u5b9e\u4e1a\u52a1\u5c5e\u6027\uff1b\u65e0\u4f9d\u636e\u65f6\u6574\u683c\u6807\u8bb0\u201c\u5f85\u786e\u8ba4\u201d\uff0c\u4e0d\u5f97\u9760\u901a\u7528\u7f16\u53f7/\u72b6\u6001/\u65f6\u95f4\u5236\u9020\u5dee\u5f02\u3002");
        }
    }

    private boolean isGenericPlaceholderAttributes(String attributes) {
        String value = attributeComparable(attributes);
        return value.equals(attributeComparable("\u8bf7\u6c42\u7f16\u53f7\u3001\u64cd\u4f5c\u4eba\u3001\u8bf7\u6c42\u65f6\u95f4"))
                || value.equals(attributeComparable("\u4e1a\u52a1\u7f16\u53f7\u3001\u4e1a\u52a1\u72b6\u6001\u3001\u4e1a\u52a1\u5185\u5bb9"))
                || value.equals(attributeComparable("\u8bb0\u5f55\u7f16\u53f7\u3001\u5904\u7406\u72b6\u6001\u3001\u5904\u7406\u65f6\u95f4"))
                || value.equals(attributeComparable("\u7ed3\u679c\u7f16\u53f7\u3001\u7ed3\u679c\u72b6\u6001\u3001\u63d0\u793a\u4fe1\u606f"));
    }

    private void validateHistoricalReviewDecision(RuleValidationResponse response,
                                                    FunctionSplitRow row,
                                                    int rowNumber) {
        String reviewText = safe(row.getFunctionDescription()) + " " + safe(row.getEvidence());
        String decision = historicalReviewDecision(reviewText);
        if (!StringUtils.hasText(decision)) {
            return;
        }
        String suggestion = containsAny(decision, "N", "NM", "UIUE", "C", "NR", "\u7b97\u6cd5", "\u9700\u6c42\u6587\u6863\u4e0d\u6d89\u53ca")
                ? "\u8be5\u5386\u53f2\u5224\u4f8b\u901a\u5e38\u8868\u793a\u4e0d\u5b58\u5728\u3001\u65e0\u6570\u636e\u79fb\u52a8\u6216\u975e COSMIC \u5185\u5bb9\uff1b\u5e94\u5220\u9664\u65e0\u4f9d\u636e\u884c\uff0c\u7b97\u6cd5\u4ec5\u4fdd\u7559\u771f\u5b9e E/R/W/X\u3002"
                : "\u5386\u53f2\u5224\u4f8b\u4f18\u5148\u4e8e\u901a\u7528\u6a21\u677f\uff1b\u6838\u5bf9\u5bf9\u5e94\u53c2\u8003\u884c\u53ca\u529f\u80fd\u5305\u542b\u5173\u7cfb\uff0c\u529f\u80fd\u7ea7\u91cd\u590d/OLD \u6309 0\uff0c\u5b50\u8fc7\u7a0b\u590d\u7528\u5019\u9009\u6309 0.33 \u590d\u6838\u3002";
        add(response, rowNumber, safe(row.getFunctionProcess()), "\u5386\u53f2\u8bc4\u5ba1\u5224\u4f8b", "RISK",
                "\u8bc1\u636e/\u529f\u80fd\u63cf\u8ff0\u4e2d\u5305\u542b\u8bbe\u8ba1\u9662\u8bc4\u5ba1\u6807\u7b7e\u201c" + decision + "\u201d", suggestion);
    }

    private String historicalReviewDecision(String value) {
        String text = safe(value);
        for (String phrase : Arrays.asList("\u9700\u6c42\u6587\u6863\u4e0d\u6d89\u53ca", "N\u9700\u6c42\u4e0d\u6d89\u53ca", "\u529f\u80fd\u91cd\u590d", "\u5305\u542b\u5728", "\u5408\u5e76\u4e3a", "\u7b97\u6cd5")) {
            if (text.contains(phrase)) {
                return phrase;
            }
        }
        String normalized = text.toUpperCase(Locale.ROOT).trim();
        for (String prefix : Arrays.asList("评审备注：", "评审标签：", "REVIEW=")) {
            int index = normalized.indexOf(prefix);
            if (index < 0) {
                continue;
            }
            String remainder = normalized.substring(index + prefix.length()).trim();
            String token = remainder.split("[\\s,，;；]", 2)[0];
            if (token.matches("\\d+(?:[-~～—]\\d+)?")) {
                return token;
            }
        }
        for (String code : Arrays.asList("NM", "OLD", "UIUE", "NR", "N", "O", "C")) {
            if (normalized.equals(code) || normalized.contains("\u8bc4\u5ba1\u5907\u6ce8\uff1a" + code)
                    || normalized.contains("\u8bc4\u5ba1\u6807\u7b7e\uff1a" + code)
                    || normalized.contains("REVIEW=" + code)) {
                return code;
            }
        }
        return "";
    }

    private void validateDataAttributeAudit(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        String attributes = safe(row.getDataAttributes());
        if (!StringUtils.hasText(attributes) || "\u5f85\u786e\u8ba4".equals(attributes)) {
            return;
        }
        if (containsAny(attributes, "\u540c\u4e0a", "\u89c1\u4e0a", "\u4e0e\u4e0a\u76f8\u540c")) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "K\u6570\u636e\u5c5e\u6027", "RISK",
                    "\u6570\u636e\u5c5e\u6027\u4f7f\u7528\u201c\u540c\u4e0a/\u89c1\u4e0a\u201d\uff0c\u65e0\u6cd5\u72ec\u7acb\u5ba1\u67e5\u590d\u7528\u5ea6",
                    "\u8bf7\u628a\u539f\u6587\u660e\u786e\u652f\u6301\u7684\u5b57\u6bb5\u5b8c\u6574\u5199\u5165\u672c\u884c\uff1b\u539f\u6587\u672a\u63d0\u4f9b\u65f6\u586b\u201c\u5f85\u786e\u8ba4\u201d\uff0c\u4e0d\u5f97\u81c6\u9020\u3002");
            return;
        }
    }

    private void validatePendingDataAttributes(RuleValidationResponse response,
                                               Map<String, List<RowRef>> groups) {
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            List<RowRef> rows = entry.getValue();
            String process = FunctionProcessKey.process(entry.getKey());
            Map<String, String> knownAttributesByGroup = new LinkedHashMap<String, String>();
            Set<String> ambiguousGroups = new HashSet<String>();
            for (RowRef ref : rows) {
                String group = safe(ref.row.getDataGroup());
                String attributes = safe(ref.row.getDataAttributes());
                if (!StringUtils.hasText(group) || !isEvidenceBackedAttributes(attributes)) {
                    continue;
                }
                String existing = knownAttributesByGroup.get(group);
                if (existing == null) {
                    knownAttributesByGroup.put(group, attributes);
                } else if (!existing.equals(attributes)) {
                    ambiguousGroups.add(group);
                }
            }
            for (RowRef ref : rows) {
                if (!"\u5f85\u786e\u8ba4".equals(safe(ref.row.getDataAttributes()))) {
                    continue;
                }
                String group = safe(ref.row.getDataGroup());
                if (isNoPayloadScheduledEntry(ref.row)) {
                    add(response, ref.rowNumber, process, "K\u6570\u636e\u5c5e\u6027", "RISK",
                            "\u65e0\u8f7d\u8377\u7684\u5b9a\u65f6/\u65f6\u949f\u89e6\u53d1\u4e0d\u5e94\u4f7f\u7528\u201c\u5f85\u786e\u8ba4\u201d",
                            "\u82e5\u8be5 E \u4ec5\u8868\u793a\u5b9a\u65f6\u4efb\u52a1\u6216\u7cfb\u7edf\u65f6\u949f\u89e6\u53d1\uff0c\u6570\u636e\u5c5e\u6027\u6539\u4e3a\u201c\u65e0\u4e1a\u52a1\u6570\u636e\u201d\u3002");
                    continue;
                }
                String known = ambiguousGroups.contains(group) ? "" : knownAttributesByGroup.get(group);
                if (StringUtils.hasText(known)) {
                    add(response, ref.rowNumber, process, "K\u6570\u636e\u5c5e\u6027", "RISK",
                            "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u7684\u76f8\u540c\u6570\u636e\u7ec4\u201c" + group
                                    + "\u201d\u5df2\u6709\u660e\u786e\u6570\u636e\u5c5e\u6027\uff0c\u5f53\u524d\u884c\u4e0d\u5e94\u518d\u5199\u201c\u5f85\u786e\u8ba4\u201d",
                            "\u6838\u5bf9\u662f\u5426\u4e3a\u540c\u4e00\u6279\u4e1a\u52a1\u6570\u636e\uff1b\u5982\u679c\u662f\uff0c\u5c06\u5df2\u6709\u5c5e\u6027\u201c" + known
                                    + "\u201d\u5b8c\u6574\u590d\u7528\u5230\u672c\u884c\uff0c\u4e0d\u4f7f\u7528\u201c\u540c\u4e0a\u201d\u3002");
                } else {
                    add(response, ref.rowNumber, process, "K\u6570\u636e\u5c5e\u6027", "RISK",
                            "\u6570\u636e\u5c5e\u6027\u4e3a\u201c\u5f85\u786e\u8ba4\u201d\uff0c\u8868\u660e\u9001\u5ba1\u6750\u6599\u5c1a\u6709\u5b57\u6bb5\u4f9d\u636e\u5f85\u8865\u5145",
                            "\u5148\u68c0\u7d22\u9700\u6c42\u8868\u683c\u3001\u63a5\u53e3\u5b9a\u4e49\u3001\u6b63\u6587\u4e2d\u660e\u786e\u51fa\u73b0\u7684\u4e1a\u52a1\u6570\u636e\u9879\u548c\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u7684\u5df2\u77e5\u5c5e\u6027\uff1b\u4ecd\u65e0\u6cd5\u8bc6\u522b\u65f6\u4fdd\u7559\u201c\u5f85\u786e\u8ba4\u201d\u5e76\u8865\u5145\u4eba\u5de5\u4f9d\u636e\u3002");
                }
            }
        }
    }

    private boolean isEvidenceBackedAttributes(String attributes) {
        return StringUtils.hasText(attributes)
                && !attributes.contains("\u5f85\u786e\u8ba4")
                && !"\u65e0\u4e1a\u52a1\u6570\u636e".equals(attributes)
                && !containsAny(attributes, "\u540c\u4e0a", "\u89c1\u4e0a", "\u4e0e\u4e0a\u76f8\u540c");
    }

    private boolean isNoPayloadScheduledEntry(FunctionSplitRow row) {
        if (!"E".equals(safe(row.getMovementType()).toUpperCase(Locale.ROOT))) {
            return false;
        }
        String evidence = safe(row.getTriggerEvent()) + " " + safe(row.getSubProcessDescription()) + " " + safe(row.getDataGroup());
        return containsAny(evidence, "\u5b9a\u65f6\u4efb\u52a1\u89e6\u53d1", "\u5b9a\u65f6\u89e6\u53d1", "\u5b9a\u65f6\u5668\u89e6\u53d1", "\u65f6\u949f\u89e6\u53d1", "\u8f6e\u8be2\u89e6\u53d1", "\u5b9a\u65f6\u89e6\u53d1\u4fe1\u53f7");
    }

    private String firstContained(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return keyword;
            }
        }
        return "";
    }

    private void validateMovementWording(RuleValidationResponse response, FunctionSplitRow row, int rowNumber) {
        String description = safe(row.getSubProcessDescription());
        if (description.contains("请求请求")) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "H子过程描述", "RISK",
                    "子过程描述出现重复的“请求请求”，通常由固定模板拼接功能过程名称造成",
                    "请按真实数据移动改写，例如“提交查询条件”“接收XX上报数据”或“发送XX查询请求”，不要使用“接收+功能过程名+请求”的固定模板。");
        }
        String movement = safe(row.getMovementType()).toUpperCase(Locale.ROOT);
        if ("E".equals(movement) && description.startsWith("接收向")
                && containsAny(description, "发送", "推送", "同步")) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "H子过程描述", "RISK",
                    "E 描述同时包含“接收向外发送/推送”，输入方向与系统向外输出方向矛盾",
                    "E 应改为实际进入本系统的触发数据或业务数据；向外部平台发送的报文应作为 X。");
        }
        String sequence = comparable(row.getSequenceDiagram());
        if (("R".equals(movement) || "W".equals(movement))
                && "后台服务ui".equals(sequence)) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "数据移动结构", "RISK",
                    movement + " 是软件边界内部的持久化数据移动，时序却写成“后台服务->UI”，与跨边界输出 X 的方向相同",
                    "请核对系统边界；R/W 通常使用“后台服务->后台服务”，向 UI 或外部系统输出才使用 X。");
        }
        if ("X".equals(movement) && ("uiui".equals(sequence) || "ui后台服务".equals(sequence)
                || "后台服务后台服务".equals(sequence))) {
            add(response, rowNumber, safe(row.getFunctionProcess()), "数据移动结构", "RISK",
                    "X 应跨软件边界输出，但当前时序没有体现从本系统向功能用户或外部系统输出",
                    "请将时序改为本系统/后台服务向 UI、功能用户或外部平台输出，并核对数据组确为输出结果。");
        }
    }

    private void validateKeywords(RuleValidationResponse response, FunctionSplitRow row, int rowNumber,
                                  boolean hasStructureRisk, List<String> highRiskKeywords) {
        String text = safe(row.getFunctionProcess()) + " " + validationFunctionDescription(row.getFunctionDescription()) + " "
                + safe(row.getSubProcessDescription()) + " " + safe(row.getDataGroup()) + " " + safe(row.getDataAttributes());
        for (String keyword : highRiskKeywords) {
            if (text.contains(keyword)) {
                if (!hasStructureRisk) {
                    break;
                }
                String severity = "RISK";
                String field = "\u8868\u8fbe\u98ce\u9669";
                String keywordReason = keywordReason(keyword);
                String message = "\u8868\u8fbe\u4e2d\u51fa\u73b0\u201c" + keyword + "\u201d\uff1b" + keywordReason
                        + "\uff1b\u4e14\u8be5\u529f\u80fd\u8fc7\u7a0b\u5b58\u5728 E/R/W/X \u7ed3\u6784\u98ce\u9669\uff0c\u9700\u4f18\u5148\u4fee\u6b63\u3002";
                String suggestion = "\u5148\u8865\u9f50\u8be5\u529f\u80fd\u8fc7\u7a0b\u7684 E \u8f93\u5165\u548c W/X \u5199\u5165\u6216\u8f93\u51fa\uff1b\u518d" + keywordRewriteAdvice(keyword);
                add(response, rowNumber, safe(row.getFunctionProcess()), field, severity, message, suggestion);
                break;
            }
        }
    }

    private String validationFunctionDescription(String value) {
        String text = safe(value);
        if (text.startsWith("\u672c\u529f\u80fd\u7528\u4e8e\u5b8c\u6210")) {
            text = text.replace("\uff0c\u652f\u6491\u8be5\u529f\u80fd\u7684\u8f93\u5165\u3001\u8bfb\u53d6\u3001\u5199\u5165\u6216\u8f93\u51fa\u7ed3\u679c\u3002", "");
        }
        return text;
    }

    private String keywordRewriteAdvice(String keyword) {
        if ("\u63d0\u793a".equals(keyword)) {
            return "\u5982\u679c\u786e\u5b9e\u662f\u65b0\u589e\u529f\u80fd\uff0c\u8bf7\u5199\u6210\u201c\u7cfb\u7edf\u8f93\u51faXX\u63d0\u793a\u7ed3\u679c/XX\u6821\u9a8c\u7ed3\u679c\u201d\uff0c\u5e76\u5728\u6570\u636e\u7ec4\u548c\u6570\u636e\u5c5e\u6027\u4e2d\u5199\u6e05\u63d0\u793a\u5bf9\u8c61\u3001\u63d0\u793a\u5185\u5bb9\u3001\u72b6\u6001\u6216\u539f\u56e0\uff1b\u5982\u679c\u53ea\u662f\u9875\u9762\u6587\u6848\u6539\u52a8\uff0c\u5efa\u8bae\u4e0d\u4f5c\u4e3a\u72ec\u7acb\u529f\u80fd\u70b9\u3002";
        }
        if ("\u65e5\u5fd7".equals(keyword)) {
            return "\u8bf7\u5224\u65ad\u662f\u5426\u6709\u65b0\u7684\u65e5\u5fd7\u67e5\u8be2\u3001\u751f\u6210\u6216\u8f93\u51fa\u9700\u6c42\uff1b\u5982\u679c\u6709\uff0c\u6539\u5199\u4e3a\u201c\u7528\u6237\u63d0\u4ea4XX\u67e5\u8be2\u6761\u4ef6/\u7cfb\u7edf\u8bfb\u53d6XX\u65e5\u5fd7/\u7cfb\u7edf\u8f93\u51faXX\u65e5\u5fd7\u660e\u7ec6\u201d\uff1b\u5982\u679c\u53ea\u662f\u540e\u53f0\u8bb0\u5f55\uff0c\u5efa\u8bae\u5220\u9664\u6216\u5408\u5e76\u5230\u76f8\u5173\u529f\u80fd\u3002";
        }
        if ("\u89e6\u53d1".equals(keyword) || "\u8c03\u7528".equals(keyword)) {
            return "\u8bf7\u4e0d\u8981\u53ea\u5199\u6280\u672f\u52a8\u4f5c\uff0c\u6539\u6210\u4e1a\u52a1\u7ed3\u679c\uff1a\u8c01\u53d1\u8d77\u8bf7\u6c42\u3001\u7cfb\u7edf\u8bfb\u53d6\u54ea\u4e9b\u4e1a\u52a1\u6570\u636e\u3001\u6700\u7ec8\u5199\u5165\u6216\u8f93\u51fa\u4ec0\u4e48\u7ed3\u679c\u3002";
        }
        if ("\u8ba1\u7b97".equals(keyword) || "\u5339\u914d".equals(keyword) || "\u8fc7\u6ee4".equals(keyword) || "\u89e3\u6790".equals(keyword)) {
            return "\u8bf7\u628a\u5904\u7406\u903b\u8f91\u8865\u6210\u6570\u636e\u79fb\u52a8\uff1a\u8f93\u5165\u4ec0\u4e48\u6761\u4ef6\u6216\u6570\u636e\u3001\u8bfb\u53d6\u4ec0\u4e48\u539f\u59cb\u6570\u636e\u3001\u751f\u6210/\u8f93\u51fa\u4ec0\u4e48\u4e1a\u52a1\u7ed3\u679c\uff0c\u6570\u636e\u5c5e\u6027\u8981\u5199\u5230\u5177\u4f53\u5b57\u6bb5\u3002";
        }
        if ("UI".equals(keyword) || "UE".equals(keyword) || "\u9875\u9762\u8bbe\u8ba1".equals(keyword) || "\u8bbe\u8ba1".equals(keyword)) {
            return "\u8bf7\u5224\u65ad\u662f\u5426\u53ea\u662f\u754c\u9762\u6837\u5f0f\u6216\u4ea4\u4e92\u8c03\u6574\uff1b\u5982\u679c\u4e0d\u6d89\u53ca\u65b0\u7684\u4e1a\u52a1\u6570\u636e\u8f93\u5165\u3001\u8bfb\u53d6\u3001\u5199\u5165\u6216\u8f93\u51fa\uff0c\u5efa\u8bae\u4e0d\u5355\u72ec\u8ba1\u91cf\uff1b\u5982\u679c\u6d89\u53ca\uff0c\u6539\u5199\u4e3a\u5bf9\u5e94\u4e1a\u52a1\u6570\u636e\u5904\u7406\u3002";
        }
        if ("\u63a5\u53e3".equals(keyword) || "\u914d\u7f6e".equals(keyword) || "\u96c6\u6210".equals(keyword)) {
            return "\u8bf7\u786e\u8ba4\u4e0d\u662f\u5355\u7eaf\u5229\u65e7\u6216\u6280\u672f\u63a5\u5165\uff1b\u5982\u679c\u6709\u65b0\u4e1a\u52a1\u5904\u7406\uff0c\u5199\u6e05\u63a5\u6536\u54ea\u4e9b\u5916\u90e8\u6570\u636e\u3001\u8bfb\u53d6/\u5199\u5165\u54ea\u4e9b\u4e1a\u52a1\u6570\u636e\u3001\u8f93\u51fa\u4ec0\u4e48\u5904\u7406\u7ed3\u679c\u3002";
        }
        return "\u8bf7\u5c06\u62bd\u8c61\u6216\u6280\u672f\u8868\u8fbe\u6539\u5199\u4e3a\u660e\u786e\u7684 E/R/W/X\uff1a\u8c01\u8f93\u5165\u3001\u7cfb\u7edf\u8bfb\u53d6\u4ec0\u4e48\u3001\u7cfb\u7edf\u5199\u5165\u6216\u8f93\u51fa\u4ec0\u4e48\u4e1a\u52a1\u7ed3\u679c\u3002";
    }

    private String keywordReason(String keyword) {
        if ("\u63d0\u793a".equals(keyword)) {
            return "\u201c\u63d0\u793a\u201d\u5e38\u53ef\u80fd\u53ea\u662f\u9875\u9762\u6587\u6848\u6216\u7cfb\u7edf\u53cd\u9988\uff0c\u4e0d\u4e00\u5b9a\u6784\u6210\u72ec\u7acb\u53ef\u8ba1\u91cf\u7684\u529f\u80fd";
        }
        if ("\u65e5\u5fd7".equals(keyword)) {
            return "\u201c\u65e5\u5fd7\u201d\u5bb9\u6613\u53ea\u662f\u6280\u672f\u8bb0\u5f55\u6216\u5df2\u6709\u67e5\u8be2\u80fd\u529b\uff0c\u9700\u786e\u8ba4\u662f\u5426\u6709\u65b0\u7684\u4e1a\u52a1\u8f93\u5165\u3001\u8bfb\u53d6\u6216\u8f93\u51fa";
        }
        if ("\u89e6\u53d1".equals(keyword) || "\u8c03\u7528".equals(keyword)) {
            return "\u8be5\u8bcd\u5bb9\u6613\u8868\u793a\u6280\u672f\u52a8\u4f5c\u6216\u5df2\u6709\u80fd\u529b\u8c03\u7528\uff0c\u9700\u786e\u8ba4\u662f\u5426\u4ea7\u751f\u65b0\u7684\u4e1a\u52a1\u6570\u636e\u79fb\u52a8";
        }
        if ("\u8ba1\u7b97".equals(keyword) || "\u5339\u914d".equals(keyword) || "\u8fc7\u6ee4".equals(keyword) || "\u89e3\u6790".equals(keyword)) {
            return "\u8be5\u8bcd\u504f\u5904\u7406\u903b\u8f91\uff0c\u9700\u786e\u8ba4\u5df2\u5199\u660e\u88ab\u5904\u7406\u7684\u4e1a\u52a1\u6570\u636e\u548c\u5904\u7406\u540e\u7684\u7ed3\u679c";
        }
        if ("UI".equals(keyword) || "UE".equals(keyword) || "\u9875\u9762\u8bbe\u8ba1".equals(keyword) || "\u8bbe\u8ba1".equals(keyword)) {
            return "\u8be5\u8bcd\u5bb9\u6613\u53ea\u4ee3\u8868\u754c\u9762\u6216\u4ea4\u4e92\u8c03\u6574\uff0c\u9700\u786e\u8ba4\u662f\u5426\u6d89\u53ca\u65b0\u7684\u4e1a\u52a1\u6570\u636e\u8f93\u5165\u6216\u8f93\u51fa";
        }
        if ("\u63a5\u53e3".equals(keyword) || "\u914d\u7f6e".equals(keyword) || "\u96c6\u6210".equals(keyword)) {
            return "\u8be5\u8bcd\u5bb9\u6613\u88ab\u8ba4\u4e3a\u5229\u65e7\u6216\u6280\u672f\u5b9e\u73b0\uff0c\u9700\u786e\u8ba4\u662f\u5426\u5bf9\u4e1a\u52a1\u6570\u636e\u4ea7\u751f\u65b0\u589e\u5904\u7406";
        }
        return "\u8be5\u8bcd\u504f\u62bd\u8c61\u6216\u6280\u672f\u8868\u8fbe\uff0c\u4e13\u5bb6\u8bc4\u5ba1\u65f6\u53ef\u80fd\u8981\u6c42\u6539\u5199\u4e3a\u660e\u786e\u7684\u6570\u636e\u79fb\u52a8";
    }

    private Set<Integer> validateGroups(RuleValidationResponse response, Map<String, List<RowRef>> groups) {
        Set<Integer> structureRiskRows = new HashSet<Integer>();
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            String key = entry.getKey();
            String process = FunctionProcessKey.process(key);
            if (key.startsWith("__ROW_")) {
                continue;
            }
            List<RowRef> rows = entry.getValue();
            if (rows.size() < 2) {
                add(response, rows.get(0).rowNumber, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u529f\u80fd\u8fc7\u7a0b\u53ea\u6709 1 \u4e2a\u5b50\u8fc7\u7a0b", "\u5efa\u8bae\u68c0\u67e5\u662f\u5426\u7f3a\u5c11\u8f93\u51fa\u6216\u5199\u5165\u7c7b\u6570\u636e\u79fb\u52a8\u3002");
                structureRiskRows.add(rows.get(0).rowNumber);
            }
            String firstMovement = safe(rows.get(0).row.getMovementType()).toUpperCase(Locale.ROOT);
            if (!"E".equals(firstMovement)) {
                add(response, rows.get(0).rowNumber, process, "\u6570\u636e\u79fb\u52a8\u7ed3\u6784", "RISK",
                        "\u529f\u80fd\u8fc7\u7a0b\u7684\u7b2c\u4e00\u4e2a\u6570\u636e\u79fb\u52a8\u4e0d\u662f E",
                        "\u8bf7\u6838\u5bf9\u529f\u80fd\u7528\u6237\u548c\u89e6\u53d1\u4e8b\u4ef6\uff0c\u4ee5\u8de8\u8d8a\u8f6f\u4ef6\u8fb9\u754c\u8fdb\u5165\u672c\u7cfb\u7edf\u7684\u771f\u5b9e\u8f93\u5165 E \u5f00\u59cb\u3002");
                markRows(structureRiskRows, rows);
            }
            RowRef lastRef = rows.get(rows.size() - 1);
            String lastMovement = safe(lastRef.row.getMovementType()).toUpperCase(Locale.ROOT);
            if (!("W".equals(lastMovement) || "X".equals(lastMovement))) {
                add(response, lastRef.rowNumber, process, "\u6570\u636e\u79fb\u52a8\u7ed3\u6784", "ERROR",
                        "\u529f\u80fd\u8fc7\u7a0b\u7684\u6700\u540e\u4e00\u4e2a\u6570\u636e\u79fb\u52a8\u4e0d\u662f W \u6216 X",
                        "\u8bf7\u6838\u5bf9\u529f\u80fd\u8fc7\u7a0b\u7684\u4e1a\u52a1\u7ed3\u679c\uff1a\u6700\u7ec8\u5e94\u5199\u5165\u6301\u4e45\u5316\u6570\u636e\u7ec4 W\uff0c\u6216\u8de8\u8fb9\u754c\u8f93\u51fa\u7ed3\u679c X\u3002");
                markRows(structureRiskRows, rows);
            }
            boolean hasEntry = false;
            boolean hasWriteOrExit = false;
            for (RowRef ref : rows) {
                String movement = safe(ref.row.getMovementType()).toUpperCase(Locale.ROOT);
                if ("E".equals(movement)) {
                    hasEntry = true;
                }
                if ("W".equals(movement) || "X".equals(movement)) {
                    hasWriteOrExit = true;
                }
            }
            if (!hasEntry) {
                addGroupIssue(response, rows, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u7ec4\u5185\u672a\u53d1\u73b0 E \u8f93\u5165",
                        "\u8bf7\u786e\u8ba4\u8be5\u529f\u80fd\u662f\u5426\u5b58\u5728\u89e6\u53d1\u8f93\u5165\u6570\u636e\u3002");
                markRows(structureRiskRows, rows);
            }
            if (!hasWriteOrExit) {
                addGroupIssue(response, rows, process, "\u529f\u80fd\u8fc7\u7a0b", "RISK",
                        "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u7ec4\u5185\u672a\u53d1\u73b0 W \u5199\u6216 X \u8f93\u51fa",
                        "\u8bf7\u786e\u8ba4\u6574\u4e2a\u529f\u80fd\u8fc7\u7a0b\u662f\u5426\u6709\u5199\u5165\u6216\u8f93\u51fa\u7ed3\u679c\u3002");
                markRows(structureRiskRows, rows);
            }
            validateMovementOrder(response, process, rows, structureRiskRows);
            validateActionMovementCombination(response, process, rows, structureRiskRows);
        }
        return structureRiskRows;
    }

    private void validateMovementOrder(RuleValidationResponse response,
                                       String process,
                                       List<RowRef> rows,
                                       Set<Integer> structureRiskRows) {
        for (int i = 0; i < rows.size(); i++) {
            RowRef ref = rows.get(i);
            String movement = safe(ref.row.getMovementType()).toUpperCase(Locale.ROOT);
            if (i == 0) {
                continue;
            }
            RowRef previous = rows.get(i - 1);
            String previousMovement = safe(previous.row.getMovementType()).toUpperCase(Locale.ROOT);
            if ("E".equals(movement)) {
                if ("E".equals(previousMovement)) {
                    add(response, ref.rowNumber, process, "数据移动结构", "RISK",
                            "同一功能过程出现连续 E，后一个 E 缺少外部请求 X 与响应进入的边界依据",
                            "只有首行是初始触发 E；后续 E 必须是本系统先向外部系统输出请求 X 后，在同一业务目标内返回的同步响应。新的用户触发、异步回调或结果上报应另拆功能过程。");
                    structureRiskRows.add(ref.rowNumber);
                } else if (!"X".equals(previousMovement) || !isSupportedExternalResponse(previous.row, ref.row)) {
                    add(response, ref.rowNumber, process, "数据移动结构", "RISK",
                            "首行之后的 E 未能证明是同一功能过程中外部系统的同步响应进入",
                            "若前一 X 是向外部系统发送请求、当前 E 是同一请求的同步响应，可保留 X-E；若属于稍后回调、结果上报或新的用户目的，应从该 E 起另拆功能过程。");
                    structureRiskRows.add(ref.rowNumber);
                }
                continue;
            }
            if ("X".equals(previousMovement) && !"X".equals(movement)) {
                add(response, ref.rowNumber, process, "数据移动结构", "RISK",
                        "X 输出后直接出现 " + movement + "，中间没有外部响应 E，数据流向或执行顺序缺少依据",
                        "若 X 是最终业务结果，应在此结束；若 X 是外部请求，外部响应重新进入本系统时应明确记录 E，再按真实依赖继续 R/W/X。");
                structureRiskRows.add(ref.rowNumber);
            }
        }
    }

    private boolean isSupportedExternalResponse(FunctionSplitRow requestExit, FunctionSplitRow responseEntry) {
        String request = rowEvidenceText(requestExit);
        String response = rowEvidenceText(responseEntry);
        if (containsAny(response, "回调", "上报", "新的用户", "二次触发", "异步通知")) {
            return false;
        }
        boolean requestToExternal = containsAny(request, "外部", "第三方", "平台", "系统", "接口")
                && containsAny(request, "请求", "调用", "发送", "查询", "推送", "下发", "输出");
        boolean responseFromExternal = containsAny(response, "外部", "第三方", "平台", "系统", "接口")
                && containsAny(response, "响应", "应答", "返回", "回传", "反馈", "查询结果", "处理结果");
        return requestToExternal && responseFromExternal;
    }

    private String rowEvidenceText(FunctionSplitRow row) {
        return safe(row.getFunctionalUser()) + " " + safe(row.getTriggerEvent()) + " "
                + safe(row.getFunctionProcess()) + " " + safe(row.getFunctionDescription()) + " "
                + safe(row.getSubProcessDescription()) + " " + safe(row.getDataGroup()) + " "
                + safe(row.getEvidence()) + " " + safe(row.getSequenceDiagram());
    }

    private void validateActionMovementCombination(RuleValidationResponse response,
                                                   String process,
                                                   List<RowRef> rows,
                                                   Set<Integer> structureRiskRows) {
        String triggerEvent = rows.isEmpty() ? "" : safe(rows.get(0).row.getTriggerEvent());
        String context = groupEvidenceText(rows);
        List<ActionMovementRule.Match> actionMatches = ActionMovementRule.resolveAll(process, triggerEvent, context);
        if (actionMatches.isEmpty()) {
            return;
        }
        Set<String> present = new LinkedHashSet<String>();
        Set<String> required = new LinkedHashSet<String>();
        Set<String> discouraged = new LinkedHashSet<String>();
        for (ActionMovementRule.Match match : actionMatches) {
            required.addAll(match.getProfile().getRequiredMovements());
            discouraged.addAll(match.getProfile().getDiscouragedMovements());
        }
        discouraged.removeAll(required);
        for (RowRef ref : rows) {
            String movement = safe(ref.row.getMovementType()).toUpperCase(Locale.ROOT);
            present.add(movement);
            if (!discouraged.contains(movement)) {
                continue;
            }
            if (isSupportedOptionalMovement(actionMatches, movement, ref.row, context)) {
                continue;
            }
            ActionMovementRule.Match discouragingMatch = firstDiscouragingMatch(actionMatches, movement);
            if (discouragingMatch != null
                    && isSupportedOptionalMovement(discouragingMatch.getProfile(), movement, ref.row)) {
                continue;
            }
            add(response, ref.rowNumber, process, "数据移动组合", "RISK",
                    "“" + actionKeywords(actionMatches) + "”类功能通常不应包含 " + movement + " 数据移动",
                    discouragedMovementSuggestion(discouragingMatch == null
                            ? actionMatches.get(0).getProfile() : discouragingMatch.getProfile(), movement));
            structureRiskRows.add(ref.rowNumber);
        }
        validateConditionalMovementEvidence(response, process, rows, actionMatches, required, structureRiskRows);

        Set<String> missing = new LinkedHashSet<String>(required);
        missing.removeAll(present);
        if (missing.size() == 1 && missing.contains("W")
                && hasActionLabel(actionMatches, "流程派发/审批")
                && hasNonWorkflowAction(actionMatches)
                && !hasNonWorkflowWriteRequirement(actionMatches)) {
            // Xinjiang review does not infer a write merely because a query,
            // export or other R/X-oriented primary action also contains an object
            // name such as “派发任务/派发记录”. Standalone dispatch or another
            // matched action that independently requires W is still validated.
            missing.remove("W");
        }
        if (!missing.isEmpty()) {
            addGroupIssue(response, rows, process, "数据移动组合", "RISK",
                    missingMovementMessage(actionMatches, required, missing),
                    missingMovementSuggestion(actionMatches, missing));
            markRows(structureRiskRows, rows);
        }
        if (required.contains("X")) {
            String last = safe(rows.get(rows.size() - 1).row.getMovementType()).toUpperCase(Locale.ROOT);
            if (!"X".equals(last)) {
                addGroupIssue(response, rows, process, "数据移动组合", "RISK",
                        "“" + actionKeywords(actionMatches) + "”类功能要求跨边界输出 X，当前没有以 X 结束",
                        "将有原文依据的返回、发送、通知或接口响应作为末行 X；不得用内部 W 代替跨边界输出。");
                markRows(structureRiskRows, rows);
            }
        }
    }

    private ActionMovementRule.Match firstDiscouragingMatch(List<ActionMovementRule.Match> matches, String movement) {
        for (ActionMovementRule.Match match : matches) {
            if (match.getProfile().getDiscouragedMovements().contains(movement)) {
                return match;
            }
        }
        return null;
    }

    private boolean hasActionLabel(List<ActionMovementRule.Match> matches, String label) {
        for (ActionMovementRule.Match match : matches) {
            if (label.equals(match.getProfile().getLabel())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNonWorkflowAction(List<ActionMovementRule.Match> matches) {
        for (ActionMovementRule.Match match : matches) {
            if (!"流程派发/审批".equals(match.getProfile().getLabel())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNonWorkflowWriteRequirement(List<ActionMovementRule.Match> matches) {
        for (ActionMovementRule.Match match : matches) {
            if (!"流程派发/审批".equals(match.getProfile().getLabel())
                    && match.getProfile().getRequiredMovements().contains("W")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSupportedOptionalMovement(List<ActionMovementRule.Match> matches,
                                                String movement,
                                                FunctionSplitRow row,
                                                String context) {
        for (ActionMovementRule.Match match : matches) {
            if (isSupportedOptionalMovement(match.getProfile(), movement, row)) {
                return true;
            }
            String label = match.getProfile().getLabel();
            if ("W".equals(movement) && "发送/推送".equals(label)
                    && ActionMovementRule.hasMessageRecordEvidence(context)) {
                return true;
            }
        }
        return false;
    }

    private String missingMovementMessage(List<ActionMovementRule.Match> matches,
                                          Set<String> required,
                                          Set<String> missing) {
        if (hasActionLabel(matches, "导入") && (missing.contains("W") || missing.contains("X"))) {
            return "导入功能必须将写入导入业务数据 W 与返回导入结果信息 X 拆成两个独立子过程，当前缺少 "
                    + joinMovements(missing);
        }
        if (hasActionLabel(matches, "导出") && (missing.contains("W") || missing.contains("X"))) {
            return "导出功能必须将生成/写入 Excel 等导出文件 W 与返回文件或导出结果 X 拆成两个独立子过程，当前缺少 "
                    + joinMovements(missing);
        }
        return "“" + actionKeywords(matches) + "”类功能通常应包含 "
                + joinMovements(required) + "，当前缺少 " + joinMovements(missing);
    }

    private String missingMovementSuggestion(List<ActionMovementRule.Match> matches,
                                             Set<String> missing) {
        if (hasActionLabel(matches, "导入") && (missing.contains("W") || missing.contains("X"))) {
            return "在读取并校验导入文件后，单独增加 W 写入目标业务数据；末行单独使用 X 返回成功/失败、导入数量或错误信息。"
                    + "不得把写入数据和返回结果合并到同一个子过程。";
        }
        if (hasActionLabel(matches, "导出") && (missing.contains("W") || missing.contains("X"))) {
            return "在读取待导出数据后，单独增加 W 生成/写入 Excel、CSV、PDF 等导出文件；末行单独使用 X 返回文件、"
                    + "下载地址或导出结果。临时文件也不得省略 W。";
        }
        return "请先核对原始需求，再补充有明确依据的 " + joinMovements(missing)
                + " 数据移动；不得为了满足形式完整而虚构数据组或属性。";
    }

    private String actionKeywords(List<ActionMovementRule.Match> matches) {
        List<String> keywords = new ArrayList<String>();
        for (ActionMovementRule.Match match : matches) {
            String keyword = safe(match.getKeyword());
            if (StringUtils.hasText(keyword) && !keywords.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        return keywords.isEmpty() ? "业务动作" : String.join("/", keywords);
    }

    private boolean isSupportedOptionalMovement(ActionMovementRule.Profile profile,
                                                String movement,
                                                FunctionSplitRow row) {
        if (!"R".equals(movement) || !"新增".equals(profile.getLabel())) {
            return false;
        }
        String description = safe(row.getSubProcessDescription());
        String dataGroup = safe(row.getDataGroup());
        boolean explicitRead = description.contains("读取") || description.contains("获取") || description.contains("查询");
        boolean specificGroup = StringUtils.hasText(dataGroup)
                && !("相关数据".equals(dataGroup) || "业务数据".equals(dataGroup) || "数据表".equals(dataGroup));
        return explicitRead && specificGroup;
    }

    private void validateConditionalMovementEvidence(RuleValidationResponse response,
                                                     String process,
                                                     List<RowRef> rows,
                                                     List<ActionMovementRule.Match> actionMatches,
                                                     Set<String> combinedRequired,
                                                     Set<Integer> structureRiskRows) {
        String context = groupEvidenceText(rows);
        for (ActionMovementRule.Match actionMatch : actionMatches) {
            String label = actionMatch.getProfile().getLabel();
            if ("发送/推送".equals(label) && !combinedRequired.contains("W")
                    && !ActionMovementRule.hasMessageRecordEvidence(context)) {
                addConditionalWriteIssues(response, process, rows, structureRiskRows,
                        "发送/推送功能中的 W 缺少“保存发送结果或记录”的明确依据",
                        "发送内容通常由 R 取得并通过 X 跨边界输出；只有需要保存短信结果、发送记录、"
                                + "状态或通知结果时才增加 W。");
            }
        }
    }

    private void addConditionalWriteIssues(RuleValidationResponse response,
                                           String process,
                                           List<RowRef> rows,
                                           Set<Integer> structureRiskRows,
                                           String message,
                                           String suggestion) {
        for (RowRef ref : rows) {
            if (!"W".equals(safe(ref.row.getMovementType()).toUpperCase(Locale.ROOT))) {
                continue;
            }
            add(response, ref.rowNumber, process, "数据移动组合", "RISK", message, suggestion);
            structureRiskRows.add(ref.rowNumber);
        }
    }

    private String groupEvidenceText(List<RowRef> rows) {
        StringBuilder builder = new StringBuilder();
        for (RowRef ref : rows) {
            FunctionSplitRow row = ref.row;
            builder.append(' ').append(safe(row.getFunctionProcess()))
                    .append(' ').append(safe(row.getFunctionalUser()))
                    .append(' ').append(safe(row.getTriggerEvent()))
                    .append(' ').append(safe(row.getFunctionDescription()))
                    .append(' ').append(safe(row.getSubProcessDescription()))
                    .append(' ').append(safe(row.getDataGroup()))
                    .append(' ').append(safe(row.getDataAttributes()))
                    .append(' ').append(safe(row.getEvidence()))
                    .append(' ').append(safe(row.getSequenceDiagram()));
        }
        return builder.toString();
    }

    private String discouragedMovementSuggestion(ActionMovementRule.Profile profile, String movement) {
        if ("R".equals(movement)) {
            return "请核对原始需求；新增、删除或被调用方同步写入通常不自动增加 R。"
                    + "如确需关联查询其他业务表，必须在子过程和数据组中写清被读取的业务表及其作用；"
                    + "没有明确依据时删除 R。";
        }
        if ("W".equals(movement) && ("查询".equals(profile.getLabel())
                || "被调用方查询接口".equals(profile.getLabel()))) {
            return "普通查询或被调用方查询接口通常为 E-R-X，不写入业务数据。"
                    + "如需持久化统计或查询结果，应写清结果表和留存要求；否则删除 W。";
        }
        return "请核对原始需求，只保留有明确业务含义和原文证据的数据移动。";
    }

    private String joinMovements(Set<String> movements) {
        StringBuilder builder = new StringBuilder();
        for (String movement : movements) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(movement);
        }
        return builder.toString();
    }

    private void markRows(Set<Integer> rowNumbers, List<RowRef> rows) {
        for (RowRef ref : rows) {
            rowNumbers.add(ref.rowNumber);
        }
    }

    private void validateReuseAndDuplicateRules(RuleValidationResponse response,
                                                Map<String, List<RowRef>> groups) {
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            if (entry.getKey().startsWith("__ROW_")) {
                continue;
            }
            String process = FunctionProcessKey.process(entry.getKey());
            List<RowRef> rows = entry.getValue();
            for (int i = 0; i < rows.size(); i++) {
                RowRef current = rows.get(i);
                for (int j = 0; j < i; j++) {
                    RowRef previous = rows.get(j);
                    int same = sameAuditElementCount(previous.row, current.row);
                    if (same == 3) {
                        add(response, current.rowNumber, process, "\u590d\u7528\u4e0e CFP", "RISK",
                                "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u4e2d\uff0c\u5b50\u8fc7\u7a0b\u63cf\u8ff0\u3001\u6570\u636e\u79fb\u52a8\u7c7b\u578b\u3001\u6570\u636e\u5c5e\u6027\u4e09\u8981\u7d20\u4e0e\u7b2c "
                                        + previous.rowNumber + " \u884c\u5b8c\u5168\u76f8\u540c",
                                "\u4f18\u5148\u5220\u9664\u6216\u5408\u5e76\u91cd\u590d\u884c\uff1b\u5982\u9001\u5ba1\u8868\u5fc5\u987b\u4fdd\u7559\uff0c\u91cd\u590d\u884c\u6309\u5229\u65e7/0 \u5904\u7406\u5e76\u4eba\u5de5\u786e\u8ba4\u3002");
                        break;
                    }
                    if (same == 2 && !isDistinctDataGroupMovement(previous.row, current.row)
                            && !isReuseCfp(current.row)) {
                        add(response, current.rowNumber, process, "\u590d\u7528\u4e0e CFP", "RISK",
                                "\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u4e2d\uff0c\u4e09\u8981\u7d20\u6709\u4e24\u9879\u4e0e\u7b2c " + previous.rowNumber + " \u884c\u76f8\u540c\uff0c\u5b58\u5728\u590d\u7528\u98ce\u9669",
                                "初审只标记候选，不在此处改变复用度或 CFP；请交由拆分流程末尾的复用度 AI 调整结合全表、业务边界和原文证据统一判断。");
                        break;
                    }
                }
            }
        }
        validateCrossFunctionReuse(response, groups);
        validateDuplicateFunctionProcesses(response, groups);
    }

    /**
     * Minimal safety check for the unified Xinjiang rule XJ-TIMER-E. Semantic
     * recognition remains an AI task; this validator only catches a clearly
     * established scheduled business process whose first E was left as new.
     */
    private void validateXinjiangScheduledEntryPolicy(RuleValidationResponse response,
                                                       Map<String, List<RowRef>> groups) {
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            List<RowRef> rows = entry.getValue();
            if (rows == null || rows.size() < 2 || entry.getKey().startsWith("__ROW_")) {
                continue;
            }
            RowRef first = rows.get(0);
            if (!"E".equals(safe(first.row.getMovementType()).toUpperCase(Locale.ROOT))
                    || !hasScheduledTriggerEvidence(first.row)
                    || looksLikeTechnicalTimer(first.row)
                    || !hasBusinessOutcome(rows)) {
                continue;
            }
            if ("复用".equals(safe(first.row.getReuseDegree()))
                    && Math.abs(first.row.getCfp() - 0.33D) <= CFP_TOLERANCE) {
                continue;
            }
            add(response, first.rowNumber, safe(first.row.getFunctionProcess()), "复用与 CFP", "RISK",
                    "有效定时/时钟/系统事件功能过程的初始触发 E 未按新疆统一规则设为复用 0.33",
                    "重新执行复用度 AI 调整，按 XJ-TIMER-E 核对当前行证据；只调整首行 E，后续 R/W/X 独立判断。若只是技术定时器或内部轮询，应排除该非 COSMIC 触发。");
        }
    }

    private boolean hasScheduledTriggerEvidence(FunctionSplitRow row) {
        String value = safe(row.getFunctionalUser()) + " " + safe(row.getTriggerEvent()) + " "
                + safe(row.getFunctionProcess()) + " " + safe(row.getSubProcessDescription()) + " "
                + safe(row.getEvidence());
        return containsAny(value, "定时", "时钟", "调度", "系统事件");
    }

    private boolean looksLikeTechnicalTimer(FunctionSplitRow row) {
        String value = safe(row.getFunctionProcess()) + " " + safe(row.getFunctionDescription()) + " "
                + safe(row.getSubProcessDescription()) + " " + safe(row.getEvidence());
        return containsAny(value, "心跳", "保活", "框架调度", "定时器配置", "调度器配置", "内部轮询");
    }

    private boolean hasBusinessOutcome(List<RowRef> rows) {
        for (int i = 1; i < rows.size(); i++) {
            String movement = safe(rows.get(i).row.getMovementType()).toUpperCase(Locale.ROOT);
            if ("W".equals(movement) || "X".equals(movement)) {
                return true;
            }
        }
        return false;
    }

    private void validateCrossFunctionReuse(RuleValidationResponse response,
                                             Map<String, List<RowRef>> groups) {
        Map<String, List<ProcessRowRef>> exactIndex = new LinkedHashMap<String, List<ProcessRowRef>>();
        Map<String, List<ProcessRowRef>> pairIndex = new LinkedHashMap<String, List<ProcessRowRef>>();
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            String processKey = entry.getKey();
            if (processKey.startsWith("__ROW_")) {
                continue;
            }
            for (RowRef rowRef : entry.getValue()) {
                FunctionSplitRow row = rowRef.row;
                String subProcess = comparable(row.getSubProcessDescription());
                String movement = comparable(row.getMovementType());
                String attributes = attributeComparable(row.getDataAttributes());
                if (!ReuseAuditSimilarity.hasCompleteAuditElements(row)) {
                    continue;
                }
                String boundary = boundaryKey(row);
                String exactKey = boundary + "|" + subProcess + "|" + movement + "|" + attributes;
                ProcessRowRef exact = firstDifferentProcess(exactIndex.get(exactKey), processKey);
                boolean reported = false;
                if (exact != null && !isReuseOrLegacyCfp(row)) {
                    add(response, rowRef.rowNumber, safe(row.getFunctionProcess()), "复用与 CFP", "RISK",
                            "与第 " + exact.rowRef.rowNumber + " 行跨功能过程的子过程描述、数据移动类型、数据属性三要素完全相同，且未发现跨层/跨边界依据",
                            "先判断两个完整用户目标：功能级重复/包含时当前功能按利旧、CFP=0；若完整功能不同而只是该子过程被后置功能共享，应由复用度调整节点自动设为复用、CFP=0.33。");
                    reported = true;
                }
                if (!reported && !isReuseOrLegacyCfp(row)) {
                    String[] pairKeys = {
                            boundary + "|S+M|" + subProcess + "|" + movement,
                            boundary + "|S+A|" + subProcess + "|" + attributes,
                            boundary + "|M+A|" + movement + "|" + attributes
                    };
                    for (String pairKey : pairKeys) {
                        ProcessRowRef previous = firstComparableDifferentProcess(pairIndex.get(pairKey), processKey, row);
                        if (previous == null) {
                            continue;
                        }
                        add(response, rowRef.rowNumber, safe(row.getFunctionProcess()), "复用与 CFP", "RISK",
                                "与第 " + previous.rowRef.rowNumber + " 行跨功能过程的审核三要素有两项相同，存在语义复用候选",
                                "该候选应由复用度调整节点自动设为复用、CFP=0.33；若仍出现本提示，请重新执行复用度调整。");
                        reported = true;
                        break;
                    }
                }
                addIndex(exactIndex, exactKey, new ProcessRowRef(processKey, rowRef));
                for (String pairKey : Arrays.asList(
                        boundary + "|S+M|" + subProcess + "|" + movement,
                        boundary + "|S+A|" + subProcess + "|" + attributes,
                        boundary + "|M+A|" + movement + "|" + attributes)) {
                    addIndex(pairIndex, pairKey, new ProcessRowRef(processKey, rowRef));
                }
            }
        }
    }

    private void validateDuplicateFunctionProcesses(RuleValidationResponse response,
                                                    Map<String, List<RowRef>> groups) {
        Map<String, ProcessRowRef> fingerprints = new LinkedHashMap<String, ProcessRowRef>();
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            if (entry.getKey().startsWith("__ROW_") || entry.getValue().isEmpty()) {
                continue;
            }
            List<RowRef> rows = entry.getValue();
            StringBuilder fingerprint = new StringBuilder(boundaryKey(rows.get(0).row));
            for (RowRef ref : rows) {
                fingerprint.append('|').append(comparable(ref.row.getMovementType()))
                        .append(':').append(comparable(ref.row.getSubProcessDescription()))
                        .append(':').append(comparable(ref.row.getDataGroup()))
                        .append(':').append(attributeComparable(ref.row.getDataAttributes()));
            }
            String key = fingerprint.toString();
            ProcessRowRef previous = fingerprints.get(key);
            if (previous != null && !previous.processKey.equals(entry.getKey()) && !allLegacy(rows)) {
                RowRef current = rows.get(0);
                add(response, current.rowNumber, safe(current.row.getFunctionProcess()), "复用与 CFP", "RISK",
                        "当前完整功能过程与第 " + previous.rowRef.rowNumber + " 行所在功能过程的数据移动、数据组和数据属性结构完全相同，疑似功能级重复",
                        "按业务目标和历史判例核对包含关系；若已有功能已完整实现该用户目标，当前整个功能过程按利旧、CFP=0，而不是仅逐行改成 0.33。");
            } else if (previous == null) {
                fingerprints.put(key, new ProcessRowRef(entry.getKey(), rows.get(0)));
            }
        }
    }

    private ProcessRowRef firstDifferentProcess(List<ProcessRowRef> candidates, String processKey) {
        for (ProcessRowRef candidate : candidates == null
                ? Collections.<ProcessRowRef>emptyList() : candidates) {
            if (!candidate.processKey.equals(processKey)) {
                return candidate;
            }
        }
        return null;
    }

    private ProcessRowRef firstComparableDifferentProcess(List<ProcessRowRef> candidates,
                                                          String processKey,
                                                          FunctionSplitRow current) {
        for (ProcessRowRef candidate : candidates == null
                ? Collections.<ProcessRowRef>emptyList() : candidates) {
            if (candidate.processKey.equals(processKey)) {
                continue;
            }
            FunctionSplitRow previous = candidate.rowRef.row;
            if (ReuseAuditSimilarity.isComparableAcrossFunctions(previous, current)) {
                return candidate;
            }
        }
        return null;
    }

    private void addIndex(Map<String, List<ProcessRowRef>> index, String key, ProcessRowRef value) {
        List<ProcessRowRef> values = index.get(key);
        if (values == null) {
            values = new ArrayList<ProcessRowRef>();
            index.put(key, values);
        }
        values.add(value);
    }

    private boolean isLegacyCfp(FunctionSplitRow row) {
        return "利旧".equals(safe(row.getReuseDegree())) && Math.abs(row.getCfp()) <= CFP_TOLERANCE;
    }

    private boolean isReuseOrLegacyCfp(FunctionSplitRow row) {
        return isReuseCfp(row) || isLegacyCfp(row);
    }

    private boolean allLegacy(List<RowRef> rows) {
        for (RowRef ref : rows) {
            if (!isLegacyCfp(ref.row)) {
                return false;
            }
        }
        return !rows.isEmpty();
    }

    private void validateQueryDisplayOverlap(RuleValidationResponse response, Map<String, List<RowRef>> groups) {
        Map<String, RowRef> queries = new LinkedHashMap<String, RowRef>();
        Map<String, RowRef> displays = new LinkedHashMap<String, RowRef>();
        for (Map.Entry<String, List<RowRef>> entry : groups.entrySet()) {
            if (entry.getKey().startsWith("__ROW_") || entry.getValue().isEmpty()) {
                continue;
            }
            RowRef ref = entry.getValue().get(0);
            String process = safe(ref.row.getFunctionProcess());
            String object = processObject(process);
            if (!StringUtils.hasText(object)) {
                continue;
            }
            String key = boundaryKey(ref.row) + "|" + object;
            if (containsAny(process, "\u67e5\u8be2", "\u68c0\u7d22", "\u641c\u7d22")) {
                queries.put(key, ref);
                RowRef display = displays.get(key);
                if (display != null) {
                    addQueryDisplayIssue(response, ref, display);
                }
            }
            if (containsAny(process, "\u5c55\u793a", "\u663e\u793a")) {
                displays.put(key, ref);
                RowRef query = queries.get(key);
                if (query != null) {
                    addQueryDisplayIssue(response, ref, query);
                }
            }
        }
    }

    private void addQueryDisplayIssue(RuleValidationResponse response, RowRef current, RowRef other) {
        add(response, current.rowNumber, safe(current.row.getFunctionProcess()), "\u62c6\u5206\u7c92\u5ea6", "RISK",
                "\u4e0e\u7b2c " + other.rowNumber + " \u884c\u5206\u522b\u4ee5\u201c\u67e5\u8be2\u201d\u548c\u201c\u5c55\u793a\u201d\u62c6\u5206\u540c\u4e00\u4e1a\u52a1\u5bf9\u8c61\uff0c\u53ef\u80fd\u91cd\u590d\u8ba1\u91cf",
                "\u666e\u901a\u67e5\u8be2\u529f\u80fd\u4e2d\uff0c\u8fd4\u56de/\u5c55\u793a\u67e5\u8be2\u7ed3\u679c\u901a\u5e38\u662f\u540c\u4e00\u529f\u80fd\u8fc7\u7a0b\u7684 X\uff1b\u53ea\u6709\u5b58\u5728\u72ec\u7acb\u89e6\u53d1\u3001\u6570\u636e\u7ec4\u6216\u8f93\u51fa\u7ed3\u679c\u65f6\u624d\u5206\u5f00\u3002");
    }

    private int sameAuditElementCount(FunctionSplitRow left, FunctionSplitRow right) {
        int count = 0;
        String leftSubProcess = comparable(left.getSubProcessDescription());
        String rightSubProcess = comparable(right.getSubProcessDescription());
        String leftMovement = comparable(left.getMovementType());
        String rightMovement = comparable(right.getMovementType());
        String leftAttributes = attributeComparable(left.getDataAttributes());
        String rightAttributes = attributeComparable(right.getDataAttributes());
        if (!StringUtils.hasText(leftSubProcess) || !StringUtils.hasText(rightSubProcess)
                || !StringUtils.hasText(leftMovement) || !StringUtils.hasText(rightMovement)
                || !StringUtils.hasText(leftAttributes) || !StringUtils.hasText(rightAttributes)) {
            return 0;
        }
        if (leftSubProcess.equals(rightSubProcess)) {
            count++;
        }
        if (leftMovement.equals(rightMovement)) {
            count++;
        }
        if (leftAttributes.equals(rightAttributes)) {
            count++;
        }
        return count;
    }

    private boolean isReuseCfp(FunctionSplitRow row) {
        return "\u590d\u7528".equals(safe(row.getReuseDegree())) && Math.abs(row.getCfp() - 0.33D) <= CFP_TOLERANCE;
    }

    private boolean isDistinctDataGroupMovement(FunctionSplitRow left, FunctionSplitRow right) {
        return comparable(left.getSubProcessDescription()).equals(comparable(right.getSubProcessDescription()))
                && comparable(left.getMovementType()).equals(comparable(right.getMovementType()))
                && !comparable(left.getDataGroup()).equals(comparable(right.getDataGroup()))
                && !attributeComparable(left.getDataAttributes()).equals(attributeComparable(right.getDataAttributes()));
    }

    private String boundaryKey(FunctionSplitRow row) {
        return ReuseAuditSimilarity.boundaryKey(row);
    }

    private String processObject(String process) {
        return ReuseAuditSimilarity.processObject(process);
    }

    private String comparable(String value) {
        return ReuseAuditSimilarity.comparable(value);
    }

    private String attributeComparable(String value) {
        return ReuseAuditSimilarity.attributeComparable(value);
    }

    private void addGroupIssue(RuleValidationResponse response, List<RowRef> rows, String process, String field,
                               String severity, String message, String suggestion) {
        for (RowRef ref : rows) {
            add(response, ref.rowNumber, process, field, severity, message, suggestion);
        }
    }

    private RuleValidationIssue add(RuleValidationResponse response, int rowIndex, String functionProcess, String field,
                                    String severity, String message, String suggestion) {
        RuleValidationIssue issue = new RuleValidationIssue();
        issue.setRowIndex(rowIndex);
        issue.setFunctionProcess(functionProcess);
        issue.setField(field);
        issue.setSeverity(severity);
        issue.setMessage(message);
        issue.setSuggestion(suggestion);
        response.getIssues().add(issue);
        return issue;
    }

    private void finish(RuleValidationResponse response) {
        int errors = 0;
        int risks = 0;
        int structureRisks = 0;
        for (RuleValidationIssue issue : response.getIssues()) {
            if ("ERROR".equals(issue.getSeverity())) {
                errors++;
            } else if ("RISK".equals(issue.getSeverity())) {
                risks++;
                if ("\u529f\u80fd\u8fc7\u7a0b".equals(issue.getField())
                        || "\u6570\u636e\u79fb\u52a8\u7ed3\u6784".equals(issue.getField())
                        || "数据移动组合".equals(issue.getField())
                        || "数据组".equals(issue.getField())) {
                    structureRisks++;
                }
            }
        }
        response.setIssueCount(response.getIssues().size());
        response.setErrorCount(errors);
        response.setRiskCount(risks);
        response.setSuggestionCount(0);
        response.setStructureRiskCount(structureRisks);
        response.setExpressionConfirmCount(0);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> resolveHighRiskKeywords() {
        List<String> keywords = new ArrayList<String>(HIGH_RISK_KEYWORDS);
        for (String keyword : parseKeywords(readSplitRules())) {
            if (!"配置".equals(keyword) && !keywords.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        keywords.remove("配置");
        return keywords;
    }

    private List<String> parseKeywords(String rules) {
        List<String> keywords = new ArrayList<String>();
        if (!StringUtils.hasText(rules)) {
            return keywords;
        }
        if (rules.contains("COSMIC 软件造价拆分基础规则 v2.0")
                || rules.contains("COSMIC 软件造价拆分基础规则 v2.1")) {
            return parseV2Keywords(rules);
        }
        boolean collecting = false;
        String[] lines = rules.split("\\R");
        for (String line : lines) {
            String value = line.trim();
            if (value.startsWith("## 2.") || value.startsWith("## 3.")) {
                collecting = true;
                continue;
            }
            if (collecting && value.startsWith("处理要求")) {
                collecting = false;
                continue;
            }
            if (collecting && value.startsWith("## ")) {
                collecting = false;
            }
            if (collecting && value.startsWith("- ")) {
                String keyword = value.substring(2).trim();
                if (StringUtils.hasText(keyword) && !keywords.contains(keyword)) {
                    keywords.add(keyword);
                }
            }
        }
        return keywords;
    }

    private List<String> parseV2Keywords(String rules) {
        List<String> keywords = new ArrayList<String>();
        boolean collecting = false;
        for (String line : rules.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("### 9.1") || value.startsWith("### 9.5")) {
                collecting = true;
                continue;
            }
            if (collecting && (value.startsWith("### ") || value.startsWith("**“配置”"))) {
                collecting = false;
                continue;
            }
            if (!collecting || !value.startsWith("- ")) {
                continue;
            }
            String keyword = value.substring(2).trim().replace("`", "");
            if (keyword.endsWith("；") || keyword.endsWith("。")) {
                keyword = keyword.substring(0, keyword.length() - 1).trim();
            }
            if (StringUtils.hasText(keyword) && !keywords.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    private String readSplitRules() {
        return splitRuleConfigService == null ? "" : splitRuleConfigService.getRules();
    }

    private static class RowRef {
        private final int rowNumber;
        private final FunctionSplitRow row;

        private RowRef(int rowNumber, FunctionSplitRow row) {
            this.rowNumber = rowNumber;
            this.row = row;
        }
    }

    private static class ProcessRowRef {
        private final String processKey;
        private final RowRef rowRef;

        private ProcessRowRef(String processKey, RowRef rowRef) {
            this.processKey = processKey;
            this.rowRef = rowRef;
        }
    }
}
