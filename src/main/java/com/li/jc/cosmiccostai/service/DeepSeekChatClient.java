package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.common.BusinessException;
import com.li.jc.cosmiccostai.dto.AiModelProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** DeepSeek OpenAI-compatible transport with provider-specific structured-output controls. */
final class DeepSeekChatClient {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Set<HttpURLConnection>> activeConnections =
            new ConcurrentHashMap<String, Set<HttpURLConnection>>();
    private final Set<String> cancelledRequests = ConcurrentHashMap.newKeySet();

    DeepSeekChatClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String generate(AiModelProfile profile,
                    String apiKey,
                    AiPromptTask task,
                    String prompt,
                    long timeoutSeconds,
                    int maxRetries) {
        return generate(profile, apiKey, task, prompt, timeoutSeconds, maxRetries, null, null);
    }

    String generate(AiModelProfile profile,
                    String apiKey,
                    AiPromptTask task,
                    String prompt,
                    long timeoutSeconds,
                    int maxRetries,
                    String requestId,
                    AiRequestAttemptListener listener) {
        return generateWithinAttemptBudget(profile, apiKey, task, prompt, timeoutSeconds,
                maxRetries, requestId, listener);
    }

    void beginRequest(String requestId) {
        if (hasRequestId(requestId)) {
            cancelledRequests.remove(requestId);
        }
    }

    void endRequest(String requestId) {
        if (hasRequestId(requestId)) {
            activeConnections.remove(requestId);
            cancelledRequests.remove(requestId);
        }
    }

    boolean cancel(String requestId) {
        if (!hasRequestId(requestId)) return false;
        cancelledRequests.add(requestId);
        Set<HttpURLConnection> connections = activeConnections.get(requestId);
        if (connections != null) {
            for (HttpURLConnection connection : connections) {
                connection.disconnect();
            }
        }
        return connections != null && !connections.isEmpty();
    }

    private String generateWithinAttemptBudget(AiModelProfile profile,
                                               String apiKey,
                                               AiPromptTask task,
                                               String prompt,
                                               long timeoutSeconds,
                                               int maxRetries,
                                               String requestId,
                                               AiRequestAttemptListener listener) {
        RuntimeException last = null;
        // Extraction prompts can be very large. Never submit the same extraction twice:
        // a slow first response may still complete successfully and a retry would double
        // input-token usage. Other, smaller task types keep their configured retry budget.
        int retryBudget = task == AiPromptTask.EXTRACTION ? 0 : Math.max(0, maxRetries);
        int attempts = retryBudget + 1;
        int maxTokens = outputTokenLimit(task);
        boolean outputExpanded = false;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            throwIfCancelled(requestId);
            if (listener != null) {
                listener.onAttempt(attempt, attempts, timeoutSeconds);
            }
            try {
                return execute(profile, apiKey, task, prompt, timeoutSeconds, maxTokens, requestId);
            } catch (OutputTruncatedException ex) {
                last = ex;
                int expandedMaxTokens = Math.min(131072, maxTokens * 2);
                if (outputExpanded || expandedMaxTokens <= maxTokens || attempt >= attempts) {
                    throw ex;
                }
                outputExpanded = true;
                maxTokens = expandedMaxTokens;
            } catch (RuntimeException ex) {
                throwIfCancelled(requestId);
                last = ex;
                if (retryBudget <= 0 || attempt >= attempts) {
                    throw ex;
                }
                retryBudget--;
                if (attempt < attempts) {
                    waitBeforeRetry(attempt);
                }
            }
        }
        throw last == null ? new BusinessException("DeepSeek 调用失败。") : last;
    }

    private String execute(AiModelProfile profile,
                           String apiKey,
                           AiPromptTask task,
                           String prompt,
                           long timeoutSeconds,
                           int maxTokens,
                           String requestId) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(chatCompletionsUrl(profile.getBaseUrl())).openConnection();
            throwIfCancelled(requestId);
            if (hasRequestId(requestId)) {
                connections(requestId).add(connection);
                if (cancelledRequests.contains(requestId)) {
                    connection.disconnect();
                    throwIfCancelled(requestId);
                }
            }
            int timeoutMillis = timeoutMillis(timeoutSeconds);
            connection.setConnectTimeout(timeoutMillis);
            // DeepSeek max-reasoning extraction can legitimately take longer than the
            // configured connection timeout. Keep waiting for that one response until it
            // completes or the user cancels it; do not turn slow inference into a retry.
            connection.setReadTimeout(task == AiPromptTask.EXTRACTION ? 0 : timeoutMillis);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");

            byte[] requestBytes = objectMapper.writeValueAsBytes(requestBody(profile, task, prompt, maxTokens));
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }

            int status = connection.getResponseCode();
            String responseText = readFully(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new BusinessException("DeepSeek API 调用失败（HTTP " + status + "）：" + compact(responseText));
            }
            JsonNode root = objectMapper.readTree(responseText);
            String finishReason = root.path("choices").path(0).path("finish_reason").asText("");
            if ("length".equalsIgnoreCase(finishReason)) {
                throw new OutputTruncatedException("DeepSeek 当前分段的结构化输出达到长度上限；为避免重复消耗 token，系统未自动重试或重发。其它成功分段会保留，请手动重试失败项。");
            }
            if ("content_filter".equalsIgnoreCase(finishReason)) {
                throw new BusinessException("DeepSeek 因内容过滤未能返回完整结构化结果，请检查输入内容后重试。");
            }
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().trim().isEmpty()) {
                throw new BusinessException("DeepSeek 返回了空的结构化结果，请重试。");
            }
            return content.asText();
        } catch (IOException ex) {
            throwIfCancelled(requestId);
            throw new BusinessException("DeepSeek API 调用或结果解析失败：" + ex.getMessage(), ex);
        } finally {
            if (hasRequestId(requestId) && connection != null) {
                Set<HttpURLConnection> connections = activeConnections.get(requestId);
                if (connections != null) {
                    connections.remove(connection);
                    if (connections.isEmpty()) {
                        activeConnections.remove(requestId, connections);
                    }
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Set<HttpURLConnection> connections(String requestId) {
        Set<HttpURLConnection> current = activeConnections.get(requestId);
        if (current != null) {
            return current;
        }
        Set<HttpURLConnection> created = ConcurrentHashMap.newKeySet();
        Set<HttpURLConnection> raced = activeConnections.putIfAbsent(requestId, created);
        return raced == null ? created : raced;
    }

    private boolean hasRequestId(String requestId) {
        return requestId != null && !requestId.trim().isEmpty();
    }

    private void throwIfCancelled(String requestId) {
        if (hasRequestId(requestId) && cancelledRequests.contains(requestId)) {
            throw new AiRequestCancelledException("AI 识别已由用户取消，可重新拆分当前需求。");
        }
    }

    private Map<String, Object> requestBody(AiModelProfile profile,
                                            AiPromptTask task,
                                            String prompt,
                                            int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", profile.getModel());
        body.put("stream", false);
        body.put("response_format", singleton("type", "json_object"));
        body.put("max_tokens", maxTokens);

        boolean reasoningTask = task == AiPromptTask.EXTRACTION
                || task == AiPromptTask.DEEP_REVIEW
                || task == AiPromptTask.REUSE_ADJUSTMENT
                || task == AiPromptTask.INITIAL_REVIEW;
        body.put("thinking", singleton("type", reasoningTask ? "enabled" : "disabled"));
        if (reasoningTask) {
            // Every reasoning stage can affect the final split. DeepSeek V4.1 Flash
            // supports max natively, so keep the whole split pipeline at its highest
            // reasoning level rather than weakening later review/reuse decisions.
            body.put("reasoning_effort", "max");
        }

        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(message("system", DeepSeekPromptProfile.systemPrompt(task)));
        messages.add(message("user", prompt));
        body.put("messages", messages);
        return body;
    }

    private int outputTokenLimit(AiPromptTask task) {
        if (task == AiPromptTask.EXTRACTION) {
            return 65536;
        }
        if (task == AiPromptTask.REUSE_ADJUSTMENT) {
            return 65536;
        }
        if (task == AiPromptTask.DEEP_REVIEW
                || task == AiPromptTask.INITIAL_REVIEW) {
            return 32768;
        }
        if (task == AiPromptTask.FUNCTION_DESCRIPTION) {
            return 8192;
        }
        return 16384;
    }

    private Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key, value);
        return result;
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private String chatCompletionsUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value + "/chat/completions";
    }

    private int timeoutMillis(long timeoutSeconds) {
        long millis = Math.max(5L, timeoutSeconds) * 1000L;
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private String readFully(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String compact(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    private void waitBeforeRetry(int completedAttempt) {
        try {
            Thread.sleep(Math.min(2000L, completedAttempt * 500L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待重试 DeepSeek 调用时任务被中断。", ex);
        }
    }

    private static final class OutputTruncatedException extends BusinessException {
        private OutputTruncatedException(String message) {
            super(message);
        }
    }
}
