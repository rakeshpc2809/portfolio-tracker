package com.oreki.cas_injector.convictionmetrics.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrnsteinUhlenbeckService {

    private final JdbcTemplate jdbcTemplate;

    public record OUThresholds(double buyZ, double sellZ) {}

    /**
     * Computes and persists OU parameters + optimal thresholds for mean-reverting funds.
     */
    public void computeAndPersistOUMetrics() {
        String amfiSql = """
            SELECT amfi_code
            FROM fund_history
            GROUP BY amfi_code
            HAVING COUNT(*) >= 253
            """;
        List<String> amfiCodes = jdbcTemplate.queryForList(amfiSql, String.class);
        log.info("📐 Computing OU Metrics for {} funds...", amfiCodes.size());

        int success = 0;
        for (String amfi : amfiCodes) {
            try {
                String navSql = """
                    SELECT nav FROM (
                        SELECT nav, nav_date FROM fund_history
                        WHERE amfi_code = ?
                        ORDER BY nav_date DESC
                        LIMIT 253
                    ) sub ORDER BY nav_date ASC
                    """;
                List<Double> navs = jdbcTemplate.queryForList(navSql, Double.class, amfi);
                if (navs.size() < 253) continue;

                // 1. Convert to log returns
                double[] returns = new double[252];
                for (int i = 0; i < 252; i++) {
                    returns[i] = Math.log(navs.get(i + 1) / navs.get(i));
                }

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

                // 3. Compute OU parameters
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
        log.info("✅ OU Engine complete. Processed {}/{} funds.", success, amfiCodes.size());
    }
}
