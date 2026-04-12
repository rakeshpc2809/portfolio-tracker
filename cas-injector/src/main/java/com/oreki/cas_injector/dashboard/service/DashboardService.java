package com.oreki.cas_injector.dashboard.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import com.oreki.cas_injector.backfill.model.HistoricalNav;
import com.oreki.cas_injector.backfill.repository.HistoricalNavRepository;
import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.dto.SchemePerformanceDTO;
import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.transactions.model.Transaction;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.core.utils.FundStatus;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.transactions.dto.TransactionDTO;
import com.oreki.cas_injector.transactions.model.CapitalGainAudit;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final InvestorRepository investorRepo;
    private final TaxLotRepository taxLotRepo;
    private final CapitalGainAuditRepository auditRepo;
    private final TransactionRepository txnRepo;
    private final NavService navService;
    private final ConvictionMetricsRepository metricsRepo;
    private final HistoricalNavRepository historicalNavRepo;
    private final BenchmarkService benchmarkService;

    @Transactional(readOnly = true)
    public DashboardSummaryDTO getInvestorSummary(String rawPan) {
        String pan = rawPan.trim().toUpperCase();
        log.info("🔍 Generating Dashboard Summary for PAN: {}", pan);
        
        Optional<Investor> investorOpt = investorRepo.findByPanWithDetails(pan);
        if (investorOpt.isEmpty()) {
            log.warn("⚠️ Investor not found for PAN: {}", pan);
            return DashboardSummaryDTO.builder()
                .investorName("New Investor")
                .schemeBreakdown(Collections.emptyList())
                .totalInvestedAmount(BigDecimal.ZERO)
                .currentValueAmount(BigDecimal.ZERO)
                .overallReturn("0%")
                .overallXirr("0%")
                .build();
        }

        Investor investor = investorOpt.get();
        Set<Scheme> allSchemes = investor.getFolios().stream()
            .flatMap(f -> f.getSchemes().stream())
            .collect(Collectors.toSet());

        // Batch fetch heavy data to avoid N+1
        List<CapitalGainAudit> allInvestorAudits = auditRepo.findAllBySellTransactionSchemeFolioInvestorPan(pan);
        Map<Long, List<CapitalGainAudit>> auditMapBySchemeId = allInvestorAudits.stream()
            .collect(Collectors.groupingBy(a -> a.getSellTransaction().getScheme().getId()));

        List<TaxLot> allOpenLots = taxLotRepo.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        Map<Long, List<TaxLot>> openLotsBySchemeId = allOpenLots.stream()
            .collect(Collectors.groupingBy(l -> l.getScheme().getId()));

        List<String> amfiCodes = allSchemes.stream()
            .map(s -> sanitizeAmfi(s.getAmfiCode()))
            .distinct()
            .toList();

        List<LocalDate> allTxDates = allSchemes.stream()
            .flatMap(s -> s.getTransactions().stream())
            .map(Transaction::getDate)
            .distinct()
            .toList();

        Map<String, Map<LocalDate, BigDecimal>> navHistoryMap = historicalNavRepo.findByAmfiCodeInAndNavDateIn(amfiCodes, allTxDates).stream()
            .collect(Collectors.groupingBy(HistoricalNav::getAmfiCode, 
                Collectors.toMap(HistoricalNav::getNavDate, HistoricalNav::getNav, (a, b) -> a)));

        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);

        List<TransactionDTO> totalCashFlow = new ArrayList<>();

        List<SchemePerformanceDTO> breakdown = allSchemes.stream()
            .map(scheme -> {
                String code = sanitizeAmfi(scheme.getAmfiCode());
                List<TaxLot> openLots = openLotsBySchemeId.getOrDefault(scheme.getId(), Collections.emptyList());
                List<CapitalGainAudit> audits = auditMapBySchemeId.getOrDefault(scheme.getId(), Collections.emptyList());
                Map<LocalDate, BigDecimal> schemeNavHistory = navHistoryMap.getOrDefault(code, Collections.emptyMap());

                // Value aggregation
                BigDecimal unitsHeld = openLots.stream()
                    .map(TaxLot::getRemainingUnits)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal currentInvested = openLots.stream()
                    .map(lot -> lot.getRemainingUnits().multiply(lot.getCostBasisPerUnit()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                SchemeDetailsDTO schemeDetails = navService.getLatestSchemeDetails(scheme.getAmfiCode());
                BigDecimal currentNav = schemeDetails.getNav();
                
                BigDecimal currentValue = unitsHeld.multiply(currentNav).setScale(2, RoundingMode.HALF_UP);
                BigDecimal unrealizedGain = currentValue.subtract(currentInvested);

                BigDecimal realizedGain = audits.stream()
                    .map(CapitalGainAudit::getRealizedGain)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Tax calculations (Simplified for dashboard)
                String amfiCategory = (schemeDetails.getCategory() != null) ? schemeDetails.getCategory().toUpperCase() : "OTHER";
                double ltcgUnrealized = openLots.stream()
                    .mapToDouble(lot -> {
                        String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), amfiCategory);
                        if (taxCat.contains("LTCG")) {
                            double val = lot.getRemainingUnits().doubleValue() * currentNav.doubleValue();
                            double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
                            return Math.max(0, val - cost);
                        }
                        return 0.0;
                    }).sum();

                double stcgUnrealized = openLots.stream()
                    .mapToDouble(lot -> {
                        String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), amfiCategory);
                        if (!taxCat.contains("LTCG")) {
                            double val = lot.getRemainingUnits().doubleValue() * currentNav.doubleValue();
                            double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
                            return Math.max(0, val - cost);
                        }
                        return 0.0;
                    }).sum();

                // Cash flows for XIRR
                List<TransactionDTO> cashFlows = scheme.getTransactions().stream()
                    .map(t -> {
                        BigDecimal preciseAmount = Optional.ofNullable(schemeNavHistory.get(t.getDate()))
                            .map(nav -> nav.multiply(t.getUnits().abs()))
                            .orElse(t.getAmount());
                        return new TransactionDTO(preciseAmount.negate(), t.getDate());
                    })
                    .collect(Collectors.toList());
                                
                totalCashFlow.addAll(cashFlows);
                cashFlows.sort(Comparator.comparing(TransactionDTO::getDate));

                FundStatus status = (unitsHeld.compareTo(new BigDecimal("0.001")) > 0) ? FundStatus.ACTIVE : FundStatus.REDEEMED;
                if (status == FundStatus.ACTIVE) {
                    cashFlows.add(new TransactionDTO(currentValue, LocalDate.now()));
                }
              
                BigDecimal xirrValue = CommonUtils.SOLVE_XIRR.apply(cashFlows);
                String displayXirr = CommonUtils.SCALE_MONEY.apply(xirrValue).toString() + "%";

                String bucket = "OTHERS";
                if (amfiCategory.contains("ELSS") || amfiCategory.contains("TAX")) bucket = "TAX_SAVER_ELSS";
                else if (amfiCategory.contains("GOLD") || scheme.getName().toUpperCase().contains("GOLD")) bucket = "GOLD_HEDGE_24M";
                else if (amfiCategory.contains("EQUITY") || amfiCategory.contains("INDEX") || amfiCategory.contains("GROWTH")) bucket = "AGGRESSIVE_GROWTH";
                else if (amfiCategory.contains("DEBT") || amfiCategory.contains("LIQUID")) bucket = "DEBT_SLAB_TAXED";

                double benchXirr = benchmarkService.getBenchmarkReturn(bucket, amfiCategory, scheme.getBenchmarkIndex());
                MarketMetrics m = metricsMap.getOrDefault(code, MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, LocalDate.of(1970, 1, 1)));

                return SchemePerformanceDTO.builder()
                    .schemeName(scheme.getName())
                    .simpleName(CommonUtils.NORMALIZE_NAME.apply(scheme.getName()))
                    .isin(scheme.getIsin())
                    .amfiCode(scheme.getAmfiCode())
                    .totalInvested(scheme.getTransactions().stream().filter(t -> t.getUnits().compareTo(BigDecimal.ZERO) > 0).map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .currentInvested(currentInvested)
                    .currentValue(currentValue)
                    .realizedGain(realizedGain)
                    .unrealizedGain(unrealizedGain)
                    .ltcgUnrealizedGain(ltcgUnrealized)
                    .stcgUnrealizedGain(stcgUnrealized)
                    .transactionCount(scheme.getTransactions().size())
                    .xirr(displayXirr)
                    .benchmarkXirr(benchXirr)
                    .status(status)
                    .category(amfiCategory)
                    .bucket(bucket)
                    .amc(scheme.getFolio().getAmc())
                    .benchmarkIndex(scheme.getBenchmarkIndex())
                    .convictionScore(m.convictionScore())
                    .sortinoRatio(m.sortinoRatio())
                    .maxDrawdown(m.maxDrawdown())
                    .cvar5(m.cvar5())
                    .navPercentile3yr(m.navPercentile3yr())
                    .drawdownFromAth(m.drawdownFromAth())
                    .returnZScore(m.returnZScore())
                    .lastBuyDate(m.lastBuyDate())
                    .rollingZScore252(m.rollingZScore252())
                    .hurstExponent(m.hurstExponent())
                    .volatilityTax(m.volatilityTax())
                    .hurstRegime(m.hurstRegime())
                    .historicalRarityPct(m.historicalRarityPct())
                    .ouHalfLife(m.ouHalfLife())
                    .ouValid(m.ouValid())
                    .hmmState(m.hmmState())
                    .hmmBullProb(m.hmmBullProb())
                    .hmmBearProb(m.hmmBearProb())
                    .build();
            })
            .collect(Collectors.toList());

        BigDecimal aggregateCurrentInvested = breakdown.stream().map(SchemePerformanceDTO::getCurrentInvested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal aggregateCurrentValue = breakdown.stream().map(SchemePerformanceDTO::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal aggregateTotalInvested = breakdown.stream().map(SchemePerformanceDTO::getTotalInvested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal aggregateRealizedGain = breakdown.stream().map(SchemePerformanceDTO::getRealizedGain).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal aggregateUnrealizedGain = aggregateCurrentValue.subtract(aggregateCurrentInvested);

        // STCG for current FY
        LocalDate fyStart = CommonUtils.getCurrentFyStart();
        BigDecimal realizedStcg = allInvestorAudits.stream()
            .filter(a -> (a.getTaxCategory().contains("STCG") || a.getTaxCategory().contains("SLAB")) && !a.getSellTransaction().getDate().isBefore(fyStart))
            .map(CapitalGainAudit::getRealizedGain)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal portfolioPerformance = aggregateCurrentInvested.compareTo(BigDecimal.ZERO) > 0 
            ? aggregateUnrealizedGain.divide(aggregateCurrentInvested, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
            : BigDecimal.ZERO;

        totalCashFlow.add(new TransactionDTO(aggregateCurrentValue, LocalDate.now()));
        totalCashFlow.sort(Comparator.comparing(TransactionDTO::getDate));
        BigDecimal totalXirr = CommonUtils.SOLVE_XIRR.apply(totalCashFlow);

        return DashboardSummaryDTO.builder()
            .investorName(investor.getName())
            .totalFolios(investor.getFolios().size())
            .totalSchemes(breakdown.size())
            .totalTransactions(txnRepo.countActualTransactionsByPan(pan)) 
            .totalInvestedAmount(aggregateTotalInvested)
            .currentInvestedAmount(aggregateCurrentInvested)
            .currentValueAmount(aggregateCurrentValue)
            .totalRealizedGain(aggregateRealizedGain)
            .totalUnrealizedGain(aggregateUnrealizedGain)
            .openTaxLots(allOpenLots.size())
            .overallReturn(CommonUtils.SCALE_MONEY.apply(portfolioPerformance) + "%")
            .overallXirr(CommonUtils.SCALE_MONEY.apply(totalXirr) + "%")
            .totalSTCG(realizedStcg)
            .schemeBreakdown(breakdown)
            .build();
    }

    private String sanitizeAmfi(String code) {
        if (code == null) return "0";
        String s = code.trim();
        while (s.startsWith("0") && s.length() > 1) {
            s = s.substring(1);
        }
        return s;
    }
}
