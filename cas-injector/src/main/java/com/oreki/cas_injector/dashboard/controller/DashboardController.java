package com.oreki.cas_injector.dashboard.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oreki.cas_injector.core.repository.FolioRepository;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.core.repository.SchemeRepository;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.dashboard.service.DashboardService;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/dashboard")
@Slf4j
@CrossOrigin(origins = "http://localhost:5173")
public class DashboardController {

    @Autowired private DashboardService dashboardService;
    
    // Repositories for the "Wipe" operation
    @Autowired private TransactionRepository txnRepo;
    @Autowired private TaxLotRepository taxLotRepo;
    @Autowired private CapitalGainAuditRepository auditRepo;
    @Autowired private SchemeRepository schemeRepo;
    @Autowired private FolioRepository folioRepo;
    @Autowired private InvestorRepository investorRepo;

    @GetMapping("/summary/{pan}")
    public ResponseEntity<DashboardSummaryDTO> getSummary(@PathVariable String pan) {
        return ResponseEntity.ok(dashboardService.getInvestorSummary(pan));
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
        
        return ResponseEntity.ok("Database cleared. Ready for fresh injection.");
    }
}