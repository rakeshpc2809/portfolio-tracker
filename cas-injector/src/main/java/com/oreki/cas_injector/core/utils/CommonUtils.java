package com.oreki.cas_injector.core.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.oreki.cas_injector.transactions.dto.TransactionDTO;

import com.fasterxml.jackson.databind.JsonNode;

public class CommonUtils {

// Sanitizes strings for database consistency
    public static final Function<String, String> SANITIZE = s -> 
        s == null ? "" : s.trim().toUpperCase();

    public static final Function<BigDecimal, BigDecimal> SCALE_MONEY = b -> 
        (b == null ? BigDecimal.ZERO : b)
            .setScale(2, RoundingMode.HALF_UP);

    // Safely converts JSON nodes to BigDecimal
    public static final Function<JsonNode, BigDecimal> TO_DECIMAL = node -> 
        (node == null || node.isMissingNode() || node.isNull()) 
            ? BigDecimal.ZERO 
            : new BigDecimal(node.asText());

    // Calculates NAV/Price per unit
    public static final BiFunction<BigDecimal, BigDecimal, BigDecimal> CALC_NAV = (amt, units) -> 
        (units == null || units.compareTo(BigDecimal.ZERO) == 0) 
            ? BigDecimal.ZERO 
            : amt.abs().divide(units.abs(), 4, RoundingMode.HALF_UP);

    // Generates the Idempotency Hash
    public static final BiFunction<JsonNode, Long, String> GENERATE_HASH = (node, schemeId) -> {
        try {
            String raw = node.path("date").asText() + 
                         node.path("description").asText().trim().toUpperCase() + 
                         node.path("amount").asText() + 
                         node.path("units").asText() + 
                         schemeId;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hash generation failed", e);
        }
    };


    private static double calculateNpv(double rate, List<TransactionDTO> txs) {
        double npv = 0.0;
        for (TransactionDTO tx : txs) {
            // Convert BigDecimal to double for the math
            double amount = tx.getAmount().doubleValue(); 
            long days = ChronoUnit.DAYS.between(txs.get(0).getDate(), tx.getDate());
            // Use annualization: (1 + rate)^(days/365.25)
            npv += amount / Math.pow(1.0 + rate, days / 365.25);
        }
        return npv;
    }

    public static final Function<List<TransactionDTO>, BigDecimal> SOLVE_XIRR = txs -> {
        if (txs == null || txs.size() < 2) return BigDecimal.ZERO;

        double rate = 0.1; // Initial guess 10%
        
        // Phase 1: Newton-Raphson for fast convergence
        for (int i = 0; i < 30; i++) {
            double epsilon = 0.0001;
            double npv = calculateNpv(rate, txs);
            double derivative = (calculateNpv(rate + epsilon, txs) - npv) / epsilon;
            
            if (Math.abs(derivative) < 1e-7) break; 
            rate = rate - (npv / derivative);
        }
        
        // Phase 2: Robustness fallback (Bisection) for non-converged or extreme cases
        if (!Double.isFinite(rate) || rate < -0.99 || rate > 10.0) { // Bound at 1000% return
            double lo = -0.99, hi = 10.0;
            for (int j = 0; j < 50; j++) {
                double mid = (lo + hi) / 2.0;
                if (calculateNpv(mid, txs) > 0) lo = mid; else hi = mid;
            }
            rate = (lo + hi) / 2.0;
        }

        return BigDecimal.valueOf(rate * 100);
    };
    
    public static final Function<BigDecimal, FundStatus> GET_STATUS = units -> 
    (units != null && units.compareTo(new BigDecimal("0.001")) > 0) 
        ? FundStatus.ACTIVE 
        : FundStatus.REDEEMED;

    public static final Function<String, String> NORMALIZE_NAME = name -> {
        if (name == null) return "UNKNOWN FUND";
        String s = name.toUpperCase()
            .replaceAll("MUTUAL FUND", "")
            .replaceAll("DIRECT PLAN", "")
            .replaceAll("GROWTH", "")
            .replaceAll("DIRECT", "")
            .replaceAll(" FUND", "")
            .replaceAll("-", " ")
            .replaceAll("\\s+", " ")
            .trim();
        
        // Truncate to first 25 chars if still too long, but try to keep it readable
        if (s.length() > 28) {
            return s.substring(0, 25) + "...";
        }
        return s;
    };

    public static LocalDate getCurrentFyStart() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        if (today.getMonthValue() < 4) {
            year--;
        }
        return LocalDate.of(year, 4, 1);
    }

    private static long getDays(LocalDate buy, LocalDate sell) {
        return ChronoUnit.DAYS.between(buy, sell);
    }

    /**
     * Tri-Function to determine the Tax Category based on:
     * 1. Purchase Date (Important for Debt/Gold post-April 2023)
     * 2. Sale Date
     * 3. Fund Category (Equity, Arbitrage, Gold, Debt)
     */
    public static final TriFunction<LocalDate, LocalDate, String, String> DETERMINE_TAX_CATEGORY = (buyDate, sellDate, category) -> {
        if (buyDate == null || sellDate == null || category == null) return "UNKNOWN";

        String cat = category.toUpperCase();
        long days = getDays(buyDate, sellDate);
        LocalDate cutoff2023 = LocalDate.of(2023, 4, 1);

        // --- RULE 1: EQUITY & ARBITRAGE ---
        // Arbitrage funds are taxed as Equity (65%+ exposure)
        if (cat.contains("EQUITY") || cat.contains("ARBITRAGE")) {
            return (days > 365) ? "EQUITY_LTCG" : "EQUITY_STCG";
        }

        // --- RULE 2: GOLD FoF & DEBT FUNDS ---
        if (cat.contains("GOLD") || cat.contains("DEBT") || cat.contains("BOND")) {
            
            // New 2023 Rule: If bought on/after April 1, 2023, 
            // no LTCG benefit exists (taxed at slab rate).
            if (!buyDate.isBefore(cutoff2023)) {
                return "SLAB_RATE_TAX";
            }

            // Old Rule: Units bought before April 1, 2023 
            // get LTCG benefit after 3 years (1095 days).
            return (days > 1095) ? "NON_EQUITY_LTCG_OLD" : "NON_EQUITY_STCG_OLD";
        }

        // --- RULE 3: OTHER HYBRIDS (Balanced/Conservative) ---
        // For funds with 35% to 65% Equity (post-2024 rules)
        if (cat.contains("BALANCED") || cat.contains("HYBRID")) {
             return (days > 730) ? "HYBRID_LTCG" : "SLAB_RATE_TAX";
        }

        return "OTHER_STCG";
    };

    /**
     * Maps common bucket/category keywords to canonical benchmark indices
     */
    public static final Map<String, String> CATEGORY_TO_BENCHMARK = Map.of(
        "LARGE",      "NIFTY 50",
        "MID",        "NIFTY MIDCAP 150",
        "SMALL",      "NIFTY SMALLCAP 250",
        "FLEXI",      "NIFTY 500",
        "DEBT",       "NIFTY 10 YR BENCHMARK G-SEC",
        "GOLD",       "GOLD_PRICE_INDEX"
    );

    public static final BiFunction<String, String, String> DETERMINE_BENCHMARK = (bucket, category) -> {
        String search = (bucket + " " + category).toUpperCase();
        return CATEGORY_TO_BENCHMARK.entrySet().stream()
            .filter(e -> search.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst().orElse("NIFTY 50");
    };

    /**
     * Sanitizes AMFI code by trimming and removing leading zeros.
     */
    public static final Function<String, String> SANITIZE_AMFI = amfi -> {
        if (amfi == null) return "";
        String s = amfi.trim();
        return s.replaceFirst("^0+(?!$)", "");
    };
}
