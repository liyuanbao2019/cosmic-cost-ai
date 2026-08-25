package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionDescriptionAiServiceTest {

    @Test
    void projectDescriptionsRunRequirementsConcurrentlyAndReportProgress() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger finalCompletedRequirements = new AtomicInteger();
        AtomicInteger finalCompletedGroups = new AtomicInteger();
        FunctionDescriptionAiService service = new FunctionDescriptionAiService(
                "http://model.test", "test-key", "test-model", 30, 0, 2) {
            @Override
            protected String callAi(String prompt) {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(2, TimeUnit.SECONDS),
                            "两个单需求应同时进入大模型调用");
                    return "{\"G001\":\"大模型生成的完整功能描述\"}";
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                } finally {
                    active.decrementAndGet();
                }
            }
        };

        Map<String, Map<String, List<FunctionSplitRow>>> requirements =
                new LinkedHashMap<String, Map<String, List<FunctionSplitRow>>>();
        requirements.put("需求甲", groups(row("需求甲", "客户查询")));
        requirements.put("需求乙", groups(row("需求乙", "订单查询")));

        Map<String, Map<String, String>> descriptions =
                service.generateDescriptionsByRequirementConcurrently(requirements,
                        (completedRequirements, totalRequirements, completedGroups, totalGroups, activeRequirements) -> {
                            finalCompletedRequirements.set(completedRequirements);
                            finalCompletedGroups.set(completedGroups);
                        });

        assertEquals(2, descriptions.size());
        assertEquals(2, finalCompletedRequirements.get());
        assertEquals(2, finalCompletedGroups.get());
        assertTrue(maxActive.get() >= 2, "应按单个需求并发调用大模型");
    }

    @Test
    void largeRequirementRunsItsModelBatchesConcurrentlyAndReportsPartialGroups() {
        CountDownLatch twoBatchesStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicBoolean sawPartialGroupProgress = new AtomicBoolean();
        FunctionDescriptionAiService service = new FunctionDescriptionAiService(
                "http://model.test", "test-key", "test-model", 30, 0, 2) {
            @Override
            protected String callAi(String prompt) {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                twoBatchesStarted.countDown();
                try {
                    assertTrue(twoBatchesStarted.await(2, TimeUnit.SECONDS),
                            "同一大需求拆出的模型分批应并发执行");
                    Matcher matcher = Pattern.compile("- key: (G\\d+)").matcher(prompt);
                    StringBuilder json = new StringBuilder("{");
                    while (matcher.find()) {
                        if (json.length() > 1) json.append(',');
                        json.append('\"').append(matcher.group(1)).append("\":\"大模型描述\"");
                    }
                    return json.append('}').toString();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        Map<String, List<FunctionSplitRow>> largeRequirement =
                new LinkedHashMap<String, List<FunctionSplitRow>>();
        for (int i = 1; i <= 25; i++) {
            FunctionSplitRow row = row("大需求", "功能过程" + i);
            largeRequirement.put(FunctionProcessKey.of(row), Arrays.asList(row));
        }
        Map<String, Map<String, List<FunctionSplitRow>>> requirements =
                new LinkedHashMap<String, Map<String, List<FunctionSplitRow>>>();
        requirements.put("大需求", largeRequirement);

        Map<String, Map<String, String>> descriptions =
                service.generateDescriptionsByRequirementConcurrently(requirements,
                        (completedRequirements, totalRequirements, completedGroups, totalGroups, activeRequirements) -> {
                            if (completedGroups > 0 && completedGroups < totalGroups) {
                                sawPartialGroupProgress.set(true);
                            }
                        });

        assertEquals(25, descriptions.get("大需求").size());
        assertTrue(maxActive.get() >= 2, "大需求内部应继续使用全局模型并发额度");
        assertTrue(sawPartialGroupProgress.get(), "每个模型分批完成后都应推进功能过程进度");
    }

    private Map<String, List<FunctionSplitRow>> groups(FunctionSplitRow row) {
        Map<String, List<FunctionSplitRow>> groups = new LinkedHashMap<String, List<FunctionSplitRow>>();
        groups.put(FunctionProcessKey.of(row), Arrays.asList(row));
        return groups;
    }

    private FunctionSplitRow row(String requirement, String process) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setLevel1Module(requirement);
        row.setLevel2Module("业务模块");
        row.setFunctionProcess(process);
        row.setSubProcessDescription("发起" + process);
        row.setMovementType("E");
        row.setDataGroup("查询条件");
        row.setDataAttributes("客户编号");
        return row;
    }
}
