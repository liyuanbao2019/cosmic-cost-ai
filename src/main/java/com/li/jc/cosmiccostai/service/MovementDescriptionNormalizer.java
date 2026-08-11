package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.springframework.util.StringUtils;

/** Makes mechanical entry descriptions read naturally without changing movement direction. */
final class MovementDescriptionNormalizer {

    private MovementDescriptionNormalizer() {
    }

    static void normalize(FunctionSplitRow row) {
        if (row == null || !"E".equalsIgnoreCase(text(row.getMovementType()))) {
            return;
        }
        row.setSubProcessDescription(normalizeEntryDescription(
                row.getSubProcessDescription(), row.getFunctionalUser(), row.getSequenceDiagram()));
    }

    static String normalizeEntryDescription(String description,
                                            String functionalUser,
                                            String sequenceDiagram) {
        String value = text(description);
        if (!value.startsWith("接收") || isExternalInbound(value, functionalUser, sequenceDiagram)) {
            return value;
        }
        String body = value.substring(2).trim();
        int requestAt = requestSuffixIndex(body);
        if (requestAt >= 0) {
            String action = stripHumanActor(body.substring(0, requestAt));
            String tail = body.substring(requestAt + 2).trim();
            if (!StringUtils.hasText(action)) {
                return value;
            }
            return "发起" + action + normalizeInputTail(tail);
        }
        if (endsWithAny(body, "查询条件", "检索条件", "筛选条件", "业务信息", "录入信息", "提交信息")) {
            return "提交" + stripHumanActor(body);
        }
        return value;
    }

    private static boolean isExternalInbound(String description,
                                             String functionalUser,
                                             String sequenceDiagram) {
        if (containsAny(description, "上报", "回调", "推送", "通知", "同步响应", "异步响应", "响应数据")) {
            return true;
        }
        String initiator = initiator(functionalUser);
        if (!StringUtils.hasText(initiator)) {
            initiator = sequenceSender(sequenceDiagram);
        }
        return containsAny(initiator, "系统", "平台", "服务", "接口", "网关", "设备", "引擎", "程序", "任务");
    }

    private static String initiator(String functionalUser) {
        String value = text(functionalUser);
        int marker = value.indexOf("发起者：");
        if (marker < 0) {
            return "";
        }
        String result = value.substring(marker + 4);
        int lineEnd = result.indexOf('\n');
        return (lineEnd >= 0 ? result.substring(0, lineEnd) : result).trim();
    }

    private static String sequenceSender(String sequenceDiagram) {
        String value = text(sequenceDiagram);
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon);
        }
        int arrow = value.indexOf("->");
        return arrow > 0 ? value.substring(0, arrow).trim() : "";
    }

    private static int requestSuffixIndex(String value) {
        int selected = -1;
        int cursor = value.indexOf("请求");
        while (cursor >= 0) {
            int after = cursor + 2;
            if (after == value.length() || isSeparator(value.charAt(after))) {
                selected = cursor;
                break;
            }
            cursor = value.indexOf("请求", cursor + 2);
        }
        return selected;
    }

    private static boolean isSeparator(char value) {
        return value == '，' || value == ',' || value == '；' || value == ';' || value == '。'
                || Character.isWhitespace(value);
    }

    private static String normalizeInputTail(String value) {
        String tail = text(value);
        while (StringUtils.hasText(tail) && isSeparator(tail.charAt(0))) {
            tail = tail.substring(1).trim();
        }
        if (!StringUtils.hasText(tail)) {
            return "";
        }
        if (tail.startsWith("包含")) {
            tail = tail.substring(2).trim();
        } else if (tail.startsWith("携带") || tail.startsWith("提供") || tail.startsWith("提交")) {
            tail = tail.substring(2).trim();
        }
        return "，提交" + tail;
    }

    private static String stripHumanActor(String value) {
        String result = text(value);
        for (String prefix : new String[]{"用户发起的", "用户提交的", "用户", "管理员发起的", "管理员提交的"}) {
            if (result.startsWith(prefix) && result.length() > prefix.length()) {
                result = result.substring(prefix.length()).trim();
                break;
            }
        }
        return result;
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... keywords) {
        String source = text(value);
        for (String keyword : keywords) {
            if (source.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
