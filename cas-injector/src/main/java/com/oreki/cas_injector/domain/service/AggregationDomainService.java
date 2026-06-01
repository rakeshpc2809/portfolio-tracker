package com.oreki.cas_injector.domain.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.oreki.cas_injector.domain.model.TaxLotDomain;
import com.oreki.cas_injector.domain.spec.TaxLotDomainSpecs;
import com.oreki.cas_injector.core.dto.AggregatedHolding;

public class AggregationDomainService {

    public AggregatedHolding aggregateSchemeLots(String schemeName, String isin, String category, double liveNav, List<TaxLotDomain> lots) {
        BigDecimal units = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal val = BigDecimal.ZERO;
        BigDecimal ltcgGains = BigDecimal.ZERO;
        BigDecimal stcgGains = BigDecimal.ZERO;
        BigDecimal ltcgVal = BigDecimal.ZERO;
        BigDecimal stcgVal = BigDecimal.ZERO;
        BigDecimal liveNavDec = BigDecimal.valueOf(liveNav);

        int minDaysToLtcg = 1095;
        LocalDate oldest = LocalDate.now();

        for (TaxLotDomain lot : lots) {
            BigDecimal lUnits = BigDecimal.valueOf(lot.getRemainingUnits());
            BigDecimal lPrice = BigDecimal.valueOf(lot.getPurchasePrice());
            BigDecimal lCost  = lPrice.multiply(lUnits);
            BigDecimal lVal   = lUnits.multiply(liveNavDec);
            BigDecimal gain   = lVal.subtract(lCost);

            units = units.add(lUnits);
            cost = cost.add(lCost);
            val = val.add(lVal);

            if (lot.getPurchaseDate().isBefore(oldest)) oldest = lot.getPurchaseDate();

            if (TaxLotDomainSpecs.isDebt(category).isSatisfiedBy(lot)) {
                stcgVal   = stcgVal.add(lVal);
                stcgGains = stcgGains.add(gain.max(BigDecimal.ZERO));
                continue;
            }

            if (TaxLotDomainSpecs.isLtcgEligible(category).isSatisfiedBy(lot)) {
                ltcgVal   = ltcgVal.add(lVal);
                ltcgGains = ltcgGains.add(gain.max(BigDecimal.ZERO));
            } else {
                stcgVal   = stcgVal.add(lVal);
                stcgGains = stcgGains.add(gain.max(BigDecimal.ZERO));

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

        int finalDaysToNext = (stcgVal.compareTo(BigDecimal.ZERO) > 0 && minDaysToLtcg < 1095) ? minDaysToLtcg : 0;
        BigDecimal stcgTaxEst = stcgGains.multiply(BigDecimal.valueOf(0.20));

        return new AggregatedHolding(
            schemeName, units, val, cost,
            ltcgVal, ltcgGains, stcgVal, stcgGains,
            finalDaysToNext, (int) ChronoUnit.DAYS.between(oldest, LocalDate.now()),
            category, "ACTIVE", isin, stcgTaxEst, liveNavDec
        );
    }
}
