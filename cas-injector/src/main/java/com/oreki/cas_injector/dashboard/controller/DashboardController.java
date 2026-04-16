package com.oreki.cas_injector.dashboard.controller;

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
import com.oreki.cas_injector.core.GoogleSheetService;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.dashboard.dto.PortfolioPerformanceDTO;
import com.oreki.cas_injector.dashboard.service.DashboardService;
import com.oreki.cas_injector.dashboard.service.PortfolioFullService;
import com.oreki.cas_injector.rebalancing.dto.RebalancingTrade;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.taxmanagement.service.LtcgExitSchedulerService;
import com.oreki.cas_injector.taxmanagement.service.LtcgExitSchedulerService.ExitScheduleItem;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import java.util.Map;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/dashboard")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final PortfolioFullService fullService;
    private final InvestorRepository investorRepo;
    private final FolioRepository folioRepo;
    private final SchemeRepository schemeRepo;
    private final TransactionRepository txnRepo;
    private final TaxLotRepository taxLotRepo;
    private final CapitalGainAuditRepository auditRepo;
    private final CacheManager cacheManager;
    private final LtcgExitSchedulerService ltcgExitScheduler;
    private final GoogleSheetService strategyService;
    private final JdbcTemplate jdbcTemplate;

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

    @GetMapping("/rebalancing-trades/{pan}")
    public ResponseEntity<List<RebalancingTrade>> getRebalancingTrades(@PathVariable String pan) {
        String cleanPan = pan.trim().toUpperCase();
        return ResponseEntity.ok(fullService.computeRebalancingTrades(cleanPan));
    }

    @GetMapping("/ltcg-exit-schedule/{pan}")
    public ResponseEntity<List<ExitScheduleItem>> getLtcgSchedule(@PathVariable String pan) {
        String cleanPan = pan.trim().toUpperCase();
        List<String> droppedIsins = strategyService.fetchLatestStrategy().stream()
            .filter(t -> "DROPPED".equalsIgnoreCase(t.status()) || "EXIT".equalsIgnoreCase(t.status()))
            .map(StrategyTarget::isin)
            .toList();

        Double fyLtcg = jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(cg.realized_gain), 0)
            FROM capital_gain_audit cg
            JOIN "transaction" t ON cg.sell_transaction_id = t.id
            JOIN scheme s ON t.scheme_id = s.id
            JOIN folio f ON s.folio_id = f.id
            WHERE f.investor_pan = ?
            AND cg.tax_category IN ('EQUITY_LTCG', 'HYBRID_LTCG', 'NON_EQUITY_LTCG_OLD')
            AND t.transaction_date >= ?
            """,
            Double.class, cleanPan, CommonUtils.getCurrentFyStart());
        if (fyLtcg == null) fyLtcg = 0.0;

        return ResponseEntity.ok(ltcgExitScheduler.computeOptimalExitSchedule(cleanPan, fyLtcg, droppedIsins));
    }

    @DeleteMapping("/reset")
    public ResponseEntity<String> resetAllData(@RequestParam(required = true) String confirmPhrase) {
        if (!"I-CONFIRM-DATA-WIPE".equals(confirmPhrase)) {
            log.warn("⚠️ Data wipe attempted without confirmation phrase.");
            return ResponseEntity.badRequest()
                .body("Safety phrase required: ?confirmPhrase=I-CONFIRM-DATA-WIPE");
        }
        
        log.warn("🚨 Wiping all financial data for a fresh start...");
        
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
