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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekChatClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<JsonNode>();
    private final AtomicReference<String> requestPath = new AtomicReference<String>();
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
    void extractionUsesJsonModeAndMaximumThinking() {
        String content = client().generate(profile(), "test-key", AiPromptTask.EXTRACTION,
                "output json", 10, 0);

        assertEquals("{\"functions\":[]}", content);
        assertEquals("/v1/chat/completions", requestPath.get());
        JsonNode body = requestBody.get();
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("max", body.path("reasoning_effort").asText());
        assertFalse(body.has("temperature"));
        assertEquals("system", body.path("messages").path(0).path("role").asText());
        assertTrue(body.path("messages").path(0).path("content").asText().contains("唯一业务判定依据"));
        assertEquals("user", body.path("messages").path(1).path("role").asText());
    }

    @Test
    void deepReviewEnablesMaximumEffortThinkingWithoutTemperature() {
        client().generate(profile(), "test-key", AiPromptTask.DEEP_REVIEW,
                "output json", 10, 0);

        JsonNode body = requestBody.get();
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("max", body.path("reasoning_effort").asText());
        assertFalse(body.has("temperature"));
    }

    @Test
    void functionDescriptionAlsoUsesMaximumThinking() {
        client().generate(profile(), "test-key", AiPromptTask.FUNCTION_DESCRIPTION,
                "output json", 10, 0);

        JsonNode body = requestBody.get();
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("max", body.path("reasoning_effort").asText());
        assertTrue(body.path("messages").path(0).path("content").asText()
                .contains("不改变 E/R/W/X、复用度或 CFP"));
    }

    @Test
    void reuseAdjustmentUsesMaximumThinkingAndDedicatedSystemRole() {
        client().generate(profile(), "test-key", AiPromptTask.REUSE_ADJUSTMENT,
                "output json", 10, 0);

        JsonNode body = requestBody.get();
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("max", body.path("reasoning_effort").asText());
        assertTrue(body.path("messages").path(0).path("content").asText()
                .contains("复用度全表调整"));
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
            String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"functions\\\":[]}\"}}]}";
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
