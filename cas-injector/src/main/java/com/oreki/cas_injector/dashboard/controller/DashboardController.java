package com.oreki.cas_injector.dashboard.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;

import com.oreki.cas_injector.core.repository.FolioRepository;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.dashboard.dto.PortfolioPerformanceDTO;
import com.oreki.cas_injector.dashboard.service.DashboardService;
import com.oreki.cas_injector.dashboard.service.PortfolioFullService;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/dashboard")
@CrossOrigin(origins = "*")
@Slf4j
public class DashboardController {

    @Autowired private DashboardService dashboardService;
    @Autowired private PortfolioFullService fullService;
    @Autowired private InvestorRepository investorRepo;
    @Autowired private FolioRepository folioRepo;
    @Autowired private SchemeRepository schemeRepo;
    @Autowired private TransactionRepository txnRepo;
    @Autowired private TaxLotRepository taxLotRepo;
    @Autowired private CapitalGainAuditRepository auditRepo;
    @Autowired private CacheManager cacheManager;

    @GetMapping("/summary/{pan}")
    public ResponseEntity<DashboardSummaryDTO> getSummary(@PathVariable String pan) {
        String cleanPan = pan.trim().toUpperCase();
        log.info("📊 Fetching summary for PAN: {}", cleanPan);
        return ResponseEntity.ok(dashboardService.getInvestorSummary(cleanPan));
    }

    @GetMapping("/full/{pan}")
    public ResponseEntity<DashboardSummaryDTO> getFullPortfolio(
        @PathVariable String pan,
        @RequestParam(defaultValue = "75000") double sip,
        @RequestParam(defaultValue = "0") double lumpsum
    ) {
        String cleanPan = pan.trim().toUpperCase();
        log.info("📊 Fetching full portfolio for PAN: {}", cleanPan);
        return ResponseEntity.ok(fullService.getFullPortfolioWithTactical(cleanPan, sip, lumpsum));
    }

    @GetMapping("/performance/{pan}")
    public ResponseEntity<PortfolioPerformanceDTO> getPerformance(@PathVariable String pan) {
        String cleanPan = pan.trim().toUpperCase();
        log.info("📊 Fetching performance history for PAN: {}", cleanPan);
        return ResponseEntity.ok(fullService.getPerformanceHistory(cleanPan));
    }

    @GetMapping("/correlation/{pan}")
    public ResponseEntity<Map<String, Object>> getCorrelation(@PathVariable String pan) {
        String cleanPan = pan.trim().toUpperCase();
        log.info("🔗 Fetching HRP correlation matrix for PAN: {}", cleanPan);
        return ResponseEntity.ok(fullService.getCorrelationMatrix(cleanPan));
    }

    @DeleteMapping("/reset")
    public ResponseEntity<String> resetAllData() {
        log.warn("Wiping all financial data for a fresh start...");
        
        // Order matters due to Foreign Key constraints
        auditRepo.deleteAll();
        taxLotRepo.deleteAll();
        txnRepo.deleteAll();
        schemeRepo.deleteAll();
        folioRepo.deleteAll();
        investorRepo.deleteAll();

        // Evict caches
        Cache pCache = cacheManager.getCache("portfolioCache");
        if (pCache != null) pCache.clear();
        Cache dCache = cacheManager.getCache("dashboardSummaryV3");
        if (dCache != null) dCache.clear();
        
        return ResponseEntity.ok("Database and caches cleared. Ready for fresh injection.");
    }
}
