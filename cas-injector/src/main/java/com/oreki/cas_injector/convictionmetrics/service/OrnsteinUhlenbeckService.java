package com.oreki.cas_injector.convictionmetrics.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrnsteinUhlenbeckService {

    private final JdbcTemplate jdbcTemplate;

    public record OUThresholds(double buyZ, double sellZ) {}

    /**
     * Computes and persists OU parameters + optimal thresholds for mean-reverting funds.
     */
    public void computeAndPersistOUMetrics(Map<String, double[]> returnsCache) {
        log.info("📐 Computing OU Metrics for {} funds from cache...", returnsCache.size());

        int success = 0;
        for (var entry : returnsCache.entrySet()) {
            String amfi = entry.getKey();
            try {
                double[] returns = entry.getValue();
                if (returns.length < 252) continue;

                // 2. Fit AR(1) OLS: X_t = alpha + beta * X_{t-1} + epsilon
                double n = 251;
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
                for (int i = 0; i < 251; i++) {
                    double x = returns[i];
                    double y = returns[i + 1];
                    sumX += x; sumY += y;
                    sumXY += x * y;
                    sumX2 += x * x;
                }
                double beta = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
                double alpha = (sumY - beta * sumX) / n;

                // 3. Compute OU parameters (with unit-root guard)
                if (Math.abs(beta) >= 0.9999 || Math.abs(beta) < 1e-9 || Math.abs(1.0 - beta) < 1e-6) {
                    log.debug("⏩ Skipping AMFI {}: Series is a random walk or non-stationary (beta={})", amfi, beta);
                    jdbcTemplate.update("""
                        UPDATE fund_conviction_metrics
                        SET ou_valid = false
                        WHERE amfi_code = ?
                        AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics WHERE amfi_code = ?)
                        """, amfi, amfi);
                    continue;
                }

                double dt = 1.0 / 252.0;  // daily step
                double theta = -Math.log(Math.abs(beta)) / dt;   // speed of reversion
                double mu = alpha / (1.0 - beta);                 // long-run mean
                
                // Compute residuals and their std dev (sigmaEpsilon)
                double sumResSq = 0;
                for (int i = 0; i < 251; i++) {
                    double pred = alpha + beta * returns[i];
                    double res = returns[i + 1] - pred;
                    sumResSq += res * res;
                }
                double sigmaEpsilon = Math.sqrt(sumResSq / (n - 2));
                double sigma = sigmaEpsilon / Math.sqrt(dt);

                double halfLife = Math.log(2.0) / Math.max(theta, 0.001);
                boolean valid = halfLife >= 5 && halfLife <= 180;

                // 4. Compute optimal thresholds
                double spread = Math.sqrt(sigmaEpsilon * sigmaEpsilon / (2.0 * Math.max(theta, 0.001)));
                double buyZ = Math.max(-3.0, Math.min(-0.8, -spread));
                double sellZ = Math.min( 3.0, Math.max( 0.8,  spread));

                // 5. UPDATE fund_conviction_metrics
                jdbcTemplate.update("""
                    UPDATE fund_conviction_metrics
                    SET ou_theta = ?,
                        ou_mu = ?,
                        ou_sigma = ?,
                        ou_half_life = ?,
                        ou_valid = ?,
                        ou_buy_threshold = ?,
                        ou_sell_threshold = ?
                    WHERE amfi_code = ?
                    AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics WHERE amfi_code = ?)
                    """, theta, mu, sigma, halfLife, valid, buyZ, sellZ, amfi, amfi);

                success++;
            } catch (Exception e) {
                log.warn("⚠️ OU calculation failed for AMFI {}: {}", amfi, e.getMessage());
            }
        }
        log.info("✅ OU Engine complete. Processed {}/{} funds.", success, returnsCache.size());
    }
}
