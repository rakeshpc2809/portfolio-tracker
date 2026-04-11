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
    private static final double MEAN_REVERTING_THRESHOLD = 0.47;
    private static final double TRENDING_THRESHOLD       = 0.53;
    private static final int    LOOKBACK_DAYS            = 252;
    private static final int    LOOKBACK_SHORT           = 20;
    private static final int    LOOKBACK_MID             = 60;

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
                double[] returnsAll = new double[LOOKBACK_DAYS];
                for (int i = 0; i < LOOKBACK_DAYS; i++) {
                    double prevNav = navs.get(i);
                    double todayNav = navs.get(i + 1);
                    if (prevNav > 0) {
                        returnsAll[i] = Math.log(todayNav / prevNav);
                    }
                }

                // Multi-scale windows
                double[] returnsShort = Arrays.copyOfRange(returnsAll, LOOKBACK_DAYS - LOOKBACK_SHORT, LOOKBACK_DAYS);
                double[] returnsMid   = Arrays.copyOfRange(returnsAll, LOOKBACK_DAYS - LOOKBACK_MID, LOOKBACK_DAYS);

                double hurstShort = returnsShort.length >= LOOKBACK_SHORT ? calculateHurst(returnsShort) : 0.5;
                double hurstMid   = returnsMid.length >= LOOKBACK_MID ? calculateHurst(returnsMid) : 0.5;
                double hurst      = calculateHurst(returnsAll);

                double volTax            = calculateVolatilityTax(returnsAll);
                String regime            = classifyRegime(hurst);
                String multiScaleRegime  = classifyMultiScaleRegime(hurstShort, hurstMid, hurst);
                double rarityPct         = calculateHistoricalRarityPct(returnsAll);

                // 3. Persist to fund_conviction_metrics (latest calculation_date row)
                jdbcTemplate.update("""
                    UPDATE fund_conviction_metrics
                    SET hurst_exponent        = ?,
                        volatility_tax        = ?,
                        hurst_regime          = ?,
                        historical_rarity_pct = ?,
                        hurst_20d             = ?,
                        hurst_60d             = ?,
                        multi_scale_regime    = ?
                    WHERE amfi_code = ?
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics WHERE amfi_code = ?)
                    """, hurst, volTax, regime, rarityPct, hurstShort, hurstMid, multiScaleRegime, amfi, amfi);

                success++;
            } catch (Exception e) {
                log.warn("⚠️ Hurst calculation failed for AMFI {}: {}", amfi, e.getMessage());
            }
        }
        log.info("✅ Hurst Engine complete. Processed {}/{} funds.", success, amfiCodes.size());
    }

    private String classifyMultiScaleRegime(double h20, double h60, double h252) {
        if (h20 > 0.55 && h60 > 0.55 && h252 > 0.55) return "FRACTAL_BREAKOUT";
        if (h20 > 0.75) return "STRONG_HOLD";
        if (h20 > 0.55 && h252 < 0.50) return "MEAN_REVERSION_RALLY";
        return classifyRegime(h252);
    }

    /**
     * R/S Analysis — Hurst Exponent calculation using corrected Rescaled Range.
     * Uses Anis-Lloyd correction for expected R/S of a random walk to reduce bias in small samples.
     * @param returns  Array of log daily returns (chronological order)
     * @return         Hurst exponent H ∈ [0, 1]
     */
    double calculateHurst(double[] returns) {
        int n = returns.length;
        if (n < 10) return 0.5;

        DescriptiveStatistics stats = new DescriptiveStatistics(returns);
        double mean = stats.getMean();
        double S = stats.getStandardDeviation();

        if (S == 0) return 0.5;

        // 1. Calculate Rescaled Range (R/S)
        double[] Y = new double[n];
        double cumDev = 0;
        for (int i = 0; i < n; i++) {
            cumDev += (returns[i] - mean);
            Y[i] = cumDev;
        }

        double max = -Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        for (double val : Y) {
            if (val > max) max = val;
            if (val < min) min = val;
        }
        double R = max - min;
        double rsObserved = R / S;

        // 2. Anis-Lloyd Correction: Expected R/S for a random walk of size n
        // E(R/S)_n = [ (n-0.5)/n * (n*pi/2)^-0.5 ] * sum_{r=1}^{n-1} [ (n-r)/r ]^0.5
        // A simpler but robust approximation for n > 20:
        double expectedRS = ((double) n - 0.5) / n * Math.sqrt(Math.PI * n / 2.0);
        
        // 3. H = 0.5 + log(rsObserved / expectedRS) / log(n)
        // This anchors the result to 0.5 for random walks and measures deviation.
        double h = 0.5 + (Math.log(rsObserved) - Math.log(expectedRS)) / Math.log(n);
        
        return Math.max(0.0, Math.min(1.0, h));
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
