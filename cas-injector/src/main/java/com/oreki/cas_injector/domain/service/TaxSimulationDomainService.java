package com.oreki.cas_injector.domain.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.oreki.cas_injector.domain.model.TaxLotDomain;
import com.oreki.cas_injector.taxmanagement.dto.TaxSimulationResult;

public class TaxSimulationDomainService {

    private static final double EQUITY_STCG_RATE = 0.20;
    private static final double EQUITY_LTCG_RATE = 0.125;
    private static final double MAX_ACCEPTABLE_TAX_DRAG = 0.02;

    public TaxSimulationResult calculateTaxFriction(List<TaxLotDomain> openLots, double targetSellAmount, double currentNav, double slabRate) {
        if (openLots.isEmpty()) {
            return new TaxSimulationResult(targetSellAmount, 0, 0, 0, 0, false, false);
        }
        double unitsToSell = targetSellAmount / currentNav;
        return calculate(openLots, unitsToSell, currentNav, slabRate, targetSellAmount);
    }

    public TaxSimulationResult simulateHifoExit(List<TaxLotDomain> lots, String category, double slabRate) {
        if (lots == null || lots.isEmpty()) {
            return new TaxSimulationResult(0, 0, 0, 0, 0, false, false);
        }

        double estimatedCurrentNav = lots.get(0).getPurchasePrice() * 1.10;
        double totalUnits = lots.stream().mapToDouble(TaxLotDomain::getRemainingUnits).sum();
        double totalValue = totalUnits * estimatedCurrentNav;

        return calculate(lots, totalUnits, estimatedCurrentNav, slabRate, totalValue);
    }

    private TaxSimulationResult calculate(List<TaxLotDomain> lots, double unitsToSell, double currentNav, double slabRate, double totalValue) {
        double remainingUnitsToSell = unitsToSell;
        double stcgProfit = 0.0;
        double ltcgProfit = 0.0;

        boolean isEquity = lots.get(0).getAssetCategory() != null && 
                           lots.get(0).getAssetCategory().toUpperCase().contains("EQUITY");

        for (TaxLotDomain lot : lots) {
            if (remainingUnitsToSell <= 0) break;

            double unitsConsumedFromLot = Math.min(lot.getRemainingUnits(), remainingUnitsToSell);
            double profitFromLot = (currentNav - lot.getPurchasePrice()) * unitsConsumedFromLot;
            
            long daysHeld = ChronoUnit.DAYS.between(lot.getPurchaseDate(), LocalDate.now());

            if (isEquity) {
                if (daysHeld < 365) {
                    stcgProfit += profitFromLot;
                } else {
                    ltcgProfit += profitFromLot;
                }
            } else {
                stcgProfit += profitFromLot;
            }

            remainingUnitsToSell -= unitsConsumedFromLot;
        }

        double estimatedTax = 0.0;
        if (isEquity) {
            estimatedTax += Math.max(0, stcgProfit) * EQUITY_STCG_RATE;
            estimatedTax += Math.max(0, ltcgProfit) * EQUITY_LTCG_RATE;
        } else {
            estimatedTax += Math.max(0, stcgProfit) * slabRate;
        }

        double taxDragPercentage = totalValue > 0 ? estimatedTax / totalValue : 0;
        boolean isTaxLocked = taxDragPercentage > MAX_ACCEPTABLE_TAX_DRAG;

        return new TaxSimulationResult(totalValue, stcgProfit, ltcgProfit, estimatedTax, taxDragPercentage, isTaxLocked, !isEquity);
    }
}
