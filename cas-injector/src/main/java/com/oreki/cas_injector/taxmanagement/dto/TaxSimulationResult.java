package com.oreki.cas_injector.taxmanagement.dto;

public record TaxSimulationResult(
    double totalWithdrawalAmount,
    double stcgProfit,
    double ltcgProfit,
    double estimatedTaxLiability,
    double taxDragPercentage, // How much of your withdrawal is eaten by tax
    boolean isTaxLocked // A flag to stop the trade if friction is too high
) {}