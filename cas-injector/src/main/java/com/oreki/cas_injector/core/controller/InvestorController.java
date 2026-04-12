package com.oreki.cas_injector.core.controller;

import com.oreki.cas_injector.core.model.Investor;
import com.oreki.cas_injector.core.repository.InvestorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/investor")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InvestorController {

    private final InvestorRepository investorRepo;

    @GetMapping("/check/{pan}")
    public ResponseEntity<Map<String, String>> checkInvestor(@PathVariable String pan) {
        String cleanPan = pan.trim().toUpperCase();
        return investorRepo.findById(cleanPan)
            .map(investor -> ResponseEntity.ok(Map.of(
                "pan", investor.getPan(),
                "name", investor.getName() != null ? investor.getName() : "Investor",
                "email", investor.getEmail() != null ? investor.getEmail() : ""
            )))
            .orElse(ResponseEntity.notFound().build());
    }
}
