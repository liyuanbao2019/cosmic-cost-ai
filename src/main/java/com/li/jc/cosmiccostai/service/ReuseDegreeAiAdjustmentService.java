package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.AiModelProfile;
import com.li.jc.cosmiccostai.dto.ReuseDegreeAdjustmentDecision;
import com.li.jc.cosmiccostai.dto.ReuseDegreeAdjustmentResponse;
import com.li.jc.cosmiccostai.dto.ReviewResponse;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Final AI stage of the split pipeline. It evaluates reuse classifications across
 * the complete table and applies only evidence-backed changes that pass server-side
 * safety gates. This is independent from the disabled row-level AI review feature.
 */
@Service
public class ReuseDegreeAiAdjustmentService {

    private static final Set<String> REUSE_VALUES = new HashSet<String>(
            Arrays.asList("新增", "复用", "利旧"));
    private static final Set<String> AUDIT_DIMENSIONS = new HashSet<String>(
            Arrays.asList("subprocessdescription", "movementtype", "dataattributes"));
    private static final double REUSE_CONFIDENCE = 0.90D;
    private static final double LEGACY_CONFIDENCE = 0.95D;
    private static final double NEW_CONFIDENCE = 0.90D;
    private static final String MODULE_ANCHOR_DEFAULT_SCOPE = "MODULE_ANCHOR_DEFAULT";
    private static final String CROSS_FUNCTION_TWO_DIMENSIONS_SCOPE = "CROSS_FUNCTION_TWO_DIMENSIONS";
    private static final String CROSS_FUNCTION_SHARED_MOVEMENT_SCOPE = "CROSS_FUNCTION_SHARED_MOVEMENT";
    private static final String RULE_POLICY = "RULE_POLICY";
    private static final String CURRENT_TABLE_REUSE = "CURRENT_TABLE_REUSE";
    private static final String XINJIANG_TIMER_ENTRY_RULE = "XJ-TIMER-E";
    private static final String KEY_SEPARATOR = "\u001f";

    private final AiModelConfigService aiModelConfigService;
    private final SplitRuleConfigService splitRuleConfigService;
    private final ReviewService reviewService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> runningProjects = ConcurrentHashMap.newKeySet();

    public ReuseDegreeAiAdjustmentService(AiModelConfigService aiModelConfigService,
                                          SplitRuleConfigService splitRuleConfigService,
                                          ReviewService reviewService) {
        this.aiModelConfigService = aiModelConfigService;
        this.splitRuleConfigService = splitRuleConfigService;
        this.reviewService = reviewService;
    }

    public ReuseDegreeAdjustmentResponse adjust(String projectId, List<FunctionSplitRow> inputRows) {
        if (!StringUtils.hasText(projectId)) {
            throw new BusinessException("项目 ID 不能为空。");
        }
        if (inputRows == null || inputRows.isEmpty()) {
            throw new BusinessException("复用度 AI 调整前必须先生成完整拆分结果。");
        }
        if (!runningProjects.add(projectId)) {
            throw new BusinessException("该项目正在执行复用度 AI 调整，请等待当前任务完成。");
        }
        try {
            List<FunctionSplitRow> rows = copyRows(inputRows);
            double cfpBefore = totalCfp(rows);
            String prompt = buildPrompt(rows);
            String aiText = aiModelConfigService.generateForTask(
                    prompt, AiPromptTask.REUSE_ADJUSTMENT, false);
            List<ReuseDegreeAdjustmentDecision> decisions = parseDecisions(aiText);
            ModuleReusePlan moduleReusePlan = moduleReusePlan(rows);
            addCrossFunctionSharedMovementTargets(rows, moduleReusePlan);
            List<ReuseDegreeAdjustmentDecision> modelDecisionsToApply =
                    excludeModuleReuseModelDecisions(decisions, moduleReusePlan);
            ApplySummary summary = applyDecisions(rows, modelDecisionsToApply);
            removeAppliedRulePolicyRows(moduleReusePlan, modelDecisionsToApply);
            int governedModelDecisions = decisions.size() - modelDecisionsToApply.size();
            if (governedModelDecisions > 0) {
                summary.skipped += governedModelDecisions;
                summary.warnings.add("有 " + governedModelDecisions
                        + " 条模型建议属于服务端确定性复用规则范围，已由服务端统一接管。");
            }
            List<ReuseDegreeAdjustmentDecision> moduleRuleDecisions =
                    applyModuleReusePlan(rows, moduleReusePlan);
            summary.applied += moduleRuleDecisions.size();
            ReviewResponse saved = reviewService.saveReuseAdjustedReview(projectId, rows);
            List<FunctionSplitRow> savedRows = saved.getSplitRows() == null ? rows : saved.getSplitRows();

            List<ReuseDegreeAdjustmentDecision> allDecisions =
                    new ArrayList<ReuseDegreeAdjustmentDecision>(decisions);
            allDecisions.addAll(moduleRuleDecisions);

            ReuseDegreeAdjustmentResponse response = new ReuseDegreeAdjustmentResponse();
            response.setProjectId(projectId);
            response.setModel(currentModel());
            response.setRuleVersion(splitRuleConfigService.getRuleVersion());
            response.setTotalRows(savedRows.size());
            response.setModelDecisionCount(decisions.size());
            response.setAdjustedRows(summary.applied);
            response.setRuleAdjustedRows(moduleRuleDecisions.size());
            response.setSkippedDecisions(summary.skipped);
            response.setCfpBefore(cfpBefore);
            response.setCfpAfter(totalCfp(savedRows));
            response.setWarnings(summary.warnings);
            response.setDecisions(allDecisions);
            response.setRows(savedRows);
            return response;
        } finally {
            runningProjects.remove(projectId);
        }
    }

    String buildPrompt(List<FunctionSplitRow> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append("你正在执行拆分流程最后的独立节点【复用度 AI 调整】，不是 AI 初审或风险复核。\n");
        builder.append("业务目标：依据完整规则和全表证据给出最接近最终设计院评审的复用度，降低后续核减率；不追求 CFP 多，也不追求 CFP 少。\n");
        builder.append("必须遵守：\n");
        builder.append("1. 必须把整张表作为同一判断范围，先识别功能级重复/包含，再识别子过程级复用，最后识别真实跨层、跨边界的新增。\n");
        builder.append("2. 同一一级/二级/三级模块路径内，新增和查询功能过程的全部子过程设为新增；导入或上传若是同模块新增数据功能的配套能力，全部子过程设为复用，若同模块不存在新增功能则可作为新增基准；同模块修改、删除、导出、下载功能过程的全部子过程设为复用。不要求增删改查四类齐全。该规则由服务端统一落地，模型不要为这些行输出 decision，也不得提出相反值。\n");
        builder.append("3. 服务端先判断整个功能过程是否重复/包含，再按表格顺序判断跨功能共享子过程：后置功能过程中审核三要素恰有两项相同，或三项完全相同但整个功能过程并不重复，且未发现跨层/跨边界差异时，当前行自动设为复用/0.33；模型不要为这些行输出 decision，也不得提出相反值。\n");
        builder.append("4. 新增=1、复用=0.33、利旧=0。你只判断 suggestedReuseDegree，CFP 由服务端统一映射。\n");
        builder.append("5. 一般表内复用判断使用 decisionType=CURRENT_TABLE_REUSE；建议复用时 duplicateScope=MOVEMENT，必须给出有效 referenceRowIndexes，并在 matchedDimensions 中列出审核三要素中至少两项：subProcessDescription、movementType、dataAttributes。\n");
        builder.append("6. 建议利旧时 duplicateScope=FUNCTION，必须证明整个用户目标重复/被包含，并为该功能过程的每一行分别输出 decision；matchedDimensions 必须包含全部三项。\n");
        builder.append("7. 将复用/利旧恢复为新增时，必须在 boundaryDifference 中写清真实的功能用户、业务目标、跨层或跨软件边界差异。\n");
        builder.append("8. evidenceSufficient 只有在当前行、参考行、原文 evidence/sourceReferences 能形成闭环时才可为 true；不得用同义词、字段换序或模板常识代替证据。\n");
        builder.append("9. 执行统一规则中的新疆特殊场景 XJ-TIMER-E：逐个识别有效定时/时钟/系统事件功能过程。只有功能过程成立、有明确业务目标且后续存在真实 W 或 X 时，首个初始触发 E 才调整为复用/0.33；纯技术定时器不计，后续 R/W/X 不随 E 调整。该决策使用 decisionType=RULE_POLICY、ruleId=XJ-TIMER-E、rowScope=MOVEMENT，不要求引用其它表格行，但必须在 evidenceReferences 中引用当前行真实 REQ 标识；Excel 导入没有 REQ 标识时引用 ROW-行号。\n");
        builder.append("10. 只输出确需改变的行；不改变功能过程、E/R/W/X、数据组、数据属性、描述和行顺序。\n");
        builder.append("11. 输出顶层 JSON 对象：{\"decisions\":[{\"rowIndex\":1,\"decisionType\":\"CURRENT_TABLE_REUSE\",\"ruleId\":\"\",\"rowScope\":\"MOVEMENT\",\"evidenceReferences\":[\"REQ-L0001\"],\"suggestedReuseDegree\":\"复用\",\"duplicateScope\":\"MOVEMENT\",\"referenceRowIndexes\":[2],\"matchedDimensions\":[\"movementType\",\"dataAttributes\"],\"boundaryDifference\":\"\",\"reason\":\"...\",\"evidenceSufficient\":true,\"confidence\":0.96}]}。无可靠调整时输出 {\"decisions\":[]}。\n\n");
        builder.append("【当前生效完整规则】\n");
        builder.append(splitRuleConfigService.getEffectiveRulesPrompt()).append("【当前生效完整规则结束】\n\n");
        builder.append("【待整体判断的完整拆分表，共 ").append(rows.size()).append(" 行】\n");
        builder.append(rowsJson(rows));
        builder.append("\n【完整拆分表结束】");
        String prompt = builder.toString();
        return aiModelConfigService.isDeepSeekActive()
                ? DeepSeekPromptProfile.specialize(
                        AiPromptTask.REUSE_ADJUSTMENT, prompt, splitRuleConfigService.getRuleVersion())
                : prompt;
    }

    List<ReuseDegreeAdjustmentDecision> parseDecisions(String aiText) {
        String json = stripJson(aiText);
        try {
            if (json.startsWith("[")) {
                return objectMapper.readValue(json,
                        new TypeReference<List<ReuseDegreeAdjustmentDecision>>() { });
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode decisions = root.path("decisions");
            if (!decisions.isArray()) {
                throw new BusinessException("复用度 AI 调整结果缺少 decisions 数组。");
            }
            return objectMapper.convertValue(decisions,
                    new TypeReference<List<ReuseDegreeAdjustmentDecision>>() { });
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("复用度 AI 调整结果不是合法 JSON，请重新执行。", ex);
        }
    }

    ApplySummary applyDecisions(List<FunctionSplitRow> rows,
                                List<ReuseDegreeAdjustmentDecision> decisions) {
        List<ReuseDegreeAdjustmentDecision> values = decisions == null
                ? Collections.<ReuseDegreeAdjustmentDecision>emptyList() : decisions;
        Map<Integer, ReuseDegreeAdjustmentDecision> unique = new LinkedHashMap<Integer, ReuseDegreeAdjustmentDecision>();
        for (ReuseDegreeAdjustmentDecision decision : values) {
            if (decision != null && !unique.containsKey(decision.getRowIndex())) {
                unique.put(decision.getRowIndex(), decision);
            } else if (decision != null) {
                reject(decision, "同一行返回了重复 decision，已拒绝自动调整。");
            }
        }
        Map<String, List<Integer>> processRows = processRows(rows);
        ApplySummary summary = new ApplySummary();
        for (ReuseDegreeAdjustmentDecision decision : values) {
            if (decision == null || decision.isApplied() || StringUtils.hasText(decision.getApplyMessage())) {
                if (decision != null && !decision.isApplied()) {
                    summary.skipped++;
                }
                continue;
            }
            int index = decision.getRowIndex() - 1;
            if (index < 0 || index >= rows.size()) {
                reject(decision, "rowIndex 超出当前完整表范围。");
                summary.skipped++;
                continue;
            }
            FunctionSplitRow row = rows.get(index);
            String original = normalizeReuse(row.getReuseDegree());
            String target = normalizeReuse(decision.getSuggestedReuseDegree());
            decision.setOriginalReuseDegree(original);
            decision.setSuggestedReuseDegree(target);
            decision.setSuggestedCfp(cfp(target));
            String validation = validateDecision(rows, processRows, unique, decision, row);
            if (StringUtils.hasText(validation)) {
                reject(decision, validation);
                summary.skipped++;
                continue;
            }
            if (original.equals(target)) {
                reject(decision, "建议值与当前复用度相同，无需调整。");
                summary.skipped++;
                continue;
            }
            row.setReuseDegree(target);
            row.setCfp(cfp(target));
            row.setReuseBasis(normalizeDecisionType(decision.getDecisionType()));
            row.setReuseRuleId(text(decision.getRuleId()));
            decision.setApplied(true);
            decision.setApplyMessage("已按证据调整为" + target + "，CFP=" + formatCfp(cfp(target)) + "。");
            summary.applied++;
        }
        if (summary.skipped > 0) {
            summary.warnings.add("有 " + summary.skipped + " 条模型建议未通过服务端证据闸门，已保持原复用度。 ");
        }
        return summary;
    }

    List<ReuseDegreeAdjustmentDecision> applyModuleReuseDefaults(List<FunctionSplitRow> rows) {
        ModuleReusePlan plan = moduleReusePlan(rows);
        addCrossFunctionSharedMovementTargets(rows, plan);
        return applyModuleReusePlan(rows, plan);
    }

    private List<ReuseDegreeAdjustmentDecision> excludeModuleReuseModelDecisions(
            List<ReuseDegreeAdjustmentDecision> decisions, ModuleReusePlan plan) {
        List<ReuseDegreeAdjustmentDecision> result = new ArrayList<ReuseDegreeAdjustmentDecision>();
        for (ReuseDegreeAdjustmentDecision decision : decisions == null
                ? Collections.<ReuseDegreeAdjustmentDecision>emptyList() : decisions) {
            if (decision != null && plan.targets.containsKey(decision.getRowIndex())
                    && !isRulePolicyDecision(decision)) {
                reject(decision, "该行属于服务端确定性复用规则范围，模型建议不参与落地。 ");
                continue;
            }
            result.add(decision);
        }
        return result;
    }

    private void removeAppliedRulePolicyRows(ModuleReusePlan plan,
                                             List<ReuseDegreeAdjustmentDecision> decisions) {
        for (ReuseDegreeAdjustmentDecision decision : decisions == null
                ? Collections.<ReuseDegreeAdjustmentDecision>emptyList() : decisions) {
            if (decision != null && decision.isApplied() && isRulePolicyDecision(decision)) {
                plan.targets.remove(decision.getRowIndex());
            }
        }
    }

    private ModuleReusePlan moduleReusePlan(List<FunctionSplitRow> rows) {
        ModuleReusePlan plan = new ModuleReusePlan();
        Map<String, Map<String, ProcessGroup>> modules =
                new LinkedHashMap<String, Map<String, ProcessGroup>>();
        for (int index = 0; index < rows.size(); index++) {
            FunctionSplitRow row = rows.get(index);
            String moduleKey = moduleKey(row);
            String process = text(row.getFunctionProcess());
            ModuleAction action = moduleAction(process);
            if (!StringUtils.hasText(moduleKey) || !StringUtils.hasText(process) || action == null) {
                continue;
            }
            Map<String, ProcessGroup> processGroups = modules.get(moduleKey);
            if (processGroups == null) {
                processGroups = new LinkedHashMap<String, ProcessGroup>();
                modules.put(moduleKey, processGroups);
            }
            String processKey = moduleKey + KEY_SEPARATOR + process;
            ProcessGroup group = processGroups.get(processKey);
            if (group == null) {
                group = new ProcessGroup(moduleLabel(row), process, action);
                processGroups.put(processKey, group);
            }
            if (group.action == action) {
                group.rowIndexes.add(index + 1);
            }
        }

        for (Map<String, ProcessGroup> processGroups : modules.values()) {
            Set<ModuleAction> actions = new HashSet<ModuleAction>();
            for (ProcessGroup group : processGroups.values()) {
                actions.add(group.action);
            }
            if (!actions.contains(ModuleAction.CREATE)
                    && !actions.contains(ModuleAction.QUERY)
                    && !actions.contains(ModuleAction.IMPORT)) {
                continue;
            }
            List<Integer> anchors = firstRows(processGroups,
                    ModuleAction.CREATE, ModuleAction.QUERY, ModuleAction.IMPORT);
            List<Integer> createAnchors = firstRows(processGroups, ModuleAction.CREATE);
            boolean hasCreate = actions.contains(ModuleAction.CREATE);
            for (ProcessGroup group : processGroups.values()) {
                String target;
                String reason;
                List<Integer> referenceAnchors = anchors;
                if (group.action == ModuleAction.CREATE || group.action == ModuleAction.QUERY) {
                    target = "新增";
                    reason = "新增或查询属于同模块基准功能";
                } else if (group.action == ModuleAction.IMPORT) {
                    target = hasCreate ? "复用" : "新增";
                    reason = hasCreate
                            ? "同模块已存在新增数据功能，当前导入或上传属于其配套能力"
                            : "同模块不存在新增数据功能，当前导入或上传可作为独立新增基准";
                    referenceAnchors = hasCreate
                            ? createAnchors : Collections.<Integer>emptyList();
                } else if (group.action == ModuleAction.DELETE || group.action == ModuleAction.MODIFY
                        || group.action == ModuleAction.EXPORT) {
                    target = "复用";
                    reason = "同模块存在新增、查询、导入或上传基准功能";
                } else {
                    continue;
                }
                for (Integer rowIndex : group.rowIndexes) {
                    List<Integer> references = new ArrayList<Integer>();
                    for (Integer anchor : referenceAnchors) {
                        if (!anchor.equals(rowIndex)) {
                            references.add(anchor);
                        }
                    }
                    plan.targets.put(rowIndex,
                            new ModuleReuseTarget(target, group.moduleLabel, group.process,
                                    references, reason));
                }
            }
        }
        return plan;
    }

    private void addCrossFunctionSharedMovementTargets(List<FunctionSplitRow> rows,
                                                        ModuleReusePlan plan) {
        Set<String> duplicateProcessKeys = duplicateProcessKeys(rows);
        Map<String, List<IndexedRow>> exactIndex = new LinkedHashMap<String, List<IndexedRow>>();
        Map<String, List<IndexedRow>> pairIndex = new LinkedHashMap<String, List<IndexedRow>>();
        for (int index = 0; index < rows.size(); index++) {
            FunctionSplitRow row = rows.get(index);
            if (!ReuseAuditSimilarity.hasCompleteAuditElements(row)) {
                continue;
            }
            int rowIndex = index + 1;
            String processKey = FunctionProcessKey.of(row);
            IndexedRow exact = firstDifferentProcess(exactIndex.get(
                    ReuseAuditSimilarity.exactKey(row)), processKey, row, true);
            if ("新增".equals(normalizeReuse(row.getReuseDegree()))
                    && !duplicateProcessKeys.contains(processKey)) {
                if (exact != null) {
                    plan.targets.put(rowIndex, new ModuleReuseTarget(
                            "复用", moduleLabel(row), text(row.getFunctionProcess()),
                            Collections.singletonList(exact.rowIndex),
                            "与拆分数据第 " + exact.rowIndex
                                    + " 条跨功能过程的审核三要素完全相同，但两个完整功能过程并不重复，当前行为后置功能共享子过程",
                            CROSS_FUNCTION_SHARED_MOVEMENT_SCOPE,
                            Arrays.asList("subProcessDescription", "movementType", "dataAttributes")));
                } else {
                String[] pairKeys = ReuseAuditSimilarity.pairKeys(row);
                for (int pair = 0; pair < pairKeys.length; pair++) {
                    IndexedRow previous = firstDifferentProcess(
                            pairIndex.get(pairKeys[pair]), processKey, row, true);
                    if (previous == null) {
                        continue;
                    }
                    plan.targets.put(rowIndex, new ModuleReuseTarget(
                            "复用", moduleLabel(row), text(row.getFunctionProcess()),
                            Collections.singletonList(previous.rowIndex),
                            "与拆分数据第 " + previous.rowIndex
                                    + " 条跨功能过程的审核三要素恰有两项相同，且未发现跨层/跨边界差异",
                            CROSS_FUNCTION_TWO_DIMENSIONS_SCOPE,
                            ReuseAuditSimilarity.pairDimensions(pair)));
                    break;
                }
                }
            }
            addIndex(exactIndex, ReuseAuditSimilarity.exactKey(row),
                    new IndexedRow(rowIndex, processKey, row));
            for (String pairKey : ReuseAuditSimilarity.pairKeys(row)) {
                addIndex(pairIndex, pairKey, new IndexedRow(rowIndex, processKey, row));
            }
        }
    }

    private Set<String> duplicateProcessKeys(List<FunctionSplitRow> rows) {
        Map<String, List<FunctionSplitRow>> processRows = new LinkedHashMap<String, List<FunctionSplitRow>>();
        for (FunctionSplitRow row : rows) {
            String processKey = FunctionProcessKey.of(row);
            List<FunctionSplitRow> group = processRows.get(processKey);
            if (group == null) {
                group = new ArrayList<FunctionSplitRow>();
                processRows.put(processKey, group);
            }
            group.add(row);
        }
        Map<String, String> firstByFingerprint = new LinkedHashMap<String, String>();
        Set<String> duplicates = new HashSet<String>();
        for (Map.Entry<String, List<FunctionSplitRow>> entry : processRows.entrySet()) {
            String fingerprint = ReuseAuditSimilarity.processFingerprint(entry.getValue());
            String previous = firstByFingerprint.get(fingerprint);
            if (previous == null) {
                firstByFingerprint.put(fingerprint, entry.getKey());
            } else if (!previous.equals(entry.getKey())) {
                duplicates.add(entry.getKey());
            }
        }
        return duplicates;
    }

    private IndexedRow firstDifferentProcess(List<IndexedRow> candidates,
                                             String processKey,
                                             FunctionSplitRow current,
                                             boolean requireComparableObject) {
        for (IndexedRow candidate : candidates == null
                ? Collections.<IndexedRow>emptyList() : candidates) {
            if (candidate.processKey.equals(processKey)) {
                continue;
            }
            if (!requireComparableObject
                    || ReuseAuditSimilarity.isComparableAcrossFunctions(candidate.row, current)) {
                return candidate;
            }
        }
        return null;
    }

    private void addIndex(Map<String, List<IndexedRow>> index,
                          String key,
                          IndexedRow row) {
        List<IndexedRow> values = index.get(key);
        if (values == null) {
            values = new ArrayList<IndexedRow>();
            index.put(key, values);
        }
        values.add(row);
    }

    private List<Integer> firstRows(Map<String, ProcessGroup> groups, ModuleAction... actions) {
        Set<ModuleAction> accepted = new HashSet<ModuleAction>(Arrays.asList(actions));
        List<Integer> result = new ArrayList<Integer>();
        for (ProcessGroup group : groups.values()) {
            if (accepted.contains(group.action) && !group.rowIndexes.isEmpty()) {
                result.add(group.rowIndexes.get(0));
            }
        }
        return result;
    }

    private List<ReuseDegreeAdjustmentDecision> applyModuleReusePlan(
            List<FunctionSplitRow> rows, ModuleReusePlan plan) {
        List<ReuseDegreeAdjustmentDecision> decisions =
                new ArrayList<ReuseDegreeAdjustmentDecision>();
        for (Map.Entry<Integer, ModuleReuseTarget> entry : plan.targets.entrySet()) {
            int rowIndex = entry.getKey();
            if (rowIndex < 1 || rowIndex > rows.size()) {
                continue;
            }
            FunctionSplitRow row = rows.get(rowIndex - 1);
            ModuleReuseTarget target = entry.getValue();
            String original = normalizeReuse(row.getReuseDegree());
            double expectedCfp = cfp(target.reuseDegree);
            if (target.reuseDegree.equals(original)
                    && Math.abs(row.getCfp() - expectedCfp) < 0.000001D) {
                continue;
            }
            row.setReuseDegree(target.reuseDegree);
            row.setCfp(expectedCfp);
            row.setReuseBasis(target.scope);
            row.setReuseRuleId(target.scope);

            ReuseDegreeAdjustmentDecision decision = new ReuseDegreeAdjustmentDecision();
            decision.setRowIndex(rowIndex);
            decision.setOriginalReuseDegree(original);
            decision.setSuggestedReuseDegree(target.reuseDegree);
            decision.setSuggestedCfp(expectedCfp);
            decision.setDuplicateScope(target.scope);
            decision.setReferenceRowIndexes(target.referenceRowIndexes);
            decision.setMatchedDimensions(target.matchedDimensions);
            decision.setReason("同一功能模块“" + target.moduleLabel + "”中，"
                    + target.ruleReason + "；当前“" + target.process
                    + "”按同模块默认规则判为" + target.reuseDegree + "。");
            decision.setEvidenceSufficient(true);
            decision.setConfidence(1D);
            decision.setApplied(true);
            decision.setApplyMessage((MODULE_ANCHOR_DEFAULT_SCOPE.equals(target.scope)
                    ? "已按同模块基准功能默认规则调整为"
                    : "已按跨功能两项相同确定性规则调整为")
                    + target.reuseDegree + "，CFP=" + formatCfp(expectedCfp) + "。");
            decisions.add(decision);
        }
        return decisions;
    }

    private ModuleAction moduleAction(String process) {
        if (!StringUtils.hasText(process)
                || ActionMovementRule.hasMultipleIndependentActions(process, "", "")) {
            return null;
        }
        Set<ModuleAction> actions = new LinkedHashSet<ModuleAction>();
        for (ActionMovementRule.Match match : ActionMovementRule.resolveAll(process, "", "")) {
            String label = match.getProfile().getLabel();
            if ("新增".equals(label)) {
                actions.add(ModuleAction.CREATE);
            } else if ("删除".equals(label)) {
                actions.add(ModuleAction.DELETE);
            } else if ("修改".equals(label)) {
                actions.add(ModuleAction.MODIFY);
            } else if ("查询".equals(label)) {
                actions.add(ModuleAction.QUERY);
            } else if ("导入".equals(label)) {
                actions.add(ModuleAction.IMPORT);
            } else if ("导出".equals(label) || "下载/预览已有文件".equals(label)) {
                actions.add(ModuleAction.EXPORT);
            }
        }
        if (actions.isEmpty() && process.contains("上传")) {
            actions.add(ModuleAction.IMPORT);
        }
        return actions.size() == 1 ? actions.iterator().next() : null;
    }

    private String moduleKey(FunctionSplitRow row) {
        if (row == null) {
            return "";
        }
        String level1 = text(row.getLevel1Module());
        String level2 = text(row.getLevel2Module());
        String level3 = text(row.getLevel3Module());
        if (!StringUtils.hasText(level1) && !StringUtils.hasText(level2)
                && !StringUtils.hasText(level3)) {
            return "";
        }
        return level1 + KEY_SEPARATOR + level2 + KEY_SEPARATOR + level3;
    }

    private String moduleLabel(FunctionSplitRow row) {
        List<String> levels = new ArrayList<String>();
        for (String value : Arrays.asList(row.getLevel1Module(), row.getLevel2Module(), row.getLevel3Module())) {
            if (StringUtils.hasText(value)) {
                levels.add(value.trim());
            }
        }
        StringBuilder label = new StringBuilder();
        for (String level : levels) {
            if (label.length() > 0) {
                label.append(" / ");
            }
            label.append(level);
        }
        return label.toString();
    }

    private String validateDecision(List<FunctionSplitRow> rows,
                                    Map<String, List<Integer>> processRows,
                                    Map<Integer, ReuseDegreeAdjustmentDecision> unique,
                                    ReuseDegreeAdjustmentDecision decision,
                                    FunctionSplitRow row) {
        String target = decision.getSuggestedReuseDegree();
        if (!REUSE_VALUES.contains(target)) {
            return "suggestedReuseDegree 只能是新增、复用或利旧。";
        }
        if (!decision.isEvidenceSufficient() || !StringUtils.hasText(decision.getReason())) {
            return "证据未闭环或未说明判断理由。";
        }
        if ("新增".equals(target)) {
            if (decision.getConfidence() < NEW_CONFIDENCE
                    || !StringUtils.hasText(decision.getBoundaryDifference())) {
                return "恢复为新增必须达到置信度 0.90，并明确真实跨层、跨边界或用户目标差异。";
            }
            return "";
        }
        if (isRulePolicyDecision(decision)) {
            return validateRulePolicyDecision(rows, processRows, decision, row);
        }
        if (!hasValidReference(rows, decision)) {
            return "调整为复用或利旧必须引用当前全表中的其它有效行。";
        }
        int matched = matchedAuditDimensions(decision.getMatchedDimensions());
        if ("复用".equals(target)) {
            if (decision.getConfidence() < REUSE_CONFIDENCE
                    || !"MOVEMENT".equals(normalizeScope(decision.getDuplicateScope()))
                    || matched < 2) {
                return "复用必须达到置信度 0.90、限定为子过程级，并匹配至少两项审核三要素。";
            }
            return "";
        }
        if (decision.getConfidence() < LEGACY_CONFIDENCE
                || !"FUNCTION".equals(normalizeScope(decision.getDuplicateScope()))
                || matched < 3) {
            return "利旧必须达到置信度 0.95、限定为功能级，并匹配全部审核三要素。";
        }
        if (!hasDifferentFunctionReference(rows, decision, row)) {
            return "功能级利旧必须引用另一个已存在的功能过程，不能用本功能内部行互相证明。";
        }
        List<Integer> group = processRows.get(FunctionProcessKey.of(row));
        for (Integer oneBased : group == null ? Collections.<Integer>emptyList() : group) {
            ReuseDegreeAdjustmentDecision peer = unique.get(oneBased);
            if (peer == null || !"利旧".equals(normalizeReuse(peer.getSuggestedReuseDegree()))
                    || !peer.isEvidenceSufficient()
                    || peer.getConfidence() < LEGACY_CONFIDENCE
                    || !"FUNCTION".equals(normalizeScope(peer.getDuplicateScope()))
                    || matchedAuditDimensions(peer.getMatchedDimensions()) < 3
                    || !hasValidReference(rows, peer)
                    || !hasDifferentFunctionReference(rows, peer, rows.get(oneBased - 1))) {
                return "功能级利旧必须覆盖该功能过程的全部子过程行，当前证据不完整。";
            }
        }
        return "";
    }

    private String validateRulePolicyDecision(List<FunctionSplitRow> rows,
                                              Map<String, List<Integer>> processRows,
                                              ReuseDegreeAdjustmentDecision decision,
                                              FunctionSplitRow row) {
        if (!XINJIANG_TIMER_ENTRY_RULE.equalsIgnoreCase(text(decision.getRuleId()))) {
            return "未知的统一规则政策决策。";
        }
        if (!"复用".equals(decision.getSuggestedReuseDegree())) {
            return "XJ-TIMER-E 只能将有效定时初始 E 调整为复用。";
        }
        if (!"MOVEMENT".equals(normalizeScope(decision.getRowScope()))) {
            return "XJ-TIMER-E 只能作用于单个数据移动行。";
        }
        if (decision.getConfidence() < REUSE_CONFIDENCE) {
            return "有效定时初始 E 的规则调整必须达到置信度 0.90。";
        }
        if (!"E".equals(text(row.getMovementType()).toUpperCase(Locale.ROOT))) {
            return "XJ-TIMER-E 的目标行必须是 E。";
        }
        List<Integer> group = processRows.get(FunctionProcessKey.of(row));
        if (group == null || group.isEmpty() || group.get(0) != decision.getRowIndex()) {
            return "XJ-TIMER-E 只能作用于功能过程首行的初始触发 E。";
        }
        if (!hasScheduledTriggerEvidence(row)) {
            return "当前行缺少定时、时钟、调度或系统事件触发证据。";
        }
        if (looksLikeTechnicalTimer(row)) {
            return "当前描述更接近技术定时器、轮询或保活，不得机械保留 0.33 E。";
        }
        if (!hasIndependentBusinessOutcome(rows, group)) {
            return "当前定时处理未形成有依据的持久化或跨边界业务结果。";
        }
        if (!hasCurrentEvidenceReference(row, decision)) {
            return "XJ-TIMER-E 必须引用当前需求 REQ 证据；Excel 导入行可引用 ROW-行号。";
        }
        return "";
    }

    private boolean isRulePolicyDecision(ReuseDegreeAdjustmentDecision decision) {
        return decision != null && RULE_POLICY.equals(normalizeDecisionType(decision.getDecisionType()));
    }

    private String normalizeDecisionType(String value) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        return StringUtils.hasText(normalized) ? normalized : CURRENT_TABLE_REUSE;
    }

    private boolean hasScheduledTriggerEvidence(FunctionSplitRow row) {
        String value = text(row.getFunctionalUser()) + " " + text(row.getTriggerEvent()) + " "
                + text(row.getFunctionProcess()) + " " + text(row.getSubProcessDescription()) + " "
                + text(row.getEvidence());
        return containsAny(value, "定时", "时钟", "调度", "系统事件");
    }

    private boolean looksLikeTechnicalTimer(FunctionSplitRow row) {
        String value = text(row.getFunctionProcess()) + " " + text(row.getFunctionDescription()) + " "
                + text(row.getSubProcessDescription()) + " " + text(row.getEvidence());
        return containsAny(value, "心跳", "保活", "框架调度", "定时器配置", "调度器配置", "内部轮询");
    }

    private boolean hasIndependentBusinessOutcome(List<FunctionSplitRow> rows, List<Integer> group) {
        for (Integer rowIndex : group == null ? Collections.<Integer>emptyList() : group) {
            if (rowIndex == null || rowIndex < 1 || rowIndex > rows.size()) {
                continue;
            }
            String movement = text(rows.get(rowIndex - 1).getMovementType()).toUpperCase(Locale.ROOT);
            if ("W".equals(movement) || "X".equals(movement)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCurrentEvidenceReference(FunctionSplitRow row,
                                                ReuseDegreeAdjustmentDecision decision) {
        List<String> references = decision.getEvidenceReferences() == null
                ? Collections.<String>emptyList() : decision.getEvidenceReferences();
        List<String> sourceReferences = row.getSourceReferences() == null
                ? Collections.<String>emptyList() : row.getSourceReferences();
        for (String reference : references) {
            String value = text(reference);
            if (sourceReferences.contains(value)
                    || ("ROW-" + decision.getRowIndex()).equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasValidReference(List<FunctionSplitRow> rows,
                                      ReuseDegreeAdjustmentDecision decision) {
        for (Integer rowIndex : safeIntegers(decision.getReferenceRowIndexes())) {
            if (rowIndex != null && rowIndex >= 1 && rowIndex <= rows.size()
                    && rowIndex != decision.getRowIndex()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDifferentFunctionReference(List<FunctionSplitRow> rows,
                                                  ReuseDegreeAdjustmentDecision decision,
                                                  FunctionSplitRow current) {
        String currentKey = FunctionProcessKey.of(current);
        for (Integer rowIndex : safeIntegers(decision.getReferenceRowIndexes())) {
            if (rowIndex != null && rowIndex >= 1 && rowIndex <= rows.size()
                    && rowIndex != decision.getRowIndex()
                    && !currentKey.equals(FunctionProcessKey.of(rows.get(rowIndex - 1)))) {
                return true;
            }
        }
        return false;
    }

    private int matchedAuditDimensions(List<String> dimensions) {
        Set<String> matched = new LinkedHashSet<String>();
        for (String dimension : dimensions == null ? Collections.<String>emptyList() : dimensions) {
            String value = dimension == null ? "" : dimension.replaceAll("[^A-Za-z]", "")
                    .toLowerCase(Locale.ROOT);
            if (AUDIT_DIMENSIONS.contains(value)) {
                matched.add(value);
            }
        }
        return matched.size();
    }

    private Map<String, List<Integer>> processRows(List<FunctionSplitRow> rows) {
        Map<String, List<Integer>> result = new HashMap<String, List<Integer>>();
        for (int i = 0; i < rows.size(); i++) {
            String key = FunctionProcessKey.of(rows.get(i));
            List<Integer> indexes = result.get(key);
            if (indexes == null) {
                indexes = new ArrayList<Integer>();
                result.put(key, indexes);
            }
            indexes.add(i + 1);
        }
        return result;
    }

    private String rowsJson(List<FunctionSplitRow> rows) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rows.size(); i++) {
            FunctionSplitRow row = rows.get(i);
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("rowIndex", i + 1);
            value.put("level1Module", text(row.getLevel1Module()));
            value.put("level2Module", text(row.getLevel2Module()));
            value.put("level3Module", text(row.getLevel3Module()));
            value.put("functionalUser", text(row.getFunctionalUser()));
            value.put("triggerEvent", text(row.getTriggerEvent()));
            value.put("functionProcess", text(row.getFunctionProcess()));
            value.put("functionDescription", text(row.getFunctionDescription()));
            value.put("subProcessDescription", text(row.getSubProcessDescription()));
            value.put("movementType", text(row.getMovementType()));
            value.put("dataGroup", text(row.getDataGroup()));
            value.put("dataAttributes", text(row.getDataAttributes()));
            value.put("sequenceDiagram", text(row.getSequenceDiagram()));
            value.put("reuseDegree", normalizeReuse(row.getReuseDegree()));
            value.put("cfp", cfp(normalizeReuse(row.getReuseDegree())));
            value.put("evidence", text(row.getEvidence()));
            value.put("sourceReferences", row.getSourceReferences() == null
                    ? Collections.emptyList() : row.getSourceReferences());
            values.add(value);
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new BusinessException("生成复用度全表判断输入失败。", ex);
        }
    }

    private List<FunctionSplitRow> copyRows(List<FunctionSplitRow> rows) {
        return objectMapper.convertValue(rows, new TypeReference<List<FunctionSplitRow>>() { });
    }

    private String currentModel() {
        AiModelProfile profile = aiModelConfigService.getActiveProfile();
        return profile == null ? "" : text(profile.getModel());
    }

    private String stripJson(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                text = text.substring(firstLine + 1, lastFence).trim();
            }
        }
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start = objectStart < 0 ? arrayStart : (arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart));
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        if (start < 0 || end < start) {
            throw new BusinessException("复用度 AI 调整没有返回 JSON 对象。");
        }
        return text.substring(start, end + 1);
    }

    private String normalizeReuse(String value) {
        String text = text(value);
        return REUSE_VALUES.contains(text) ? text : "新增";
    }

    private String normalizeScope(String value) {
        return text(value).toUpperCase(Locale.ROOT);
    }

    private double cfp(String reuse) {
        return "复用".equals(reuse) ? 0.33D : ("利旧".equals(reuse) ? 0D : 1D);
    }

    private double totalCfp(List<FunctionSplitRow> rows) {
        double total = 0D;
        for (FunctionSplitRow row : rows) {
            total += cfp(normalizeReuse(row.getReuseDegree()));
        }
        return Math.round(total * 100D) / 100D;
    }

    private void reject(ReuseDegreeAdjustmentDecision decision, String message) {
        decision.setApplied(false);
        decision.setApplyMessage(message);
    }

    private String formatCfp(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001D) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private List<Integer> safeIntegers(List<Integer> values) {
        return values == null ? Collections.<Integer>emptyList() : values;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private enum ModuleAction {
        CREATE,
        DELETE,
        MODIFY,
        QUERY,
        IMPORT,
        EXPORT
    }

    private static final class ProcessGroup {
        private final String moduleLabel;
        private final String process;
        private final ModuleAction action;
        private final List<Integer> rowIndexes = new ArrayList<Integer>();

        private ProcessGroup(String moduleLabel, String process, ModuleAction action) {
            this.moduleLabel = moduleLabel;
            this.process = process;
            this.action = action;
        }
    }

    private static final class ModuleReuseTarget {
        private final String reuseDegree;
        private final String moduleLabel;
        private final String process;
        private final List<Integer> referenceRowIndexes;
        private final String ruleReason;
        private final String scope;
        private final List<String> matchedDimensions;

        private ModuleReuseTarget(String reuseDegree, String moduleLabel, String process,
                                  List<Integer> referenceRowIndexes, String ruleReason) {
            this(reuseDegree, moduleLabel, process, referenceRowIndexes, ruleReason,
                    MODULE_ANCHOR_DEFAULT_SCOPE, Collections.<String>emptyList());
        }

        private ModuleReuseTarget(String reuseDegree, String moduleLabel, String process,
                                  List<Integer> referenceRowIndexes, String ruleReason,
                                  String scope, List<String> matchedDimensions) {
            this.reuseDegree = reuseDegree;
            this.moduleLabel = moduleLabel;
            this.process = process;
            this.referenceRowIndexes = referenceRowIndexes;
            this.ruleReason = ruleReason;
            this.scope = scope;
            this.matchedDimensions = matchedDimensions;
        }
    }

    private static final class IndexedRow {
        private final int rowIndex;
        private final String processKey;
        private final FunctionSplitRow row;

        private IndexedRow(int rowIndex, String processKey, FunctionSplitRow row) {
            this.rowIndex = rowIndex;
            this.processKey = processKey;
            this.row = row;
        }
    }

    private static final class ModuleReusePlan {
        private final Map<Integer, ModuleReuseTarget> targets =
                new LinkedHashMap<Integer, ModuleReuseTarget>();
    }

    static final class ApplySummary {
        private int applied;
        private int skipped;
        private final List<String> warnings = new ArrayList<String>();

        int getApplied() {
            return applied;
        }

        int getSkipped() {
            return skipped;
        }

        List<String> getWarnings() {
            return warnings;
        }
    }
}
