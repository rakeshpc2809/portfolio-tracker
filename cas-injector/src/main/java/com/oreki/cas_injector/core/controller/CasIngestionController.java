package com.oreki.cas_injector.core.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oreki.cas_injector.core.service.CasProcessingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/cas")
@Slf4j
@RequiredArgsConstructor
public class CasIngestionController {

    private final CasProcessingService casProcessingService;
    private final ObjectMapper objectMapper;

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingestParsedCas(@RequestBody String jsonPayload) {
        log.info("📥 Received CAS parsed payload via HTTP");
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            casProcessingService.processJson(root);
            log.info("✅ Successfully processed CAS data for PAN: {}", root.path("pan").asText());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("🚨 Failed to process CAS payload", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
