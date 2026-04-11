package com.oreki.cas_injector.convictionmetrics.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class HmmRegimeService {

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;

    @Value("${casparser.url:http://cas-parser:8000}")
    private String casparserUrl;

    @Value("${hmm.enabled:false}")
    private boolean hmmEnabled;

    public void computeAndPersistHmmStates() {
        if (!hmmEnabled) {
            log.info("HMM disabled via feature flag.");
            return;
        }

        String amfiSql = """
            SELECT amfi_code
            FROM fund_history
            GROUP BY amfi_code
            HAVING COUNT(*) >= 253
            """;
        List<String> amfiCodes = jdbcTemplate.queryForList(amfiSql, String.class);
        log.info("🧠 Computing HMM regimes for {} funds...", amfiCodes.size());

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

                double[] returns = new double[252];
                for (int i = 0; i < 252; i++) {
                    returns[i] = Math.log(navs.get(i + 1) / navs.get(i));
                }

                // POST to cas-parser
                String url = casparserUrl + "/hmm/fit";
                Map<String, Object> request = Map.of(
                    "amfi_code", amfi,
                    "returns", returns
                );

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

                Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);

                if (response != null) {
                    String state = (String) response.get("current_state");
                    double bullProb = ((Number) response.get("calm_bull_prob")).doubleValue();
                    double bearProb = ((Number) response.get("volatile_bear_prob")).doubleValue();
                    double transBear = ((Number) response.get("transition_to_bear_prob")).doubleValue();

                    jdbcTemplate.update("""
                        UPDATE fund_conviction_metrics
                        SET hmm_state = ?,
                            hmm_bull_prob = ?,
                            hmm_bear_prob = ?,
                            hmm_transition_bear = ?
                        WHERE amfi_code = ?
                        AND calculation_date = (SELECT MAX(calculation_date) FROM fund_conviction_metrics WHERE amfi_code = ?)
                        """, state, bullProb, bearProb, transBear, amfi, amfi);
                }

            } catch (Exception e) {
                log.warn("⚠️ HMM calculation failed for AMFI {}: {}", amfi, e.getMessage());
                // Fallback to defaults already in DB if fitting fails
            }
        }
        log.info("✅ HMM Regime Engine complete.");
    }
}
