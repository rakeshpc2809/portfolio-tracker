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
import com.oreki.cas_injector.stocks.StockAggregationService;
import com.oreki.cas_injector.stocks.StockHoldingDTO;
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
    private final StockAggregationService stockAggSvc;
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

        LocalDate targetDate = LocalDate.now().minusDays(30);
        Map<String, BigDecimal> nav30DaysAgoMap = new HashMap<>();
        if (!amfiCodes.isEmpty()) {
            try {
                String placeholders = amfiCodes.stream().map(c -> "?").collect(Collectors.joining(","));
                String query = "SELECT DISTINCT ON (amfi_code) amfi_code, nav FROM fund_history WHERE nav_date <= ? AND amfi_code IN (" + placeholders + ") ORDER BY amfi_code, nav_date DESC";
                List<Object> params = new ArrayList<>();
                params.add(java.sql.Date.valueOf(targetDate));
                params.addAll(amfiCodes);
                jdbcTemplate.query(query, rs -> {
                    String amfi = CommonUtils.SANITIZE_AMFI.apply(rs.getString("amfi_code"));
                    BigDecimal navVal = rs.getBigDecimal("nav");
                    nav30DaysAgoMap.put(amfi, navVal);
                }, params.toArray());
            } catch (Exception e) {
                log.warn("⚠️ Failed to fetch NAVs from 30 days ago: {}", e.getMessage());
            }
        }

        List<TransactionDTO> totalCashFlow = new ArrayList<>();

        List<SchemePerformanceDTO> breakdown = new ArrayList<>();
        
        for (Map.Entry<Long, List<PortfolioSummary>> entry : txsBySchemeId.entrySet()) {
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
            BigDecimal ltcgUnrealized = BigDecimal.ZERO;
            BigDecimal stcgUnrealized = BigDecimal.ZERO;
            BigDecimal slabRateUnrealized = BigDecimal.ZERO;
            boolean hasSlabRateLots = false;

            for (TaxLot lot : openLots) {
                BigDecimal lotVal = lot.getRemainingUnits().multiply(currentNav).setScale(4, RoundingMode.HALF_UP);
                BigDecimal lotCost = lot.getRemainingUnits().multiply(lot.getCostBasisPerUnit()).setScale(4, RoundingMode.HALF_UP);
                BigDecimal lotGain = lotVal.subtract(lotCost);
                if (lotGain.compareTo(BigDecimal.ZERO) < 0) {
                    lotGain = BigDecimal.ZERO;
                }

                String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), amfiCategory);
                if (taxCat.contains("LTCG")) {
                    ltcgUnrealized = ltcgUnrealized.add(lotGain);
                } else if (taxCat.equals("SLAB_RATE_TAX")) {
                    slabRateUnrealized = slabRateUnrealized.add(lotGain);
                    hasSlabRateLots = true;
                } else {
                    stcgUnrealized = stcgUnrealized.add(lotGain);
                }
            }

            BigDecimal unrealizedGainObj = ltcgUnrealized.add(stcgUnrealized).add(slabRateUnrealized).setScale(2, RoundingMode.HALF_UP);

            // Cash flows for XIRR
            List<TransactionDTO> cashFlows = txs.stream()
                .filter(t -> !"DIVIDEND_REINVESTMENT".equalsIgnoreCase(t.getTransactionType()))
                .map(t -> {
                    String type = t.getTransactionType().toUpperCase();
                    boolean isOutflow;
                    if (t.getUnits() != null && t.getUnits().abs().compareTo(new BigDecimal("0.0001")) > 0) {
                        isOutflow = t.getUnits().compareTo(BigDecimal.ZERO) > 0;
                    } else {
                        isOutflow = type.contains("BUY") || type.contains("PURCHASE") || 
                                   type.contains("SWITCH_IN") || type.contains("STAMP") || 
                                   type.contains("STT") || type.contains("TDS");
                    }

                    BigDecimal preciseAmount;
                    if (t.getUnits() != null && t.getUnits().abs().compareTo(new BigDecimal("0.0001")) > 0) {
                        preciseAmount = Optional.ofNullable(schemeNavHistory.get(t.getTransactionDate()))
                            .map(nav -> nav.multiply(t.getUnits().abs()))
                            .orElse(t.getAmount().abs());
                    } else {
                        preciseAmount = t.getAmount().abs();
                    }
                    
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

            Double oneMonthReturn = null;
            BigDecimal nav30d = nav30DaysAgoMap.get(code);
            if (nav30d != null && nav30d.compareTo(BigDecimal.ZERO) > 0 && currentNav != null) {
                oneMonthReturn = currentNav.subtract(nav30d)
                    .divide(nav30d, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .doubleValue();
            }

            SchemePerformanceDTO dto = SchemePerformanceDTO.builder()
                .schemeName(info.getSchemeName())
                .simpleName(CommonUtils.NORMALIZE_NAME.apply(info.getSchemeName()))
                .isin(info.getIsin())
                .amfiCode(info.getAmfiCode())
                .totalInvested(txs.stream().filter(t -> t.getUnits().compareTo(BigDecimal.ZERO) > 0).map(PortfolioSummary::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .currentInvested(currentInvested)
                .currentValue(currentValue)
                .realizedGain(realizedGain)
                .unrealizedGain(unrealizedGainObj)
                .ltcgUnrealizedGain(ltcgUnrealized.setScale(2, RoundingMode.HALF_UP))
                .stcgUnrealizedGain(stcgUnrealized.setScale(2, RoundingMode.HALF_UP))
                .slabRateGain(slabRateUnrealized.setScale(2, RoundingMode.HALF_UP))
                .stcgValue(stcgUnrealized.setScale(2, RoundingMode.HALF_UP))
                .ltcgValue(ltcgUnrealized.setScale(2, RoundingMode.HALF_UP))
                .isSlabRateFund(hasSlabRateLots)
                .transactionCount(txs.size())
                .xirr(displayXirr)
                .status(status)
                .action("HOLD")
                .category(amfiCategory)
                .bucket(bucket)
                .amc(info.getAmc())
                .oneMonthReturn(oneMonthReturn)
                .build();

            breakdown.add(dto);
        }

        // --- MERGE STOCKS ---
        List<StockHoldingDTO> stocks = stockAggSvc.getPortfolio(pan);
        List<String> tickers = stocks.stream().map(StockHoldingDTO::getTicker).distinct().toList();
        Map<String, BigDecimal> stockPrice30DaysAgoMap = new HashMap<>();
        if (!tickers.isEmpty()) {
            try {
                String placeholders = tickers.stream().map(t -> "?").collect(Collectors.joining(","));
                String query = "SELECT DISTINCT ON (ticker) ticker, close_price FROM stock_price_eod WHERE price_date <= ? AND ticker IN (" + placeholders + ") ORDER BY ticker, price_date DESC";
                List<Object> params = new ArrayList<>();
                params.add(java.sql.Date.valueOf(targetDate));
                params.addAll(tickers);
                jdbcTemplate.query(query, rs -> {
                    String ticker = rs.getString("ticker");
                    BigDecimal priceVal = rs.getBigDecimal("close_price");
                    stockPrice30DaysAgoMap.put(ticker, priceVal);
                }, params.toArray());
            } catch (Exception e) {
                log.warn("⚠️ Failed to fetch stock prices from 30 days ago: {}", e.getMessage());
            }
        }

        for (StockHoldingDTO stock : stocks) {
            BigDecimal price30d = stockPrice30DaysAgoMap.get(stock.getTicker());
            Double stockOneMonthReturn = null;
            if (price30d != null && price30d.compareTo(BigDecimal.ZERO) > 0 && stock.getCurrentPrice() != null) {
                stockOneMonthReturn = stock.getCurrentPrice().subtract(price30d)
                    .divide(price30d, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .doubleValue();
            }

            breakdown.add(SchemePerformanceDTO.builder()
                .schemeName(stock.getCompanyName() + " (" + stock.getTicker() + ")")
                .simpleName(stock.getCompanyName())
                .isin(stock.getIsin())
                .amfiCode(stock.getTicker())
                .totalInvested(stock.getInvestedAmount())
                .currentInvested(stock.getInvestedAmount())
                .currentValue(stock.getCurrentValue())
                .realizedGain(BigDecimal.ZERO)
                .unrealizedGain(stock.getUnrealisedPnl())
                .ltcgUnrealizedGain(stock.getUnrealisedLtcg() != null ? stock.getUnrealisedLtcg() : BigDecimal.ZERO)
                .stcgUnrealizedGain(stock.getUnrealisedStcg() != null ? stock.getUnrealisedStcg() : BigDecimal.ZERO)
                .stcgValue(stock.getUnrealisedStcg() != null ? stock.getUnrealisedStcg() : BigDecimal.ZERO)
                .ltcgValue(stock.getUnrealisedLtcg() != null ? stock.getUnrealisedLtcg() : BigDecimal.ZERO)
                .valueScore(BigDecimal.valueOf(60.0))
                .transactionCount(1) // Aggregated
                .xirr(CommonUtils.SCALE_MONEY.apply(stock.getXirr() != null ? stock.getXirr() : BigDecimal.ZERO) + "%")
                .status(FundStatus.ACTIVE)
                .category("Direct Stock")
                .bucket("DIRECT_EQUITY")
                .amc("NSE/BSE")
                .action("HOLD")
                .convictionScore(60) // Default for stocks
                .oneMonthReturn(stockOneMonthReturn)
                .build());
        }

        // --- ALLOCATION PERCENTAGE & METRICS ENRICHMENT ---
        BigDecimal totalPortfolioValue = breakdown.stream()
            .map(SchemePerformanceDTO::getCurrentValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (SchemePerformanceDTO dto : breakdown) {
            BigDecimal allocatedPct = BigDecimal.ZERO;
            if (totalPortfolioValue.compareTo(BigDecimal.ZERO) > 0) {
                allocatedPct = dto.getCurrentValue().multiply(new BigDecimal("100"))
                    .divide(totalPortfolioValue, 4, RoundingMode.HALF_UP);
            }
            dto.setAllocationPercentage(allocatedPct.doubleValue());

            if ("Direct Stock".equals(dto.getCategory())) {
                continue;
            }
            String code = CommonUtils.SANITIZE_AMFI.apply(dto.getAmfiCode());

            // Enrich with Benchmark return
            Double benchXirr = null;
            try {
                benchXirr = benchmarkService.getBenchmarkReturn(dto.getBucket(), dto.getCategory(), dto.getSchemeName());
            } catch (Exception e) {
                log.warn("⚠️ Failed to get benchmark return for scheme: {}, error: {}", dto.getSchemeName(), e.getMessage());
            }
            dto.setBenchmarkXirr(benchXirr);

            // Enrich with Conviction & Quantitative Metrics
            try {
                MarketMetrics m = metricsMap.get(code);
                if (m != null) {
                    dto.setValueScore(BigDecimal.valueOf(m.valueScore()));
                    dto.setConvictionScore(m.convictionScore());
                    dto.setSortinoRatio(m.sortinoRatio());
                    dto.setMaxDrawdown(m.maxDrawdown());
                    dto.setCvar5(m.cvar5());
                    dto.setNavPercentile3yr(m.navPercentile3yr());
                    dto.setDrawdownFromAth(m.drawdownFromAth());
                    dto.setReturnZScore(m.returnZScore());
                    dto.setLastBuyDate(m.lastBuyDate());
                    dto.setRollingZScore252(m.rollingZScore252());
                    dto.setHurstExponent(m.hurstExponent());
                    dto.setVolatilityTax(m.volatilityTax());
                    dto.setHurstRegime(m.hurstRegime());
                    dto.setHistoricalRarityPct(m.historicalRarityPct());
                    dto.setOuHalfLife(m.ouHalfLife());
                    dto.setOuValid(m.ouValid());
                    dto.setHmmState(m.hmmState());
                    dto.setHmmBullProb(m.hmmBullProb());
                    dto.setHmmBearProb(m.hmmBearProb());
                    dto.setAlpha(m.alpha());
                    dto.setBetaMkt(m.betaMkt());
                    dto.setBetaSmb(m.betaSmb());
                    dto.setBetaHml(m.betaHml());
                    dto.setRSquared(m.rSquared());
                } else {
                    dto.setConvictionScore(50); // Default sentinel
                    dto.setValueScore(BigDecimal.valueOf(50.0));
                }
            } catch (Exception e) {
                log.warn("⚠️ Failed to load conviction/market metrics for scheme: {}, error: {}", code, e.getMessage());
                dto.setConvictionScore(50); // Default sentinel
                dto.setValueScore(BigDecimal.valueOf(50.0));
            }
        }

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

        Double investorSlab = null;
        try {
            investorSlab = jdbcTemplate.queryForObject("SELECT tax_slab FROM investor WHERE pan = ?", Double.class, pan);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            log.info("ℹ️ No investor found for PAN: {}, defaulting tax slab to 0.30", pan);
        }
        double slabRate = (investorSlab != null) ? investorSlab : 0.30;

        // realized LTCG for current FY (Dashboard Summary)
        BigDecimal realizedLtcg = allInvestorAudits.stream()
            .filter(a -> a.getTaxCategory().contains("LTCG") && !a.getSellTransaction().getDate().isBefore(fyStart))
            .map(CapitalGainAudit::getRealizedGain)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

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
            .totalLTCG(realizedLtcg)
            .taxSlab(slabRate)
            .schemeBreakdown(breakdown)
            .build();
    }


    @Transactional(readOnly = true)
    public Map<String, Double> getVintageReturns(String pan) {
        log.info("📊 Computing vintage returns for PAN: {}", pan);
        String sql = """
            SELECT 
                TO_CHAR(tl.buy_date, 'YYYY-MM') as vintage,
                SUM(tl.remaining_units * COALESCE(s2.nav, tl.cost_basis_per_unit)) as current_val,
                SUM(tl.remaining_units * tl.cost_basis_per_unit) as cost_val
            FROM tax_lot tl
            JOIN scheme s ON tl.scheme_id = s.id
            JOIN folio f ON s.folio_id = f.id
            LEFT JOIN (
                SELECT amfi_code, nav
                FROM fund_history
                WHERE (amfi_code, nav_date) IN (
                    SELECT amfi_code, MAX(nav_date)
                    FROM fund_history
                    GROUP BY amfi_code
                )
            ) s2 ON s2.amfi_code = s.amfi_code
            WHERE f.investor_pan = ?
              AND tl.remaining_units > 0
            GROUP BY TO_CHAR(tl.buy_date, 'YYYY-MM')
            ORDER BY vintage ASC
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pan);
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String vintage = (String) row.get("vintage");
            Number currentValNum = (Number) row.get("current_val");
            Number costValNum = (Number) row.get("cost_val");
            if (vintage != null && currentValNum != null && costValNum != null) {
                double currentVal = currentValNum.doubleValue();
                double costVal = costValNum.doubleValue();
                if (costVal > 0) {
                    double ret = ((currentVal - costVal) / costVal) * 100.0;
                    result.put(vintage, ret);
                } else {
                    result.put(vintage, 0.0);
                }
            }
        }
        return result;
    }

    @Transactional
    public void refreshMaterializedView() {
        jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_portfolio_summary");
    }
}
