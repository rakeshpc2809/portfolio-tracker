package com.oreki.cas_injector.core.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oreki.cas_injector.rebalancing.dto.PortfolioStateItemDTO;
import com.oreki.cas_injector.rebalancing.dto.TaxHeadroomDTO;
import com.oreki.cas_injector.rebalancing.service.PortfolioOrchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/v1/portfolio")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioOrchestrator portfolioOrchestrator;

    @GetMapping("/state")
    public ResponseEntity<List<PortfolioStateItemDTO>> getPortfolioState() {
        String pan = getAuthenticatedPan();
        log.info("📥 GET /api/v1/portfolio/state called for PAN: {}", pan);
        List<PortfolioStateItemDTO> state = portfolioOrchestrator.getPortfolioState(pan);
        return ResponseEntity.ok(state);
    }

    @GetMapping("/tax-headroom")
    public ResponseEntity<TaxHeadroomDTO> getTaxHeadroom() {
        String pan = getAuthenticatedPan();
        log.info("📥 GET /api/v1/portfolio/tax-headroom called for PAN: {}", pan);
        TaxHeadroomDTO headroom = portfolioOrchestrator.calculateTaxHeadroom(pan);
        return ResponseEntity.ok(headroom);
    }

    private String getAuthenticatedPan() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new org.springframework.security.access.AccessDeniedException("User not authenticated");
        }
        return auth.getName().trim().toUpperCase();
    }
}
