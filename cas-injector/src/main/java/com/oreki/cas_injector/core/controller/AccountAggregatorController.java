package com.oreki.cas_injector.core.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/aa")
@RequiredArgsConstructor
@Slf4j
public class AccountAggregatorController {

    @Value("${fiu.id}")
    private String fiuId;

    @Value("${fiu.sandbox.mode}")
    private boolean sandboxMode;

    @PostMapping("/consent/initiate")
    public Map<String, String> initiateConsent(@RequestBody Map<String, String> request) {
        String customerVpa = request.get("vpa");
        log.info("📡 [AA] Initiating consent for VPA: {}", customerVpa);

        // Mocking the AA Consent Flow
        String consentId = UUID.randomUUID().toString();
        String redirectUrl = "https://sandbox.setu.co/aa/consent/" + consentId;

        return Map.of(
            "consentId", consentId,
            "redirectUrl", redirectUrl,
            "status", "PENDING"
        );
    }

    @GetMapping("/consent/status/{id}")
    public Map<String, String> checkStatus(@PathVariable String id) {
        return Map.of(
            "consentId", id,
            "status", "ACTIVE",
            "message", "Consent successfully granted via Account Aggregator"
        );
    }

    @PostMapping("/fetch-data")
    public Map<String, String> fetchData(@RequestBody Map<String, String> request) {
        String consentId = request.get("consentId");
        log.info("🔄 [AA] Fetching live financial data for consent: {}", consentId);
        
        return Map.of(
            "jobId", UUID.randomUUID().toString(),
            "status", "DATA_READY"
        );
    }
}
