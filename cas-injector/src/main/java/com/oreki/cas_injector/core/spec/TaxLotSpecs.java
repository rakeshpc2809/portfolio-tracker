package com.oreki.cas_injector.core.spec;

import com.oreki.cas_injector.domain.spec.Specification;
import com.oreki.cas_injector.transactions.model.TaxLot;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class TaxLotSpecs {

    public static Specification<TaxLot> isEquity(String category) {
        return lot -> category != null && category.toUpperCase().contains("EQUITY");
    }

    public static Specification<TaxLot> isDebt(String category) {
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

    public static Specification<TaxLot> heldLongerThan(int days) {
        return lot -> ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now()) >= days;
    }

    public static Specification<TaxLot> isLtcgEligible(String category) {
        return isEquity(category).and(heldLongerThan(365))
            .or(
                notEquityOrDebt(category).and(lot -> {
                    if (category != null && category.toUpperCase().contains("HYBRID")) {
                        return ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now()) >= 730;
                    } else if (lot.getBuyDate().isBefore(LocalDate.of(2023, 4, 1))) {
                        return ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now()) >= 1095;
                    }
                    return false;
                })
            );
    }
    
    private static Specification<TaxLot> notEquityOrDebt(String category) {
        return lot -> !isEquity(category).isSatisfiedBy(lot) && !isDebt(category).isSatisfiedBy(lot);
    }

    public static Specification<TaxLot> isStcgEligible(String category) {
        return isLtcgEligible(category).not();
    }
    
    // Note: Debt funds bought post April 1, 2023 are always STCG (slab rate)
    // The isStcgEligible handles this implicitly because isLtcgEligible is false for new debt.
}
