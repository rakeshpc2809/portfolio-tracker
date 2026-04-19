package com.oreki.cas_injector.domain.spec;

import com.oreki.cas_injector.domain.model.TaxLotDomain;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class TaxLotDomainSpecs {

    public static Specification<TaxLotDomain> isEquity(String category) {
        return lot -> category != null && category.toUpperCase().contains("EQUITY");
    }

    public static Specification<TaxLotDomain> isDebt(String category) {
        return lot -> {
            if (category == null) return false;
            String upper = category.toUpperCase();
            return upper.contains("DEBT") || upper.contains("GILT") || upper.contains("BOND")
                || upper.contains("LIQUID") || upper.contains("ARBITRAGE")
                || upper.contains("MONEY MARKET") || upper.contains("BANKING AND PSU")
                || upper.contains("CORPORATE") || upper.contains("OVERNIGHT")
                || upper.contains("ULTRA SHORT") || upper.contains("LOW DURATION");
        };
    }

    public static Specification<TaxLotDomain> heldLongerThan(int days) {
        return lot -> ChronoUnit.DAYS.between(lot.getPurchaseDate(), LocalDate.now()) >= days;
    }

    public static Specification<TaxLotDomain> isLtcgEligible(String category) {
        return isEquity(category).and(heldLongerThan(365))
            .or(
                notEquityOrDebt(category).and(lot -> {
                    if (category != null && category.toUpperCase().contains("HYBRID")) {
                        return ChronoUnit.DAYS.between(lot.getPurchaseDate(), LocalDate.now()) >= 730;
                    } else if (lot.getPurchaseDate().isBefore(LocalDate.of(2023, 4, 1))) {
                        return ChronoUnit.DAYS.between(lot.getPurchaseDate(), LocalDate.now()) >= 1095;
                    }
                    return false;
                })
            );
    }
    
    private static Specification<TaxLotDomain> notEquityOrDebt(String category) {
        return lot -> !isEquity(category).isSatisfiedBy(lot) && !isDebt(category).isSatisfiedBy(lot);
    }

    public static Specification<TaxLotDomain> isStcgEligible(String category) {
        return isLtcgEligible(category).not();
    }
}
