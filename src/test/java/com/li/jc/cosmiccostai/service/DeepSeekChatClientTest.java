package com.li.jc.cosmiccostai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.li.jc.cosmiccostai.dto.AiModelProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekChatClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<JsonNode>();
    private final AtomicReference<String> requestPath = new AtomicReference<String>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicBoolean truncateFirstResponse = new AtomicBoolean();
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", new JsonHandler());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void extractionUsesJsonModeWithMaximumReasoningEffort() {
        String content = client().generate(profile(), "test-key", AiPromptTask.EXTRACTION,
                "output json", 10, 0);

        assertEquals("{\"functions\":[]}", content);
        assertEquals("/v1/chat/completions", requestPath.get());
        JsonNode body = requestBody.get();
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("max", body.path("reasoning_effort").asText());
        assertEquals(65536, body.path("max_tokens").asInt());
        assertFalse(body.has("temperature"));
        assertEquals("system", body.path("messages").path(0).path("role").asText());
        assertTrue(body.path("messages").path(0).path("content").asText().contains("唯一业务判定依据"));
        assertEquals("user", body.path("messages").path(1).path("role").asText());
    }

    @Test
    void deepReviewEnablesHighEffortThinkingWithoutTemperature() {
        client().generate(profile(), "test-key", AiPromptTask.DEEP_REVIEW,
                "output json", 10, 0);

        JsonNode body = requestBody.get();
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("high", body.path("reasoning_effort").asText());
        assertEquals(32768, body.path("max_tokens").asInt());
        assertFalse(body.has("temperature"));
    }

    @Test
    void functionDescriptionDoesNotSpendTimeOnLongChainThinking() {
        client().generate(profile(), "test-key", AiPromptTask.FUNCTION_DESCRIPTION,
                "output json", 10, 0);

        JsonNode body = requestBody.get();
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals(8192, body.path("max_tokens").asInt());
        assertFalse(body.has("reasoning_effort"));
        assertTrue(body.path("messages").path(0).path("content").asText()
                .contains("不改变 E/R/W/X、复用度或 CFP"));
    }

    @Test
    void reuseAdjustmentUsesHighThinkingAndDedicatedSystemRole() {
        client().generate(profile(), "test-key", AiPromptTask.REUSE_ADJUSTMENT,
                "output json", 10, 0);

        JsonNode body = requestBody.get();
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("high", body.path("reasoning_effort").asText());
        assertTrue(body.path("messages").path(0).path("content").asText()
                .contains("复用度全表调整"));
    }

    @Test
    void lengthTruncationAutomaticallyRetriesOnceWithLargerOutputBudget() {
        truncateFirstResponse.set(true);

        String content = client().generate(profile(), "test-key", AiPromptTask.EXTRACTION,
                "output json", 10, 0);

        assertEquals("{\"functions\":[]}", content);
        assertEquals(2, requestCount.get());
        assertEquals(131072, requestBody.get().path("max_tokens").asInt());
    }

    private DeepSeekChatClient client() {
        return new DeepSeekChatClient(objectMapper);
    }

    private AiModelProfile profile() {
        AiModelProfile profile = new AiModelProfile();
        profile.setProvider("deepseek");
        profile.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        profile.setModel("deepseek-v4-pro");
        return profile;
    }

    private class JsonHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(objectMapper.readTree(readFully(exchange.getRequestBody())));
            int currentRequest = requestCount.incrementAndGet();
            boolean truncated = truncateFirstResponse.get() && currentRequest == 1;
            String finishReason = truncated ? "length" : "stop";
            String content = truncated ? "{\\\"functions\\\":[" : "{\\\"functions\\\":[]}";
            String response = "{\"choices\":[{\"finish_reason\":\"" + finishReason
                    + "\",\"message\":{\"content\":\"" + content + "\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private byte[] readFully(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
