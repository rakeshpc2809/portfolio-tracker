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
    public void computeAndPersistOUMetrics(Map<String, double[]> navsCache) {
        log.info("📐 Computing OU Metrics for {} funds from price series cache...", navsCache.size());

        int success = 0;
        for (var entry : navsCache.entrySet()) {
            String amfi = entry.getKey();
            try {
                double[] navs = entry.getValue();
                if (navs.length < 252) continue;

                // 1. Transform to log-prices
                double[] x = new double[navs.length];
                for (int i = 0; i < navs.length; i++) {
                    x[i] = Math.log(Math.max(navs[i], 0.001));
                }

                // 2. Fit AR(1) OLS: X_t = alpha + beta * X_{t-1} + epsilon
                double n = x.length - 1;
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
                for (int i = 0; i < n; i++) {
                    sumX += x[i];
                    sumY += x[i+1];
                    sumXY += x[i] * x[i+1];
                    sumX2 += x[i] * x[i];
                }
                double beta = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
                double alpha = (sumY - beta * sumX) / n;

                // 3. Compute OU parameters
                // Mutual funds are typically non-stationary (trending), so beta will be close to 1.
                // We are looking for "relative" mean reversion.
                if (beta >= 1.0 || beta <= 0.0) {
                    log.debug("⏩ Skipping AMFI {}: Series is trending or invalid (beta={})", amfi, beta);
                    continue;
                }

                double dt = 1.0 / 252.0;  
                double theta = -Math.log(beta) / dt;   
                double mu = alpha / (1.0 - beta);                 
                
                double sumResSq = 0;
                for (int i = 0; i < n; i++) {
                    double pred = alpha + beta * x[i];
                    double res = x[i + 1] - pred;
                    sumResSq += res * res;
                }
                double sigmaEpsilon = Math.sqrt(sumResSq / (n - 2));
                double sigma = sigmaEpsilon / Math.sqrt(dt);

                double halfLife = Math.log(2.0) / Math.max(theta, 0.001) * 252.0; // In days
                boolean valid = halfLife >= 2 && halfLife <= 500; // Looser bounds for prices

                // 4. Compute optimal thresholds (simplified based on volatility)
                double spread = sigma / Math.sqrt(2.0 * theta);
                double buyZ = -1.5 * spread; 
                double sellZ = 1.5 * spread; 

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
        log.info("✅ OU Engine complete. Processed {}/{} funds.", success, navsCache.size());
    }
}
