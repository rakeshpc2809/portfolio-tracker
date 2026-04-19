package com.oreki.cas_injector.core.event;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oreki.cas_injector.core.service.CasProcessingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CasParsedEventConsumer {

    private final CasProcessingService casProcessingService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "cas.parsed.events", groupId = "cas-injector-group")
    public void listen(String message) {
        log.info("📥 Received CAS parsed event from Kafka");
        try {
            JsonNode root = objectMapper.readTree(message);
            casProcessingService.processJson(root);
            log.info("✅ Asynchronously processed CAS data for PAN: {}", root.path("pan").asText());
        } catch (Exception e) {
            log.error("🚨 Failed to process CAS event from Kafka", e);
        }
    }
}
