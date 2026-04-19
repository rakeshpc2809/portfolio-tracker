package com.oreki.cas_injector.convictionmetrics.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PythonQuantClient {

    private final RestTemplate restTemplate;

    @Value("${casparser.url:http://cas-parser:8000}")
    private String casparserUrl;

    public record QuantAnalyzeRequest(
            String amfi_code,
            double[] navs,
            double[] returns) {
    }

    public record QuantAnalyzeResponse(
            String amfi_code,
            double hurst,
            double ou_half_life,
            boolean ou_valid,
            int hmm_state,
            double bull_prob,
            double bear_prob,
            double transition_to_bear) {
    }

    public record BatchAnalyzeRequest(List<QuantAnalyzeRequest> funds) {
    }

    public record BatchAnalyzeResponse(List<QuantAnalyzeResponse> results) {
    }

    public record PythonAggregatedHolding(String isin, String scheme_name, double current_value, double ltcg_amount,
            double stcg_amount, int days_to_next_ltcg, double nav) {
    }

    public record PythonStrategyTarget(String isin, String scheme_name, double target_portfolio_pct, double sip_pct,
            String status, String category) {
    }

    public record PythonMarketMetrics(String amfi_code, double conviction_score, double rolling_z_score_252,
            double hurst_exponent, String hurst_regime, String hmm_state, double hmm_transition_bear_prob,
            boolean ou_valid, double ou_half_life, double volatility_tax, double historical_rarity_pct) {
    }

    public record PythonRebalanceRequest(String pan, double total_portfolio_value, double fy_ltcg_already_realized,
            String tail_risk_level, List<PythonAggregatedHolding> holdings, List<PythonStrategyTarget> targets,
            Map<String, PythonMarketMetrics> metrics, Map<String, String> amfi_map) {
    }

    public record PythonTacticalSignal(String scheme_name, String amfi_code, String action, double amount,
            double planned_percentage, double actual_percentage, List<String> justifications, String fund_status) {
    }

    public record PythonScoringRequest(String amfi_code, double personal_cagr, double max_cagr_found,
            double tax_pct_of_value, String category, String phil_status, double sortino_ratio,
            double rolling_z_score_252, double max_drawdown, boolean ou_valid, double ou_half_life,
            double hmm_bear_prob, double expense_ratio, double aum_cr, double nav_percentile_1yr) {
    }

    public record PythonScoringResponse(String amfi_code, double yield_score, double risk_score, double value_score,
            double pain_recovery_score, double regime_score, double friction_score, double expense_score,
            double final_conviction_score) {
    }

    public QuantAnalyzeResponse analyze(String amfi, double[] navs, double[] returns) {
        try {
            String url = casparserUrl + "/api/v1/quant/analyze";
            QuantAnalyzeRequest request = new QuantAnalyzeRequest(amfi, navs, returns);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<QuantAnalyzeRequest> entity = new HttpEntity<>(request, headers);

            return restTemplate.postForObject(url, entity, QuantAnalyzeResponse.class);
        } catch (Exception e) {
            log.warn("⚠️ Python Quant analysis failed for AMFI {}: {}", amfi, e.getMessage());
            return null;
        }
    }

    public List<QuantAnalyzeResponse> analyzeBatch(List<QuantAnalyzeRequest> batch) {
        try {
            String url = casparserUrl + "/api/v1/quant/analyze-batch";
            BatchAnalyzeRequest request = new BatchAnalyzeRequest(batch);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<BatchAnalyzeRequest> entity = new HttpEntity<>(request, headers);

            BatchAnalyzeResponse response = restTemplate.postForObject(url, entity, BatchAnalyzeResponse.class);
            return response != null ? response.results() : List.of();
        } catch (Exception e) {
            log.error("❌ Batch Python Quant analysis failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<PythonTacticalSignal> rebalancePortfolio(PythonRebalanceRequest request) {
        try {
            String url = casparserUrl + "/api/v1/quant/rebalance";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<PythonRebalanceRequest> entity = new HttpEntity<>(request, headers);

            PythonTacticalSignal[] response = restTemplate.postForObject(url, entity, PythonTacticalSignal[].class);
            return response != null ? List.of(response) : List.of();
        } catch (Exception e) {
            log.error("❌ Python Quant rebalance failed: {}", e.getMessage());
            return List.of();
        }
    }

    public PythonScoringResponse scoreFund(PythonScoringRequest request) {
        try {
            String url = casparserUrl + "/api/v1/quant/score";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<PythonScoringRequest> entity = new HttpEntity<>(request, headers);

            return restTemplate.postForObject(url, entity, PythonScoringResponse.class);
        } catch (Exception e) {
            log.error("❌ Python Quant scoring failed for AMFI {}: {}", request.amfi_code(), e.getMessage());
            return null;
        }
    }
}
