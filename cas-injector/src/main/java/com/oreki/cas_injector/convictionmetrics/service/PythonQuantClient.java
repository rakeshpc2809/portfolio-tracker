package com.oreki.cas_injector.convictionmetrics.service;

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

    @Qualifier("hmmRestTemplate")
    private final RestTemplate restTemplate;

    @Value("${casparser.url:http://cas-parser:8000}")
    private String casparserUrl;

    public record QuantAnalyzeRequest(
        String amfi_code,
        double[] navs,
        double[] returns
    ) {}

    public record QuantAnalyzeResponse(
        double hurst,
        double ou_half_life,
        boolean ou_valid,
        int hmm_state,
        double bull_prob,
        double bear_prob,
        double transition_to_bear
    ) {}

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
}
