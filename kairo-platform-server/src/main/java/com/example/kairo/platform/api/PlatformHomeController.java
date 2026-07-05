package com.example.kairo.platform.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public final class PlatformHomeController {

    @GetMapping({"/", "/api/v1"})
    public Map<String, Object> home() {
        return Map.of(
                "status", "UP",
                "service", "kairo-platform-server",
                "health", "/api/v1/control/health",
                "actuatorHealth", "/actuator/health",
                "apiBase", "/api/v1"
        );
    }
}
