package com.lineaibot.shared;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    public record HealthResponse(String status, String service, String version) {}

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    HealthResponse health() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
        return new HealthResponse("ok", "LINE AI Bot", "0.2.0");
    }
}
