package com.oreki.cas_injector.stocks;

import com.oreki.cas_injector.stocks.model.Stock;
import com.oreki.cas_injector.stocks.model.StockPriceEod;
import com.oreki.cas_injector.stocks.repository.StockRepository;
import com.oreki.cas_injector.stocks.repository.StockPriceEodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockSyncService {
    private static final Logger log = LoggerFactory.getLogger(StockSyncService.class);

    private final StockRepository stockRepo;
    private final StockPriceEodRepository priceRepo;
    private final RestTemplate rest;

    @Value("${indstocks.api.base-url:https://api.indstocks.com}")
    private String baseUrl;
    @Value("${indstocks.api.token:}")
    private String token;

    public int syncLivePrices(String pan) {
        List<Stock> stocks = stockRepo.findByFolioInvestorPan(pan);
        if (stocks.isEmpty()) return 0;

        String codes = stocks.stream()
            .map(s -> s.getExchange() + "_" + s.getIsin())
            .collect(Collectors.joining(","));

        log.info("🔄 Syncing live prices for {} stocks...", stocks.size());

        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", token);
        }

        try {
            var resp = rest.exchange(
                baseUrl + "/market/quotes/ltp?scrip-codes=" + codes,
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            
            if (resp.getBody() == null || !resp.getBody().containsKey("data")) {
                log.warn("Price sync API returned empty data");
                return 0;
            }
            
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            LocalDate today = LocalDate.now();
            int updatedCount = 0;

            for (Stock stock : stocks) {
                String lookupKey = stock.getExchange() + "_" + stock.getIsin();
                if (data.containsKey(lookupKey)) {
                    Map<String, Object> quote = (Map<String, Object>) data.get(lookupKey);
                    double ltp = ((Number) quote.get("ltp")).doubleValue();

                    priceRepo.save(StockPriceEod.builder()
                            .ticker(stock.getTicker())
                            .priceDate(today)
                            .closePrice(BigDecimal.valueOf(ltp))
                            .build());
                    updatedCount++;
                }
            }
            return updatedCount;
        } catch (Exception e) {
            log.error("Live price sync failed for codes {}: {}", codes, e.getMessage());
            return 0;
        }
    }
}
