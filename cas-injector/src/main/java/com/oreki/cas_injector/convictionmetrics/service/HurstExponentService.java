package com.oreki.cas_injector.convictionmetrics.service;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HurstExponentService {

    private final JdbcTemplate jdbcTemplate;

    // Regime thresholds — tuned for mutual fund daily NAV series
    private static final double MEAN_REVERTING_THRESHOLD = 0.45;
    private static final double TRENDING_THRESHOLD       = 0.55;
    private static final int    LOOKBACK_DAYS            = 252;

    /**
     * Computes and persists Hurst Exponent + Volatility Tax for all funds
     * that have at least LOOKBACK_DAYS of history.
     *
     * Called by QuantitativeEngineService as Step 5 of the nightly engine.
     */
    public void computeAndPersistHurstMetrics() {
        // 1. Fetch all AMFI codes with sufficient history
        String amfiSql = """
            SELECT amfi_code
            FROM fund_history
            GROUP BY amfi_code
            HAVING COUNT(*) >= ?
            """;
        List<String> amfiCodes = jdbcTemplate.queryForList(amfiSql, String.class, LOOKBACK_DAYS);
        log.info("🔬 Computing Hurst Exponent for {} funds...", amfiCodes.size());

        int success = 0;
        for (String amfi : amfiCodes) {
            try {
                // 2. Fetch last LOOKBACK_DAYS + 1 NAV values in ascending date order
                // Actually, the DESIGN.md says descending then reverse, I'll just fetch ascending.
                String navSql = """
                    SELECT nav FROM (
                        SELECT nav, nav_date FROM fund_history
                        WHERE amfi_code = ?
                        ORDER BY nav_date DESC
                        LIMIT ?
                    ) sub ORDER BY nav_date ASC
                    """;
                List<Double> navs = jdbcTemplate.queryForList(navSql, Double.class, amfi, LOOKBACK_DAYS + 1);
                if (navs.size() < LOOKBACK_DAYS + 1) continue;

                // Convert to log daily returns
                double[] returns = new double[LOOKBACK_DAYS];
                for (int i = 0; i < LOOKBACK_DAYS; i++) {
                    double prevNav = navs.get(i);
                    double todayNav = navs.get(i + 1);
                    if (prevNav > 0) {
                        returns[i] = Math.log(todayNav / prevNav);
                    }
                }

                double hurst       = calculateHurst(returns);
                double volTax      = calculateVolatilityTax(returns);
                String regime      = classifyRegime(hurst);
                double rarityPct   = calculateHistoricalRarityPct(returns);

                // 3. Persist to fund_conviction_metrics (latest calculation_date row)
                jdbcTemplate.update("""
                    UPDATE fund_conviction_metrics
                    SET hurst_exponent        = ?,
                        volatility_tax        = ?,
                        hurst_regime          = ?,
                        historical_rarity_pct = ?
                    WHERE amfi_code = ?
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
                    """, hurst, volTax, regime, rarityPct, amfi);

                success++;
            } catch (Exception e) {
                log.warn("⚠️ Hurst calculation failed for AMFI {}: {}", amfi, e.getMessage());
            }
        }
        log.info("✅ Hurst Engine complete. Processed {}/{} funds.", success, amfiCodes.size());
    }

    /**
     * R/S Analysis — Hurst Exponent calculation using Apache Commons Math.
     * @param returns  Array of log daily returns (chronological order)
     * @return         Hurst exponent H ∈ [0, 1]
     */
    double calculateHurst(double[] returns) {
        int n = returns.length;
        DescriptiveStatistics stats = new DescriptiveStatistics(returns);
        double mean = stats.getMean();
        double S = stats.getStandardDeviation();

        if (S == 0) return 0.5;

        // Cumulative deviation series
        double[] Y = new double[n];
        double cumDev = 0;
        for (int i = 0; i < n; i++) {
            cumDev += (returns[i] - mean);
            Y[i] = cumDev;
        }

        // Range (R) = max cumulative deviation − min cumulative deviation
        double max = Arrays.stream(Y).max().orElse(0);
        double min = Arrays.stream(Y).min().orElse(0);
        double R = max - min;

        if (R == 0) return 0.5;

        // H = log(R/S) / log(N)
        return Math.log(R / S) / Math.log(n);
    }

    /**
     * Volatility Tax = 2σ² (annualised).
     */
    double calculateVolatilityTax(double[] returns) {
        DescriptiveStatistics stats = new DescriptiveStatistics(returns);
        double variance = stats.getVariance();
        // Annualise: multiply daily variance by 252 trading days
        double annualisedVariance = variance * 252;
        return 2 * annualisedVariance;
    }

    /**
     * Returns what % of days over the lookback period had a return at least
     * as extreme (in absolute value) as the most recent day.
     */
    double calculateHistoricalRarityPct(double[] returns) {
        if (returns.length == 0) return 50.0;
        double latestReturn = returns[returns.length - 1];
        
        DescriptiveStatistics stats = new DescriptiveStatistics(returns);
        double mean = stats.getMean();
        double std  = stats.getStandardDeviation();
        double latestZ = std > 0 ? Math.abs((latestReturn - mean) / std) : 0;

        long extremeDays = 0;
        for (double r : returns) {
            if (std > 0 && Math.abs((r - mean) / std) >= latestZ) {
                extremeDays++;
            }
        }
        return (double) extremeDays / returns.length * 100.0;
    }

    String classifyRegime(double h) {
        if (h < MEAN_REVERTING_THRESHOLD) return "MEAN_REVERTING";
        if (h > TRENDING_THRESHOLD)       return "TRENDING";
        return "RANDOM_WALK";
    }
}
