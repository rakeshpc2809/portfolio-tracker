package com.oreki.cas_injector.taxmanagement.service;


import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.taxmanagement.dto.OpenTaxLot;
import com.oreki.cas_injector.taxmanagement.dto.TaxSimulationResult;
import com.oreki.cas_injector.transactions.model.TaxLot;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TaxSimulatorService {

    private final JdbcTemplate jdbcTemplate;

    private static final double EQUITY_STCG_RATE = 0.20; // 20%
    private static final double EQUITY_LTCG_RATE = 0.125; // 12.5%
    private static final double MAX_ACCEPTABLE_TAX_DRAG = 0.02; // 2% Max friction

    public TaxSimulatorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TaxSimulationResult simulateSellOrder(String schemeName, double targetSellAmount, double currentNav, String investorPan) {
        log.info("🔍 Simulating HIFO Tax Friction for {} (PAN: {}) - Target: ₹{}", schemeName, investorPan, targetSellAmount);

        double unitsToSell = targetSellAmount / currentNav;
        
        String sql = """
                        SELECT
                            tl.id,
                            s.name,
                            tl.remaining_units,
                            tl.cost_basis_per_unit,
                            tl.buy_date,
                            s.asset_category  AS asset_category
                        FROM tax_lot tl
                        JOIN scheme s ON tl.scheme_id = s.id
                        JOIN folio f ON s.folio_id = f.id
                        WHERE s.name = ?
                        AND f.investor_pan = ?
                        AND tl.remaining_units > 0
                        AND tl.status = 'OPEN'
                        ORDER BY tl.cost_basis_per_unit DESC
            """;

        List<OpenTaxLot> openLots = jdbcTemplate.query(sql, (rs, rowNum) -> new OpenTaxLot(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getDouble("remaining_units"),
            rs.getDouble("cost_basis_per_unit"),
            rs.getDate("buy_date").toLocalDate(),
            rs.getString("asset_category")
        ), schemeName, investorPan);

        double remainingUnitsToSell = unitsToSell;
        double stcgProfit = 0.0;
        double ltcgProfit = 0.0;
        
        // Safety check: If no lots found, abort simulation
        if (openLots.isEmpty()) {
            log.warn("No OPEN tax lots found for scheme: {}", schemeName);
            return new TaxSimulationResult(targetSellAmount, 0, 0, 0, 0, false, false);
        }

        // Determine if equity or debt for tax bracket routing
        boolean isEquity = openLots.get(0).assetCategory() != null && 
                           openLots.get(0).assetCategory().toUpperCase().contains("EQUITY");

        for (OpenTaxLot lot : openLots) {
            if (remainingUnitsToSell <= 0) break;

            double unitsConsumedFromLot = Math.min(lot.remainingUnits(), remainingUnitsToSell);
            double profitFromLot = (currentNav - lot.purchasePrice()) * unitsConsumedFromLot;
            
            long daysHeld = ChronoUnit.DAYS.between(lot.purchaseDate(), LocalDate.now());

            if (isEquity) {
                if (daysHeld < 365) {
                    stcgProfit += profitFromLot;
                } else {
                    ltcgProfit += profitFromLot;
                }
            } else {
                stcgProfit += profitFromLot; // Default non-equity to STCG slab for worst-case modeling
            }

            remainingUnitsToSell -= unitsConsumedFromLot;
        }

        double estimatedTax = 0.0;
        if (isEquity) {
            estimatedTax += Math.max(0, stcgProfit) * EQUITY_STCG_RATE;
            estimatedTax += Math.max(0, ltcgProfit) * EQUITY_LTCG_RATE;
        } else {
            // Post-April 2023: Debt gains are always slab-taxed regardless of holding period.
            // We use 30% as the conservative upper bound — actual rate depends on investor's slab.
            double slabRate = 0.30; 
            estimatedTax += Math.max(0, stcgProfit) * slabRate;
        }

        double taxDragPercentage = estimatedTax / targetSellAmount;
        boolean isTaxLocked = taxDragPercentage > MAX_ACCEPTABLE_TAX_DRAG;

        return new TaxSimulationResult(targetSellAmount, stcgProfit, ltcgProfit, estimatedTax, taxDragPercentage, isTaxLocked, !isEquity);
    }

    public TaxSimulationResult simulateHifoExit(List<TaxLot> lots, String category) {
        if (lots == null || lots.isEmpty()) {
            return new TaxSimulationResult(0, 0, 0, 0, 0, false, false);
        }

        // We assume current NAV is roughly 10% higher than cost for scoring simulation
        double estimatedCurrentNav = lots.get(0).getCostBasisPerUnit().doubleValue() * 1.10;
        double totalValue = 0;
        double stcgProfit = 0;
        double ltcgProfit = 0;
        boolean isEquity = category != null && category.toUpperCase().contains("EQUITY");

        for (TaxLot lot : lots) {
            double units = lot.getRemainingUnits().doubleValue();
            double cost = lot.getCostBasisPerUnit().doubleValue();
            double value = units * estimatedCurrentNav;
            double profit = value - (units * cost);
            
            long daysHeld = ChronoUnit.DAYS.between(lot.getBuyDate(), LocalDate.now());
            if (isEquity) {
                if (daysHeld < 365) stcgProfit += profit;
                else ltcgProfit += profit;
            } else {
                stcgProfit += profit;
            }
            totalValue += value;
        }

        double estimatedTax = 0;
        if (isEquity) {
            estimatedTax += Math.max(0, stcgProfit) * EQUITY_STCG_RATE;
            estimatedTax += Math.max(0, ltcgProfit) * EQUITY_LTCG_RATE;
        } else {
            estimatedTax += Math.max(0, stcgProfit) * 0.30;
        }

        double taxDrag = totalValue > 0 ? estimatedTax / totalValue : 0;
        return new TaxSimulationResult(totalValue, stcgProfit, ltcgProfit, estimatedTax, taxDrag, taxDrag > MAX_ACCEPTABLE_TAX_DRAG, !isEquity);
    }
}
