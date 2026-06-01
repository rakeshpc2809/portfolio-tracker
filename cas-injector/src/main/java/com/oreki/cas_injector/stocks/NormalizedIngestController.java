package com.oreki.cas_injector.stocks;

import com.oreki.cas_injector.stocks.dto.IngestRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class NormalizedIngestController {
    private static final Logger log = LoggerFactory.getLogger(NormalizedIngestController.class);
    private final StockImportService importSvc;

    @PostMapping("/v1/ingest/normalized-transactions")
    public ResponseEntity<?> ingestNormalizedTransactions(@RequestBody IngestRequest request) {
        try {
            log.info("📥 Received POST /v1/ingest/normalized-transactions with {} items", 
                request.getTransactions() != null ? request.getTransactions().size() : 0);
            int count = importSvc.importNormalizedTransactions(request.getPan(), request.getTransactions());
            return ResponseEntity.ok(Map.of("status", "success", "imported", count));
        } catch (Exception e) {
            log.error("Failed to ingest normalized transactions", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
