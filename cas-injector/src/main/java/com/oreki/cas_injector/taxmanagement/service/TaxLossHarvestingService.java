package com.oreki.cas_injector.taxmanagement.service;


import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TaxLossHarvestingService {

    private final TaxLotRepository taxLotRepository;
    private final NavService amfiService;

    // Minimum loss required to trigger a harvest (e.g., ₹1,000) to avoid "dusting"
    private static final double MIN_ABSOLUTE_LOSS_THRESHOLD = 1000.0;
    // Minimum percentage drop to consider the lot "broken"
    private static final double MIN_PERCENTAGE_DROP = -0.05; // -5%

    // A basic proxy map: If you sell X, you must buy Y to maintain market exposure without triggering wash sale rules
    private static final Map<String, String> PROXY_MAP = Map.of(
        "MOTILAL OSWAL NIFTY MIDCAP 150 INDEX FUND - DIRECT PLAN GROWTH", "NIPPON INDIA NIFTY MIDCAP 150 INDEX FUND",
        "PARAG PARIKH FLEXI CAP FUND - DIRECT PLAN GROWTH", "HDFC FLEXI CAP FUND - DIRECT PLAN",
        "ICICI PRUDENTIAL NIFTY LARGEMIDCAP 250 INDEX FUND - DIRECT PLAN - GROWTH", "ZERODHA NIFTY LARGEMIDCAP 250 INDEX FUND"
    );

    public TaxLossHarvestingService(TaxLotRepository taxLotRepository, NavService amfiService) {
        this.taxLotRepository = taxLotRepository;
        this.amfiService = amfiService;
    }

    public List<TlhOpportunity> scanForOpportunities(String investorPan) {
        log.info("🔎 Initiating FIFO-Aware Tax-Loss Harvesting Scan for {}", investorPan);
        List<TlhOpportunity> opportunities = new ArrayList<>();

        // 1. Fetch all open lots
        List<TaxLot> allOpenLots = taxLotRepository.findByStatusAndSchemeFolioInvestorPan("OPEN", investorPan);
        
        // 2. Group by Scheme
        Map<Scheme, List<TaxLot>> lotsByScheme = allOpenLots.stream()
            .collect(Collectors.groupingBy(TaxLot::getScheme));

        for (Map.Entry<Scheme, List<TaxLot>> entry : lotsByScheme.entrySet()) {
            Scheme scheme = entry.getKey();
            
            // 3. Sort lots strictly by Buy Date ASC (The FIFO Law)
            List<TaxLot> fifoQueue = entry.getValue().stream()
                .sorted(Comparator.comparing(TaxLot::getBuyDate))
                .toList();

            double currentNav = amfiService.getLatestSchemeDetails(scheme.getAmfiCode()).getNav().doubleValue();
            
            double accumHarvestAmount = 0.0;
            double accumCapitalLoss = 0.0;
            boolean isShortTerm = true; // Default assumption

            // 4. The FIFO Front-Runner Check
            for (TaxLot lot : fifoQueue) {
                double lotCost = lot.getCostBasisPerUnit().doubleValue();
                double lotDropPercentage = (currentNav - lotCost) / lotCost;
                
                // If the lot is at a gain, or the loss isn't deep enough, the FIFO chain is broken. STOP.
                if (lotDropPercentage > MIN_PERCENTAGE_DROP) {
                    break; 
                }

                double lotValue = lot.getRemainingUnits().doubleValue() * currentNav;
                double lotLoss = (lotCost * lot.getRemainingUnits().doubleValue()) - lotValue; // Positive number representing the loss

                // Check age to classify the loss bucket
                long daysHeld = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
                if (daysHeld > 365 && (scheme.getAssetCategory() == null || scheme.getAssetCategory().contains("EQUITY"))) {
                    isShortTerm = false; // It takes just one LTCG lot to require careful handling
                }

                accumHarvestAmount += lotValue;
                accumCapitalLoss += lotLoss;
            }

            // 5. Evaluate the accumulated FIFO harvest
            if (accumCapitalLoss >= MIN_ABSOLUTE_LOSS_THRESHOLD) {
                String taxBucket = isShortTerm ? "STCL (Offsets any gain)" : "LTCL (Offsets only LTCG)";
                String proxy = PROXY_MAP.getOrDefault(scheme.getName(), "Search for similar category peer");

                opportunities.add(new TlhOpportunity(
                    scheme.getName(),
                    scheme.getAmfiCode(),
                    accumHarvestAmount,
                    accumCapitalLoss, // The exact tax write-off value
                    taxBucket,
                    proxy
                ));
                
                log.info("🪓 TLH Found: Sell ₹{} of {} to bank ₹{} in {}", 
                    Math.round(accumHarvestAmount), scheme.getName(), Math.round(accumCapitalLoss), taxBucket);
            }
        }

        return opportunities;
    }
}