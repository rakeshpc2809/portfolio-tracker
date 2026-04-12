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
        
        String cleanAmfiCode = amfiCode.trim().replaceFirst("^0+(?!$)", "");
        List<HistoricalNav> fundHistory = navRepo.findByAmfiCodeOrderByNavDateDesc(cleanAmfiCode);
        
        String cleanBenchmark = benchmark.trim().toUpperCase();
        List<IndexFundamentals> benchmarkHistory = indexRepo.findByIndexNameOrderByDateDesc(cleanBenchmark);

        if (fundHistory.size() > 756) fundHistory = fundHistory.subList(0, 756);
        if (benchmarkHistory.size() > 756) benchmarkHistory = benchmarkHistory.subList(0, 756);

        Map<String, Object> response = new HashMap<>();
        response.put("fund", fundHistory);
        response.put("benchmark", benchmarkHistory);
        return ResponseEntity.ok(response);
    }
}
