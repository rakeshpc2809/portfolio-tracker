package com.oreki.cas_injector.taxmanagement.dto;

public record TaxSimulationResult(
    double sellAmount,
    double stcgProfit,
    double ltcgProfit,
    double estimatedTax,
    double taxDragPercentage,
    boolean isTaxLocked,
    boolean isDebt  // true for non-equity funds
) {
    public boolean hasStcg() {
        return stcgProfit > 0;
    }
}
