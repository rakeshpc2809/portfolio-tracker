package com.oreki.cas_injector.backfill.controller;

import com.oreki.cas_injector.backfill.model.HistoricalNav;
import com.oreki.cas_injector.backfill.repository.HistoricalNavRepository;
import com.oreki.cas_injector.core.model.IndexFundamentals;
import com.oreki.cas_injector.core.repository.IndexFundamentalsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
public class HistoricalDataController {

    private final HistoricalNavRepository navRepo;
    private final IndexFundamentalsRepository indexRepo;

    @GetMapping("/fund/{amfiCode}")
    public ResponseEntity<Map<String, Object>> getFundHistory(
            @PathVariable String amfiCode,
            @RequestParam(defaultValue = "NIFTY 50") String benchmark) {
        
        List<HistoricalNav> fundHistory = navRepo.findByAmfiCodeOrderByNavDateDesc(amfiCode);
        if (fundHistory.size() > 252) fundHistory = fundHistory.subList(0, 252);

        List<IndexFundamentals> benchmarkHistory = indexRepo.findByIndexNameOrderByDateDesc(benchmark);
        if (benchmarkHistory.size() > 252) benchmarkHistory = benchmarkHistory.subList(0, 252);

        Map<String, Object> response = new HashMap<>();
        response.put("fund", fundHistory);
        response.put("benchmark", benchmarkHistory);
        return ResponseEntity.ok(response);
    }
}
