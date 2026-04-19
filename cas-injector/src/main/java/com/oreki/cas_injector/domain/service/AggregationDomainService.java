package com.oreki.cas_injector.domain.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import com.oreki.cas_injector.domain.model.TaxLotDomain;
import com.oreki.cas_injector.domain.spec.TaxLotDomainSpecs;
import com.oreki.cas_injector.core.dto.AggregatedHolding;

public class AggregationDomainService {

    public AggregatedHolding aggregateSchemeLots(String schemeName, String isin, String category, double liveNav, List<TaxLotDomain> lots) {
        double units = 0, cost = 0, val = 0, ltcgGains = 0, stcgGains = 0;
        double ltcgVal = 0, stcgVal = 0;
        int minDaysToLtcg = 1095;
        LocalDate oldest = LocalDate.now();

        for (TaxLotDomain lot : lots) {
            double lUnits = lot.getRemainingUnits();
            double lCost  = lot.getPurchasePrice() * lUnits;
            double lVal   = lUnits * liveNav;
            double gain   = lVal - lCost;

            units += lUnits; cost += lCost; val += lVal;
            if (lot.getPurchaseDate().isBefore(oldest)) oldest = lot.getPurchaseDate();

            if (TaxLotDomainSpecs.isDebt(category).isSatisfiedBy(lot)) {
                stcgVal   += lVal;
                stcgGains += Math.max(0, gain);
                continue;
            }

            if (TaxLotDomainSpecs.isLtcgEligible(category).isSatisfiedBy(lot)) {
                ltcgVal   += lVal;
                ltcgGains += Math.max(0, gain);
            } else {
                stcgVal   += lVal;
                stcgGains += Math.max(0, gain);

                int waitDays = 0;
                if (TaxLotDomainSpecs.isEquity(category).isSatisfiedBy(lot)) waitDays = 365;
                else if (category != null && category.toUpperCase().contains("HYBRID")) waitDays = 730;
                else if (lot.getPurchaseDate().isBefore(LocalDate.of(2023, 4, 1))) waitDays = 1095;

                if (waitDays > 0) {
                    long age      = ChronoUnit.DAYS.between(lot.getPurchaseDate(), LocalDate.now());
                    int  daysLeft = waitDays - (int) age;
                    if (daysLeft > 0 && daysLeft < minDaysToLtcg) minDaysToLtcg = daysLeft;
                }
            }
        }

        int finalDaysToNext = (stcgVal > 0 && minDaysToLtcg < 1095) ? minDaysToLtcg : 0;

        return new AggregatedHolding(
            schemeName, units, val, cost,
            ltcgVal, ltcgGains, stcgVal, stcgGains,
            finalDaysToNext, (int) ChronoUnit.DAYS.between(oldest, LocalDate.now()),
            category, "ACTIVE", isin, stcgGains * 0.20, liveNav
        );
    }
}
