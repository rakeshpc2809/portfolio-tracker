package com.oreki.cas_injector.dashboard.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.repository.HistoricalNavRepository;
import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.dto.SchemePerformanceDTO;
import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.core.utils.FundStatus;
import com.oreki.cas_injector.dashboard.dto.DashboardSummaryDTO;
import com.oreki.cas_injector.transactions.dto.TransactionDTO;
import com.oreki.cas_injector.transactions.model.CapitalGainAudit;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.model.Transaction;
import com.oreki.cas_injector.transactions.repository.CapitalGainAuditRepository;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;
import com.oreki.cas_injector.transactions.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;



@Service
@RequiredArgsConstructor
public class DashboardService {

    private final InvestorRepository investorRepo;
    private final TaxLotRepository taxLotRepo;
    private final CapitalGainAuditRepository auditRepo;
    private final TransactionRepository txnRepo;
    private final NavService navService;
    private final HistoricalNavRepository historicalNavRepo;

    private String sanitizeAmfi(String amfi) {
        if (amfi == null) return "";
        String s = amfi.trim();
        return s.replaceFirst("^0+(?!$)", "");
    }

    @Cacheable(value = "dashboardSummary", key = "#pan")
    public DashboardSummaryDTO getInvestorSummary(String pan) {
        Optional<Investor> investorOpt = investorRepo.findById(pan);
        if (investorOpt.isEmpty()) {
            return DashboardSummaryDTO.builder()
                .investorName("New Investor")
                .schemeBreakdown(List.of())
                .totalInvestedAmount(BigDecimal.ZERO)
                .totalRealizedGain(BigDecimal.ZERO)
                .totalLTCG(BigDecimal.ZERO)
                .totalSTCG(BigDecimal.ZERO)
                .currentInvestedAmount(BigDecimal.ZERO)
                .currentValueAmount(BigDecimal.ZERO)
                .totalUnrealizedGain(BigDecimal.ZERO)
                .overallReturn("0%")
                .overallXirr("0%")
                .build();
        }
        Investor investor = investorOpt.get();

        List<TransactionDTO> totalCashFlow=new ArrayList<>();
        
        List<CapitalGainAudit> allInvestorAudits = auditRepo.findAllBySellTransactionSchemeFolioInvestorPan(pan);
        Map<Long, List<CapitalGainAudit>> auditsBySchemeId = allInvestorAudits.stream()
            .collect(Collectors.groupingBy(a -> a.getSellTransaction().getScheme().getId()));

        List<SchemePerformanceDTO> breakdown = investor.getFolios().stream()
            .flatMap(f -> f.getSchemes().stream())
            .map(scheme -> {
                List<TaxLot> allLots = scheme.getTaxLots();
                List<CapitalGainAudit> schemeAudits = auditsBySchemeId.getOrDefault(scheme.getId(), List.of());

                BigDecimal totalInvested = scheme.getTransactions().stream()
                    .filter(t -> "BUY".equalsIgnoreCase(t.getTransactionType()) || 
                    "STAMP_DUTY".equalsIgnoreCase(t.getTransactionType()))
                    .map(Transaction::getAmount)
                    .map(CommonUtils.SCALE_MONEY)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal soldAmountCost = schemeAudits.stream()
                    .map(audit -> audit.getUnitsMatched().multiply(audit.getTaxLot().getCostBasisPerUnit()))
                    .map(CommonUtils.SCALE_MONEY)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal currentInvested = allLots.stream()
                    .filter(lot -> "OPEN".equalsIgnoreCase(lot.getStatus()))
                    .map(lot -> lot.getRemainingUnits().multiply(lot.getCostBasisPerUnit()))
                    .map(CommonUtils.SCALE_MONEY)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal schemeGain = schemeAudits.stream()
                    .map(CapitalGainAudit::getRealizedGain)
                    .map(CommonUtils.SCALE_MONEY)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                long cleanTxCount = scheme.getTransactions().stream()
                    .filter(t -> "BUY".equalsIgnoreCase(t.getTransactionType()) || 
                    "SELL".equalsIgnoreCase(t.getTransactionType())).count();

                SchemeDetailsDTO schemeDetails = navService.getLatestSchemeDetails(scheme.getAmfiCode());
                BigDecimal currentNav = schemeDetails.getNav();
                String amfiCategory = schemeDetails.getCategory().toUpperCase();

                String bucket;
                if (amfiCategory.contains("ARBITRAGE")) {
                    bucket = "SAFE_REBALANCER_EQUITY_TAX"; 
                } 
                else if (amfiCategory.contains("GOLD") || schemeDetails.getSchemeName().toUpperCase().contains("GOLD")) {
                    bucket = "GOLD_HEDGE_24M";
                }
                else if (amfiCategory.contains("EQUITY") || amfiCategory.contains("INDEX") || amfiCategory.contains("FLEXI") || amfiCategory.contains("GROWTH")) {
                    bucket = "AGGRESSIVE_GROWTH";
                } 
                else if (amfiCategory.contains("TERM") || amfiCategory.contains("LIQUID") || amfiCategory.contains("DEBT")) {
                    bucket = "DEBT_SLAB_TAXED";
                } 
                else {
                    bucket = "OTHERS_CHECK_ISIN";
                }
                    
                BigDecimal unitsHeld = allLots.stream()
                    .filter(lot -> "OPEN".equalsIgnoreCase(lot.getStatus()))
                    .map(TaxLot::getRemainingUnits)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal currentValue = Optional.of(unitsHeld.multiply(currentNav))
                    .map(CommonUtils.SCALE_MONEY)
                    .orElse(BigDecimal.ZERO);

                double ltcgUnrealized = allLots.stream()
                    .filter(lot -> "OPEN".equalsIgnoreCase(lot.getStatus()))
                    .mapToDouble(lot -> {
                        String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), amfiCategory);
                        if (taxCat.contains("LTCG")) {
                            double val = lot.getRemainingUnits().doubleValue() * currentNav.doubleValue();
                            double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
                            return Math.max(0, val - cost);
                        }
                        return 0.0;
                    }).sum();

                double stcgUnrealized = allLots.stream()
                    .filter(lot -> "OPEN".equalsIgnoreCase(lot.getStatus()))
                    .mapToDouble(lot -> {
                        String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), amfiCategory);
                        if (!taxCat.contains("LTCG")) {
                            double val = lot.getRemainingUnits().doubleValue() * currentNav.doubleValue();
                            double cost = lot.getRemainingUnits().doubleValue() * lot.getCostBasisPerUnit().doubleValue();
                            return Math.max(0, val - cost);
                        }
                        return 0.0;
                    }).sum();

                BigDecimal unrealizedGain =  Optional.of(currentValue.subtract(currentInvested))
                    .map(CommonUtils.SCALE_MONEY)
                    .orElse(BigDecimal.ZERO);

                List<TransactionDTO> cashFlows = scheme.getTransactions().stream()
                    .map(t -> {
                        BigDecimal preciseAmount = historicalNavRepo.findByAmfiCodeAndNavDate(sanitizeAmfi(scheme.getAmfiCode()), t.getDate())
                            .map(h -> h.getNav().multiply(t.getUnits().abs()))
                            .orElse(t.getAmount());
                            
                        return new TransactionDTO(preciseAmount.negate(), t.getDate());
                    })
                    .collect(Collectors.toList());
                                
                FundStatus status = CommonUtils.GET_STATUS.apply(unitsHeld);
                totalCashFlow.addAll(cashFlows);
                cashFlows.sort(Comparator.comparing(TransactionDTO::getDate));

                if (status == FundStatus.ACTIVE) {
                    cashFlows.add(new TransactionDTO(currentValue, LocalDate.now()));
                }
              
                BigDecimal xirrValue = CommonUtils.SOLVE_XIRR.apply(cashFlows);
                String displayXirr = CommonUtils.SCALE_MONEY.apply(xirrValue).toString() + "%";

                return SchemePerformanceDTO.builder()
                    .schemeName(scheme.getName())
                    .isin(scheme.getIsin())
                    .amfiCode(scheme.getAmfiCode())
                    .totalInvested(totalInvested)
                    .soldAmountCost(soldAmountCost)
                    .currentInvested(currentInvested)
                    .currentValue(currentValue)
                    .realizedGain(schemeGain)
                    .unrealizedGain(unrealizedGain)
                    .ltcgUnrealizedGain(ltcgUnrealized)
                    .stcgUnrealizedGain(stcgUnrealized)
                    .transactionCount(cleanTxCount)
                    .xirr(displayXirr)
                    .status(status)
                    .category(amfiCategory)
                    .bucket(bucket)
                    .benchmarkIndex(scheme.getBenchmarkIndex())
                    .build();
            })
            .collect(Collectors.toList());

        BigDecimal aggregateTotalInvested = breakdown.stream()
            .map(SchemePerformanceDTO::getTotalInvested)
            .map(CommonUtils.SCALE_MONEY)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal aggregateCurrentInvested = breakdown.stream()
            .map(SchemePerformanceDTO::getCurrentInvested)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal aggregateCurrentValue = breakdown.stream()
            .map(SchemePerformanceDTO::getCurrentValue)
            .map(CommonUtils.SCALE_MONEY)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal aggregateRealizedGain = breakdown.stream()
            .map(SchemePerformanceDTO::getRealizedGain)
            .map(CommonUtils.SCALE_MONEY)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal aggregateUnrealizedGain = breakdown.stream()
            .map(SchemePerformanceDTO::getUnrealizedGain)
            .map(CommonUtils.SCALE_MONEY)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate fyStart = CommonUtils.getCurrentFyStart();

        BigDecimal totalLTCG = allInvestorAudits.stream()
            .filter(a -> a.getTaxCategory().contains("LTCG") && !a.getSellTransaction().getDate().isBefore(fyStart)) 
            .map(CapitalGainAudit::getRealizedGain)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSTCG = allInvestorAudits.stream()
            .filter(a -> (a.getTaxCategory().contains("STCG") || a.getTaxCategory().contains("SLAB")) && !a.getSellTransaction().getDate().isBefore(fyStart))
            .map(CapitalGainAudit::getRealizedGain)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal portfolioPerformance = aggregateTotalInvested.compareTo(BigDecimal.ZERO) > 0 
         ? aggregateUnrealizedGain.divide(aggregateTotalInvested, 4, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100")): BigDecimal.ZERO;

        String overallReturn = CommonUtils.SCALE_MONEY.apply(portfolioPerformance) + "%";

        BigDecimal totalPortfolioValue = breakdown.stream()
            .map(SchemePerformanceDTO::getCurrentValue) 
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        totalCashFlow.add(new TransactionDTO(totalPortfolioValue, LocalDate.now()));
        totalCashFlow.sort(Comparator.comparing(TransactionDTO::getDate));
        
        BigDecimal totalXirr = CommonUtils.SOLVE_XIRR.apply(totalCashFlow);
        String overallXirr = CommonUtils.SCALE_MONEY.apply(totalXirr) + "%";
        
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
            .openTaxLots(taxLotRepo.countByStatusAndSchemeFolioInvestorPan("OPEN", pan))
            .totalSTCG(totalSTCG)
            .totalLTCG(totalLTCG)
            .schemeBreakdown(breakdown)
            .overallReturn(overallReturn)
            .overallXirr(overallXirr)
            .build();
    }
}
