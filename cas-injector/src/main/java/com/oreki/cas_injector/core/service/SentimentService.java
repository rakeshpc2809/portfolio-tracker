package com.oreki.cas_injector.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentService {

    private final RestClient restClient = RestClient.create();

    @Value("${cas.parser.url}")
    private String parserUrl;

    /**
     * Submits a news headline for sentiment analysis.
     */
    @CircuitBreaker(name = "sentimentService", fallbackMethod = "fallbackAnalyzeSentiment")
    @Retry(name = "sentimentService")
    public Map<String, Object> analyzeSentiment(String text, Map<String, Object> metadata) {
        String url = parserUrl + "/api/v1/sentiment/analyze";
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        payload.put("metadata", metadata);

        Map<?, ?> result = restClient.post()
            .uri(url)
            .body(payload)
            .retrieve()
            .body(Map.class);
            
        return (Map<String, Object>) result;
    }

    public Map<String, Object> fallbackAnalyzeSentiment(String text, Map<String, Object> metadata, Throwable t) {
        log.warn("🛡️ Circuit breaker fallback activated for sentiment analysis! Error: {}", t.getMessage());
        return Map.of("error", "Sentiment analysis service is currently unavailable", "status", "fallback");
    }

    /**
     * Fetches the latest Alpha Signals (Mocked for now, will pull from Qdrant via Python)
     */
    public List<Map<String, Object>> getAlphaFeed() {
        // This will eventually call cas-parser to fetch from Qdrant
        return List.of(
            Map.of(
                "title", "FII inflows surge in Large-cap Mutual Funds",
                "sentiment", "positive",
                "confidence", 0.92,
                "timestamp", "2026-04-23T10:00:00Z"
            ),
            Map.of(
                "title", "New SEBI guidelines on mid-cap liquidity stress tests",
                "sentiment", "neutral",
                "confidence", 0.75,
                "timestamp", "2026-04-23T09:30:00Z"
            ),
            Map.of(
                "title", "Inflation data exceeds estimates, pressure on yield-sensitive funds",
                "sentiment", "negative",
                "confidence", 0.88,
                "timestamp", "2026-04-23T08:15:00Z"
            )
        );
    }
}
