package com.oreki.cas_injector.core.controller;

import com.oreki.cas_injector.core.service.StrategyService;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService strategyService;

    private final com.oreki.cas_injector.core.repository.StrategyTargetRepository repository;

    @GetMapping("/{pan}")
    public ResponseEntity<?> getTargets(@PathVariable String pan) {
        validatePan(pan);
        return ResponseEntity.ok(repository.findByInvestorPan(pan));
    }

    @PostMapping("/target")
    public ResponseEntity<Void> updateTarget(@RequestBody StrategyRequest request) {
        validatePan(request.getPan());
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

    private void validatePan(String pan) {
        org.springframework.security.core.Authentication auth = 
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()) || !auth.getName().equalsIgnoreCase(pan)) {
            throw new org.springframework.security.access.AccessDeniedException("Unauthorized access to PAN: " + pan);
        }
    }
}
