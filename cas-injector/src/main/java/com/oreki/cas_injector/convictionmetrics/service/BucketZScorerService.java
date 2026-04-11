package com.oreki.cas_injector.convictionmetrics.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BucketZScorerService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Computes peer-relative Z-scores for Sortino, WinRate, CVaR, MaxDD
     * grouped by the fund's strategy bucket (core/strategy/satellite/accumulator).
     * 
     * Writes a composite_quant_score back to fund_conviction_metrics.
     * 
     * WEIGHTS (from design doc):
     *   Sortino  : +0.35
     *   WinRate  : +0.25
     *   CVaR 5%  : -0.25  (lower is better — negate)
     *   MaxDD    : -0.15  (lower magnitude is better — negate)
     */
    public void computeBucketCqs() {
        boolean hasStrategyTable = false;
        try {
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM google_sheet_strategy", Integer.class);
            hasStrategyTable = true;
        } catch (Exception ignored) {
            log.warn("google_sheet_strategy table not accessible. Falling back to asset_category bucketing.");
        }

        String sql = hasStrategyTable
            ? """
              SELECT m.amfi_code, m.sortino_ratio, m.win_rate, m.cvar_5, m.max_drawdown,
                     COALESCE(gs.bucket, 'core') as bucket
              FROM fund_conviction_metrics m
              JOIN scheme s ON m.amfi_code = s.amfi_code
              LEFT JOIN google_sheet_strategy gs ON gs.isin = s.isin
              WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
              """
            : """
              SELECT m.amfi_code, m.sortino_ratio, m.win_rate, m.cvar_5, m.max_drawdown,
                     CASE
                       WHEN UPPER(s.asset_category) LIKE '%DEBT%'
                         OR UPPER(s.asset_category) LIKE '%GILT%'
                         OR UPPER(s.asset_category) LIKE '%LIQUID%'
                         OR UPPER(s.asset_category) LIKE '%ARBITRAGE%'
                         OR UPPER(s.asset_category) LIKE '%BOND%'
                         OR UPPER(s.asset_category) LIKE '%MONEY MARKET%'
                       THEN 'fixed_income'
                       ELSE 'equity'
                     END as bucket
              FROM fund_conviction_metrics m
              JOIN scheme s ON m.amfi_code = s.amfi_code
              WHERE m.calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics)
              """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        if (rows.isEmpty()) return;

        // Step 2: Group by bucket
        Map<String, List<Map<String, Object>>> byBucket = rows.stream()
            .collect(Collectors.groupingBy(r -> String.valueOf(r.getOrDefault("bucket", "core"))));

        for (Map.Entry<String, List<Map<String, Object>>> entry : byBucket.entrySet()) {
            List<Map<String, Object>> peers = entry.getValue();
            if (peers.size() < 2) {
                // For single fund buckets, set a neutral CQS
                for (Map<String, Object> fund : peers) {
                    updateCqs((String) fund.get("amfi_code"), 50, 1);
                }
                continue;
            }

            // Step 3: Compute bucket mean & stddev for each metric
            double[] sortinos  = peers.stream().mapToDouble(r -> safeDouble(r.get("sortino_ratio"))).toArray();
            double[] winRates  = peers.stream().mapToDouble(r -> safeDouble(r.get("win_rate"))).toArray();
            double[] cvars     = peers.stream().mapToDouble(r -> safeDouble(r.get("cvar_5"))).toArray();
            double[] maxDDs    = peers.stream().mapToDouble(r -> safeDouble(r.get("max_drawdown"))).toArray();

            double sortinoMean = mean(sortinos), sortinoStd = std(sortinos);
            double winMean     = mean(winRates),  winStd     = std(winRates);
            double cvarMean    = mean(cvars),      cvarStd    = std(cvars);
            double mddMean     = mean(maxDDs),     mddStd     = std(maxDDs);

            for (Map<String, Object> fund : peers) {
                String amfi = (String) fund.get("amfi_code");

                double zSortino = sortinoStd > 0 ? (safeDouble(fund.get("sortino_ratio")) - sortinoMean) / sortinoStd : 0;
                double zWin     = winStd > 0     ? (safeDouble(fund.get("win_rate")) - winMean) / winStd               : 0;
                double zCvar    = cvarStd > 0    ? (safeDouble(fund.get("cvar_5"))  - cvarMean) / cvarStd              : 0;
                double zMdd     = mddStd > 0     ? (safeDouble(fund.get("max_drawdown")) - mddMean) / mddStd          : 0;

                // CQS: positive = outperforming peers, negative = underperforming
                // Negate CVaR and MDD z-scores (higher raw = worse risk)
                double cqs = (0.35 * zSortino)
                           + (0.25 * zWin)
                           + (-0.25 * zZScore(safeDouble(fund.get("cvar_5")), cvarMean, cvarStd))   // lower CVaR is better
                           + (-0.15 * zZScore(safeDouble(fund.get("max_drawdown")), mddMean, mddStd));   // shallower drawdown is better

                // Scale to 0–100 for storage (raw CQS is typically -3 to +3)
                int cqsScore = (int) Math.max(0, Math.min(100, 50 + (cqs * 15)));

                updateCqs(amfi, cqsScore, peers.size());
                
                log.debug("CQS [{}] bucket={} cqs={} (sortino_z={:.2f}, win_z={:.2f}, cvar_z={:.2f}, mdd_z={:.2f})",
                    amfi, entry.getKey(), cqsScore, zSortino, zWin, zCvar, zMdd);
            }
        }
        log.info("✅ Bucket CQS scoring complete across {} buckets.", byBucket.size());
    }

    private void updateCqs(String amfi, int cqsScore, int peerCount) {
        jdbcTemplate.update("""
            UPDATE fund_conviction_metrics 
            SET composite_quant_score = ?, bucket_peer_count = ?
            WHERE amfi_code = ?
            AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics WHERE amfi_code = ?)
            """, cqsScore, peerCount, amfi, amfi);
    }

    private double zZScore(double val, double mean, double std) {
        return std > 0 ? (val - mean) / std : 0;
    }

    private double mean(double[] arr) {
        return Arrays.stream(arr).average().orElse(0);
    }
    private double std(double[] arr) {
        double m = mean(arr);
        return Math.sqrt(Arrays.stream(arr).map(x -> (x-m)*(x-m)).average().orElse(0));
    }
    private double safeDouble(Object o) { return o == null ? 0.0 : ((Number)o).doubleValue(); }
}
