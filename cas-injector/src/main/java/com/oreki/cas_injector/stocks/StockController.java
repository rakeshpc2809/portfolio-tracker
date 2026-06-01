package com.oreki.cas_injector.stocks;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stocks")
@RequiredArgsConstructor
public class StockController {
    private static final Logger log = LoggerFactory.getLogger(StockController.class);
    private final StockImportService importSvc;
    private final StockSyncService   syncSvc;
    private final StockAggregationService aggSvc;


    @DeleteMapping("/purge")
    public ResponseEntity<?> purgeData(@RequestParam("pan") String pan) {
        importSvc.purgeStockData(pan);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Stock data purged for " + pan));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> syncPrices(@RequestParam("pan") String pan) {
        int updated = syncSvc.syncLivePrices(pan);
        Map<String, Object> resp = new HashMap<>();
        resp.put("synced", updated);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/portfolio")
    public ResponseEntity<List<StockHoldingDTO>> getPortfolio(@RequestParam("pan") String pan) {
        return ResponseEntity.ok(aggSvc.getPortfolio(pan));
    }
}
