package com.oreki.cas_injector.core.controller;

import com.oreki.cas_injector.core.model.IndexFundamentals;
import com.oreki.cas_injector.core.repository.IndexFundamentalsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/market")
@RequiredArgsConstructor
public class MarketDataController {

    private final IndexFundamentalsRepository indexRepo;

    @PostMapping("/index-history")
    public ResponseEntity<String> saveIndexHistory(@RequestBody List<IndexFundamentals> history) {
        for (IndexFundamentals item : history) {
            indexRepo.findByIndexNameAndDate(item.getIndexName(), item.getDate())
                .ifPresentOrElse(
                    existing -> {
                        existing.setClosingPrice(item.getClosingPrice());
                        indexRepo.save(existing);
                    },
                    () -> indexRepo.save(item)
                );
        }
        return ResponseEntity.ok("Successfully synced " + history.size() + " data points.");
    }
}
