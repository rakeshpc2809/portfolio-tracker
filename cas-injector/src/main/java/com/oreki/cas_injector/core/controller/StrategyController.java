package com.oreki.cas_injector.core.controller;

import com.oreki.cas_injector.core.service.StrategyService;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService strategyService;

    private final com.oreki.cas_injector.core.repository.StrategyTargetRepository repository;

    @GetMapping("/{pan}")
    public ResponseEntity<?> getTargets(@PathVariable String pan) {
        return ResponseEntity.ok(repository.findAll());
    }

    @PostMapping("/target")
    public ResponseEntity<Void> updateTarget(@RequestBody StrategyRequest request) {
        strategyService.updateTarget(
            request.getPan(),
            request.getAmfiCode(),
            request.getAllocation(),
            request.getStrategyType(),
            "UI"
        );
        return ResponseEntity.accepted().build();
    }



    @Data
    public static class StrategyRequest {
        private String pan;
        private String amfiCode;
        private BigDecimal allocation;
        private String strategyType;
    }
}
