package com.eventledger.account.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight health endpoint at {@code GET /health} (in addition to Spring Actuator's
 * {@code /actuator/health}). Reports overall status plus database connectivity.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp = isDatabaseReachable();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "account-service");
        body.put("status", dbUp ? "UP" : "DOWN");
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("db", Map.of("status", dbUp ? "UP" : "DOWN"));
        body.put("components", components);
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
