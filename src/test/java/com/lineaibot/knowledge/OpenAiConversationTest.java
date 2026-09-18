package com.lineaibot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineaibot.config.AppProperties;
import com.lineaibot.line.ConversationContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiConversationTest {
    @Test
    void sendsHistoryAsDataAndParsesStructuredResponse() throws Exception {
        var mapper = new ObjectMapper();
        var captured = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            captured.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            String result = mapper.writeValueAsString(Map.of("intent", "KNOWLEDGE", "standalone_question", "深層清潔需要多久？",
                    "topic", "深層清潔", "needs_clarification", false, "clarification_question", ""));
            byte[] response = mapper.writeValueAsBytes(Map.of("status", "completed", "output", List.of(Map.of("type", "message",
                    "content", List.of(Map.of("type", "output_text", "text", result))))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var settings = new AppProperties();
            settings.getAi().setOpenaiApiKey("fake-test-key");
            settings.getAi().setOpenaiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var history = new ConversationContext.History(List.of(new ConversationContext.Turn("深層清潔多少錢？", "800元",
                    new ConversationContext.State("深層清潔", "深層清潔多少錢？", "", "reservation-secret", "token-secret"))));
            var result = new OpenAiProvider(settings, mapper).understandConversation("那要多久？", history, "hashed-user");
            assertThat(result.standaloneQuestion()).isEqualTo("深層清潔需要多久？");
            assertThat(captured.get().path("text").path("format").path("type").asText()).isEqualTo("json_schema");
            assertThat(captured.get().path("text").path("format").path("strict").asBoolean()).isTrue();
            assertThat(captured.get().path("store").asBoolean()).isFalse();
            assertThat(captured.get().path("input").asText()).contains("深層清潔", "那要多久").doesNotContain("token-secret", "reservation-secret");
            assertThat(captured.get().path("instructions").asText()).contains("不可信資料", "不能作為商家價格");
        } finally { server.stop(0); }
    }

    @Test
    void incompleteResponsesAreRejected() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            byte[] body = "{\"status\":\"incomplete\",\"output\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var settings = new AppProperties();
            settings.getAi().setOpenaiApiKey("fake-test-key");
            settings.getAi().setOpenaiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var provider = new OpenAiProvider(settings, new ObjectMapper());
            assertThatThrownBy(() -> provider.understandConversation("那個呢", ConversationContext.History.empty(), "hash"))
                    .isInstanceOf(IllegalStateException.class);
        } finally { server.stop(0); }
    }
}
