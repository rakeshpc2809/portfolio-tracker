package com.oreki.cas_injector.core.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oreki.cas_injector.core.service.CasProcessingService;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.dashboard.service.PortfolioFullService;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class InjectionController {

    @Autowired
    private CasProcessingService casProcessingService;

    @Autowired
    private PortfolioFullService fullService;

    @PostMapping("/inject")
    public ResponseEntity<String> inject(@RequestBody JsonNode root) {
        casProcessingService.processJson(root);
        return ResponseEntity.ok("Successfully processed portfolio data");
    }

    @GetMapping("/dashboard/full/{pan}")
    public DashboardSummaryDTO getFullDashboard(
        @PathVariable String pan,
        @RequestParam(defaultValue = "75000") double sip,
        @RequestParam(defaultValue = "0") double lumpsum
    ) {
        return fullService.getFullPortfolio(pan, sip, lumpsum);
    }
}
