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
import com.oreki.cas_injector.dashboard.model.PortfolioSummary;
import com.oreki.cas_injector.dashboard.repository.PortfolioDashboardReadModelRepository;
import com.oreki.cas_injector.transactions.dto.TransactionDTO;
import com.oreki.cas_injector.transactions.model.CapitalGainAudit;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private final JdbcTemplate jdbcTemplate;
    private final PortfolioDashboardReadModelRepository summaryRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DashboardSummaryDTO getInvestorSummary(String rawPan) {
        String pan = rawPan.trim().toUpperCase();
        log.info("🚀 [CQRS] Fetching Dashboard Summary from Read Model for PAN: {}", pan);
        
        return summaryRepo.findById(pan)
            .map(summary -> {
                try {
                    return objectMapper.readValue(summary.getContent(), DashboardSummaryDTO.class);
                } catch (Exception e) {
                    log.error("🚨 Failed to deserialize dashboard summary for PAN: {}", pan, e);
                    return null;
                }
            })
            .orElseGet(() -> computeSummary(pan));
    }

    @Transactional(readOnly = true)
    public DashboardSummaryDTO computeSummary(String pan) {
        log.info("📊 [CQRS] Computing fresh Dashboard Summary (Write-Model query) for PAN: {}", pan);
        
        String sql = "SELECT * FROM mv_portfolio_summary WHERE investor_pan = ?";
        List<PortfolioSummary> summaryData;
        try {
            summaryData = jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(PortfolioSummary.class), pan);
        } catch (Exception e) {
            log.warn("⚠️ Materialized view query failed (likely first run): {}", e.getMessage());
            summaryData = Collections.emptyList();
        }

        if (summaryData.isEmpty()) {
            log.warn("⚠️ No data found in MV for PAN: {}", pan);
            return DashboardSummaryDTO.builder()
                .investorName("New Investor")
                .schemeBreakdown(Collections.emptyList())
                .totalInvestedAmount(BigDecimal.ZERO)
                .currentValueAmount(BigDecimal.ZERO)
                .overallReturn("0%")
                .overallXirr("0%")
                .build();
        }

        String investorName = summaryData.get(0).getInvestorName();
        Map<Long, List<PortfolioSummary>> txsBySchemeId = summaryData.stream()
            .collect(Collectors.groupingBy(PortfolioSummary::getSchemeId));

        // Batch fetch heavy data to avoid N+1
        List<CapitalGainAudit> allInvestorAudits = auditRepo.findAllBySellTransactionSchemeFolioInvestorPan(pan);
        Map<Long, List<CapitalGainAudit>> auditMapBySchemeId = allInvestorAudits.stream()
            .collect(Collectors.groupingBy(a -> a.getSellTransaction().getScheme().getId()));

        List<TaxLot> allOpenLots = taxLotRepo.findByStatusAndSchemeFolioInvestorPan("OPEN", pan);
        Map<Long, List<TaxLot>> openLotsBySchemeId = allOpenLots.stream()
            .collect(Collectors.groupingBy(l -> l.getScheme().getId()));

        List<String> amfiCodes = summaryData.stream()
            .map(s -> CommonUtils.SANITIZE_AMFI.apply(s.getAmfiCode()))
            .distinct()
            .toList();

        List<LocalDate> allTxDates = summaryData.stream()
            .map(PortfolioSummary::getTransactionDate)
            .distinct()
            .toList();

        Map<String, Map<LocalDate, BigDecimal>> navHistoryMap = historicalNavRepo.findByAmfiCodeInAndNavDateIn(amfiCodes, allTxDates).stream()
            .collect(Collectors.groupingBy(HistoricalNav::getAmfiCode, 
                Collectors.toMap(HistoricalNav::getNavDate, HistoricalNav::getNav, (a, b) -> a)));

        Map<String, MarketMetrics> metricsMap = metricsRepo.fetchLiveMetricsMap(pan);

        List<TransactionDTO> totalCashFlow = new ArrayList<>();

        List<SchemePerformanceDTO> breakdown = txsBySchemeId.entrySet().stream()
            .map(entry -> {
                Long schemeId = entry.getKey();
                List<PortfolioSummary> txs = entry.getValue();
                PortfolioSummary info = txs.get(0);
                
                String code = CommonUtils.SANITIZE_AMFI.apply(info.getAmfiCode());
                List<TaxLot> openLots = openLotsBySchemeId.getOrDefault(schemeId, Collections.emptyList());
                List<CapitalGainAudit> audits = auditMapBySchemeId.getOrDefault(schemeId, Collections.emptyList());
                Map<LocalDate, BigDecimal> schemeNavHistory = navHistoryMap.getOrDefault(code, Collections.emptyMap());

                // Value aggregation
                BigDecimal unitsHeld = openLots.stream()
                    .map(TaxLot::getRemainingUnits)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal currentInvested = openLots.stream()
                    .map(lot -> lot.getRemainingUnits().multiply(lot.getCostBasisPerUnit()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                SchemeDetailsDTO schemeDetails = navService.getLatestSchemeDetails(info.getAmfiCode());
                BigDecimal currentNav = schemeDetails.getNav();
                
                BigDecimal currentValue = unitsHeld.multiply(currentNav).setScale(2, RoundingMode.HALF_UP);
                BigDecimal unrealizedGain = currentValue.subtract(currentInvested);

                BigDecimal realizedGain = audits.stream()
                    .map(CapitalGainAudit::getRealizedGain)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Tax calculations (Simplified for dashboard)
                String amfiCategory = (schemeDetails.getCategory() != null) ? schemeDetails.getCategory().toUpperCase() : "OTHER";
                openLots = taxLotRepo.findByStatusAndSchemeAmfiCodeAndSchemeFolioInvestorPan("OPEN", code, pan);
                double ltcgUnrealized = 0;
                double stcgUnrealized = 0;
                double slabRateUnrealized = 0;
                boolean hasSlabRateLots = false;

                for (TaxLot lot : openLots) {
                    double lotVal = lot.getRemainingUnits().doubleValue() * currentNav.doubleValue();
                    double lotCost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
                    double lotGain = Math.max(0, lotVal - lotCost);

                    String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), amfiCategory);
                    if (taxCat.contains("LTCG")) {
                        ltcgUnrealized += lotGain;
                    } else if (taxCat.equals("SLAB_RATE_TAX")) {
                        slabRateUnrealized += lotGain;
                        hasSlabRateLots = true;
                    } else {
                        stcgUnrealized += lotGain;
                    }
                }

                BigDecimal unrealizedGainObj = BigDecimal.valueOf(ltcgUnrealized + stcgUnrealized + slabRateUnrealized);

                // Cash flows for XIRR
                List<TransactionDTO> cashFlows = txs.stream()
                    .map(t -> {
                        BigDecimal preciseAmount = Optional.ofNullable(schemeNavHistory.get(t.getTransactionDate()))
                            .map(nav -> nav.multiply(t.getUnits().abs()))
                            .orElse(t.getAmount().abs());
                        
                        // Outflows (Buy/Tax) are negative, Inflows (Sell/Div) are positive
                        boolean isOutflow = "BUY".equalsIgnoreCase(t.getTransactionType()) || "STAMP_DUTY".equalsIgnoreCase(t.getTransactionType());
                        BigDecimal flow = isOutflow ? preciseAmount.negate() : preciseAmount;
                        
                        return new TransactionDTO(flow, t.getTransactionDate());
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
                else if (amfiCategory.contains("GOLD") || info.getSchemeName().toUpperCase().contains("GOLD")) bucket = "GOLD_HEDGE_24M";
                else if (amfiCategory.contains("EQUITY") || amfiCategory.contains("INDEX") || amfiCategory.contains("GROWTH")) bucket = "AGGRESSIVE_GROWTH";
                else if (amfiCategory.contains("DEBT") || amfiCategory.contains("LIQUID")) bucket = "DEBT_SLAB_TAXED";

                Double benchXirr = benchmarkService.getBenchmarkReturn(bucket, amfiCategory, info.getSchemeName());
                MarketMetrics m = metricsMap.getOrDefault(code, MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, LocalDate.of(1970, 1, 1)));

                return SchemePerformanceDTO.builder()
                    .schemeName(info.getSchemeName())
                    .simpleName(CommonUtils.NORMALIZE_NAME.apply(info.getSchemeName()))
                    .isin(info.getIsin())
                    .amfiCode(info.getAmfiCode())
                    .totalInvested(txs.stream().filter(t -> t.getUnits().compareTo(BigDecimal.ZERO) > 0).map(PortfolioSummary::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .currentInvested(currentInvested)
                    .currentValue(currentValue)
                    .realizedGain(realizedGain)
                    .unrealizedGain(unrealizedGainObj)
                    .ltcgUnrealizedGain(ltcgUnrealized)
                    .stcgUnrealizedGain(stcgUnrealized)
                    .slabRateGain(slabRateUnrealized)
                    .isSlabRateFund(hasSlabRateLots)
                    .transactionCount(txs.size())
                    .xirr(displayXirr)
                    .benchmarkXirr(benchXirr)
                    .status(status)
                    .category(amfiCategory)
                    .bucket(bucket)
                    .amc(info.getAmc())
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

        BigDecimal aggregateCurrentInvested = breakdown.stream().map(s -> s.getCurrentInvested()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal aggregateCurrentValue = breakdown.stream().map(s -> s.getCurrentValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal aggregateTotalInvested = breakdown.stream().map(s -> s.getTotalInvested()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal aggregateRealizedGain = breakdown.stream().map(s -> s.getRealizedGain()).reduce(BigDecimal.ZERO, BigDecimal::add);
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

        Double investorSlab = jdbcTemplate.queryForObject("SELECT tax_slab FROM investor WHERE pan = ?", Double.class, pan);
        double slabRate = (investorSlab != null) ? investorSlab : 0.30;

        return DashboardSummaryDTO.builder()
            .investorName(investorName)
            .totalFolios((int) summaryData.stream().map(PortfolioSummary::getFolioId).distinct().count())
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
            .taxSlab(slabRate)
            .schemeBreakdown(breakdown)
            .build();
    }
}
