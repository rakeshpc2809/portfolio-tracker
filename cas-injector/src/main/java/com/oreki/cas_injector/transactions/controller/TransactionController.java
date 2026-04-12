package com.oreki.cas_injector.transactions.controller;


import com.oreki.cas_injector.dashboard.model.PortfolioSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oreki.cas_injector.transactions.dto.TransactionResponseDTO;
import com.oreki.cas_injector.transactions.service.TransactionService;

@RestController
@RequestMapping("/transactions")
@CrossOrigin(origins = "*") // Adjust based on your React dev server port
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public ResponseEntity<Page<TransactionResponseDTO>> getTransactions(
            @RequestParam(required = false) String pan,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "transactionDate,desc") String sort
    ) {
        // 🛠️ Dynamic Sort Parsing: Handles "transactionDate,desc" or "amount,asc"
        // Note: Field names now match PortfolioSummary (e.g. transactionDate instead of date)
        String[] sortParams = sort.split(",");
        String sortField = sortParams[0];
        
        // Map common sort fields to PortfolioSummary fields if needed
        if ("date".equalsIgnoreCase(sortField)) sortField = "transactionDate";
        
        // Default to DESC if the second param is missing or invalid
        Sort.Direction direction = (sortParams.length > 1 && sortParams[1].equalsIgnoreCase("asc")) 
                                    ? Sort.Direction.ASC 
                                    : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortField));

        // 🚀 Execute the lookup on the Materialized View
        Page<PortfolioSummary> pageSummary = transactionService.getFilteredTransactions(pan, type, pageable);
    
    // Map Read Model to DTO
    Page<TransactionResponseDTO> dtoPage = pageSummary.map(s -> TransactionResponseDTO.builder()
            .id(s.getTxnId())
            .txnHash(s.getTxnHash())
            .date(s.getTransactionDate())
            .description(s.getDescription())
            .units(s.getUnits())
            .amount(s.getAmount())
            .transactionType(s.getTransactionType())
            .schemeName(s.getSchemeName())
            .isin(s.getIsin())
            .build());

    return ResponseEntity.ok(dtoPage);
    }
}
