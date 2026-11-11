package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.CosmicMovement;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class MovementDataAttributeNormalizer {

    static final String SCHEDULED_ENTRY_ATTRIBUTES = "调度时间、调度任务名称";

    private MovementDataAttributeNormalizer() {
    }

    static void normalize(List<CosmicMovement> movements) {
        if (movements == null || movements.isEmpty()) {
            return;
        }
        for (CosmicMovement movement : movements) {
            if (movement == null || !isPendingOrBlank(movement.getDataAttributes())) {
                continue;
            }
            if (isNoPayloadScheduledEntry(movement)) {
                // v2.8 V37：属性至少 2 个，定时触发 E 用调度信息本身作属性，不写“无业务数据”。
                movement.setDataAttributes(SCHEDULED_ENTRY_ATTRIBUTES);
            }
        }

        Map<String, String> knownAttributesByGroup = new LinkedHashMap<String, String>();
        Set<String> ambiguousGroups = new HashSet<String>();
        for (CosmicMovement movement : movements) {
            if (movement == null) {
                continue;
            }
            String group = safe(movement.getDataGroup());
            String attributes = safe(movement.getDataAttributes());
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
        for (CosmicMovement movement : movements) {
            if (movement == null || !isPendingOrBlank(movement.getDataAttributes())) {
                continue;
            }
            String group = safe(movement.getDataGroup());
            if (!StringUtils.hasText(group) || ambiguousGroups.contains(group)) {
                continue;
            }
            String knownAttributes = knownAttributesByGroup.get(group);
            if (StringUtils.hasText(knownAttributes)) {
                movement.setDataAttributes(knownAttributes);
            }
        }
    }

    private static boolean isNoPayloadScheduledEntry(CosmicMovement movement) {
        if (!"E".equals(safe(movement.getType()).toUpperCase(Locale.ROOT))) {
            return false;
        }
        String evidence = safe(movement.getDescription()) + " " + safe(movement.getDataGroup());
        return containsAny(evidence, "定时任务触发", "定时触发", "定时器触发", "时钟触发", "轮询触发", "定时触发信号");
    }

    private static boolean isPendingOrBlank(String attributes) {
        String value = safe(attributes);
        return !StringUtils.hasText(value) || "待确认".equals(value);
    }

    private static boolean isEvidenceBackedAttributes(String attributes) {
        return StringUtils.hasText(attributes)
                && !attributes.contains("待确认")
                && !"无业务数据".equals(attributes)
                && !SCHEDULED_ENTRY_ATTRIBUTES.equals(attributes)
                && !containsAny(attributes, "同上", "见上", "与上相同");
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
