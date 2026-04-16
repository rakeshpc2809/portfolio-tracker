package com.oreki.cas_injector.convictionmetrics.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.oreki.cas_injector.backfill.service.NavService;
import com.oreki.cas_injector.convictionmetrics.repository.ConvictionMetricsRepository;
import com.oreki.cas_injector.core.dto.SchemeDetailsDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BucketZScorerService {

    private final JdbcTemplate jdbcTemplate;
    private final ConvictionMetricsRepository convictionMetricsRepository;
    private final NavService navService;

    /**
     * Computes peer-relative Z-scores for Sortino, WinRate, CVaR, MaxDD
     * grouped by the fund's strategy bucket.
     */
    public void computeBucketCqs() {
        List<Map<String, Object>> metrics = convictionMetricsRepository.findAllMap();
        if (metrics.isEmpty()) return;

        log.info("🧮 Computing CQS for {} total funds in fund_conviction_metrics", metrics.size());

        // Step 1: Group by category using NavService cache
        Map<String, List<Map<String, Object>>> byBucket = metrics.stream()
            .collect(Collectors.groupingBy(m -> {
                String amfi = (String) m.get("amfi_code");
                SchemeDetailsDTO details = navService.getLatestSchemeDetails(amfi);
                String cat = details.getCategory().toUpperCase();
                
                // Broad buckets to ensure enough peers
                if (cat.contains("ELSS") || cat.contains("TAX")) return "TAX_SAVER";
                if (cat.contains("INDEX") || cat.contains("ETF")) return "INDEX_PASSIVE";
                if (cat.contains("SMALL CAP")) return "SMALL_CAP";
                if (cat.contains("MID CAP")) return "MID_CAP";
                if (cat.contains("LARGE CAP")) return "LARGE_CAP";
                if (cat.contains("DEBT") || cat.contains("BOND") || cat.contains("GILT") || cat.contains("LIQUID")) return "FIXED_INCOME";
                if (cat.contains("HYBRID") || cat.contains("BALANCED") || cat.contains("ARBITRAGE")) return "HYBRID";
                
                return "OTHER_EQUITY";
            }));

        for (Map.Entry<String, List<Map<String, Object>>> entry : byBucket.entrySet()) {
            List<Map<String, Object>> peers = entry.getValue();
            String bucketName = entry.getKey();
            
            if (peers.size() < 2) {
                for (Map<String, Object> fund : peers) {
                    updateCqs((String) fund.get("amfi_code"), 50, 1);
                }
                continue;
            }

            // Step 2: Compute stats
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

                double zSortino = zZScore(safeDouble(fund.get("sortino_ratio")), sortinoMean, sortinoStd);
                double zWin     = zZScore(safeDouble(fund.get("win_rate")), winMean, winStd);
                double zCvar    = zZScore(safeDouble(fund.get("cvar_5")), cvarMean, cvarStd);
                double zMdd     = zZScore(safeDouble(fund.get("max_drawdown")), mddMean, mddStd);

                // CQS Weights
                double cqs = (0.35 * zSortino)
                           + (0.25 * zWin)
                           + (-0.25 * zCvar) // lower CVaR (more negative) is worse, so we want high (less negative) CVaR
                           + (-0.15 * zMdd);  // lower MDD (more negative) is worse

                // Scale to 0–100 (raw CQS is typically -3 to +3)
                int cqsScore = (int) Math.max(5, Math.min(95, 50 + (cqs * 15)));

                updateCqs(amfi, cqsScore, peers.size());
            }
            log.info("  ├─ Bucket {}: Scored {} funds.", bucketName, peers.size());
        }
        log.info("✅ Bucket CQS scoring complete.");
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
        return std > 0.0001 ? (val - mean) / std : 0;
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
