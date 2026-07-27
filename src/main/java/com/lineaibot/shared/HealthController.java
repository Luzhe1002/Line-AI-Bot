package com.lineaibot.shared;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    public record HealthResponse(String status, String service, String version) {}

    @GetMapping("/health")
    HealthResponse health() {
        return new HealthResponse("ok", "LINE AI Bot", "0.2.0");
    }
}
