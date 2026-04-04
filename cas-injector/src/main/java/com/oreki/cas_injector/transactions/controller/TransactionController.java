package com.oreki.cas_injector.transactions.controller;


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
import com.oreki.cas_injector.transactions.model.Transaction;
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
            @RequestParam(defaultValue = "date,desc") String sort
    ) {
        // 🛠️ Dynamic Sort Parsing: Handles "date,desc" or "amount,asc"
        String[] sortParams = sort.split(",");
        String sortField = sortParams[0];
        // Default to DESC if the second param is missing or invalid
        Sort.Direction direction = (sortParams.length > 1 && sortParams[1].equalsIgnoreCase("asc")) 
                                    ? Sort.Direction.ASC 
                                    : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortField));

        // 🚀 Execute the Join-based Specification query        
        Page<Transaction> pageTransaction = transactionService.getFilteredTransactions(pan, type, pageable);
    
    // Map Entity to DTO
    Page<TransactionResponseDTO> dtoPage = pageTransaction.map(t -> TransactionResponseDTO.builder()
            .id(t.getId())
            .txnHash(t.getTxnHash())
            .date(t.getDate())
            .description(t.getDescription())
            .units(t.getUnits())
            .amount(t.getAmount())
            .transactionType(t.getTransactionType())
            .schemeName(t.getScheme().getName())
            .isin(t.getScheme().getIsin())
            .build());

    return ResponseEntity.ok(dtoPage);
    }
}