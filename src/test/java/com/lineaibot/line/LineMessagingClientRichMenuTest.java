package com.lineaibot.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.lineaibot.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class LineMessagingClientRichMenuTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> uploadContentLength = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createsUploadsLinksAndUnlinksPerUserRichMenus() {
        AppProperties properties = new AppProperties();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setLineApiBaseUrl(baseUrl);
        properties.setLineApiDataBaseUrl(baseUrl);
        LineMessagingClient client = new LineMessagingClient(
                mock(LineRepository.class),
                properties,
                new ObjectMapper(),
                RestClient.builder());

        assertThat(client.findRichMenuIdByName("channel-token", "staff-menu"))
                .isEmpty();
        String richMenuId = client.createRichMenu(
                "channel-token",
                Map.of(
                        "name", "staff-menu",
                        "size", Map.of("width", 2500, "height", 1686),
                        "selected", true,
                        "chatBarText", "店家管理",
                        "areas", List.of()));
        client.uploadRichMenuImage("channel-token", richMenuId, new byte[] {1, 2, 3});
        client.linkRichMenu("channel-token", "U-owner", richMenuId);
        client.unlinkRichMenu("channel-token", "U-owner");

        assertThat(richMenuId).isEqualTo("richmenu-test");
        assertThat(requests)
                .containsExactly(
                        "GET /v2/bot/richmenu/list",
                        "POST /v2/bot/richmenu",
                        "POST /v2/bot/richmenu/richmenu-test/content",
                        "POST /v2/bot/user/U-owner/richmenu/richmenu-test",
                        "DELETE /v2/bot/user/U-owner/richmenu");
        assertThat(uploadContentLength.get()).isEqualTo("3");
    }

    @Test
    void exposesTheSafeLineErrorBodyWhenRichMenuImageUploadFails() {
        AppProperties properties = new AppProperties();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setLineApiBaseUrl(baseUrl);
        properties.setLineApiDataBaseUrl(baseUrl);
        LineMessagingClient client = new LineMessagingClient(
                mock(LineRepository.class),
                properties,
                new ObjectMapper(),
                RestClient.builder());

        assertThatThrownBy(() -> client.uploadRichMenuImage(
                        "channel-token", "invalid-image", new byte[] {1, 2, 3}))
                .hasMessageContaining("LINE rich menu image upload returned HTTP 400")
                .hasMessageContaining("Invalid rich menu image");
    }

    @Test
    void treatsAnAlreadyUploadedRichMenuImageAsIdempotentSuccess() {
        AppProperties properties = new AppProperties();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setLineApiBaseUrl(baseUrl);
        properties.setLineApiDataBaseUrl(baseUrl);
        LineMessagingClient client = new LineMessagingClient(
                mock(LineRepository.class),
                properties,
                new ObjectMapper(),
                RestClient.builder());

        client.uploadRichMenuImage(
                "channel-token", "already-uploaded", new byte[] {1, 2, 3});
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        if (exchange.getRequestURI().getPath().contains("/invalid-image/content")) {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"message\":\"Invalid rich menu image\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }
        if (exchange.getRequestURI().getPath().contains("/already-uploaded/content")) {
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"message\":\"An image has already been uploaded "
                            + "to the richmenu\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/content")) {
            uploadContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
        }
        exchange.getRequestBody().readAllBytes();
        byte[] response = exchange.getRequestURI().getPath().equals("/v2/bot/richmenu/list")
                ? "{\"richmenus\":[]}".getBytes(StandardCharsets.UTF_8)
                : exchange.getRequestURI().getPath().equals("/v2/bot/richmenu")
                                && exchange.getRequestMethod().equals("POST")
                        ? "{\"richMenuId\":\"richmenu-test\"}"
                                .getBytes(StandardCharsets.UTF_8)
                        : new byte[0];
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
