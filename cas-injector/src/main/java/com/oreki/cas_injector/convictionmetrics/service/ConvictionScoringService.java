package com.oreki.cas_injector.convictionmetrics.service;


import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.core.GoogleSheetService;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.taxmanagement.dto.TaxSimulationResult;
import com.oreki.cas_injector.taxmanagement.service.TaxSimulatorService;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConvictionScoringService {

    private final ConvictionMetricsRepository convictionMetricsRepository;
    private final TaxLotRepository taxLotRepository;
    private final NavService navService;
    private final TaxSimulatorService taxSimulator;
    private final GoogleSheetService strategyService;
    private final PythonQuantClient pythonQuantClient;

    @Transactional
    public void calculateAndSaveFinalScores(String investorPan) {
        log.info("🚀 [1/3] Gathering Context for Python Conviction Scoring: {}", investorPan);

        Double slab = convictionMetricsRepository.getJdbcTemplate().queryForObject(
            "SELECT tax_slab FROM investor WHERE pan = ?", Double.class, investorPan);
        double slabRate = (slab != null) ? slab : 0.30;

        List<TaxLot> allLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        log.info("📦 Found {} total open lots for PAN: {}", allLots.size(), investorPan);
        
        Map<String, List<TaxLot>> lotsByAmfi = allLots.stream()
                .collect(Collectors.groupingBy(lot -> CommonUtils.SANITIZE_AMFI.apply(lot.getScheme().getAmfiCode())));

        List<StrategyTarget> strategy = strategyService.fetchLatestStrategy();
        Map<String, String> isinToStatus = strategy.stream()
            .collect(Collectors.toMap(s -> s.isin(), StrategyTarget::status, (a, b) -> a));

        List<Map<String, Object>> metrics = convictionMetricsRepository.findMetricsForInvestor(investorPan);
        log.info("📊 [2/3] Calling Python Scoring API for {} funds.", metrics.size());

        double maxCagrFound = metrics.stream()
            .mapToDouble(m -> {
                String amfi = CommonUtils.SANITIZE_AMFI.apply((String) m.get("amfi_code"));
                return Math.max(0, calculatePersonalCagr(lotsByAmfi.get(amfi)));
            }).max().orElse(35.0);
        
        if (maxCagrFound <= 5.0) maxCagrFound = 35.0;
        if (maxCagrFound > 150.0) maxCagrFound = 150.0;

        for (Map<String, Object> fund : metrics) {
            String amfi = CommonUtils.SANITIZE_AMFI.apply((String) fund.get("amfi_code"));
            List<TaxLot> fundLots = lotsByAmfi.get(amfi);
            if (fundLots == null || fundLots.isEmpty()) continue;

            String isin = fundLots.get(0).getScheme().getIsin();
            String philStatus = isinToStatus.getOrDefault(isin, "ACTIVE").toUpperCase();

            double personalCagr = calculatePersonalCagr(fundLots);
            
            var schemeDetails = navService.getLatestSchemeDetails(amfi);
            String category = (schemeDetails.getCategory() != null) ? schemeDetails.getCategory().toUpperCase() : "OTHER";
            TaxSimulationResult taxResult = taxSimulator.simulateHifoExit(fundLots, category, slabRate);
            double taxPctOfValue = (taxResult.sellAmount() > 0) ? (taxResult.estimatedTax() / taxResult.sellAmount()) * 100 : 0;

            PythonQuantClient.PythonScoringRequest req = new PythonQuantClient.PythonScoringRequest(
                amfi,
                personalCagr,
                maxCagrFound,
                taxPctOfValue,
                category,
                philStatus,
                safeDouble(fund.get("sortino_ratio")),
                safeDouble(fund.get("rolling_z_score_252")),
                safeDouble(fund.get("max_drawdown")),
                safeBoolean(fund.get("ou_valid")),
                safeDouble(fund.get("ou_half_life")),
                safeDouble(fund.get("hmm_bear_prob")),
                safeDouble(fund.get("expense_ratio")),
                safeDouble(fund.get("aum_cr")),
                safeDouble(fund.get("nav_percentile_1yr"))
            );

            PythonQuantClient.PythonScoringResponse response = pythonQuantClient.scoreFund(req);
            
            if (response != null) {
                convictionMetricsRepository.updateConvictionBreakdown(
                    (int) response.final_conviction_score(),
                    response.yield_score(),
                    response.risk_score(),
                    response.value_score(),
                    response.pain_recovery_score(),
                    response.regime_score(),
                    response.friction_score(),
                    response.expense_score(),
                    amfi
                );
            }
        }
        log.info("🏁 [3/3] Python Scoring API completed and saved.");
    }

    private double calculatePersonalCagr(List<TaxLot> lots) {
        if (lots == null || lots.isEmpty()) return 0.0;

        String amfiCode = lots.get(0).getScheme().getAmfiCode();
        double currentNav = navService.getLatestSchemeDetails(amfiCode).getNav().doubleValue();
        
        double totalCost = 0;
        double totalValue = 0;
        double weightedDays = 0;

        for (TaxLot lot : lots) {
            double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
            long days = Math.max(1, ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now()));
            
            // fallback to cost if NAV is 0
            double lotValue = lot.getRemainingUnits().doubleValue() * (currentNav > 0 ? currentNav : lot.getCostBasisPerUnit().doubleValue());
            
            totalCost += cost;
            totalValue += lotValue;
            weightedDays += (cost * days);
        }

        if (totalCost <= 0 || weightedDays <= 0) return 0.0;
        double avgDays = weightedDays / totalCost;
        double absoluteReturn = (totalValue / totalCost) - 1;
        return (Math.pow(1 + absoluteReturn, 365.0 / avgDays) - 1) * 100;
    }

    private double safeDouble(Object o) {
        if (o == null) return 0.0;
        return ((Number) o).doubleValue();
    }

    private boolean safeBoolean(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() > 0;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }
}
