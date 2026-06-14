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
    private final StockSyncService   syncSvc;
    private final StockAggregationService aggSvc;


    @DeleteMapping("/purge")
    public ResponseEntity<?> purgeData(@RequestParam("pan") String pan) {
        validatePan(pan);
        log.info("Stock purge requested for PAN: {} (No-op as stock importing is disabled)", pan);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Stock feature is currently read-only"));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> syncPrices(@RequestParam("pan") String pan) {
        validatePan(pan);
        int updated = syncSvc.syncLivePrices(pan);
        Map<String, Object> resp = new HashMap<>();
        resp.put("synced", updated);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/portfolio")
    public ResponseEntity<List<StockHoldingDTO>> getPortfolio(@RequestParam("pan") String pan) {
        validatePan(pan);
        return ResponseEntity.ok(aggSvc.getPortfolio(pan));
    }

    private void validatePan(String pan) {
        org.springframework.security.core.Authentication auth = 
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()) || !auth.getName().equalsIgnoreCase(pan)) {
            throw new org.springframework.security.access.AccessDeniedException("Unauthorized access to PAN: " + pan);
        }
    }
}
