package com.oreki.cas_injector.taxmanagement.service;


import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.oreki.cas_injector.taxmanagement.dto.OpenTaxLot;
import com.oreki.cas_injector.taxmanagement.dto.TaxSimulationResult;

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

    public TaxSimulationResult simulateSellOrder(String schemeName, double targetSellAmount, double currentNav) {
        log.info("🔍 Simulating FIFO Tax Friction for {} - Target Sell: ₹{}", schemeName, targetSellAmount);

        double unitsToSell = targetSellAmount / currentNav;
        
        // REWRITTEN SQL: Exactly matching your DBeaver schema using a JOIN
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
                        WHERE s.name = ?
                        AND tl.remaining_units > 0
                        AND tl.status = 'OPEN'
                        ORDER BY tl.buy_date ASC
            """;

        // Note: If you have multiple investors, you will also need to JOIN the `folio` or `investor` table here to filter by PAN.
        List<OpenTaxLot> openLots = jdbcTemplate.query(sql, (rs, rowNum) -> new OpenTaxLot(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getDouble("remaining_units"),
            rs.getDouble("cost_basis_per_unit"), // Mapped to exact DB column
            rs.getDate("buy_date").toLocalDate(), // Mapped to exact DB column
            rs.getString("asset_category")
        ), schemeName);

        double remainingUnitsToSell = unitsToSell;
        double stcgProfit = 0.0;
        double ltcgProfit = 0.0;
        
        // Safety check: If no lots found, abort simulation
        if (openLots.isEmpty()) {
            log.warn("No OPEN tax lots found for scheme: {}", schemeName);
            return new TaxSimulationResult(targetSellAmount, 0, 0, 0, 0, false);
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
            estimatedTax += Math.max(0, stcgProfit) * 0.30; 
        }

        double taxDragPercentage = estimatedTax / targetSellAmount;
        boolean isTaxLocked = taxDragPercentage > MAX_ACCEPTABLE_TAX_DRAG;

        return new TaxSimulationResult(targetSellAmount, stcgProfit, ltcgProfit, estimatedTax, taxDragPercentage, isTaxLocked);
    }
}