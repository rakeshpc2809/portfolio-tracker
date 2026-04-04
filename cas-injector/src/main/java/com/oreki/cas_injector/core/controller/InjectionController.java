package com.oreki.cas_injector.core.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oreki.cas_injector.core.service.CasProcessingService;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/cas")
@Slf4j
public class InjectionController {

    @Autowired 
    private CasProcessingService casProcessingService;

    @PostMapping("/inject")
    public ResponseEntity<String> injectCasData(@RequestBody JsonNode root) {
        try {
            log.info("Received CAS JSON for injection. Processing...");
            casProcessingService.processJson(root);
            return ResponseEntity.ok("CAS Data injected successfully. Tax lots and Capital Gains calculated.");
        } catch (Exception e) {
            log.error("Error during CAS injection: ", e);
            return ResponseEntity.internalServerError().body("Injection failed: " + e.getMessage());
        }
    }
}