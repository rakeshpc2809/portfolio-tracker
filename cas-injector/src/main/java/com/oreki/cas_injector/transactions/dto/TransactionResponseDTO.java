package com.oreki.cas_injector.transactions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransactionResponseDTO {
    private Long id;
    private String txnHash;
    private LocalDate date;
    private String description;
    private BigDecimal units;
    private BigDecimal amount;
    private String transactionType;
    private String schemeName; // Flattened
    private String isin;       // Flattened
}