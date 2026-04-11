package com.oreki.cas_injector.taxmanagement.service;


import java.io.InputStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
import com.oreki.cas_injector.transactions.model.TaxLot;
import com.oreki.cas_injector.transactions.repository.TaxLotRepository;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TaxLossHarvestingService {

    private final TaxLotRepository taxLotRepository;
    private final NavService amfiService;
    private final ObjectMapper objectMapper;

    // Minimum loss required to trigger a harvest (e.g., ₹1,000) to avoid "dusting"
    private static final double MIN_ABSOLUTE_LOSS_THRESHOLD = 1000.0;
    // Minimum percentage drop to consider the lot "broken"
    private static final double MIN_PERCENTAGE_DROP = -0.05; // -5%

    // Externalized proxy map for maintaining market exposure without violating 
    // commercial substance rules (avoiding circular trading suspicion)
    private Map<String, String> proxyMap = Collections.emptyMap();

    public TaxLossHarvestingService(TaxLotRepository taxLotRepository, NavService amfiService, ObjectMapper objectMapper) {
        this.taxLotRepository = taxLotRepository;
        this.amfiService = amfiService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadProxyMap() {
        try {
            InputStream is = new ClassPathResource("tlh_proxy.json").getInputStream();
            Map<String, String> rawMap = objectMapper.readValue(is, new TypeReference<Map<String, String>>() {});
            // Use a Case-Insensitive TreeMap for robust lookups
            this.proxyMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            rawMap.forEach((k, v) -> this.proxyMap.put(k.trim(), v));
            log.info("✅ Loaded {} TLH proxy pairs from configuration.", proxyMap.size());
        } catch (Exception e) {
            log.error("🚨 Failed to load TLH proxy map: {}", e.getMessage());
        }
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
                String proxy = proxyMap.getOrDefault(scheme.getAmfiCode(), "Search for similar category peer");

                opportunities.add(new TlhOpportunity(
                    scheme.getName(),
                    scheme.getAmfiCode(),
                    accumHarvestAmount,
                    accumCapitalLoss, // The exact tax write-off value
                    taxBucket,
                    proxy,
                    TlhOpportunity.OpportunityType.TAX_LOSS_HARVEST,
                    accumCapitalLoss * (isShortTerm ? 0.20 : 0.125),
                    String.format("Harvesting this loss saves ₹%,.0f in future taxes.", accumCapitalLoss * (isShortTerm ? 0.20 : 0.125))
                ));
                
                log.info("🪓 TLH Found: Sell ₹{} of {} to bank ₹{} in {}", 
                    Math.round(accumHarvestAmount), scheme.getName(), Math.round(accumCapitalLoss), taxBucket);
            }
        }

        // --- SECOND PASS: SIP REDIRECT ---
        for (Map.Entry<Scheme, List<TaxLot>> entry : lotsByScheme.entrySet()) {
            Scheme scheme = entry.getKey();
            String proxyFundName = proxyMap.get(scheme.getAmfiCode());
            if (proxyFundName == null) continue;

            double totalCost = entry.getValue().stream().mapToDouble(l -> l.getCostBasisPerUnit().doubleValue() * l.getRemainingUnits().doubleValue()).sum();
            double currentNav = amfiService.getLatestSchemeDetails(scheme.getAmfiCode()).getNav().doubleValue();
            double totalValue = entry.getValue().stream().mapToDouble(l -> l.getRemainingUnits().doubleValue() * currentNav).sum();
            double unrealizedGain = totalValue - totalCost;

            if (unrealizedGain > 0.20 * totalCost) {
                opportunities.add(new TlhOpportunity(
                    scheme.getName(),
                    scheme.getAmfiCode(),
                    0.0,
                    0.0,
                    "N/A",
                    proxyFundName,
                    TlhOpportunity.OpportunityType.SIP_REDIRECT,
                    unrealizedGain * 0.125,
                    "🔄 Stop SIP to " + scheme.getName() + 
                    ". Redirect to " + proxyFundName + 
                    " to build fresh cost-basis. This maintains your sector exposure " +
                    "while creating future loss-recognition capacity."
                ));
            }
        }

        return opportunities;
    }
}