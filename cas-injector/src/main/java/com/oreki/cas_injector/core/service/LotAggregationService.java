package com.oreki.cas_injector.core.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import com.oreki.cas_injector.core.model.Scheme;
import com.oreki.cas_injector.core.utils.CommonUtils;
import com.oreki.cas_injector.transactions.model.TaxLot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class LotAggregationService {

    private final NavService amfiService;

    /**
     * Aggregates open tax lots per scheme into AggregatedHolding DTOs,
     * computing LTCG/STCG splits, days-to-LTCG, and current market value.
     */
    public List<AggregatedHolding> aggregate(List<TaxLot> lots) {
        Map<Scheme, List<TaxLot>> groupedLots = lots.stream()
            .collect(Collectors.groupingBy(TaxLot::getScheme));

        return groupedLots.entrySet().stream().map(entry -> {
            Scheme scheme = entry.getKey();
            SchemeDetailsDTO details = amfiService.getLatestSchemeDetails(scheme.getAmfiCode());
            double liveNav = (details != null && details.getNav() != null) ? details.getNav().doubleValue() : 0.0;
            String category = scheme.getAssetCategory() != null ? scheme.getAssetCategory() : (details != null ? details.getCategory() : "");

            double units = 0, cost = 0, val = 0, ltcgGains = 0, stcgGains = 0;
            double ltcgVal = 0, stcgVal = 0;
            int minDaysToLtcg = 1095;
            LocalDate oldest = LocalDate.now();

            boolean isDebtFund = isDebt(category);

            for (TaxLot lot : entry.getValue()) {
                double lUnits = lot.getRemainingUnits().doubleValue();
                double lCost  = lot.getCostBasisPerUnit().doubleValue() * lUnits;
                double lVal   = lUnits * liveNav;
                double gain   = lVal - lCost;

                units += lUnits; cost += lCost; val += lVal;
                if (lot.getBuyDate().isBefore(oldest)) oldest = lot.getBuyDate();

                if (isDebtFund) {
                    stcgVal   += lVal;
                    stcgGains += Math.max(0, gain);
                    continue;
                }

                String taxCat = CommonUtils.DETERMINE_TAX_CATEGORY.apply(lot.getBuyDate(), LocalDate.now(), category);
                if (taxCat.contains("LTCG")) {
                    ltcgVal   += lVal;
                    ltcgGains += Math.max(0, gain);
                } else {
                    stcgVal   += lVal;
                    stcgGains += Math.max(0, gain);

                    int waitDays = 0;
                    if (taxCat.contains("EQUITY"))  waitDays = 365;
                    else if (taxCat.contains("HYBRID")) waitDays = 730;
                    else if (lot.getBuyDate().isBefore(LocalDate.of(2023, 4, 1))) waitDays = 1095;

                    if (waitDays > 0) {
                        long age      = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
                        int  daysLeft = waitDays - (int) age;
                        if (daysLeft > 0 && daysLeft < minDaysToLtcg) minDaysToLtcg = daysLeft;
                    }
                }
            }

            int finalDaysToNext = (stcgVal > 0 && minDaysToLtcg < 1095) ? minDaysToLtcg : 0;

            return new AggregatedHolding(
                scheme.getName(), units, val, cost,
                ltcgVal, ltcgGains, stcgVal, stcgGains,
                finalDaysToNext, (int) ChronoUnit.DAYS.between(oldest, LocalDate.now()),
                category, "ACTIVE", scheme.getIsin()
            );
        }).collect(Collectors.toList());
    }

    private boolean isDebt(String category) {
        if (category == null) return false;
        String upper = category.toUpperCase();
        return upper.contains("DEBT") || upper.contains("GILT") || upper.contains("BOND")
            || upper.contains("LIQUID") || upper.contains("ARBITRAGE")
            || upper.contains("MONEY MARKET") || upper.contains("BANKING AND PSU")
            || upper.contains("CORPORATE") || upper.contains("OVERNIGHT")
            || upper.contains("ULTRA SHORT") || upper.contains("LOW DURATION");
    }
}
